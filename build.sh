#!/bin/bash
set -e
cd "$(dirname "$0")"

./gradlew assembleDebug
mkdir -p build
cp app/build/outputs/apk/debug/app-debug.apk build/app-debug.apk
echo "APK: $(pwd)/build/app-debug.apk"
