# Wire protocol v1

> Exact bytes for spec v0.3 (`README.md` §4–§6). Status: **approved by owner on 2026-10-08** (D57–D60); §8 Bluetooth link layer approved 2026-10-09 (D99).
> All identifiers are brand-neutral (D47): the protocol is called **MSH1** in labels and the QR prefix.
> All integers are big-endian. "‖" means concatenation.

## 1. Sizes

| Field | Size | Notes |
|---|---|---|
| Device ID | 8 B | Random at install (D15) |
| Packet ID | 8 B | Random per transmission; relays use it to drop duplicates (D34) |
| Message ID | 16 B | Random per message; stays the same across retries (D34) |
| Counter | 8 B | Per sender, per chat, starts at 1 (D36) |
| Timestamp | 8 B | Milliseconds since 1970 on the sender's clock (display only, never trusted) |
| Session ID | 8 B | Random per QR code |
| Public keys | 32 B | X25519 |
| Nonce | 24 B | Random; Tink puts it at the front of the ciphertext |
| Tag | 16 B | Poly1305; Tink puts it at the end |

## 2. Outer packet (what relays see)

```
version (1) ‖ kind (1) ‖ hops_left (1) ‖ packet_id (8) ‖ sender_id (8) ‖ recipient_id (8) ‖ body
```

- `version` = 1. Unknown versions are dropped.
- `kind`: 1 = DATA, 2 = HANDSHAKE, 3 = LINK. HANDSHAKE and LINK packets are **never relayed** (direct neighbours only).
- `hops_left`: the sender sets 8. Each relay subtracts 1 and forwards only if the result is ≥ 1, so a packet crosses at most 8 links (D8).
- DATA `body` = `nonce (24) ‖ ciphertext ‖ tag (16)`. The **authenticated data** (AAD) is the header without `hops_left`: `version ‖ kind ‖ packet_id ‖ sender_id ‖ recipient_id`.
- Outer header = 27 B; DATA overhead = 27 + 24 + 16 = **67 B**.

## 3. Inner packet (encrypted, DATA only)

```
type (1) ‖ message_id (16) ‖ counter (8) ‖ timestamp (8) ‖ content_length (2) ‖ content ‖ zero padding
```

`counter` only advances for chat content (TEXT, REACTION, PROFILE, IMAGE_MANIFEST). Control packets (receipts, chunk requests) carry `counter` = 1. Image chunks carry their manifest's counter.

Photos are cut into pieces of **459 bytes**, so every IMAGE_CHUNK inner packet fills exactly the 512-byte padding step (a 50 KB photo = 112 chunks).

The whole inner packet is padded with zeros to the next **padding bucket**: 128, 256, 512, 1024, 2048, 4096 or 8192 bytes (D46). The inner header is 35 B, so the largest content is 8157 B. That is enough for 2,000 characters (≤ ~6 KB, D28).

| type | Content |
|---|---|
| 1 TEXT | UTF-8 text |
| 2 REACTION | target message ID (16) ‖ emoji (UTF-8) |
| 3 ACK_DELIVERED | count (1) ‖ message IDs (16 each) |
| 4 ACK_READ | count (1) ‖ message IDs (16 each) |
| 5 IMAGE_MANIFEST | image ID (16) ‖ total bytes (4) ‖ chunk count (2) ‖ SHA-256 of the image (32) ‖ purpose (1: chat image, 2: avatar) |
| 6 IMAGE_CHUNK | image ID (16) ‖ chunk index (2) ‖ bytes |
| 7 CHUNK_REQUEST | image ID (16) ‖ count (2) ‖ missing chunk indexes (2 each) |
| 8 PROFILE | nickname length (1) ‖ nickname (UTF-8) ‖ avatar image ID (16, all zero = no avatar) |

## 4. Keys

- **Contact root key** (32 B), made at pairing (§5).
- **Direction keys:** `k(X→Y) = HKDF-SHA256(ikm = root, salt = empty, info = "MSH1 dir" ‖ X_id ‖ Y_id, 32 B)`. A uses `k(A→B)` to send and `k(B→A)` to receive (hardening H8).
- **Cipher:** XChaCha20-Poly1305 (Tink, no-prefix key).

