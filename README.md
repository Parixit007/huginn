# Raven — Spec Sheet v0.3

> Status: v0.1 approved by owner on 2026-10-07. v0.2 added the decisions from **Audit #1** (`AUDIT.md`). v0.3 **removes group chats from v1** (owner decision, same day; the group design is parked in §14). **No code yet.**
> Name: **Raven** (working name, D94; it replaced the earlier working name Huginn, Odin's raven "Thought"). The name can change at any time before public release, because protocol identifiers are brand-neutral (D47). The project folder is still named `Hopper`.
>
> Legend: ✅ = decided by owner · 🟡 = proposal, needs owner OK · ❓ = open question · ⚠️ = known v1 limitation · ⏸ = decided but parked (not in v1)

---

## 1. What it is

An offline-first, end-to-end encrypted Android messenger (in the spirit of bitchat) for **1-to-1 chats**. Phones talk over
**Bluetooth Low Energy** and relay each other's encrypted messages in a **multi-hop mesh**.
**No internet, no servers, no accounts, no phone numbers.** You add a friend by scanning a QR code in person.

## 2. Decision log

| # | Topic | Decision | Status |
|---|---|---|---|
| D1 | Platform | Android only | ✅ |
| D2 | Tech stack | Native Kotlin + Jetpack Compose | ✅ |
| D3 | Min Android | 8.0 / API 26 | ✅ |
| D4 | Network | Bluetooth LE only. **Never** internet. | ✅ |
| D5 | Routing | Multi-hop mesh relaying | ✅ |
| D6 | Relay store-and-forward | ~~No~~ → superseded by D65 (store-and-forward is in v1) | ✅ |
| D7 | Who relays | Every Raven phone relays for everyone (encrypted blobs only) | ✅ |
| D8 | Hop limit | **8 hops**: a message crosses at most 8 links from sender to receiver | ✅ |
| D9 | Offline send | Message queues on the **sender's** phone as "pending" and auto-sends when the friend is reachable. Gives up after 3 days (D43). | ✅ |
| D10 | Encryption v1 | **One static shared key per contact.** It is created by key agreement during QR pairing and never shown in the QR (updated by D37). Ratchet and identity keys come later. | ✅ |
| D11 | Content v1 | Text, emoji reactions, small images | ✅ |
| D12 | Chats v1 | **1-to-1 only** (QR contacts). Group chats were removed from v1 (D54). | ✅ |
| D13 | Group join | Scan the group QR, send a join request, an admin approves | ⏸ |
| D14 | Packet metadata | **Plain device IDs** (sender/recipient) on packets. v1 shortcut with a known privacy cost (§9). | ✅ |
| D15 | Device ID | Random ID created at install, not tied to MAC/IMEI/phone number | ✅ |
| D16 | Profile | Nickname + avatar | ✅ |
| D17 | Local security v1 | Encrypted database | ✅ |
| D18 | Receipts | Pending, sent, delivered, read | ✅ |
| D19 | Background | Always-on foreground service (persistent notification) | ✅ |
| D20 | License | MIT | ✅ |
| D21 | Testing | Pluggable transport layer + in-memory fake transport | ✅ |
| D22 | Deferred to later versions | App lock, panic wipe, screenshot block, disappearing messages, voice notes, public nearby room | ✅ |
| D23 | QR pairing | ~~B sends hello, A accepts~~ → superseded by D37 | ✅ |
| D24 | Crypto library | Google Tink (XChaCha20-Poly1305, X25519, HKDF) | ✅ |
| D25 | Image limit | ≤ 50 KB after compression, longest side 1024 px | ✅ |
| D26 | Group size | Max 32 members, enforced when an admin approves a join | ⏸ |
| D27 | Contact QR validity | Single use; a fresh key pair each time the QR screen opens; expires after 5 min | ✅ |
| D28 | Text length | Max 2,000 characters (up to ~6 KB encoded) | ✅ |
| D29 | UI | Material 3, light + dark, English only (more languages later) | ✅ |
| D30 | Removal v1 | Delete contact, block contact. (Leave group / kick are parked with groups.) | ✅ |
| D31 | Avatar | 192×192, ≤ 20 KB. Sent after pairing; updates are pushed to contacts (D42). | ✅ |
| D32 | App name | ~~**Huginn**, working name. Can change at any time before public release.~~ → superseded by D94 | ✅ |
| D33 | Package name | ~~`app.huginn.mesh`, placeholder. Free to change until the first public release.~~ → superseded by D94 | ✅ |
| D34 | Packet IDs | Outer **packet ID** (new for every transmission; used by relays to drop duplicates) + inner encrypted **message ID** (same across retries) | ✅ (audit E1) |
| D35 | Image transfer | Every chunk is its own packet; a manifest carries chunk count + image hash; the receiver requests missing chunks; "delivered" only after the full image is verified | ✅ (audit E2) |
| D36 | Replay & ordering | Received message IDs stored per chat (unique). Per-sender counter for order and gaps. **No clock window.** Show the sender's time, sort by counter/arrival. | ✅ (audit E3) |
| D37 | Pairing handshake | QR holds a **public key** (X25519). The shared key is computed privately. Both phones show a **6-digit code** to compare. The request is accepted only over a direct link (never relayed). A sends "accepted" before both phones save. | ✅ (audit S1, E7) |
| D38 | Group join security | The group QR holds no secret. The group key is sent encrypted only to approved joiners. | ⏸ |
| D39 | Group admins | The creator, plus admins the creator picks | ⏸ |
| D40 | Group QR validity | Valid while on screen, max 5 min | ⏸ |
| D41 | Group receipts | Delivered only, batched; no read receipts in groups (delegated by owner) | ⏸ |
| D42 | Profile updates | Nickname/avatar changes are pushed to all contacts. (The group part, "everyone sees nickname + avatar of all members", is parked.) | ✅ / ⏸ |
| D43 | Pending expiry | After **3 days**, the message shows "Not delivered — retry?" | ✅ (audit E8) |
| D44 | Presence | **No "I'm here" broadcasts.** Pending messages retry when new phones come into range (with backoff). The nearby count comes from direct links. | ✅ (audit S3) |
| D45 | Routing v1 | Plain flooding. Smart flooding + route learning planned for **v1.1**. | ✅ (audit F3) |
| D46 | Packet privacy | Relays see only version, hops left, packet ID, sender, recipient. Type, IDs, time, counter and chunk info are encrypted. Payloads are padded to fixed sizes. | ✅ (audit S2) |
| D47 | Protocol identifiers | Brand-neutral (QR format, BLE service UUID), so a rename never breaks compatibility | ✅ (audit C1) |
| D48 | Hardening bundle | Strip photo metadata + checks before decoding; H1–H12; connection manager; DB usable after first unlock; library/wording fixes (AUDIT.md S4, S5, S9, F1, C2–C6) | ✅ |
| D49 | Notifications | Sender name only, never message text | ✅ |
| D50 | Auto-start | The mesh starts by itself after a reboot (after the first unlock) | ✅ |
| D51 | Block vs groups | A blocked person's group messages are still shown | ⏸ |
| D52 | Test hardware | 1 real phone for now. ⚠️ The real BLE layer stays untested until 2–3 phones are available. | ✅ |
| D53 | bitchat compatibility | Never. Raven stays an independent protocol. | ✅ |
| D54 | Group chats | **Removed from v1.** The design is parked in §14. The version they return in will be decided later. | ✅ |
| D55 | Group join check | Nickname only; no 6-digit code comparison for group joins | ⏸ |
| D56 | Group creator leaving | The creator must pick a successor before leaving | ⏸ |
| D57 | Wire protocol v1 | `docs/PROTOCOL.md` approved: field sizes, outer/inner packet layout, per-direction keys, 4-step pairing with abort on two requests | ✅ |
| D58 | Padding sizes | 128, 256, 512, 1024, 2048, 4096, 8192 bytes | ✅ |
| D59 | Nickname length | Max 32 characters | ✅ |
| D60 | QR text format | `MSH1:` + Base45 | ✅ |
| D61 | Test framework | JUnit 6.1.3 (Jazzer fuzzing confirmed compatible) | ✅ |
| D62 | Code checker | detekt 2.0 alpha (stable 1.23.8 can't run on Java 25) | ✅ |
| D63 | detekt rule tweak | `ReturnCount.excludeGuardClauses: true`: early "reject bad input" exits don't count | ✅ |
| D64 | Timer retry | While the sender has at least one phone nearby, pending messages also retry on a slow timer (e.g. 2 min, slowing to 30 min), not only when a new phone appears. Catches friends who appear 2+ hops away. | ✅ |
| D65 | Store-and-forward | **In v1** (owner decision 2026-10-08; supersedes D6). Phones carry other people's encrypted messages for a while and hand them on when they meet new phones. Details: D66–D71. | ✅ |
| D66 | Carry rule | **Carry until passed on, then drop.** A phone that has other neighbours forwards immediately and stores nothing; only a phone with nobody else around stores the message, hands it to the next phone it meets, then drops it. No "got it" notices. The sender keeps its own copy and retries (D9, D43, D64). | ✅ |
| D67 | Carry time | Up to **3 days per carrier** (so a message can travel up to ~24 days across 8 carriers) | ✅ |
| D68 | What is carried | Everything, including photos and avatars | ✅ |
| D69 | Carried storage | Up to **100 MB**, oldest dropped first. Stored on disk, encrypted with a key that exists **only in memory**: a restart or power-off makes the files unreadable, and they are deleted at startup. | ✅ |
| D70 | Carrying opt-out | None: always on, like relaying (D7) | ✅ |
| D71 | LINK packets | New packet kind for neighbours only (never relayed): OFFER ("I can give you these packet IDs") and WANT ("I need these"), so a carrier never wastes its handover | ✅ |
| D72 | Phase 2 starting values | Receipt wait 30 s, then up to 3 resends · timer retry 2 → 30 min · seen packet IDs kept 3 days · 100 packets/s per neighbour · max 4 photos reassembling at once · photo pieces fit the 512-byte padding step (~455 B, ~113 per 50 KB photo) · mesh engine tested on a virtual clock, no new libraries | ✅ |
| D73 | detekt rule tweak | `TooManyFunctions` counts only a class's public API (`ignorePrivate`, `ignoreOverridden`) | ✅ |
| D74 | Stalled photo | A photo whose pieces stop arriving stays in reassembly for up to **3 days** (matches the carry time), then the sender's retry restarts it. Trade-off: a stuck photo can hold one of the 4 reassembly slots that long. | ✅ |
| D75 | Given-up messages | The engine forgets a message once it shows "Not delivered"; the outbox (encrypted database in Phase 3) keeps it, so "Retry" works and a late receipt still marks it delivered | ✅ |
| D76 | Seen-cache memory | Compact storage: same 200k IDs and 3 days, ~5 MB instead of ~15 MB; secret hash seed against crafted IDs | ✅ |
| D77 | Replay IDs on chat delete | Received message IDs are **kept** when a chat is deleted (16 bytes each, no content), so recorded old packets can't make deleted messages reappear | ✅ |
| D78 | Pending after restart | Queued messages and photos are stored in the encrypted database and keep trying after an app restart or reboot; the 3-day limit uses wall-clock time | ✅ |
| D79 | Phase 3 tooling | Room 2.8.5 + KSP 2.3.12 (build-time only), SQLCipher 4.19.1, tink-android 1.23.0, AndroidX Test (JUnit 4) for on-device tests | ✅ |
| D80 | Database key | A random 32-byte key, wrapped by an AES-256-GCM key in the Android Keystore; usable after the first unlock (S9) | ✅ |
| D81 | Storage test devices | Android 13+ only (the Android 17 emulator); no extra downloads. ⚠️ Android 8–12 untested for now although `minSdk` is 26 (D3). | ✅ |
| D82 | Given-up wording | **"Not confirmed yet — retry?"** (a carrier may still deliver it; flips to ✓✓ on a late receipt). Replaces "Not delivered" in the UI. | ✅ |
| D83 | Colours | ~~Material 3 dynamic colour from the wallpaper (Android 12+), neutral fallback; follows light/dark~~ → superseded by D93 | ✅ |
| D84 | Home screen | Chat list with last message and ticks; a + button opens Show QR / Scan QR; profile in the top menu; nearby count in the top bar | ✅ |
| D85 | App icon | A raven, olive bird on a lime background (placeholder until the final name) | ✅ |
| D86 | UI libraries | Navigation Compose, Lifecycle/ViewModel Compose, CameraX + ZXing, Android photo picker, AndroidX emoji picker; no dependency-injection framework | ✅ |
| D87 | Reactions | Quick row 😂 😭 👍 🔥 ❤️ plus a full emoji picker | ✅ |
| D88 | Sending photos | From the gallery (photo picker) or taken in the app; in-app photos are never saved to the gallery | ✅ |
| D89 | Permission timing | Bluetooth + notifications during onboarding, each with an explanation; camera at the first QR scan | ✅ |
| D90 | Owner's phone | Xiaomi Redmi Note 6 Pro, **Android 9**, no security updates since 2020-11: used for testing too (overrides D81's Android 13+ limit for this device); Android 8–12 stay supported | ✅ |
| D91 | Test tooling on Android 17 | Device UI tests pin Espresso 3.7.0 (test-only, never in the app): the 3.5.0 that Compose's test kit brings crashes on Android 17 | ✅ |
| D92 | UI tests on the owner's MIUI phone | MIUI blocks test screens by default; before a phone run, the "Display pop-up windows while running in the background" permission is switched on for the Raven debug build only, via adb, and switched back off after the run | ✅ |
| D93 | Colours | Lime accent on every phone, like WhatsApp's green (owner decision 2026-10-09; supersedes D83). All Material 3 roles built from the icon's lime #C3E24F; follows light/dark. Plain top bar; lime + button, send button, avatars, unread badges and main buttons, all with dark text; my bubbles pale lime (deep olive in dark mode); small text accents deep lime-olive #516601 in light mode, since lime text on white is unreadable (1.4:1) | ✅ |
| D94 | Name: **Raven** | Renamed everywhere (owner decision 2026-10-09; supersedes D32/D33): launcher label and on-screen text, package ID `app.raven.mesh`, code packages `app.raven.*`, docs. Still a working name until a trademark check (open item). Protocol identifiers unchanged (D47). GitHub repo renamed `huginn` → `raven` | ✅ (working) |

## 3. Features (v1)

- **Onboarding**: pick a nickname + optional avatar, and the app generates a random device ID. Permission screens explain why each permission is needed, plus a guide to the phone's battery settings.
- **Add contact**: show my QR or scan theirs → request → both compare a 6-digit code → Accept → both phones save the contact.
- **1-to-1 chat**: text (≤ 2,000 chars), emoji reactions, small images (≤ 50 KB, location and other metadata removed).
- **Message states**: ⏳ pending → ✓ sent → ✓✓ delivered → 👁 read.
- **Pending queue**: retries when new phones come into range; after 3 days shows "Not confirmed yet — retry?" (D82).
- **Profile**: nickname and avatar can be changed at any time; the update reaches all contacts.
- **Contacts**: delete, block, re-scan to re-pair (replaces the old key).
- **Background**: the mesh service keeps receiving and relaying with the app closed, and starts again after a reboot.
- **Notifications**: show the sender's name only.
- **Nearby indicator**: the number of Raven phones directly connected (no identities shown).

## 4. Cryptography (v1)

- **Library**: Google Tink for Android. Not `androidx.security:security-crypto`, which is deprecated.
- **Contact key**: one static 256-bit key per contact, created at pairing (§5). Separate keys for each direction (A→B, B→A) are derived from it with HKDF.
- **Cipher**: XChaCha20-Poly1305 using Tink "no-prefix" keys. Tink puts the random 24-byte nonce inside its output, and the chance of two nonces colliding is negligible.
- **Authenticated header**: version, packet ID, sender ID and recipient ID are covered by the encryption tag, so relays can't change them. "Hops left" is excluded because it changes at every hop. Every retry is re-encrypted with a new packet ID, so observers can't link retries together.
- **Replay protection**: each chat stores the message IDs it has received and drops repeats. A per-sender counter orders messages and detects gaps. There is no clock check (D36).
- **Crypto behind an interface** (`CryptoSuite`) so v2 can swap in identity keys + Noise/Double Ratchet without touching the mesh or UI.
- ⚠️ Known v1 limitation: no forward secrecy. A leaked contact key exposes all past and future messages in that chat.

## 5. Pairing

**QR format**: binary data that starts with a brand-neutral marker and a version byte (D47). No separate checksum is needed because QR codes already have error correction. QR codes are scanned **only with the in-app camera** (ZXing). There are no web links that can start a pairing.

**Contact pairing (D37):**
1. A opens "Show my QR". The phone makes a **fresh** key pair. The QR contains A's public key, device ID, nickname and the version. It is single use and expires after 5 min.
2. B scans it. B's phone makes its own key pair and sends a request (B's public key, ID and nickname) to A **over a direct Bluetooth link only**. Requests are never relayed.
3. A's phone answers with a fresh random value. Both phones compute the same shared key (X25519 → HKDF) and show the same **6-digit code**, derived from both public keys and that random value.
4. The users compare the codes and A taps **Accept**. A's phone sends "accepted" (encrypted), and only now do both phones save the contact. Avatars are then exchanged encrypted. The temporary private keys are deleted.
5. Scanning an existing contact again replaces the old key.

