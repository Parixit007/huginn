# Spike A — Bluetooth LE between two Android emulators

- **Date:** 2026-10-08
- **Code:** `spikes/ble-netsim/` (throwaway; separate Gradle build; never part of the app)
- **Question:** can two emulators on one Mac talk Bluetooth LE to each other, so Phase 5 can be developed without several phones?

## Setup

- Emulator 37.2.12; two instances of `Medium_Phone_API_37.0` (Android 17, API 37), started with `-read-only` so one virtual phone can run twice.
- Both connect automatically to the emulator's shared virtual radio (`netsimd`, using the Root-Canal controller).
- One instance is the **peripheral**: advertises a service UUID and runs a GATT server. The other is the **central**: scans with a service filter, connects, negotiates the MTU, requests the 2M PHY, subscribes to notifications, then PING/PONG and two 50 KB transfers.

## Result: ✅ works

| Check | Result |
|---|---|
| Discovery with a service-UUID scan filter | ✅ (RSSI −8) |
| Connection | ✅ |
| MTU | ✅ 517 |
| 2M PHY | ✅ tx = rx = LE 2M |
| Notifications | ✅ |
| PING → PONG round trip | ✅ 19 ms |
| 50 KB, write-without-response | ✅ 1.5 s at the receiver (32.5 KB/s) |
| 50 KB, write-with-response | ✅ 2.4 s at the receiver (20.8 KB/s) |

⚠️ These are **virtual radio** numbers. Real phones will be slower and less steady, and the spike gives no information about brand-specific bugs, range, or background behaviour.

## Lessons for the real Bluetooth layer (Phase 5)

1. **`neverForLocation` is required.** On Android 12+, scan results reach an app only if it holds Location **or** declares `BLUETOOTH_SCAN` with `usesPermissionFlags="neverForLocation"`. Without either, results are dropped with no error, even though the radio sees the other phone. Spec §8 already plans this flag.
2. **At most 512 bytes per write**, even with MTU 517: ATT limits one attribute value to 512 bytes. Android throws an error inside the Bluetooth callback that is easy to miss. Chunk size = `min(MTU − 3, 512)`.
3. **The scan can report the same phone twice** before stopping. Without a guard, the app opens two connections to one phone. The connection manager (F1) must ignore duplicates.
4. **Bluetooth must not live in a screen (Activity).** The emulator recreated the screen at launch, which started a second copy of the Bluetooth logic. The real app keeps all Bluetooth in the foreground service (already planned).
5. **Old connections can linger** for a while after an app is killed: the peripheral saw a connection from the previous run before its service was even added. The connection manager must handle stale links.
6. Android's write flow control: after each write, wait for its callback before sending the next one. No "busy" retries were needed this way.

## Consequence for the plan

Phase 5 can be developed and tested on **pairs of emulators**, plus the Mac test peer, before any second phone exists. Real-phone testing (Spike B, Phase 7) is still needed for range, speed, background survival and brand quirks.

## How to rerun

```bash
emulator -avd Medium_Phone_API_37.0 -read-only -no-snapshot -no-window -port 5554 &
emulator -avd Medium_Phone_API_37.0 -read-only -no-snapshot -no-window -port 5556 &
./gradlew -p spikes/ble-netsim assembleDebug
adb -s emulator-5554 install -r -g spikes/ble-netsim/app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install -r -g spikes/ble-netsim/app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -S -n app.raven.spike.ble/.SpikeActivity --es mode peripheral
adb -s emulator-5556 shell am start -S -n app.raven.spike.ble/.SpikeActivity --es mode central
adb -s emulator-5556 logcat -s RavenSpike
```
