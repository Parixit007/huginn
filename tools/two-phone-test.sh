#!/usr/bin/env bash
# Build plan 5.7: two Android devices (e.g. two emulators on the emulator's virtual Bluetooth, Spike A)
# pair and chat over real Bluetooth LE. Phone A shows a QR code; this script hands its text to phone B,
# which "scans" it. Both apps are reinstalled from scratch, with permissions granted.
#
#   tools/two-phone-test.sh <serial A> <serial B>      e.g. tools/two-phone-test.sh emulator-5554 emulator-5556
set -euo pipefail

A="${1:?serial of phone A (shows the QR code)}"
B="${2:?serial of phone B (scans it)}"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
TESTS="$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
RUNNER="app.raven.mesh.test/androidx.test.runner.AndroidJUnitRunner"
CLASS="app.raven.app.TwoPhoneBleTest"
OUT="$(mktemp -d)"

(cd "$ROOT" && ./gradlew -q :app:assembleDebug :app:assembleDebugAndroidTest)

for phone in "$A" "$B"; do
  "$ADB" -s "$phone" uninstall app.raven.mesh >/dev/null 2>&1 || true
  "$ADB" -s "$phone" uninstall app.raven.mesh.test >/dev/null 2>&1 || true
  "$ADB" -s "$phone" install -g "$APP" >/dev/null
  "$ADB" -s "$phone" install -g "$TESTS" >/dev/null
  "$ADB" -s "$phone" logcat -c
done

echo "A ($A): showing the QR code…"
"$ADB" -s "$A" shell am instrument -w -e role owner -e class "$CLASS" "$RUNNER" >"$OUT/a.txt" 2>&1 &
A_PID=$!

QR=""
for _ in $(seq 1 120); do
  QR="$("$ADB" -s "$A" logcat -d -s TwoPhone:I | sed -n 's/.*QR:\([0-9a-f]*\).*/\1/p' | tail -1)"
  [ -n "$QR" ] && break
  sleep 1
done
[ -n "$QR" ] || { echo "no QR code from A"; cat "$OUT/a.txt"; exit 1; }

echo "B ($B): scanning it, pairing and chatting over Bluetooth…"
"$ADB" -s "$B" shell am instrument -w -e role scanner -e qr "$QR" -e class "$CLASS" "$RUNNER" >"$OUT/b.txt" 2>&1 &
B_PID=$!
wait "$A_PID" || true
wait "$B_PID" || true

for phone in "$A" "$B"; do
  echo "--- $phone"
  "$ADB" -s "$phone" logcat -d -s TwoPhone:I | sed 's/^.*TwoPhone *: //' | grep -v '^QR:' || true
done
if grep -q "OK (1 test)" "$OUT/a.txt" && grep -q "OK (1 test)" "$OUT/b.txt"; then
  echo "PASS: paired and chatted over Bluetooth"
else
  echo "FAIL (instrumentation output in $OUT)"
  tail -5 "$OUT/a.txt" "$OUT/b.txt"
  exit 1
fi
