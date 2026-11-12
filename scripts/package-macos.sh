#!/bin/bash
# Builds one macOS architecture and wraps it into a single-architecture DMG.
# Used by both the CI build and the tagged release in .github/workflows/macos.yml.
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "Usage: $0 arm64|x64 path/to/output.dmg" >&2
    exit 2
fi

ARCH="$1"
OUTPUT="$2"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

case "$ARCH" in
    arm64) RID="osx-arm64"; LABEL="Apple Silicon" ;;
    x64)   RID="osx-x64";   LABEL="Intel" ;;
    *) echo "Unknown architecture: $ARCH" >&2; exit 2 ;;
esac

if ! command -v dotnet >/dev/null 2>&1; then
    echo "Error: .NET 10 SDK is required." >&2
    exit 1
fi

mkdir -p "$(dirname "$OUTPUT")"
OUTDIR="$(cd "$(dirname "$OUTPUT")" && pwd)"
OUTPUT="$OUTDIR/$(basename "$OUTPUT")"

WORK="$ROOT/build/macos-$ARCH"
STAGE="$WORK/dmg-root"
APP="$STAGE/iap2loc.app"

rm -rf "$WORK"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"

echo "Building universal IOBluetooth bridge and $RID payload"
xcrun clang -fobjc-arc -fPIC -O2 -dynamiclib \
    -arch arm64 -arch x86_64 \
    -framework Foundation -framework IOBluetooth \
    -o "$WORK/libiap2macbt.dylib" Native/MacBluetoothBridge.m

dotnet publish Iap2Loc.csproj -c Release -r "$RID" \
    --self-contained true \
    -p:PublishSingleFile=true \
    -p:EnableCompressionInSingleFile=true \
    -p:IncludeNativeLibrariesForSelfExtract=true \
    -p:DebugType=None -p:DebugSymbols=false \
    -o "$WORK/publish"

cp "$WORK/publish/iap2loc" "$APP/Contents/MacOS/iap2loc"
cp "$WORK/libiap2macbt.dylib" "$APP/Contents/MacOS/libiap2macbt.dylib"
cp LICENSE "$APP/Contents/Resources/LICENSE"
cp README-macOS.md "$APP/Contents/Resources/README-macOS.md"
chmod 755 "$APP/Contents/MacOS/iap2loc"

cat > "$APP/Contents/Info.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleExecutable</key><string>iap2loc</string>
  <key>CFBundleIdentifier</key><string>local.iap2loc.macos.$ARCH</string>
  <key>CFBundleName</key><string>iap2loc</string>
  <key>CFBundleDisplayName</key><string>iap2loc ($LABEL)</string>
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

plutil -lint "$APP/Contents/Info.plist"
codesign --force --sign - "$APP/Contents/MacOS/libiap2macbt.dylib"
codesign --force --sign - "$APP/Contents/MacOS/iap2loc"
codesign --force --deep --sign - "$APP"
codesign --verify --deep --strict --verbose=2 "$APP"

cp README-macOS.md "$STAGE/README-macOS.md"
cp LICENSE "$STAGE/LICENSE"
ln -sf /Applications "$STAGE/Applications"
printf 'upstream=%s\narchitecture=%s\n' "$(git rev-parse HEAD)" "$ARCH" > "$WORK/build-revision.txt"
cp "$WORK/build-revision.txt" "$STAGE/build-revision.txt"
git archive --format=tar HEAD | gzip -9 > "$STAGE/source.tar.gz"

hdiutil create -ov -format UDZO -fs HFS+ \
    -volname "iap2loc ($LABEL)" -srcfolder "$STAGE" "$OUTPUT"
hdiutil verify "$OUTPUT"

(
    cd "$OUTDIR"
    shasum -a 256 "$(basename "$OUTPUT")" > SHA256SUMS
)

echo "DMG=$OUTPUT"
ls -lh "$OUTPUT"
cat "$OUTDIR/SHA256SUMS"
