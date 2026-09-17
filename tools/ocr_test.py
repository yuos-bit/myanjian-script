# -*- coding: utf-8 -*-
"""OCR 调优测试: 用 png/1.jpg 对比不同 PSM / 预处理组合的识别效果。

用法: python tools/ocr_test.py
前提: tesseract.exe 已安装 (winget install tesseract-ocr.tesseract)
      模型: tools/chi_sim.traineddata (与 APK 打包的同一份)
"""
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
IMG = os.path.join(ROOT, "png", "1.jpg")
TESSDATA = os.path.join(ROOT, "tools")
TESS = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Programs", "Tesseract-OCR", "tesseract.exe")
if not os.path.exists(TESS):
    TESS = "tesseract"  # PATH 里可用时

# 与 App 端一致的基础变量
BASE_VARS = ["user_defined_dpi=300"]


def run_tess(img, psm, extra_vars=(), tag=""):
    cfg = os.path.join(ROOT, "build", "ocr_cfg")
    os.makedirs(cfg, exist_ok=True)
    var_args = []
    for v in list(BASE_VARS) + list(extra_vars):
        var_args += ["-c", v]
    out_base = os.path.join(cfg, "out_" + tag)
    cmd = [TESS, img, out_base, "--tessdata-dir", TESSDATA,
           "--psm", str(psm), "-l", "chi_sim"] + var_args
    r = subprocess.run(cmd, capture_output=True, text=True)
    txt_path = out_base + ".txt"
    if os.path.exists(txt_path):
        with open(txt_path, "r", encoding="utf-8") as f:
            return f.read()
    return "(运行失败: %s)" % r.stderr.strip()[:200]


def make_variants():
    """生成预处理变体图，返回 [(tag, path)]；无 Pillow 时只测原图。"""
    variants = [("raw", IMG)]
    try:
        from PIL import Image, ImageEnhance, ImageFilter, ImageOps
    except ImportError:
        return variants
    tmp = os.path.join(ROOT, "build", "ocr_cfg")
    os.makedirs(tmp, exist_ok=True)

    im = Image.open(IMG).convert("L")
    # 1.2x 放大 + 锐化
    up = im.resize((int(im.width * 1.2), int(im.height * 1.2)), Image.LANCZOS)
    up = up.filter(ImageFilter.SHARPEN)
    p1 = os.path.join(tmp, "v_up.png")
    up.save(p1)
    variants.append(("up1.2", p1))

    # 自动对比度
    ac = ImageOps.autocontrast(im)
    p2 = os.path.join(tmp, "v_ac.png")
    ac.save(p2)
    variants.append(("autocontrast", p2))

    # 二值化 (Otsu 近似: autocontrast + 点变换)
    bw = ac.point(lambda x: 255 if x > 140 else 0)
    p3 = os.path.join(tmp, "v_bw.png")
    bw.save(p3)
    variants.append(("bw140", p3))
    return variants


def main():
    variants = make_variants()
    psms = [3, 4, 6]
    results = []
    for tag, path in variants:
        for psm in psms:
            t = run_tess(path, psm, tag="%s_psm%d" % (tag, psm))
            results.append((tag, psm, t))
            print("=" * 60)
            print(">>> 变体=%s  PSM=%d  字符数=%d" % (tag, psm, len(t.strip())))
            print(t.strip())
    # 汇总
    print("\n" + "#" * 60)
    print("汇总 (按识别字符数):")
    for tag, psm, t in sorted(results, key=lambda x: -len(x[2].strip())):
        print("  %-14s psm%d -> %d chars" % (tag, psm, len(t.strip())))


if __name__ == "__main__":
    main()
