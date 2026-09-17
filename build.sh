#!/bin/bash
# 自动打卡 APK 构建脚本 (aapt2 + javac + d8 + apksigner, 无需 Gradle)
set -e
cd "$(dirname "$0")"

ROOT="$(pwd)"
SDK="$ROOT/tools/android-sdk"
JDK_DIR="$ROOT/tools/jdk"
export JAVA_HOME="$JDK_DIR"
export PATH="$JDK_DIR/bin:$PATH"

BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-33/android.jar"
OUT="$ROOT/build"

# Windows 原生工具需要 C:/ 风格路径
BTW=$(cygpath -m "$BT")
PLATFORMW=$(cygpath -m "$PLATFORM")

for i in 1 2 3 4 5; do
    rm -rf "$OUT" 2>/dev/null && break
    sleep 1
done
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

# 读取版本号（初始 1.0.0，每次更新末位 +1）
VER=$(tr -d '[:space:]' < "$ROOT/VERSION")
VER_CODE=$(( $(echo "$VER" | cut -d. -f1) * 10000 + $(echo "$VER" | cut -d. -f2) * 100 + $(echo "$VER" | cut -d. -f3) ))
echo "==> 版本号: $VER (versionCode=$VER_CODE)"

echo "==> [1/6] aapt2 编译资源"
"$BT/aapt2" compile --dir app/src/main/res -o build/res.zip

echo "==> [2/6] aapt2 链接资源并生成 R.java"
"$BT/aapt2" link \
    -o build/app.unaligned.apk \
    -I "$PLATFORMW" \
    --manifest app/src/main/AndroidManifest.xml \
    --min-sdk-version 26 \
    --target-sdk-version 28 \
    --version-code "$VER_CODE" \
    --version-name "$VER" \
    --java build/gen \
    --auto-add-overlay \
    build/res.zip

echo "==> [3/6] javac 编译 Java 源码"
# OCR（Tesseract 4.0 + LSTM）：解包 tesseract4android AAR 供编译和打包使用
AAR="$ROOT/tools/tesseract4android-2.0.0.aar"
rm -rf "$OUT/tess-classes" "$OUT/libs"
if [ -f "$AAR" ]; then
    mkdir -p "$OUT/tess-classes" "$OUT/libs"
    (cd "$OUT/tess-classes" && jar xf "$AAR" classes.jar && jar xf classes.jar)
    (cd "$OUT/libs" && jar xf "$AAR" jni)
    mkdir -p "$OUT/libs/lib"
    for abi in arm64-v8a armeabi-v7a; do
        if [ -d "$OUT/libs/jni/$abi" ]; then
            cp -r "$OUT/libs/jni/$abi" "$OUT/libs/lib/$abi"
        fi
    done
    echo "    已集成 OCR 组件 (tesseract4android / tesseract 4.0.0)"
fi

find app/src/main/java -name "*.java" > build/sources.txt
find build/gen -name "*.java" >> build/sources.txt
javac -source 8 -target 8 -encoding UTF-8 -nowarn \
    -bootclasspath "$PLATFORMW" \
    -classpath "$BTW/core-lambda-stubs.jar;$(cygpath -m "$OUT/tess-classes")" \
    -d build/classes \
    @build/sources.txt

echo "==> [4/6] d8 生成 classes.dex"
find build/classes -name "*.class" | sed 's|^build/|build/|' > build/classlist.txt
if [ -f "$AAR" ]; then
    find "$OUT/tess-classes" -name "*.class" | sed "s|.*/build/|build/|" >> build/classlist.txt
fi

"$BT/d8.bat" --min-api 26 --lib "$PLATFORMW" \
    --output "$(cygpath -m "$OUT/dex")" \
    @build/classlist.txt

echo "==> [5/6] 打包 dex/OCR库/模型 进 APK 并对齐"
(cd build/dex && jar uf ../app.unaligned.apk classes.dex)
# 原生 so 库
if [ -d "$OUT/libs/lib" ]; then
    (cd "$OUT/libs" && jar uf "$OUT/app.unaligned.apk" lib)
fi
# OCR 中文模型 → assets/tessdata/
if [ -f "$ROOT/tools/chi_sim.traineddata" ]; then
    mkdir -p "$ROOT/app/src/main/assets/tessdata"
    cp -f "$ROOT/tools/chi_sim.traineddata" "$ROOT/app/src/main/assets/tessdata/"
    (cd "$ROOT/app/src/main" && jar uf "$OUT/app.unaligned.apk" assets)
fi
"$BT/zipalign" -f 4 build/app.unaligned.apk build/app.aligned.apk

echo "==> [6/6] 生成签名密钥并签名 (自签证书)"
if [ ! -f "$ROOT/keystore.jks" ]; then
    keytool -genkeypair -keystore keystore.jks -alias daka \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass daka123456 -keypass daka123456 \
        -dname "CN=daka, OU=daka, O=daka, L=CN, ST=CN, C=CN"
fi
"$BT/apksigner.bat" sign \
    --ks keystore.jks \
    --ks-key-alias daka \
    --ks-pass pass:daka123456 \
    --key-pass pass:daka123456 \
    --out "daka_v$VER.apk" \
    build/app.aligned.apk

# 同时复制一份 daka.apk 方便固定文件名引用
cp -f "daka_v$VER.apk" daka.apk

echo ""
echo "OK 构建完成: $ROOT/daka_v$VER.apk (另附副本 daka.apk)"
"$BT/apksigner.bat" verify --print-certs "daka_v$VER.apk" | head -5
