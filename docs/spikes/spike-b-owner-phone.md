# Spike B — the owner's phone

- **Date:** 2026-10-09
- **Code:** `spikes/ble-netsim/` (the same throwaway app as Spike A)
- **Phone:** Xiaomi Redmi Note 6 Pro, MIUI 12, **Android 9 (API 28)**, last security update **2020-11-05**

## Results

| Check | Result |
|---|---|
| Bluetooth LE | ✅ |
| Can advertise (act as a peripheral) | ✅ |
| LE 2M PHY (faster radio mode) | ✅ |
| Extended advertising (BLE 5 long adverts) | ❌. Legacy 31-byte adverts only, which is enough for our 128-bit service UUID |
| GATT server + advertising started | ✅ |
| Max packet size (MTU) | ✅ 517, negotiated by a device that connected (see below) |
| Filtered scanning | ✅ Started with status 0; Location is on (gps, network), which Android 9 needs for BLE scans |
| Background survival overnight (MIUI battery saver) | ⏳ Not tested yet: needs a foreground-service version of the spike and the phone left overnight |

## Findings

1. **Strangers' devices connect on their own.** Within seconds of advertising, an unknown nearby device connected to the phone's GATT server and negotiated MTU 517. The Bluetooth layer (Phase 5) must treat every incoming connection as untrusted: rate limits (already in the router), ignoring anything that isn't a valid packet, and not wasting one of the few connection slots on silent devices. → Add to the connection manager: drop links that send no valid LINK hello within a few seconds.
2. **Android 9 means the old permission model.** Bluetooth scanning needs the Location permission *and* Location switched on. Onboarding already explains this (D89); Phase 5 must also detect "Location is off" and tell the user.
3. **No security updates since November 2020.** Android's own image decoders on this phone have known, unfixed flaws (for example the 2023 WebP bug). Huginn decodes photos from contacts with those system decoders. → The v2 item "decode received images in an isolated process" matters for phones like this. The threat model already lists "OS bugs on old, unpatched phones".
4. **MIUI is known for stopping background apps.** The overnight foreground-service test is the next useful check on this phone.
5. **MIUI blocks automated UI tests.** It silently refuses to open the test screen until "Display pop-up windows while running in the background" is on for the debug build (MIUI permission 10021). → D92: switched on via adb for a test run, off again afterwards.
6. **Slower phones expose UI bugs.** The first UI test run here found the endless query → redraw loop that the fast emulator hid (fixed in Phase 4).

## Decisions taken with this spike

- **D90:** the owner's Android 9 phone is used for testing too (it overrides D81's "Android 13+ only" for this device).
- **D92:** the MIUI permission above is switched on via adb for phone test runs.
