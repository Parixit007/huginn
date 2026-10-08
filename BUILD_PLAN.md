# Huginn — Build Plan v0.2

> Builds the v1 described in `README.md` (spec v0.3: 1-to-1 chats only). Phase order approved by owner on 2026-10-07.
> Legend: ✅ = decided by owner · 🟡 = proposal, needs owner OK · ⚠️ = risk · 👤 = needs you to do something
> Sizes are relative effort: S < M < L < XL. No calendar estimates yet.

---

## How we work (every phase)

1. **Gate in:** a phase starts only after you say go. Open decisions for that phase are asked first.
2. **Small steps:** each numbered step = code + tests + docs, then a short report to you: what was built, which files changed, test results, any decisions made.
3. **Gate out:** a phase ends with a **phase report** and your sign-off against its "Done when" list.
4. Every decision is asked; every file is reported.
5. **The owner makes every git commit.** When a step is ready, I say so and suggest a short commit message; I never commit or push.
6. Repository hygiene: no AI-tool names or attribution anywhere in the repo (docs, code, file names, ignore files, CI, commit/PR text). Local-only working files are kept out of git with git's local exclude file, not `.gitignore`.
7. Security items are never postponed "until later" within a phase. If something can't be done, it is written down as a known issue.

## Why this order

- **The core goes first** (crypto, packet format, mesh logic). This is where security lives, and it can be tested fully on your Mac with no phones at all.
- **Bluetooth is the riskiest part, but with 1 phone it's the hardest to test.** So Phase 0 runs two small *spikes* (quick experiments) to learn early what's possible. The real Bluetooth work then comes after the app already works against simulated friends.
- **Real multi-phone testing waits for more phones** (D52).

## Your dev machine (checked 2026-10-07)

| Item | Status |
|---|---|
| Android Studio 2026.2 | ✅ installed (bundled JDK 25, used for all builds) |
| Android SDK, emulator, system images | ❌ not installed yet. 👤 You run Studio's setup wizard and accept the SDK licenses. |
| adb, git, GitHub CLI, Python 3.14 | ✅ installed |
| Your phone | 👤 Tell me the model + Android version; turn on Developer options and USB debugging |

---

## Overview

| Phase | Name | Phones needed | Size | You get |
|---|---|---|---|---|
| 0 | Setup & spikes | your 1 phone | S | An empty app that installs, safety checks in the build, 2 spike reports |
| 1 | Crypto & packet format | none | M | Encryption, pairing and the packet format, fully tested |
| 2 | Mesh engine + simulator | none | L | Mesh logic proven on 2–30 virtual phones, with a cost report |
| 3 | Secure storage | emulator | M | Encrypted database, keys and image store |
| 4 | App UI on simulated friends | emulator + your phone | L | The whole app usable against simulated friends |
| 5 | Bluetooth + background service | your phone + emulator/Mac | XL ⚠️ | Real-radio messaging, 24-hour background run, battery report |
| 6 | Integration & security hardening | your phone | M | Release-candidate APK + security review |
| 7 | Field test | **2–3 phones** ⚠️ blocked | M | v1.0 prototype |

**Dependencies:** 0 → 1 → 2 → 4 · 1 → 3 → 4 · (0 spikes + 2) → 5 · (4 + 5) → 6 → 7

---

## Phase 0 — Setup & spikes · S

**Goal:** a skeleton that builds, plus early answers to the two biggest unknowns.

| Step | What |
|---|---|
| 0.1 | 👤 You finish the Android Studio setup (SDK, emulator) and enable USB debugging on your phone. |
| 0.2 | Version control: `git init`; `.gitignore` (build outputs, `local.properties`, keystores and signing files); MIT `LICENSE` (P7); local-only files excluded via `.git/info/exclude`. I create the empty **public** GitHub repo `huginn` with `gh`, after showing you the exact command. 👤 You make the first commit and push. |
| 0.3 | Gradle skeleton: Kotlin DSL, version catalog, the 8 modules from spec §10 (empty), `minSdk` 26, latest `targetSdk`, Compose. |
| 0.4 | **Build guards from day 1:** the release build **fails** if the INTERNET permission appears; backup is disabled; dependency checksums are verified; Android Lint + ktlint + detekt run. |
| 0.5 | CI (GitHub Actions): build + all tests + Lint/ktlint/detekt on every push. |
| 0.6 | **Spike A, emulator Bluetooth:** can two emulators (netsim) advertise, scan, connect and exchange bytes? Throwaway code in `spikes/`, never shipped. |
| 0.7 | **Spike B, your phone:** Android version, BLE advertising support, 2M PHY, max MTU. Does a foreground service survive overnight on your phone's brand? |

**Done when:** the empty app installs on your phone and on an emulator · adding INTERNET is proven to fail the release build · both spike reports are written.
**Report:** spike results + the recommended way to test Bluetooth in Phase 5.

