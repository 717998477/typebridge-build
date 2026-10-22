#!/bin/bash
# TypeBridge TV 一键构建（无 gradle：aapt2 + javac + d8 + zipalign + apksigner）
# SDK 路径优先取 $ANDROID_SDK_ROOT（GitHub Actions 已预设），否则回退本机默认路径
set -e
SDK="${ANDROID_SDK_ROOT:-/home/user/Doubao/chats/38446541428096002/sdk/android}"
BT="$SDK/build-tools/30.0.3"
PLAT="$SDK/platforms/android-30/android.jar"
cd "$(dirname "$0")"

rm -rf res.zip base.apk aligned.apk gen classes dexout TypeBridgeTV.apk
mkdir -p gen classes dexout

echo "[1/6] aapt2 compile"
"$BT/aapt2" compile --dir res -o res.zip

echo "[2/6] aapt2 link (+assets, +R.java)"
"$BT/aapt2" link -o base.apk -I "$PLAT" --manifest AndroidManifest.xml --java gen -A assets res.zip

echo "[3/6] javac"
javac -source 8 -target 8 -encoding UTF-8 \
  -classpath "$PLAT:libs/core-3.5.2.jar:libs/shizuku-api.jar:libs/shizuku-aidl.jar:libs/shizuku-shared.jar" \
  -d classes gen/com/cdhwan/wq/R.java src/com/cdhwan/wq/*.java

echo "[4/6] d8 dex (app + zxing)"
jar cf classes.jar -C classes .
"$BT/d8" --release --min-api 19 --lib "$PLAT" --output dexout classes.jar libs/core-3.5.2.jar libs/shizuku-api.jar libs/shizuku-aidl.jar libs/shizuku-shared.jar

echo "[5/6] pack + zipalign"
cd dexout && zip -q ../base.apk classes.dex && cd ..
"$BT/zipalign" -f 4 base.apk aligned.apk

echo "[6/6] sign"
keytool -genkeypair -keystore tb.keystore -alias tb -keyalg RSA -keysize 2048 \
  -validity 10000 -storepass typebridge -dname "CN=TypeBridge TV" 2>/dev/null || true
"$BT/apksigner" sign --ks tb.keystore --ks-pass pass:typebridge --out TypeBridgeTV.apk aligned.apk

echo "=== BUILD OK ==="
ls -la TypeBridgeTV.apk
"$BT/apksigner" verify --print-certs TypeBridgeTV.apk | head -3
