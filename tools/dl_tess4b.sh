#!/bin/bash
cd "$(dirname "$0")"
curl -sL --retry 3 -o tesseract4android.aar "https://jitpack.io/com/github/Adaptech/Tesseract4Android/4.1.0/Tesseract4Android-4.1.0.aar"
ls -la tesseract4android.aar
echo "TESS4B_DONE"
