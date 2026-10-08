# Phase 2 cost report — plain flooding (D45)

- **Date:** 2026-10-08
- **Source:** `core/mesh/src/test/kotlin/.../scenario/CostReportTest.kt`. It is rerun on every build and writes `core/mesh/build/reports/mesh-cost.md`.
- **Conditions:** simulated phones, virtual clock, fixed seeds. "Radio transmissions" counts every packet put on the air (one packet to one neighbour = 1), including retries, receipts and lost packets.
- **Purpose:** this is the baseline v1.1 smart routing must beat (build plan 2.9).

| Scenario | Delivered | Radio transmissions | Per delivered message |
|---|---|---|---|
| Line of 9 phones (8 hops), text + receipt | 1 / 1 | 16 | 16 |
| Crowd of 30, 10 texts + receipts, 0% loss per link | 10 / 10 | 1,961 | 196 |
| Crowd of 30, 10 texts + receipts, 20% loss per link | 10 / 10 | 2,443 | 244 |
| Crowd of 30, one 50 KB photo, 5 hops | 1 / 1 | 16,095 | 16,095 (**8.8 MB on air**) |
| A → B → C carried hours apart (the owner's scenario) | 1 / 1 | — | B carried 2 copies |

## What this means

1. **A photo is very expensive.** One 50 KB photo across 5 hops in a 30-phone crowd put **8.8 MB** on the air: about 176 times its size, because every phone forwards each of its 112 pieces to every neighbour. Real BLE moves tens of KB/s per link, so in a real crowd one photo could keep the area's radios busy for minutes. → **Smart routing in v1.1 should start with photos.**
2. **Honest photo floods trip the rate limit** (271 pieces dropped in that run). The "send me the missing pieces" requests recovered every time. The limit still does its job against attackers: in the attack scenario 4,900 of 5,000 junk packets were dropped.
3. **Texts are affordable:** about 200 transmissions per text plus its receipt in a crowd of 30, and 16 along a line.
4. **"Delivered" ticks can lag on bad paths.** With 20% loss on every link, along a long, thin chain of phones, one message arrived within a minute but its tick took 1–3 hours. A receipt is only sent when a copy gets through, and it must survive the same path back. Real BLE connections retransmit lost packets at the radio level, so 20% loss per link is a deliberately harsh setting.
5. **Retries create carried copies.** Each retry is a new, unlinkable packet (D34), so a carrier can't tell copies apart and carries all of them. It carried 2 copies here, and more after longer isolation. The recipient still shows the message once.

## Possible improvements for v1.1 (not decided)

- Route learning, so photos and receipts follow the path a contact's packets came from instead of flooding (D45).
- Fewer resends while the set of neighbours hasn't changed (cuts the carried copies from point 5).
- Spread photo pieces out in time instead of sending all 112 at once (stops tripping the rate limit).
