#!/bin/bash
cd "$(dirname "$0")"
rm -f jdk17.zip
curl -sL --retry 3 -o jdk17.zip "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip"
echo "JDK_DONE $(ls -la jdk17.zip)"