## 6. Mesh & transport

- **Transport interface** (`Transport`): start/stop, the current links (connected neighbours), `send(link, bytes)`, `onReceive(link, bytes)`. Implementations: `BleTransport` (real) and `FakeTransport` (in-memory, simulates many nodes with loss and reordering).
- **BLE link layer**:
  - Every phone is both advertiser/GATT server *and* scanner/GATT client, using a brand-neutral 128-bit service UUID.
  - Scan only with a service filter, and start scans at most 5 times per 30 s.
  - Adaptive scan duty cycling to save battery.
  - Negotiate MTU (up to 517), falling back to 20-byte writes if that fails.
  - Per-hop fragmentation lives inside the transport; relays never see it.
- **Connection manager**:
  - A maximum number of links (around 4–5, tuned in testing).
  - When two phones see each other, the one with the lower device ID connects, so the pair never connects twice.
  - Links are rotated periodically so new neighbours get a turn.
  - The app warns if the phone can't advertise.
- **Routing v1 (D45)**: plain flooding.
  - Each packet starts with "hops left" = 8.
  - A memory-only "seen" cache of packet IDs makes every node forward each packet **once**, to all links except the one it arrived on.
  - v1.1 adds smart flooding + route learning.
- **Outer packet (seen by relays)**: `version | kind (DATA / HANDSHAKE) | hops left | packet ID | sender ID | recipient ID | ciphertext`
  - HANDSHAKE packets (pairing requests) are never relayed.
