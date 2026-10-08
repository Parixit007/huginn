# Huginn — Spec Audit #1

- **Date:** 2026-10-07
- **Audited:** `README.md` spec v0.1 (approved by owner, except the final name)
- **Code:** none exists yet. This audit only covers the design.

Legend: 🔴 Error (won't work as written) · 🟠 Security gap · 🔵 Android/BLE reality check · ⚪ Clarification
Status: all items decided. See "Decisions" at the bottom; recorded in the README decision log as D34–D53.

---

## Summary

| Kind | Count |
|---|---|
| 🔴 Errors | 8 |
| 🟠 Security gaps | 9 (+ 12 small hardening items) |
| 🔵 Android/BLE reality checks | 7 |
| ⚪ Clarifications | 10 |

**The three biggest:**
1. **The QR shows the secret key itself.** A photo of the screen is a copy of the key (S1).
2. **Relays would silently drop retries and image pieces** (E1, E2).
3. **The clock check would reject queued messages** (E3).

None of these forces a redesign. Every fix fits the current architecture.

---

## 🔴 Errors

### E1 — Retries get dropped by relays
**Problem:** relays remember the message IDs they have already forwarded (the "seen" cache, §6). A retry reuses the same message ID, so every relay that saw the first attempt drops the retry. Retries would only ever reach direct neighbours.
**Fix:** use two IDs.
- **Packet ID** (outer, plaintext): a new random value for every transmission. Relays use it to drop duplicates.
- **Message ID** (inner, encrypted): stays the same across retries. The receiver uses it to drop duplicates and to send receipts.

### E2 — Image pieces get dropped as duplicates; lost pieces can't be recovered
**Problem:** all fragments of an image share one message ID, so relays would forward piece 1 and drop the rest. If 1 of about 120 pieces is lost, the spec has no way to recover except resending the whole image.
**Fix:**
- Each image chunk is its own encrypted packet with its own packet ID.
- The first chunk carries a manifest: number of chunks and a hash of the whole image.
- After a timeout, the receiver asks for only the missing chunks.
- "Delivered" is sent only once the full image checks out.
- Fitting packets into a BLE write (per-hop fragmentation) becomes a separate layer inside the transport. Relays never see it.

### E3 — The clock check rejects queued messages and phones with the wrong time
**Problem:** §4 rejects messages whose timestamp is outside ±N minutes. Two things break this:
- D9 queues messages, sometimes for hours, so they arrive "too old" and get rejected.
- Phones without internet or a SIM often have drifting or manually set clocks.

**Fix:**
- Replay protection becomes: remember every message ID received per chat (unique key in the database). No clock window.
- Add a per-sender counter, used to order messages and to detect gaps.
- Show the sender's time in the chat, but sort by counter and arrival order.

### E4 — The group QR is undefined and conflicts with the QR rules
**Problem:** D27 (single use, fresh key every time the screen opens) can't apply to groups. Many people scan the same group QR, and a fresh key per scan would split the group into many groups. If the group key is printed in the QR instead, anyone who photographs it can read the group forever, because kicking members is a v2 feature.
**Fix options:** see the decision list (Q3).

### E5 — Group receipts multiply traffic
**Problem:** one message in a 32-member group triggers up to 31 "delivered" and 31 "read" receipts. Each one floods the whole mesh, so one message costs **63 floods**.
**Fix options:** see the decision list (Q5).

### E6 — Group members you never paired with have no name or picture, and profile changes never spread
**Problem:** avatars are sent once, after 1-to-1 pairing (D31). In a group you may meet people you never scanned. And if you change your nickname or avatar later, nobody ever learns about it.
**Fix:**
- A "profile" packet is sent to your contacts whenever you change your profile.
- It is also sent to a group when you join it, encrypted with the group key.
- People in a group who are not your contacts get a "not a contact" badge.
- What non-contacts can see is asked in Q6.

### E7 — Pairing never confirms back
**Problem:** B saves A immediately after scanning (§5). If A rejects the request, or never sees it, B is left with a dead contact and B's messages vanish.
**Fix:** pairing becomes: scan → request → A accepts → A's phone sends "accepted" → only now do both phones save the contact.

### E8 — Pending messages retry forever
**Problem:** if a contact deleted you, blocked you or lost their phone, your queued messages keep re-flooding the mesh forever.
**Fix:** messages give up after a time limit (Q8) and show "Not delivered — retry?". Retries only happen when new phones come into range, with backoff. No timer floods while you are alone.

---

## 🟠 Security gaps

### S1 — The QR shows the secret key itself
**Problem:** the QR contains the key (D10/§5). Anyone who sees your screen for a second (someone behind you, a CCTV camera, a photo) gets the key. With it they can:
- read everything you and that friend ever send,
- pretend to be either of you, forever, because the key never changes,
- race your friend and pair with you first.

**Fix (recommended):**
- The QR carries a **public key**. Think of it as an open padlock: anyone can see it, but only your phone holds the key that opens it.
- During the request step, both phones do a key agreement (X25519, which Tink supports) and calculate the same shared key. That key never appears on any screen.
- Both phones then show the same **6-digit code**. You check that the codes match, then tap Accept. Your phone mixes in a random number after the request arrives, so an attacker can't pre-compute a matching code.
- Pairing requests are accepted only from a phone in direct Bluetooth range. They are never relayed.
- Messages still use one static key per contact, so D10 is unchanged. Only the way that key is created changes.

### S2 — Packet type and size reveal behaviour
**Problem:** §6 puts the packet type (MESSAGE, ACK_READ, IMAGE_CHUNK…) in plain text. Anyone nearby could see "B just read A's message" or "A is sending a photo". Message length leaks information too.
**Fix:**
- Relays only see what they need to forward a packet: version, hops left, packet ID, sender and recipient.
- Everything else goes inside the encryption: type, message ID, time, counter and chunk number.
- Payloads are padded to fixed sizes, so a read receipt looks the same as a short text.

### S3 — "I'm here" broadcasts turn every phone into a tracking beacon
**Problem:** the §6 presence announce sends your static device ID every few seconds, up to 8 hops away, even when you aren't chatting. Anyone with a few scanners around a city could log where your ID has been for days. Android randomizes Bluetooth addresses to prevent exactly this, and a fixed ID undoes that protection.
**Fix options:** see the decision list (Q4).

### S4 — Photos can leak your location, and old phones' image decoders are risky
**Problem:** phone photos contain EXIF data: GPS location, phone model and time. Image decoders have also had serious bugs, such as the 2023 WebP flaw, and Android 8–9 phones often no longer receive security updates.
**Fix:**
- Always re-encode images from their pixels. This drops all metadata.
- Check the size and dimensions of anything received before decoding it.
- In v2, decode received images in an isolated process.

### S5 — Hardening bundle (small items, all recommended)
| # | Item |
|---|---|
| H1 | Disable Android backup and device-to-device transfer of app data |
| H2 | Scan QR codes with **ZXing** (open source, offline). Google's ML Kit sends usage metrics to Google and can download models through Play Services. |
| H3 | Make the build fail if any library sneaks in the INTERNET permission |
| H4 | "Incognito keyboard": stop keyboards like Gboard from learning or storing what you type in chats |
| H5 | Tapjacking protection: ignore taps on Accept and pairing buttons when another app draws over the screen |
| H6 | Clean up nicknames: strip invisible and direction-flipping characters, cap the length |
| H7 | No `huginn:` web links: pairing only works through the in-app camera, so a website or message can't trigger it |
| H8 | Key separation: derive one key per direction (A→B and B→A) from the shared key using HKDF |
| H9 | Per-neighbour rate limits, plus caps on memory used for unfinished images (prevents denial of service from strangers) |
| H10 | Fuzz-test the packet parser, because it reads data from strangers |
| H11 | Gradle dependency verification (checksums on every library); no analytics or crash-reporting SDKs |
| H12 | Never write plaintext or keys to logs; relays keep nothing on disk (the "seen" cache lives only in memory) |

### S6 — Leaving a group doesn't remove access *(info only)*
A person who leaves a group, or who is simply no longer trusted, keeps the group key. They can still read future group messages whenever they are within mesh range. This stays true until v2 adds "kick + new key". The v1 workaround is to create a new group and re-invite everyone. → Will be added to the threat model.

### S7 — Anyone can tell a phone is running Huginn *(info only)*
Bluetooth discovery needs a public service ID in the advertisement, so a scanner can detect "a Huginn user is here". Every BLE mesh app has this problem, bitchat included. It matters in places where using such an app is itself risky. → Will be added to the threat model.

### S8 — Lock-screen notifications can show messages
Notifications currently have no rules. Anyone glancing at your lock screen could read messages. → Q11.

### S9 — The database can be read while the phone is locked *(trade-off)*
To receive messages while the screen is locked, the database key must stay usable after the first unlock since boot. Most messengers work this way. The cost: forensic tools have a better chance against a locked-but-on phone than against one that is switched off. A v2 option is to store incoming encrypted packets in a "spool" and decrypt them only once the phone is unlocked. → Recommend accepting this for v1.

---

## 🔵 Android/BLE reality checks

### F1 — The spec needs a connection manager
In BLE, "broadcast to everyone" really means "send to each connected phone, one at a time". Android phones only hold a handful of stable connections, often around 4–7. The spec needs a connection manager that:
- caps the number of links (for example 4–5),
- prevents two phones connecting to each other twice (the lower ID connects),
- rotates links so new neighbours get a turn.

### F2 — "Bluetooth Mesh" naming *(info)*
We are **not** using the official Bluetooth SIG "Bluetooth Mesh" standard. That standard was built for IoT devices like light bulbs and sensors, has tiny payloads, and Android apps can't use it. Huginn is a custom mesh over BLE connections, the same approach bitchat takes.

### F3 — Flooding images clogs the mesh
Each phone forwards every packet to each of its links. In a crowd of 30 phones, one 50 KB photo costs roughly **4–5 MB of radio time**. Realistic BLE speed is tens of KB/s at best, shared across all links, so one photo could jam the mesh for minutes. → Q9.

### F4 — Background running and permissions
- **Permissions per Android version:**
  - Android 8–11: BLE scanning needs the **Location** permission, and often Location turned on. This prompt scares users, so onboarding must explain it.
  - Android 12+: the "Nearby devices" permission replaces it.
  - Android 13+: the notification permission.
  - Android 14+: the foreground service must declare the `connectedDevice` type.
- **Battery killers:** some brands (Xiaomi, Samsung, Oppo/Vivo, OnePlus…) kill background services. We need an onboarding screen that walks the user through their battery settings. The Play Store only allows apps to request this exemption directly in limited cases.
- **Starting after a reboot:** undecided. → Q12.

### F5 — BLE quirks to design around *(implementation notes)*
- Android blocks apps that start BLE scans more than 5 times in 30 seconds, so scan restarts must stay rare.
- With the screen off, scans without a filter stop. Always scan with our service filter.
- Some older phones fail to negotiate a larger MTU, so the link layer must work even with 20-byte writes.
- Some phones can't advertise at all. They can still connect to others, but two such phones can't find each other. The app should warn the user.
- Battery: use adaptive scan duty cycling.

### F6 — Play Store requirements *(if we publish there)*
- Target the latest Android SDK, which brings the strictest background and Bluetooth rules.
- Native libraries such as SQLCipher must support 16 KB memory pages.
- Where to publish (Play Store / F-Droid / APK) is still undecided. Listed as an open question for later.

### F7 — Testing with one phone
One real phone can't test Bluetooth between phones at all. The plan:
- Build and test the mesh logic on the fake transport first.
- As a stopgap, try the emulator's virtual Bluetooth radio (netsim, recent system images). It is unproven for our use case.
- Real BLE work needs at least 2–3 real phones, ideally from different brands. → Q14.

---

## ⚪ Clarifications

| # | Item |
|---|---|
| C1 | **Renaming:** the display name can change at any time, even after release. The package name is free to change until the first public release; after that it is permanent, and changing it would mean users lose their contacts. Protocol identifiers (QR prefix, BLE service UUID) must **not** contain the brand name. That way a rename never breaks compatibility between versions. The current `huginn:v1:` QR prefix will become a neutral one. |
| C2 | **Library details:** Tink already puts the nonce inside its ciphertext, so the separate nonce field goes. Use Tink keys with "no prefix" to avoid 5 extra bytes per packet. Avoid `androidx.security:security-crypto`, which was deprecated in April 2025; use Tink directly. Use the newer `net.zetetic:sqlcipher-android` artifact. Drop the QR checksum, because QR codes already have error correction. Keep a version byte. |
| C3 | **Wording:** "random nonces never collide" becomes "the chance of a collision is negligible". |
| C4 | **Hop count definition:** a message travels across at most 8 links, from sender to receiver. |
| C5 | **Text limit:** 2,000 characters can be up to about 6 KB once encoded (emoji and some scripts take more bytes). That's fine, it just may take two packets. |
| C6 | **Lost phone or reinstall:** there is no backup, so every contact must be paired again. Scanning a contact again replaces the old entry and refreshes the key. This also gives you a manual way to rotate a key. |
| C7 | **bitchat compatibility:** not decided. → Q15 |
| C8 | **Distribution channel** (Play Store / F-Droid / APK): decide later. |
| C9 | **Blocked contacts inside groups:** not decided. → Q13 |
| C10 | **Who can invite people to a group:** not decided. → Q7 |

---

## Decisions (all made by owner on 2026-10-07)

| Q | Topic | Decision | README |
|---|---|---|---|
| Q1 | Fixes for E1, E2, E3, E7 | Approved as recommended | D34, D35, D36, D37 |
| Q2 | QR key design (S1) | Public key in QR + 6-digit code | D37 |
| Q3 | Group join (E4) | Join request + admin approval | D38 |
| Q4 | Presence (S3) | No "I'm here" broadcasts | D44 |
| Q5 | Group receipts (E5) | Delegated by owner → delivered-only, batched, no read receipts in groups. 1-to-1 keeps full delivered + read. | D41 |
| Q6 | Group member profiles (E6) | Nickname + avatar for all members | D42 |
| Q7 | Who can invite (C10) | Creator + admins the creator picks | D39 |
| Q8 | Pending expiry (E8) | 3 days | D43 |
| Q9 | Routing (F3) | Plain flooding in v1; smart routing in v1.1 | D45 |
| Q10 | Hardening & clean-up bundle | Approved all | D48 |
| Q11 | Notifications (S8) | Sender name only | D49 |
| Q12 | Auto-start after reboot (F4) | Yes, after first unlock | D50 |
| Q13 | Blocked contacts in groups (C9) | Still shown in groups | D51 |
| Q14 | Test phones (F7) | Stay with 1 phone for now | D52 |
| Q15 | bitchat compatibility (C7) | Never; independent protocol | D53 |
| Q16 | Package name (C1) | Keep `app.huginn.mesh` as placeholder | D33 |
| Q17 | Hide packet type + padding (S2) | Approved (S2 had been missing from the first 16 questions) | D46 |
| Q18 | Brand-neutral protocol identifiers (C1) | Approved | D47 |
| Q19 | Group QR validity (new, found while writing v0.2) | While on screen, max 5 min | D40 |

## Found while applying the fixes

- **Inconsistency, fixed:** the v0.1 roadmap listed "kick member + group rekey" under v3, but D30 says v2. It is now under v2.
- **New 🟡 proposals** (still need the owner's OK, listed in README §13):
  - group joins also use the 6-digit code check;
  - group retry policy: re-flood when new phones connect, until all known members have acknowledged or 3 days pass.

## Follow-up answers (2026-10-07)

- **Group chats removed from v1** (owner). README v0.3: v1 is 1-to-1 only; the group design is parked in README §14 (D54). The version groups return in will be decided later.
- The group findings (E4, E5, the group part of E6, S6, C9, C10) and Q3, Q5, Q6, Q7, Q13, Q19 now apply to the **parked** group design only.
- Group join code check: **no**, nickname only (D55, parked).
- Group creator leaving: **creator must pick a successor** (D56, parked).
- Group retry policy: **not decided** (dropped together with groups).
