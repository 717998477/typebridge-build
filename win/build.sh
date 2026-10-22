#!/bin/bash
# TypeBridge Windows 版一键构建（Linux/mono 交叉编译 Windows exe）
# 在 GitHub Actions 云构建时由 workflow 调用；本地 Linux 也可直接 bash build.sh
set -e
cd "$(dirname "$0")"
mkdir -p build
mcs /nologo /optimize+ /platform:anycpu /target:winexe \
  /r:System.dll /r:System.Core.dll /r:System.Drawing.dll \
  /r:System.Windows.Forms.dll /r:System.Web.Extensions.dll \
  /res:src/web/index.html,TypeBridgeRes.index.html \
  /res:src/web/pair.html,TypeBridgeRes.pair.html \
  /res:src/web/mobile.html,TypeBridgeRes.mobile.html \
  /res:src/web/qrcode.js,TypeBridgeRes.qrcode.js \
  /out:build/TypeBridge.exe src/TypeBridge.cs
echo "=== BUILD OK ==="
ls -la build/TypeBridge.exe
