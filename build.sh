#!/bin/bash
set -e
cd "$(dirname "$0")"
PROJECT=~/projects/tailscale-remote
APP=app/src/main
OUT=$PROJECT/build
MANIFEST=$APP/AndroidManifest.xml
ANDROID_JAR=~/Android/sdk/platforms/android-35/android.jar
AAPT=~/Android/sdk/build-tools/35.0.1/aapt
D8=~/Android/sdk/build-tools/35.0.1/d8
ZIPALIGN=~/Android/sdk/build-tools/35.0.1/zipalign
APKSIGNER=~/Android/sdk/build-tools/35.0.1/apksigner

rm -rf $OUT/classes $OUT/dex $OUT/aapt $OUT/aligned.apk $OUT/app-debug.apk
rm -rf $PROJECT/tmp_apk $PROJECT/app_unsigned.apk
mkdir -p $OUT/classes $OUT/dex $OUT/aapt/gen

# Use persistent keystore for stable signature (enables in-app upgrade)
KEYSTORE=~/projects/tailscale-remote/keystore.jks
if [ ! -f "$KEYSTORE" ]; then
  echo "[*] Creating persistent keystore..."
  keytool -genkeypair -keystore "$KEYSTORE" -alias debug -storepass android -keypass android \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Debug,OU=Debug,O=Debug,C=US" -noprompt
fi

echo "====[1/5] aapt: generate R.java + resources APK ===="
$AAPT package -f -M $MANIFEST -S $APP/res -I $ANDROID_JAR \
  -F $OUT/aapt/base.apk \
  -J $OUT/aapt/gen

echo "====[2/5] javac ===="
javac -source 11 -target 11 -nowarn \
  -cp "$PROJECT/libs/*:$ANDROID_JAR" \
  -sourcepath "$OUT/aapt/gen:$APP/java" \
  -d $OUT/classes \
  $(find $APP/java -name '*.java') $OUT/aapt/gen/R.java

echo "====[3/5] d8 ===="
$D8 \
  --min-api 26 --release \
  --lib $ANDROID_JAR \
  --output $OUT/dex \
  $(find $OUT/classes -name '*.class') \
  $PROJECT/libs/okhttp-3.14.9.jar \
  $PROJECT/libs/okio-1.17.5.jar \
  $PROJECT/libs/gson-2.11.0.jar

echo "=====[4/5] merge dex + zipalign ===="
mkdir -p $PROJECT/tmp_apk
unzip -q $OUT/aapt/base.apk -d $PROJECT/tmp_apk
cp $OUT/dex/classes.dex $PROJECT/tmp_apk/classes.dex
(cd $PROJECT/tmp_apk && zip -r -q -0 ../app_unsigned.apk .)
rm -rf $PROJECT/tmp_apk
$ZIPALIGN -f -p 4 $PROJECT/app_unsigned.apk $OUT/aligned.apk

echo "=====[5/5] apksigner sign ===="
$APKSIGNER sign \
  --ks "$KEYSTORE" --ks-pass pass:android --ks-key-alias debug \
  --out $OUT/app-debug.apk \
  $OUT/aligned.apk

rm -f $PROJECT/app_unsigned.apk
echo "=====DONE====="
ls -la $OUT/app-debug.apk
