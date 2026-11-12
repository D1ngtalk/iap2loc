#!/usr/bin/env bash
# Smoke-test the distributed DMG, not only the working tree.
# Headless GitHub runners cannot test iPhone pairing, RFCOMM or MFi auth.
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 path/to/package.dmg arm64|x64" >&2
  exit 2
fi

DMG="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
ARCH="$2"
case "$ARCH" in
  arm64) APP_NAME="iap2loc (Apple Silicon).app"; OS_ARCH="arm64" ;;
  x64) APP_NAME="iap2loc (Intel).app"; OS_ARCH="x86_64" ;;
  *) echo "Unknown architecture: $ARCH" >&2; exit 2 ;;
esac

actual_arch="$(uname -m)"
if [[ "$actual_arch" != "$OS_ARCH" ]]; then
  echo "Runner mismatch: expected $OS_ARCH, got $actual_arch" >&2
  exit 1
fi

(
  cd "$(dirname "$DMG")"
  shasum -a 256 -c SHA256SUMS
)
hdiutil verify "$DMG"

MOUNT="$(mktemp -d -t iap2loc-smoke)"
LOG="$(mktemp -t iap2loc-http)"
HTML="$(mktemp -t iap2loc-html)"
STATE="$(mktemp -t iap2loc-state)"
server_pid=""
cleanup() {
  if [[ -n "$server_pid" ]]; then
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
  hdiutil detach -quiet "$MOUNT" >/dev/null 2>&1 || true
  rm -rf "$MOUNT"
  rm -f "$LOG" "$HTML" "$STATE"
}
trap cleanup EXIT

hdiutil attach -quiet -readonly -nobrowse -mountpoint "$MOUNT" "$DMG"
for required in LICENSE README-macOS.md source.tar.gz; do
  test -f "$MOUNT/$required"
done
# The CI DMG ships both flavours, the release DMG ships one; verify every bundle present.
app_count=0
for bundle in "$MOUNT"/*.app; do
  [[ -d "$bundle" ]] || continue
  codesign --verify --deep --strict "$bundle"
  app_count=$((app_count + 1))
done
test "$app_count" -ge 1

APP="$MOUNT/$APP_NAME"
if [[ ! -d "$APP" ]]; then
  APP="$(find "$MOUNT" -maxdepth 1 -name '*.app' -print -quit)"
fi
test -n "$APP"
BIN="$APP/Contents/MacOS/iap2loc"
test -x "$BIN"
test -f "$APP/Contents/MacOS/libiap2macbt.dylib"
test -n "$(/usr/libexec/PlistBuddy -c 'Print :NSBluetoothAlwaysUsageDescription' "$APP/Contents/Info.plist")"
file "$BIN"
file "$APP/Contents/MacOS/libiap2macbt.dylib"
file "$BIN" | grep -q "$OS_ARCH"
lipo -archs "$APP/Contents/MacOS/libiap2macbt.dylib" | grep -q arm64
lipo -archs "$APP/Contents/MacOS/libiap2macbt.dylib" | grep -q x86_64

"$BIN" --help | grep -q iap2loc
# Do not assume a physical Bluetooth adapter or paired iPhone on GitHub runners.

PORT=18739
IAP2LOC_NO_BROWSER=1 "$BIN" serve --port "$PORT" > "$LOG" 2>&1 &
server_pid=$!
ready=0
for ((tries=0; tries<50; tries++)); do
  if curl --silent --fail --max-time 2 "http://127.0.0.1:$PORT/api/state" > /dev/null 2>&1; then
    ready=1
    break
  fi
  if ! kill -0 "$server_pid" 2>/dev/null; then
    echo "Web server exited unexpectedly" >&2
    cat "$LOG" >&2
    exit 1
  fi
  sleep 0.25
done
if [[ "$ready" -ne 1 ]]; then
  echo "Web server did not become ready" >&2
  cat "$LOG" >&2
  exit 1
fi
curl --silent --show-error --fail --max-time 5 "http://127.0.0.1:$PORT/" -o "$HTML"
grep -qi html "$HTML"
curl --silent --show-error --fail --max-time 5 "http://127.0.0.1:$PORT/api/state" -o "$STATE"
python3 -m json.tool "$STATE" > /dev/null

echo "PASS: $ARCH DMG hashes, mount, signatures, architecture, CLI, web/API."