## Phase 1 — Crypto & packet format · M
Modules: `:core:model`, `:core:crypto`, the codec part of `:core:mesh`. Pure Kotlin, no Android.

| Step | What |
|---|---|
| 1.1 | Basic types: device ID (8 B), packet ID, message ID (16 B), counters. |
| 1.2 | `CryptoSuite` interface + `StaticKeyV1`: XChaCha20-Poly1305 (Tink, no-prefix keys), HKDF per-direction keys, authenticated header. |
| 1.3 | Pairing handshake: X25519 key pairs, request/response, A's random value, the 6-digit code, the "accepted" step, temporary-key wipe, single use + 5-min expiry. |
| 1.4 | QR codec: brand-neutral marker + version, strict parsing. |
| 1.5 | Packet codec: outer/inner headers, padding to fixed sizes, versioning, strict length checks. |
| 1.6 | Tests: known-answer vectors; flipping any bit → rejected; wrong-direction key → rejected; replayed message ID → caught; an attacker racing the pairing → codes differ; padded sizes indistinguishable. |
| 1.7 | Fuzz the QR and packet parsers with Jazzer. |

**Done when:** all tests green · fuzzers run 30 min with no crash · you get a plain-language "how pairing works" walkthrough from the test output.
**Note:** Tink comes in a JVM artifact (used in tests) and an Android artifact (used in the app). We set them up so they never clash.

## Phase 2 — Mesh engine + simulator · L
Modules: `:core:transport`, `:transport:fake`, `:core:mesh`.

| Step | What |
|---|---|
| 2.1 | `Transport` interface: links, `send(link)`, `onReceive`, link up/down events. |
| 2.2 | Simulator (`FakeTransport`): N virtual phones; line/grid/crowd layouts; packet loss, delay, reordering; links breaking and moving; repeatable runs. |
| 2.3 | Flooding: hops left = 8; memory-only "seen" cache (size- and time-limited); forward once to every link except the one the packet came from. |
| 2.4 | Reliable delivery: delivered/read receipts; on timeout → re-encrypt with a new packet ID → resend N times → back to pending. |
| 2.5 | Pending queue: retry when a new link appears (with backoff); after 3 days → "Not delivered". |
| 2.6 | Images: manifest + chunks, reassembly with memory caps, requests for missing chunks, hash check, "delivered" only for the full image. |
| 2.7 | Abuse limits: rate limit per link, max packet size, max number of images being reassembled at once. |
| 2.8 | Scenario tests: 2 phones direct · a line of 10 phones (the 8-hop limit holds exactly) · a 30-phone crowd with 20% loss · split network → heal → pending messages arrive · an image under loss · a garbage-flooding attacker gets limited · no message is ever shown twice. |
| 2.8b | **Store-and-forward** (D65): carriers keep others' encrypted messages, swap "what I carry" lists when they meet, hand over what's missing, and delete copies when the delivery receipt passes by. Plus the slow timer retry (D64). ⏳ details to decide at the Phase 2 gate. |
| 2.9 | **Cost report:** radio transmissions per delivered message/image. This becomes the baseline that v1.1 smart routing must beat. |

**Done when:** all scenarios pass with fixed seeds · you receive the cost report.

## Phase 3 — Secure storage · M
Module: `:data`.

| Step | What |
|---|---|
| 3.1 | SQLCipher (`sqlcipher-android`) + Room. A random passphrase, wrapped by a Keystore key, usable after the first unlock. |
| 3.2 | Tables: me (ID, nickname, avatar), contacts (keys, names, blocked), messages (unique message IDs, counters, status), pending queue, image metadata. |
| 3.3 | Encrypted image files (Tink), in app-private storage. |
| 3.4 | Real implementations of the stores the mesh engine uses (message IDs, pending, contact keys). |
| 3.5 | Tests on emulators (API 26 + latest): the DB file can't be read without the key · data survives a restart · deleting a contact wipes its keys and chat · backups contain nothing. |

**Done when:** tests pass on the oldest and newest Android emulator.

## Phase 4 — App UI on simulated friends · L
Module: `:app`.

| Step | What |
|---|---|
| 4.1 | Navigation, Material 3 theme (light/dark). |
| 4.2 | Onboarding: nickname; avatar (192 px, ≤ 20 KB); device ID; permission explainers per Android version; battery-settings guide. |
| 4.3 | Contacts: list, delete, block, re-pair. |
| 4.4 | Pairing: show QR, scan with the in-app camera (CameraX + ZXing), compare codes, Accept/Reject (tapjacking-safe), countdown to expiry. |
| 4.5 | Chat: text (≤ 2,000), reactions, images (metadata stripped, re-encoded ≤ 50 KB), status ticks, "Not delivered — retry?", incognito keyboard. |
| 4.6 | Profile editing (the update goes to all contacts). |
| 4.7 | Notifications: sender name only. |
| 4.8 | Nearby count. |