## 5. Pairing handshake (D37)

**QR code** (A shows it): the text `MSH1:` followed by Base45 of:
```
qr_version (1) = 1 ‖ session_id (8) ‖ A_id (8) ‖ A_pub (32) ‖ nickname_length (1) ‖ nickname
```
It is single use and A's phone forgets it after 5 minutes. **No secret is in it.**

**Messages** (outer `kind` = HANDSHAKE, direct link only):

Every body starts with `type (1) ‖ session_id (8)`; the table shows what follows. At most 512 bytes.

| # | From → to | Handshake type | Body |
|---|---|---|---|
| 1 | B → A | 1 REQUEST | `session_id ‖ B_pub ‖ AEAD(k_req, B's nickname)` |
| 2 | A → B | 2 CHALLENGE | `session_id ‖ nonce_A (16)` |
| 3 | A → B | 3 CONFIRM | `session_id ‖ AEAD(k(A→B), A's nickname)`, sent after A taps Accept |
| 3′ | A → B | 4 DECLINE | `session_id ‖ AEAD(k(A→B), empty)`, sent if A taps Reject |

**Computations** (both phones compute the same values):
```
shared     = X25519(own private key, peer public key)
k_req      = HKDF(shared, salt = session_id, info = "MSH1 req", 32)
transcript = SHA-256("MSH1 pair" ‖ session_id ‖ A_id ‖ B_id ‖ A_pub ‖ B_pub ‖ nonce_A)
code       = (first 4 bytes of HKDF(shared, salt = transcript, info = "MSH1 code", 4) as unsigned int) mod 1,000,000
root       = HKDF(shared, salt = transcript, info = "MSH1 root", 32)
aad(type)  = "MSH1 hs" ‖ type ‖ session_id ‖ A_id ‖ B_id     (authenticated data for every AEAD above)
```

**Safety rules:**
1. A's phone answers only the **first** request for a session. If a second, different request arrives, A's phone **aborts** and warns "Two phones tried to pair. Start again."
2. `nonce_A` is chosen only **after** B's public key has arrived. An attacker therefore can't search for a public key that produces a matching 6-digit code.
3. B saves A only after a valid CONFIRM. A saves B when it taps Accept.
4. Temporary private keys are wiped after CONFIRM/DECLINE or after 5 minutes.
5. A photo of the QR code reveals only public data.

## 6. LINK packets (neighbours only, D71)

Used when two phones meet, so a carrier hands over only what the other phone lacks (D66). Body, at most 512 bytes:
```
link_type (1) ‖ count (1) ‖ packet_ids (8 each, at most 63)
```
- `link_type` 1 = OFFER: "I'm carrying these packets".
- `link_type` 2 = WANT: "send me these", a subset of an OFFER.
- Neither carries any content; packet IDs are already visible on the air.
- An **empty OFFER** is sent when a link comes up, even if nothing is carried. It works as a "hello", so the neighbour learns our device ID.
- `recipient_id` is the neighbour's ID once known, otherwise all zeros ("whoever is on this link").

## 7. Parsing rules (all decoders)

- Every length is checked against the remaining bytes before reading. Unknown `version`, `kind` or `type` → drop.
- Nicknames: at most **32 characters** (Unicode code points, so ≤ 128 bytes of UTF-8), not empty, with control, invisible and direction-flipping characters removed (H6).
- Decoders never throw on bad input from strangers. They return "invalid", and fuzz tests enforce this (Phase 1.7).

## 8. Bluetooth LE link layer (Phase 5) — approved by owner on 2026-10-09 (D99)

How packets travel over one Bluetooth hop. Relays and the mesh engine never see any of this (spec §6).

### 8.1 GATT layout

