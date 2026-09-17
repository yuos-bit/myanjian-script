#!/bin/bash
cd "$(dirname "$0")"
curl -sL --retry 3 -o tesseract4android-4.1.0.aar "https://repo1.maven.org/maven2/cz/adaptech/tesseract/tesseract4android/4.1.0/tesseract4android-4.1.0.aar"
ls -la tesseract4android-4.1.0.aar
echo "TESS4_DONE"
