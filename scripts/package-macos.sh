#!/bin/bash
# Builds both macOS architectures and creates installable .app bundles in one DMG.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
DATE="$(date +%Y%m%d)"
OUT="$ROOT/releases/iap2loc-macos-bt-$DATE"
STAGE="$OUT/dmg-root"
mkdir -p "$OUT" "$STAGE"
export DOTNET_CLI_TELEMETRY_OPTOUT=1
if ! command -v dotnet >/dev/null 2>&1; then
    echo "Error: .NET 10 SDK is required. Install it before packaging." >&2
    exit 1
fi

xcrun clang -fobjc-arc -fPIC -O2 -dynamiclib \
    -arch arm64 -arch x86_64 \
    -framework Foundation -framework IOBluetooth \
    -o Native/libiap2macbt.dylib Native/MacBluetoothBridge.m

for spec in "arm64:Apple Silicon" "x64:Intel"; do
    arch="$(printf '%s' "$spec" | cut -d: -f1)"
    label="$(printf '%s' "$spec" | cut -d: -f2)"
    rid="osx-$arch"
    appname="iap2loc ($label).app"
    app="$STAGE/$appname"
    echo "Building $rid -> $appname"
    dotnet publish Iap2Loc.csproj -c Release -r "$rid" \
        --self-contained true \
        -p:PublishSingleFile=true \
        -p:EnableCompressionInSingleFile=true \
        -p:IncludeNativeLibrariesForSelfExtract=true \
        -p:DebugType=None -p:DebugSymbols=false \
        -o "$OUT/publish/$rid"

    mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources"
    cp "$OUT/publish/$rid/iap2loc" "$app/Contents/MacOS/iap2loc"
    cp Native/libiap2macbt.dylib "$app/Contents/MacOS/libiap2macbt.dylib"
    cp LICENSE "$app/Contents/Resources/LICENSE"
    cp "$ROOT/README-macOS.md" "$app/Contents/Resources/README-macOS.md"
    chmod 755 "$app/Contents/MacOS/iap2loc"

    cat > "$app/Contents/Info.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleExecutable</key><string>iap2loc</string>
  <key>CFBundleIdentifier</key><string>local.iap2loc.macos.$arch</string>
  <key>CFBundleName</key><string>iap2loc</string>
  <key>CFBundleDisplayName</key><string>iap2loc ($label)</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>0.1.0</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>LSMinimumSystemVersion</key><string>13.0</string>
  <key>LSUIElement</key><true/>
  <key>NSHighResolutionCapable</key><true/>
  <key>NSBluetoothAlwaysUsageDescription</key><string>用于查找已配对的 iPhone，查询 iAP2 服务并建立蓝牙 RFCOMM 连接。</string>
</dict>
</plist>
EOF
    plutil -lint "$app/Contents/Info.plist"
    codesign --force --sign - "$app/Contents/MacOS/libiap2macbt.dylib"
    codesign --force --sign - "$app/Contents/MacOS/iap2loc"
    codesign --force --deep --sign - "$app"
    codesign --verify --deep --strict --verbose=2 "$app"
done

cp README-macOS.md "$STAGE/README-macOS.md"
cp LICENSE "$STAGE/LICENSE"
ln -sf /Applications "$STAGE/Applications"
printf 'upstream=%s\n' "$(git rev-parse HEAD)" > "$OUT/build-revision.txt"
git diff -- Program.cs Engine/RunSession.cs Transport/BluetoothDevices.cs Transport/BtRfcommTransport.cs \
     > "$OUT/dotnet-modifications.patch"
tar -czf "$OUT/source.tar.gz" \
    --exclude='./.git' --exclude='./bin' --exclude='./obj' \
    --exclude='./dist' --exclude='./releases' \
    --exclude='./Native/*.dylib' --exclude='./.DS_Store' .
cp "$OUT/source.tar.gz" "$STAGE/source.tar.gz"
cp "$OUT/dotnet-modifications.patch" "$STAGE/dotnet-modifications.patch"
output="$OUT/iap2loc-macOS-Bluetooth-$DATE.dmg"
hdiutil create -ov -format UDZO -fs HFS+ \
    -volname "iap2loc macOS Bluetooth" -srcfolder "$STAGE" "$output"
hdiutil verify "$output"
( cd "$OUT" && shasum -a 256 "$(basename "$output")" source.tar.gz > SHA256SUMS )
echo "DMG=$output"
ls -lh "$output"
cat "$OUT/SHA256SUMS"