- **Inner packet (encrypted)**: `type | message ID | sender counter | timestamp | chunk index/count | body | padding`
  - Types: TEXT, REACTION, IMAGE_MANIFEST, IMAGE_CHUNK, CHUNK_REQUEST, ACK_DELIVERED, ACK_READ, PROFILE, PAIR_ACCEPTED.
- **Delivery**: if no "delivered" arrives in time, the message is re-encrypted with a new packet ID and resent (up to N times), then it goes back to pending. Pending messages retry whenever a new phone connects (with backoff) and expire after 3 days.
- **Abuse limits**: rate limits per neighbour; caps on memory used for unfinished images and on packet size.

## 7. Storage

- Room database encrypted with SQLCipher (`net.zetetic:sqlcipher-android`).
- The DB passphrase is random and wrapped by an Android Keystore key. It stays usable after the first unlock since boot, so messages can arrive while the screen is locked (audit S9).
- Contact keys are stored inside the encrypted DB.
- Images are encrypted on disk with Tink and never saved to the public gallery.
- Android backup and device-to-device transfer of app data are disabled.
- The relay "seen" cache lives only in memory. Carried messages (D69) sit on disk only encrypted with a memory-only key, so they are unreadable after a restart or power-off.
- Native libraries must support 16 KB memory pages.

