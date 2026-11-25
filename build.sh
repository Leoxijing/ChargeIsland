#!/bin/bash
# ChargeIsland 手工构建脚本：aapt2 + javac + d8 + apksigner
set -e
BASE="$(cd "$(dirname "$0")" && pwd)"
SDK="/c/Users/Xiaomi/XiaoAi/project/files/2026-11/android-sdk"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
BT="$SDK/build-tools/34.0.0"
BUILD="$BASE/build"

# JAVA_HOME 自动探测（便携 JDK 优先）
if [ -z "$JAVA_HOME" ]; then
  for c in "$SDK/jdk-17"* "$SDK/jdk"*; do
    if [ -f "$c/bin/javac.exe" ] || [ -f "$c/bin/javac" ]; then JAVA_HOME="$c"; break; fi
  done
fi
export PATH="$JAVA_HOME/bin:$PATH"
echo "JAVA_HOME=$JAVA_HOME"
java -version 2>&1 | head -1

rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

echo "[1/7] aapt2 compile resources"
"$BT/aapt2" compile --dir "$BASE/res" -o "$BUILD/res.zip"

echo "[2/7] aapt2 link"
ASSETS_ARG=""
if [ -d "$BASE/assets" ]; then ASSETS_ARG="-A $BASE/assets"; fi
# shellcheck disable=SC2086
"$BT/aapt2" link -o "$BUILD/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$BASE/AndroidManifest.xml" \
  --min-sdk-version 31 --target-sdk-version 34 \
  --java "$BUILD/gen" \
  --auto-add-overlay \
  $ASSETS_ARG \
  "$BUILD/res.zip"

echo "[3/7] javac"
find "$BASE/src" "$BUILD/gen" -name "*.java" | while IFS= read -r f; do cygpath -m "$f"; done > "$BUILD/sources.txt"
javac -encoding UTF-8 -source 11 -target 11 -nowarn -classpath "$ANDROID_JAR" -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "[4/7] jar"
jar cf "$(cygpath -m "$BUILD/classes.jar")" -C "$(cygpath -m "$BUILD/classes")" .

echo "[5/7] d8 dex"
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --lib "$ANDROID_JAR" --output "$BUILD/dex" "$BUILD/classes.jar"

echo "[6/7] inject dex + zipalign"
cd "$BUILD/dex"
jar uf "$(cygpath -m "$BUILD/base.apk")" classes.dex
cd "$BASE"
"$BT/zipalign" -f 4 "$BUILD/base.apk" "$BUILD/aligned.apk"

echo "[7/7] sign"
if [ ! -f "$BASE/debug.keystore" ]; then
  keytool -genkeypair -keystore "$BASE/debug.keystore" -storepass android -keypass android \
    -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" \
    -keyalg RSA -keysize 2048 -validity 10000
fi
java -jar "$BT/lib/apksigner.jar" sign --ks "$BASE/debug.keystore" --ks-pass pass:android --key-pass pass:android \
  --out "$BUILD/ChargeIsland.apk" "$BUILD/aligned.apk"
java -jar "$BT/lib/apksigner.jar" verify "$BUILD/ChargeIsland.apk"

echo "BUILD OK: $BUILD/ChargeIsland.apk"
ls -la "$BUILD/ChargeIsland.apk"
