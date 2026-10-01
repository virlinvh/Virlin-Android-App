# Database migration queue

Current Room version: **17**

Current integration owner: **Codex primary integration session, acting under user approval**.
The user may explicitly reassign this role; feature agents may not infer reassignment.

| Version | Reservation | Status |
|---|---|---|
| 18 | **Mind map node preview** (`map_node_previews`) — Claude, `feature/mind-map-node-preview` | **Reserved** under explicit user instruction in the node-preview brief ("Reserve exactly one version"), which the queue permits the user to grant. Not self-assigned by a feature agent. |
| 19 | Unassigned | Do not reserve before v18 design is accepted |

Rules:

1. Feature agents do not claim versions only in chat.
2. The integration owner records the reservation here before schema work begins.
3. One version has one owner and one coherent migration.
4. Every version includes exported schema and migration tests.
5. Branches created before a reservation must rebase before implementing a migration.