## 8. Android platform

- `minSdk` 26; `targetSdk` is the latest available.
- **Permissions**:
  - Bluetooth. Android 8–11 needs Location (and often Location switched on). Android 12+ uses Nearby devices: `BLUETOOTH_SCAN` (neverForLocation), `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`.
  - `CAMERA` for QR scanning.
  - `POST_NOTIFICATIONS` on Android 13+.
  - `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` on Android 14+.
  - `RECEIVE_BOOT_COMPLETED`.
  - **No `INTERNET`.** The build fails if any library adds it.
- **Foreground service** of type `connectedDevice`, which starts after reboot once the phone has been unlocked for the first time.
- **UI**:
  - Material 3, light and dark, English.
  - "Incognito keyboard" on chat input fields.
  - Taps on pairing and Accept buttons are ignored while another app draws over the screen.
  - Nicknames are cleaned of invisible and direction-flipping characters and length-capped.
- **Hygiene**:
  - Gradle dependency verification.
  - No analytics or crash-reporting SDKs.
  - No plaintext or keys in logs.

## 9. Threat model (v1, honest version)

**Protected against:**
- Relays and Bluetooth sniffers **reading** message content.
- Relays **modifying** or forging packets without the key.
- Someone **photographing a QR code**: there is no secret in it.
- Someone **racing your friend** during pairing: the 6-digit codes won't match.
- **Replays** of old messages.
- Learning **what kind** of packet you send (read receipt vs message vs photo). Timing is still visible.
- Copying the app's files off the phone: the DB is encrypted and backups are disabled.
- **Location leaks via photos**: metadata is stripped.
- The app accidentally phoning home: it has no internet permission.

