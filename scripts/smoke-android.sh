#!/usr/bin/env bash
# Smoke-test the distributed APK on a running emulator or device, not only the working tree.
# Emulators cannot test iPhone pairing, RFCOMM or MFi auth; this checks install, start-up,
# the foreground service, the embedded console page and the JSON API after R8.
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 path/to/iap2loc-android.apk" >&2
  exit 2
fi

APK="$1"
PACKAGE="io.github.d1ngtalk.iap2loc"
HOST_PORT="${IAP2LOC_SMOKE_PORT:-18737}"
ADB="${ADB:-adb}"
BASE="http://127.0.0.1:$HOST_PORT"

"$ADB" wait-for-device
for _ in $(seq 1 120); do
  [[ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]] && break
  sleep 2
done

"$ADB" install -r -g "$APK"
"$ADB" logcat -c || true
# skipOnboarding opens the console directly; the first-run guide needs a human.
"$ADB" shell am start -W -n "$PACKAGE/.app.ui.ConsoleActivity" --ez skipOnboarding true
"$ADB" forward "tcp:$HOST_PORT" tcp:8737

state=""
for _ in $(seq 1 60); do
  if state="$(curl -fsS -m 3 "$BASE/api/state" 2>/dev/null)"; then
    break
  fi
  sleep 1
done

if [[ -z "$state" ]]; then
  "$ADB" logcat -d | grep -E 'iap2loc|AndroidRuntime' | tail -80 >&2 || true
  echo "console did not answer on the device" >&2
  exit 1
fi

python3 - "$state" <<'PY'
import json, sys
state = json.loads(sys.argv[1])
assert state["target"]["mode"] == "point", state["target"]
assert state["identity"]["serial"].startswith("LC"), state["identity"]
assert state["sessions"] == [], state["sessions"]
print("state ok:", state["identity"]["serial"], state["target"]["latitude"], state["target"]["longitude"])
PY

page="$(curl -fsS -m 5 "$BASE/")"
grep -q '<html' <<<"$page"
grep -q '/api/state' <<<"$page"

devices="$(curl -fsS -m 5 "$BASE/api/devices")"
python3 -c 'import json,sys; assert isinstance(json.loads(sys.argv[1]), list)' "$devices"

applied="$(curl -fsS -m 5 -X POST -H 'Content-Type: application/json' \
  -d '{"mode":"path","pathSpeedKmh":36,"pathLoop":true,"route":[{"latitude":39.9,"longitude":116.3},{"latitude":39.91,"longitude":116.3}]}' \
  "$BASE/api/target")"
python3 - "$applied" <<'PY'
import json, sys
result = json.loads(sys.argv[1])
assert result["ok"] is True, result
assert result["target"]["mode"] == "path" and result["target"]["routePoints"] == 2, result["target"]
print("target ok: path with", result["target"]["routePoints"], "points")
PY

if "$ADB" logcat -d | grep -E "FATAL EXCEPTION|Process: $PACKAGE" >/dev/null; then
  "$ADB" logcat -d | grep -A20 'FATAL EXCEPTION' >&2
  echo "the app crashed" >&2
  exit 1
fi

"$ADB" shell dumpsys activity services "$PACKAGE" | grep -q 'EngineService'
echo "PASS: APK installs, the foreground service runs, the console page and JSON API answer."
