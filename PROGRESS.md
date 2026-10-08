# Progress log

One entry per build-plan step: what was done, files, test results, decisions. Newest phase at the bottom.

---

## Phase 0 — Setup & spikes (started 2026-10-07)

### 0.1 Dev machine ✅
- Android Studio 2026.2 with bundled JDK 25. SDK installed: platform android-37.0, build-tools 36.0.0, emulator 37.2.12, virtual phone `Medium_Phone_API_37.0` (Android 17).
- Owner's phone: not connected yet (needed for step 0.7).

### 0.2 Version control ✅
- `git init` (branch `main`). The public repo https://github.com/Parixit007/huginn was created and added as `origin`. The owner commits and pushes.
- Files: `.gitignore` (build output, IDE files, `local.properties`, keystores/signing files, fuzz corpus), `LICENSE` (MIT, Parixit007).
- Local-only files are excluded through `.git/info/exclude`, which is not part of the repo.

### 0.3 Gradle skeleton ✅
- Gradle 9.8.0 wrapper, pinned by SHA-256 (the distribution checksum was verified against services.gradle.org before use).
- AGP 9.4.1 with built-in Kotlin; Kotlin 2.4.20 (confirmed: AGP's bundled 2.2.10 is upgraded to 2.4.20).
- 8 modules from spec §10:
  - Kotlin/JVM: `:core:model`, `:core:crypto`, `:core:mesh`, `:core:transport`, `:transport:fake`
  - Android library: `:transport:ble`, `:data`
  - Android app: `:app` (Compose; shows "Huginn")
- `minSdk` 26, `compileSdk`/`targetSdk` 37, Java 17 bytecode.
- Code packages: `app.huginn.*`; application ID `app.huginn.mesh` (placeholder, D33).
- Files: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `gradle/wrapper/*`, `gradlew`, `gradlew.bat`, module `build.gradle.kts` files, `app/src/main/**`, `app/proguard-rules.pro`.

### 0.4 Build guards ✅
- **No-internet guard:** `verifyReleaseNoInternet` reads the merged release manifest and fails the build if `android.permission.INTERNET` is present.
  - Proven: adding the permission → *"Huginn must never use the internet."* → build failed. The manifest was restored afterwards.
- **Backups off:** `allowBackup=false`, `fullBackupContent` rules (Android 8–11), `dataExtractionRules` (Android 12+: cloud backup and device transfer).
- **Dependency verification:** `gradle/verification-metadata.xml` holds the SHA-256 of 422 components, plus Linux/Windows `aapt2` entries for CI and Windows developers.
  - Proven: one checksum altered → *"Dependency verification failed … kotlin-stdlib-2.4.20.jar"*; passes again after restoring.
- **Code checks:** Android Lint, ktlint 1.8.0 (official style), detekt **2.0.0-alpha.6**.
  - detekt's latest stable release (1.23.8) crashes on Java 25; the owner chose the alpha (2026-10-07).
  - Proven: a planted bad function was caught (NestedBlockDepth, MagicNumber).
- **Lint:** 0 errors. Remaining warning: no app icon (comes in Phase 4).
- **Release build:** minified and resource-shrunk. Requested permissions: only AndroidX's private `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`.
- Known harmless warning: a third-party Gradle plugin uses an API Gradle will remove in version 11.

### 0.5 CI ✅ (first run happens when the owner pushes)
- `.github/workflows/ci.yml`: on every push and pull request → validate the Gradle wrapper → JDK 25 (Temurin) → `./gradlew build` (compile, unit tests, Lint, ktlint, detekt).
- Actions pinned to commits: checkout v7.0.1, setup-java v6.0.1, gradle/actions v6.4.0.

### Tooling check: JUnit 6 + Jazzer ✅
- Owner's condition: "JUnit 6 if Jazzer works with it". Tested in a throwaway project with JUnit 6.1.3 + Jazzer 0.30.0:
  - normal test run passes;
  - fuzzing mode found a planted crash within seconds.
- → JUnit 6.1.3 will be used in Phase 1.

### 0.6 Spike A — emulator Bluetooth (netsim) ✅
- Two emulators did the **full BLE flow**: discovery, connection, MTU 517, 2M PHY, notifications, PING/PONG in 19 ms, and 50 KB in 1.5 s (write-without-response) / 2.4 s (with response). These are virtual radio speeds.
- **Conclusion:** Phase 5 can be developed on pairs of emulators (plus the Mac peer) before more phones exist.
- Six lessons were found for the real Bluetooth layer, among them: `neverForLocation` is required; at most 512 bytes per write; duplicate scan results; Bluetooth must live in the service; stale links.
- Report: `docs/spikes/spike-a-emulator-bluetooth.md`. Code: `spikes/ble-netsim/`.

### 0.7 Spike B — owner's phone ⏳ waiting for the phone

---

## Phase 1 — Crypto & packet format (started 2026-10-08)

Decisions before coding: protocol `docs/PROTOCOL.md` approved (D57); padding 128→8192 (D58); nickname ≤ 32 characters (D59); QR text `MSH1:` + Base45 (D60); JUnit 6.1.3 (D61).

### 1.1 Basic types ✅ (`:core:model`)
- `DeviceId` (8 B), `PacketId` (8 B), `MessageId` (16 B), `ImageId` (16 B), `SessionId` (8 B): compared by content, defensive copies, hex in logs.
- `Nickname`: removes control, invisible and direction-flipping characters (H6); 1–32 characters. `strict()` for received names.
- `ByteReader`/`ByteWriter`: bounds-checked, big-endian, strict UTF-8.
  - Malformed input becomes "invalid" through `decodeOrNull`.
  - Any other exception is treated as a bug and is not hidden, so fuzzing can find real bugs.

### 1.2 Encryption ✅ (`:core:crypto`)
- `ContactCipher` / `CryptoSuite` interfaces. The mesh sees only these, so v2 can swap in a ratchet.
- `StaticKeyV1`: XChaCha20-Poly1305 (Tink 1.23.0, no-prefix keys), per-direction keys via HKDF (H8), 40 bytes overhead.
- Tink is compile-only in the crypto module (the app will supply `tink-android`), so the two artifacts never clash.
- X25519 rejects all-zero shared secrets (low-order keys).

### 1.3 Pairing handshake ✅
- `InviteSession` (phone showing the QR) and `ScanSession` (phone scanning it) implement docs/PROTOCOL.md §5.
- All five safety rules are covered: abort on a second phone; the nonce is chosen after the peer's key; B saves only after a valid CONFIRM; temporary keys are wiped; no secret in the QR.
- Plain-language explanation: `docs/how-pairing-works.md`.
- PROTOCOL.md now also documents the handshake framing and the authenticated data (`"MSH1 hs" ‖ type ‖ session ‖ A_id ‖ B_id`).

### 1.4 QR format ✅
- `Base45` (RFC 9285) and `QrInvite`: `MSH1:` + Base45, strict decoding (version, no trailing bytes, clean nickname). A typical QR text is under 200 characters.

### 1.5 Packet format ✅ (`:core:mesh`)
- `OuterPacket`: the 27-byte header relays see. Rejects bad version, kind, hop count and body size. `hops_left` is excluded from the authenticated data.
- `InnerPacket`: padded to buckets 128…8192. Strict decoding: exact bucket, zero padding, smallest fitting bucket, counter ≥ 1.
- `Content`: the 8 types from PROTOCOL.md §3, with protocol limits enforced (text ≤ 2,000 characters, image ≤ 50 KB, avatar ≤ 20 KB, 1–255 acknowledgements).
- `DataPackets.seal/open`: header bound to the encryption. A relay can lower `hops_left`, but changing sender, recipient or packet ID breaks the packet.

### 1.6 Tests ✅ — 65 tests, 0 failures
| Module | Tests |
|---|---|
| `:core:model` | 15 (ids, nicknames, byte reader/writer, 1 fuzz) |
| `:core:crypto` | 35: official test vectors for **XChaCha20-Poly1305** (draft-irtf-cfrg-xchacha-03 §A.3.1), **X25519** (RFC 7748 §6.1), **HKDF** (RFC 5869 case 1) and **Base45** (RFC 9285); tamper tests (every bit of a sealed packet flipped → rejected); reflection; all pairing safety rules; 4 fuzz |
| `:core:mesh` | 15: padding buckets, every content type round-trips, strict decoding, header binding, retries unlinkable; 3 fuzz |

### 1.7 Fuzzing ✅ — ~349 million inputs, 41 minutes, 0 crashes
| Target | Inputs (5 min each) |
|---|---|
| Nickname cleaning | 62.3 M |
| Base45 decoding | 27.1 M |
| QR text decoding | 39.4 M |
| Random handshake messages to both pairing sides | 0.6 M (slow on purpose: each input creates real X25519 keys) |
| Handshake message framing | 50.8 M |
| Outer packet decoding | 42.8 M |
| Inner packet decoding | 87.6 M |
| Random packets through decryption | 38.9 M |
- Each target checks that decoding never crashes and that anything accepted re-encodes to exactly the same bytes.
- On every normal build, the fuzz tests replay their saved inputs as ordinary tests.
- To fuzz locally: `JAZZER_FUZZ=1 ./gradlew :core:mesh:test --tests "*PacketFuzzTest.outer*" --rerun`

### Build & quality
- ktlint ✅ · detekt ✅ · Android Lint ✅ · dependency checksums: **442 components** (added Tink 1.23.0, JUnit 6.1.3, Jazzer 0.30.0).
- Final check after fuzzing: full rebuild with dependency verification on → **BUILD SUCCESSFUL, 65 tests, 0 failures**.
- detekt config change (needs owner OK): `ReturnCount.excludeGuardClauses: true`, so early "reject bad input" exits don't count as extra returns.