**Not protected against in v1** ⚠️:
- A Bluetooth sniffer seeing **who talks to whom and when** (plain device IDs, D14), and following your static ID across places **while you are sending**.
- Anyone detecting that **a phone runs Raven** (the BLE advertisement).
- A leaked contact key exposing that chat's **past and future** messages (no forward secrecy).
- Someone holding your **unlocked** phone (no app lock or panic wipe yet).
- Forensic tools on a **locked but powered-on** phone (the DB is usable after the first unlock).
- Large-scale flooding or jamming of the mesh. Rate limits help but don't solve it.
- **Carriers (D65–D69):** for up to 3 days, a stranger's phone may hold your encrypted message together with the plain sender and recipient IDs. While that phone is switched on, someone with forensic tools could read who was messaging whom (not the content). After a restart, nothing is readable.
- **Late delivery:** a carried message can arrive days after the sender saw "Not confirmed yet".
- OS-level bugs on old, unpatched phones (Android 8–9).

👉 v1 is a prototype. It should **not** be presented as safe for high-risk users (journalists, activists) until v2.

## 10. Architecture

```
:app             – Compose UI, navigation, foreground service
:core:model      – data classes (Contact, Message, Packet)
:core:crypto     – CryptoSuite interface + StaticKeyV1 implementation, pairing handshake
:core:mesh       – packet parsing, routing, dedup, TTL, chunking, pending queue (pure Kotlin → unit-testable, fuzz-tested)
:core:transport  – Transport interface
:transport:ble   – BLE implementation + connection manager
:transport:fake  – in-memory multi-node simulator for tests
:data            – Room + SQLCipher, repositories
```

