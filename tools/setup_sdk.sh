#!/bin/bash
set -e
cd "$(dirname "$0")"
if [ ! -d jdk ]; then
  if [ -d jdk-17.0.20.1+1 ]; then
    cp -r "jdk-17.0.20.1+1" jdk
  else
    unzip -q jdk17.zip
    cp -r jdk-17* jdk
  fi
fi
./jdk/bin/java.exe -version 2>&1
echo "JAVA_EXTRACTED"
