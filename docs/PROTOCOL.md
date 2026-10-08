# Wire protocol v1

> Exact bytes for spec v0.3 (`README.md` §4–§6). Status: **approved by owner on 2026-10-08** (D57–D60).
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
- `kind`: 1 = DATA, 2 = HANDSHAKE. HANDSHAKE packets are **never relayed**.
- `hops_left`: the sender sets 8. Each relay subtracts 1 and forwards only if the result is ≥ 1, so a packet crosses at most 8 links (D8).
- DATA `body` = `nonce (24) ‖ ciphertext ‖ tag (16)`. The **authenticated data** (AAD) is the header without `hops_left`: `version ‖ kind ‖ packet_id ‖ sender_id ‖ recipient_id`.
- Outer header = 27 B; DATA overhead = 27 + 24 + 16 = **67 B**.

## 3. Inner packet (encrypted, DATA only)

```
type (1) ‖ message_id (16) ‖ counter (8) ‖ timestamp (8) ‖ content_length (2) ‖ content ‖ zero padding
```

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

## 6. Parsing rules (all decoders)

- Every length is checked against the remaining bytes before reading. Unknown `version`, `kind` or `type` → drop.
- Nicknames: at most **32 characters** (Unicode code points, so ≤ 128 bytes of UTF-8), not empty, with control, invisible and direction-flipping characters removed (H6).
- Decoders never throw on bad input from strangers. They return "invalid", and fuzz tests enforce this (Phase 1.7).
