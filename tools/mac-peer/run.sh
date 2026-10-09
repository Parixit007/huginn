#!/usr/bin/env bash
# Raven Mac test peer (build plan 5.6, D95, D100): your Mac acts as a second Raven phone over real Bluetooth.
# Builds the Swift Bluetooth helper and the Kotlin engine, then starts it. Dev-only, never shipped.
#
#   tools/mac-peer/run.sh [nickname]        default nickname: Mac
#
# The first time, macOS asks to allow Bluetooth for your terminal app: allow it.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
HELPER="$HERE/build/raven-radio"
STUDIO_JDK="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
if [ -z "${JAVA_HOME:-}" ] && [ -d "$STUDIO_JDK" ]; then export JAVA_HOME="$STUDIO_JDK"; fi

if [ ! -x "$HELPER" ] || [ "$HERE/radio/main.swift" -nt "$HELPER" ] || [ "$HERE/radio/Info.plist" -nt "$HELPER" ]; then
  echo "Building the Bluetooth helper…"
  mkdir -p "$HERE/build"
  swiftc -O -swift-version 5 -o "$HELPER" "$HERE/radio/main.swift" \
    -Xlinker -sectcreate -Xlinker __TEXT -Xlinker __info_plist -Xlinker "$HERE/radio/Info.plist"
fi

echo "Building the Raven engine for the Mac…"
(cd "$ROOT" && ./gradlew -q :tools:mac-peer:installDist)

exec "$HERE/build/install/mac-peer/bin/mac-peer" --radio "$HELPER" --name "${1:-Mac}"