## 11. Testing

- Unit tests: crypto test vectors, the pairing handshake, and mesh logic on `FakeTransport` (many nodes, multi-hop, loss, reordering).
- Fuzz tests on the packet parser, because it reads data from strangers.
- Spike: check whether the emulator's virtual Bluetooth (netsim) can run our BLE layer.
- ⚠️ Only 1 real phone (D52), so the real BLE layer stays untested until 2–3 phones are available.

## 12. Roadmap

- **v1 (prototype)**: everything above (1-to-1 only).
- **v1.1**: smart flooding + route learning.
- **v2 (security)**:
  - identity keys + Noise XX handshake + Double Ratchet (forward secrecy);
  - hidden/rotating recipient IDs;
  - app lock, panic wipe, screenshot block, disappearing messages;
  - encrypted spool while the phone is locked;
  - isolated image decoding.
- **v3 (features)**: voice notes, public nearby room, Wi-Fi Direct for big files. (Relay store-and-forward, "Muninn" mode, moved into v1: D65.)
- **Group chats**: version to be decided later (D54). Design parked in §14.

## 13. Open questions

1. ❓ Final app name (working name: Raven) + trademark check, before public release.
2. ❓ Package name (placeholder `app.raven.mesh`), before public release.
3. ❓ Distribution: Play Store / F-Droid / APK (affects some permission policies), before release.
4. ❓ Tuning values, to be set during the build: max links, retry backoff, padding sizes, chunk size, ack timeout.

## 14. Parked: group chats (not in v1)

These group decisions were made on 2026-10-07 and are kept here for when groups return:

- **Joining (D13, D38, D40, D55)**:
  - The creator or an admin shows a group QR with no secret in it: group ID, name, admin ID, a fresh admin public key. It is valid while on screen, max 5 min, and many people can scan it.
  - Each joiner sends a request over a direct link. The admin approves based on the nickname (no code comparison).
  - The group key is sent encrypted to that joiner only.
- **Size (D26)**: max 32 members, enforced when an admin approves.
- **Admins (D39, D56)**: the creator, plus admins the creator picks. The creator must pick a successor before leaving.
- **Receipts (D41)**: delivered only, batched; no read receipts in groups.
- **Profiles (D42)**: nickname + avatar of every member, sent once on join and cached; non-contacts get a "not a contact" badge.
- **Blocking (D51)**: a blocked person's group messages are still shown.
- ❓ **Retry policy**: undecided. Groups were dropped before this was answered.
- ⚠️ **Weak spots if built on v1 static keys**:
  - any member can impersonate another or fake admin changes;
  - former members keep the key and can read new messages;
  - no kick;
  - with nickname-only approval, the admin must recognise joiners in person.

  v2 identity keys + rekeying would fix all of these.
