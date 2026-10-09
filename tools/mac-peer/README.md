# Mac test peer

Your Mac acts as a second Raven phone over real Bluetooth (build plan 5.6). Dev-only; never shipped.

- `radio/` is a small Swift program that drives the Mac's Bluetooth through Apple's CoreBluetooth (no third-party packages, D95). It only moves raw fragments.
- `src/` is Raven's real engine (the same Kotlin code as the phone), with a `Transport` that talks to the helper.
- It only dials your phone and never advertises, and it forgets everything when you quit (D100).

## Use it

1. Install the latest debug build of Raven on your phone and open it once (Bluetooth on; on Android 8–11 Location on too).
2. On the Mac, in **Terminal**:
   ```
   tools/mac-peer/run.sh
   ```
   The first time, macOS asks to allow Bluetooth for Terminal: allow it.
3. Wait for "Connected over Bluetooth", then type `qr`. On the phone: **+ → Scan a QR code**, point it at the Mac's screen.
4. Check that both show the same 6-digit code, then type `yes` on the Mac.
5. Chat: type a message and press Enter. `/react 🔥` reacts to the last message, `/photo ~/Pictures/x.jpg` sends a photo, `/open` shows a received one, `/help` lists everything. Ctrl-C quits.

## Two phones without the Mac

`tools/two-phone-test.sh <serial A> <serial B>` pairs and chats two Android devices (for example two emulators
on the emulator's virtual Bluetooth) automatically, using the real Bluetooth transport.