**Done when:** every v1 screen is built · automated UI tests (using the test-only fake transport) cover pairing and chat · you've tried the screens that don't need a second phone (onboarding, your QR, profile, contacts) on your phone. Hands-on chatting moves to Phase 5, when your Mac acts as the second phone.

## Phase 5 — Bluetooth + background service · XL ⚠️
Modules: `:transport:ble`, plus the service in `:app`.

| Step | What |
|---|---|
| 5.1 | BLE basics: advertiser + GATT server, scanner + GATT client, brand-neutral UUID, scan filters, ≤ 5 scan starts per 30 s, duty cycling. |
| 5.2 | Connection manager: max links, "lower ID connects" rule, rotation, reconnects, a warning if the phone can't advertise. |
| 5.3 | Link layer: MTU negotiation (up to 517) with a 20-byte fallback, per-hop fragmentation, flow control. |
| 5.4 | Foreground service (`connectedDevice`), start after boot (after first unlock), behaviour under Doze and OEM battery savers. |
| 5.5 | Permission flows: Android 8–11 Location · 12+ Nearby devices · 13+ notifications · 14+ service type. |
| 5.6 | **Mac test peer** (dev-only, never shipped): a small Python tool handles only the Mac's Bluetooth radio and passes raw bytes to our real Kotlin core (Phases 1–2) running on the Mac. One implementation, nothing to keep in sync. Lives in `tools/mac-peer/`. 🟡 Python Bluetooth libraries (P8). 👤 macOS will ask you to allow Bluetooth access. |
| 5.7 | Testing with 1 phone: unit tests with a mocked BLE layer · emulators via netsim (if Spike A works) · phone ↔ Mac peer over real Bluetooth · a 24-hour soak test with a battery measurement. |

**Done when:** you pair your phone with the Mac peer and chat (text, reactions, an image, receipts) over real Bluetooth · the service survives 24 h · you get a battery report.
⚠️ **Risk:** brand-specific Bluetooth bugs only show up with more phones (Phase 7).

## Phase 6 — Integration & security hardening · M

| Step | What |
|---|---|
| 6.1 | End-to-end over Bluetooth: pair → chat → image → receipts → pending → expiry → delete/block. |
| 6.2 | Hardening checklist H1–H12, each item with proof: manifest audit (no INTERNET, nothing exported, backup off), dependency verification, logs stripped from release, tapjacking, nickname cleaning, no deep links. |
| 6.3 | Security review of all code + a walkthrough against threat model §9. |
| 6.4 | Longer fuzzing runs. |
| 6.5 | Performance: battery, memory, storage, flooding cost measured on a real device. |
| 6.6 | 🟡 Release signing key: 👤 created and backed up by you (P5); signed release-candidate APK. |

**Done when:** every checklist item ✅ · no open high-severity issues · the release-candidate APK runs on your phone.

## Phase 7 — Field test · M · ⚠️ blocked until 2–3 phones

| Step | What |
|---|---|
| 7.1 | 2 phones direct: range, pairing, images. |
| 7.2 | 3 phones in a line: real multi-hop. |
| 7.3 | Different brands/Android versions; screen off, Doze, reboots, battery killers. |
| 7.4 | If possible, a small group of friends as a mini crowd. |
| 7.5 | Fix list → tag **v1.0 prototype**. |

**Done when:** multi-hop messages and images deliver in real conditions · known issues documented.

## After v1.0
- **v1.1:** smart flooding + route learning (it must beat the Phase 2 cost baseline).
- **v2:** security upgrades (spec §12). Group chats: version to be decided (D54).

---

## Decisions for this plan

| # | Needed by | Decision | Status |
|---|---|---|---|
| P0 | now | Phase order and "Done when" criteria approved as written | ✅ |
| P1 | Phase 0 | **Public** GitHub repo named `huginn`, created by me with `gh` after you approve the exact command | ✅ |
| P2 | Phase 0 | CI on every push (GitHub Actions) | ✅ |
| P3 | Phase 0 | Tooling: latest stable Kotlin/AGP/Gradle on Studio's JDK · JUnit 5 + coroutines-test · Jazzer · CameraX + ZXing · `PROGRESS.md` step log · Android Lint + ktlint + detekt | ✅ |
| P4 | Phases 4–5 | Test aids: **Mac as a Bluetooth test peer** (Python radio + our Kotlin core). No simulated friends in debug builds. | ✅ |
| P5 | Phase 6 | Who creates and keeps the release signing key (recommended: you, with an offline backup) | later |
| P6 | Phase 0 | **The owner makes all commits and pushes**; I only report when a step is ready | ✅ |
| P7 | Phase 0 | Copyright holder name in the MIT `LICENSE` (your name, a handle, or "Huginn contributors") | ⏳ |
| P8 | Phase 5 | Python Bluetooth libraries for the Mac peer (proposal: `bleak` for scanning/connecting, `bless` for advertising) | later |
| P9 | Phase 0 | No AI-tool names or attribution anywhere in the repo; local-only working files excluded via `.git/info/exclude` | ✅ |
