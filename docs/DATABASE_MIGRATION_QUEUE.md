# Database migration queue

Current Room version: **20**.

Current integration owner: **Codex primary integration session, acting under user approval**.
The user may explicitly reassign this role; feature agents may not infer reassignment.

| Version | Reservation | Status |
|---|---|---|
| 18 | **Mind map node preview** (`map_node_previews`) — Claude, `feature/mind-map-node-preview` | Integrated; previous queue status was stale compared with database and exported schema. |
| 19 | External tracking pause persistence — Codex primary integration session | Implemented: accumulated active seconds and paused-tracking marker; additive defaults preserve existing runs. |
| 20 | Independent task execution — Codex primary integration session | Implemented: additive task execution columns, exact active-task migration, preserved session history; on-device migration test passed. |

Rules:

1. Feature agents do not claim versions only in chat.
2. The integration owner records the reservation here before schema work begins.
3. One version has one owner and one coherent migration.
4. Every version includes exported schema and migration tests.
5. Branches created before a reservation must rebase before implementing a migration.
