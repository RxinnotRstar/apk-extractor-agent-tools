#!/bin/bash
# 构建 Agent用安装包提取器
# 纯 Java，无 native 库 -> dex 与架构无关 -> 32/64 位通吃，不跟你要 so
set -e
cd "$(dirname "$0")"

ANDROID_JAR=/usr/lib/android-sdk/platforms/android-23/android.jar
DX_JAR=/usr/share/java/com.android.dx.jar
KS=build/extractor.keystore
KSPASS=probe123

echo "[1/5] 清理"
rm -rf build/classes build/dex build/*.apk
mkdir -p build/classes build/dex

echo "[2/5] javac（bootclasspath=android-23，高版本 API 全在代码里走反射）"
javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
  -d build/classes $(find src -name '*.java') 2>&1 | grep -v "^warning" || true

echo "[3/5] dx -> classes.dex"
java -cp "$DX_JAR" com.android.dx.command.Main \
  --dex --output=build/dex/classes.dex build/classes

echo "[3.5/5] 生成图标"
python3 make_icon.py > /dev/null

echo "[4/5] aapt package"
aapt package -f -M AndroidManifest.xml -S res -I "$ANDROID_JAR" \
  -F build/unsigned.apk
cp build/unsigned.apk build/withdex.apk
(cd build && zip -j -q withdex.apk dex/classes.dex)

echo "[5/5] keystore + zipalign + sign"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias extractor -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$KSPASS" -keypass "$KSPASS" \
    -dname "CN=extractor,O=agent,C=CN"
fi
zipalign -f 4 build/withdex.apk build/aligned.apk
apksigner sign --ks "$KS" --ks-pass pass:"$KSPASS" --key-pass pass:"$KSPASS" \
  --out build/extractor_signed.apk build/aligned.apk

ls -la build/extractor_signed.apk
echo "OK -> build/extractor_signed.apk"
