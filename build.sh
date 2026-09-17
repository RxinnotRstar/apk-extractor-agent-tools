#!/bin/bash
# 构建 Agent用安装包提取器
# 纯 Java，无 native 库 -> dex 与架构无关 -> 32/64 位通吃，不跟你要 so
set -e
cd "$(dirname "$0")"

ANDROID_JAR=/usr/lib/android-sdk/platforms/android-23/android.jar
DX_JAR=/usr/share/java/com.android.dx.jar

# ---- 签名密钥 ----
# 用工作区里的发布私钥（不在本仓库内，不会被 git 提交）。
# 覆盖方式：KEYDIR=... KS=... ALIAS=... KSPASS=... ./build.sh
KEYDIR="${KEYDIR:-/workspace/安卓安装包私钥}"
KS="${KS:-$KEYDIR/rxinns-release.keystore}"
ALIAS="${ALIAS:-release}"

if [ -z "$KSPASS" ]; then
  if [ -f "$KEYDIR/.kspass" ]; then
    KSPASS="$(cat "$KEYDIR/.kspass")"
  else
    echo "错误：拿不到 keystore 密码。设 KSPASS 环境变量，或在 $KEYDIR/.kspass 放一份。" >&2
    exit 1
  fi
fi

if [ ! -f "$KS" ]; then
  echo "错误：找不到 keystore: $KS" >&2
  exit 1
fi

echo "[1/5] 清理"
rm -rf build/classes build/dex build/*.apk
mkdir -p build/classes build/dex

echo "[2/5] javac（bootclasspath=android-23，高版本 API 全在代码里走反射）"
javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
  -d build/classes $(find src -name '*.java') 2>&1 | grep -v "^warning" || true

echo "[3/5] dx -> classes.dex"
java -cp "$DX_JAR" com.android.dx.command.Main \
  --dex --output=build/dex/classes.dex build/classes

echo "[3.5/5] 生成图标（最外层牛皮纸色圆环，flat）"
python3 make_icon.py flat > /dev/null

echo "[4/5] aapt package"
aapt package -f -M AndroidManifest.xml -S res -I "$ANDROID_JAR" \
  -F build/unsigned.apk
cp build/unsigned.apk build/withdex.apk
(cd build && zip -j -q withdex.apk dex/classes.dex)

echo "[5/5] zipalign + sign（$ALIAS @ $KS）"
zipalign -f 4 build/withdex.apk build/aligned.apk
apksigner sign --ks "$KS" --ks-key-alias "$ALIAS" \
  --ks-pass "pass:$KSPASS" --key-pass "pass:$KSPASS" \
  --out build/extractor_signed.apk build/aligned.apk

echo ""
echo "签名核对："
apksigner verify --print-certs build/extractor_signed.apk | head -8

ls -la build/extractor_signed.apk
echo "OK -> build/extractor_signed.apk"
