# How pairing works (plain language)

This explains the code in `core/crypto/.../pairing/` without the maths. Each step names the test that proves it (`PairingTest`).

## The idea in one paragraph

Alice shows a QR code with an **open padlock** in it: her public key. Bob scans it and sends back his own open padlock. Each phone combines its **own secret key** with the **other's padlock** and, thanks to the maths behind X25519, both arrive at the **same shared secret** without it ever travelling between them. Both phones then show a **6-digit code** derived from that secret. If the codes match, nobody got in between. Alice taps Accept, and from then on that shared secret protects every message between the two.

## Step by step

1. **Alice opens "Show my QR".** Her phone makes a fresh key pair just for this QR code, which is valid for 5 minutes and usable once. The QR code holds her public key, her device ID, her nickname and a session number. Nothing secret is in it, so a photo of the screen is useless to an attacker.
   *Test: `rule 5 - the QR code reveals no secret`*

2. **Bob scans it.** His phone makes its own key pair and sends a **request** to Alice's phone over a direct Bluetooth link; it is never relayed through other phones. The request carries his public key and his nickname, and the nickname is already encrypted so bystanders can't read it.

3. **Alice's phone answers with a fresh random number** (the "challenge"). It picks this number only *after* Bob's key has arrived. That stops an attacker from preparing a fake key in advance that happens to produce the same 6-digit code.
   *Test: `rule 2 - someone who changes the challenge on the way makes the codes differ`*

4. **Both phones show the same 6-digit code.** It is computed from both public keys, both device IDs and the random number. If anyone tampered with anything, the codes differ.
   *Test: `happy path - same code on both phones, then both get the same working key`*

5. **Alice and Bob compare the codes, and Alice taps Accept.** Alice's phone saves Bob and sends an encrypted "confirmed". Bob's phone saves Alice **only** when that confirmation arrives and checks out. A fake confirmation is ignored.
   *Tests: `rule 3 - Bob saves nothing until a valid confirm, and a decline is final`, `rule 3 - a confirm from any other phone is ignored`*

## What happens when something goes wrong

| Situation | What the phones do | Test |
|---|---|---|
| Someone else saw the QR code and also sends a request | Alice's phone **stops** and warns that two phones tried to pair; start again | `rule 1 - a second phone pairing at the same time aborts the session` |
| Bluetooth delivers Bob's request twice | Same answer again, no false alarm | `rule 1 - a repeated identical request is answered with the same challenge` |
| The codes don't match | Alice taps Reject; Bob's phone gets an encrypted "declined" and saves nothing | `rule 3 - … decline is final` |
| Pairing isn't finished within 5 minutes | Both sides give up; the temporary keys are wiped | `rule 4 - both sides expire after 5 minutes and a QR code works only once` |
| Random junk or a request for a different QR code arrives | Ignored; a real pairing still works afterwards | `garbage or wrong-session requests are ignored and do not abort a real pairing` |
| Someone scans their own QR code | Refused | `scanning your own QR code is refused` |

## After pairing

The shared secret ("root key") is split into **two keys, one per direction**: Alice→Bob and Bob→Alice. Every message gets a fresh random nonce, and its header (sender, recipient, packet ID) is locked to the encryption, so relays can't alter it without the message being rejected.
*Tests: `StaticKeyV1Test`: `a packet can't be reflected back to its sender`, `flipping any bit makes the packet invalid`, `changed header (associated data) makes the packet invalid`*

⚠️ **v1 limitation (spec D10):** the root key never changes. If it ever leaks, past and future messages of that chat are exposed. v2 adds forward secrecy.