| What | UUID | Properties |
|---|---|---|
| Service | `21f4aec6-c5b9-4784-86b5-d37334400940` | Primary |
| IN (the dialing phone writes here) | `3084ce15-1725-4635-b7bb-d9e9807a29ff` | Write, write without response |
| OUT (the advertising phone notifies here) | `0ff2fcb6-7980-41db-845a-4f495c69ba63` | Notify (standard CCCD `0x2902`) |

- Random, brand-neutral UUIDs (D47). Two characteristics, as proven in Spike A.
- Every phone runs the GATT server *and* scans/dials (spec §6). On a given link, one side is the **dialer** (GATT client) and the other the **advertiser** (GATT server).

### 8.2 Advertising and who dials

- Legacy advertising only (31 bytes; the owner's phone has no extended advertising, Spike B):
  - advert: flags + the 128-bit service UUID (21 B);
  - scan response: service data for the same UUID = an 8-byte random **link token** (26 B).
  - No device name, no TX power, never the device ID.
- The link token is random and is replaced, together with a restart of advertising (which also changes the phone's Bluetooth address), every **15 minutes**. A passive listener can't follow a phone for longer than that.
- **Who dials:** when two phones see each other, the one with the **lower link token** dials. This replaces "the lower device ID connects" (spec §6): device IDs aren't known before connecting, and broadcasting them would make phones trackable.
- An advert without a token (the Mac test peer; macOS can't send service data) may always be dialed.
- Safety net: if two links still end up with the same neighbour (same device ID in the hello), the newer one is closed. Spike A saw duplicate scan results and lingering old connections.

### 8.3 Bringing a link up

1. The dialer connects (LE transport), asks for MTU 517 and, if supported, the LE 2M PHY (Spike B: the owner's phone supports it).
2. Fragment size = `min(MTU − 3, 512)` (ATT limit, Spike A); if the MTU request fails, 20 bytes.
3. The dialer subscribes to OUT. The link is **up** on both sides once that subscription is written: `onLinkUp`.
4. The mesh engine immediately sends the empty LINK OFFER (the hello, §6). If no valid LINK packet arrives within **10 s** (D98), the engine closes the link. This needs one addition to the `Transport` interface: `disconnect(link)`.

### 8.4 Fragmentation (per hop, inside the transport)

Each GATT write (dialer → advertiser) or notification (advertiser → dialer) carries one fragment:
```
flags (1) ‖ [total_length (2), first fragment only] ‖ data
```
- `flags` bit 7 = FIRST; bits 0–6 must be 0.
- `total_length` = 1 … 8,259 bytes (the largest outer packet, §2 + §3). A larger value means a broken or hostile neighbour, so the link is closed.
- Fragments of one packet follow each other in order; Bluetooth delivers them reliably and in order on one link.
- A new FIRST before the current packet is complete → the unfinished one is thrown away. Extra bytes past `total_length` → the fragment is invalid and the unfinished packet is thrown away.
- A complete packet goes to the engine (`onReceive`), which applies its own strict parsing (§7).

### 8.5 Flow control and limits

- One write in flight per link: the next one waits for the previous write's callback (Spike A). One notification in flight per neighbour: waits for "notification sent".
- Outgoing queue: at most **64 packets** per link. When full, new packets for that link are dropped; the engine's retries and OFFER/WANT recover them.
- The engine's existing receive limits apply on top (100 packets/s per link; spec §6, abuse limits).

### 8.6 Connection manager (D98 values)

- At most **4 links** (dialed + accepted). When full: stop advertising and stop dialing, keep the duty-cycled scan so waiting neighbours are noticed.
- **Rotation:** every 10 minutes, if a Raven phone was seen that we aren't linked to and all slots are full, the link that has been up longest is closed (it has had its OFFER/WANT exchange).
- **Scanning:** only with the service-UUID filter. Continuous while the app is on screen; otherwise 10 s every 60 s. At most 5 scan starts per 30 s (Android silently ignores more).
- Duplicate scan results for a phone that's already linked or being dialed are ignored (Spike A).
- "Pause Raven" (D96) stops advertising, scanning and the GATT server and closes every link.
