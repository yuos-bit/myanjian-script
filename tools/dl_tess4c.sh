#!/bin/bash
cd "$(dirname "$0")"
rm -f tesseract4android.aar
curl -sL --retry 3 -o tesseract4android.aar "https://github.com/adaptech-cz/Tesseract4Android/releases/download/2.0.0/tesseract4android-2.0.0.aar"
ls -la tesseract4android.aar
echo "TESS4C_DONE"
