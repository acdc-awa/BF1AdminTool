#!/bin/bash
# 生成 Android 发布签名密钥并输出 GitHub Secrets 格式
# 使用方法: bash scripts/setup-keystore.sh

set -e

KEYSTORE="app/keystore.jks"
ALIAS="bf1admintool"
PASSWORD=$(openssl rand -base64 32)

echo "=== 生成密钥库 ==="
keytool -genkey -v \
  -keystore "$KEYSTORE" \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storepass "$PASSWORD" \
  -keypass "$PASSWORD" \
  -dname "CN=BF1AdminTool, OU=Dev, O=BF1, L=Unknown, ST=Unknown, C=CN"

echo ""
echo "=== 密钥库已生成: $KEYSTORE ==="
echo ""

# 输出 base64 编码后的值（用于 GitHub Secrets）
echo "========== 复制以下内容到 GitHub Secrets =========="
echo ""
echo "Secret 名称: KEYSTORE_BASE64"
echo "值:"
base64 -w0 "$KEYSTORE"
echo ""
echo ""
echo "Secret 名称: KEYSTORE_PASSWORD"
echo "值: $PASSWORD"
echo ""
echo "Secret 名称: KEY_ALIAS"
echo "值: $ALIAS"
echo ""
echo "Secret 名称: KEY_PASSWORD"
echo "值: $PASSWORD"
echo ""
echo "===================================================="
echo ""
echo "本地构建测试（可选）:"
echo "  KEYSTORE_PATH=$PWD/$KEYSTORE KEYSTORE_PASSWORD=$PASSWORD KEY_ALIAS=$ALIAS KEY_PASSWORD=$PASSWORD ./gradlew assembleRelease"
