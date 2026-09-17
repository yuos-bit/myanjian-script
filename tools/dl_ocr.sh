#!/bin/bash
cd "$(dirname "$0")"
echo "-- download tess-two aar"
curl -sL --retry 3 -o tess-two-9.0.0.aar "https://repo1.maven.org/maven2/com/rmtheis/tess-two/9.0.0/tess-two-9.0.0.aar"
ls -la tess-two-9.0.0.aar
echo "-- download chi_sim traineddata"
curl -sL --retry 3 -o chi_sim.traineddata "https://cdn.jsdelivr.net/gh/tesseract-ocr/tessdata_fast@main/chi_sim.traineddata"
ls -la chi_sim.traineddata
echo "OCR_DEPS_DONE"
