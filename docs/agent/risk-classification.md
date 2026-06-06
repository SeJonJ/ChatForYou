# Risk Classification Guide

Detailed classification criteria and examples for the risk gate in `AGENT_GUIDE.md`.

---

## L0–L3 Detailed Criteria

| Level | Included | Excluded |
|:---:|:---|:---|
| **L0** | README, CHANGELOG, comment-only edits, plan_docs document creation | Config file changes, behavior changes, environment variable changes |
| **L1** | CSS/SCSS styles, HTML markup, non-functional UI changes, log message text, simple constant changes | Anything touching Redis, Auth, or WebRTC |
| **L2** | Redis key/TTL/structure changes, JPA entity/schema changes, API request or response structure changes, JWT/auth flow changes, authorization logic, DB query changes | No WebRTC/WebSocket/Signaling involvement |
| **L3** | WebRTC signaling, Kurento media server integration, ICE/SDP/DataChannel, room lifecycle, Desktop Electron sync/runtime/packaging | Everything else |

---

## Compound Change Scenarios

| Change Description | Level | Reason |
|:---|:---:|:---|
| Fix a README typo | **L0** | Documentation only |
| Change a button's CSS color | **L1** | Pure UI |
| Add a chat message character limit (frontend only) | **L1** | Non-functional UI |
| Change an API error message text | **L1** | Log/message text |
| Change a Redis room TTL | **L2** | Backend state |
| Change JWT token expiry | **L2** | Auth logic |
| Add a field to a JPA entity | **L2** | DB schema |
| Rename a WebSocket message type | **L3** | Signaling schema |
| Modify ICE candidate handling in `kurento-service.js` | **L3** | Core WebRTC |
| Modify room termination logic in `KurentoRoomManager` | **L3** | Room lifecycle |
| Change an Electron asset sync path | **L3** | Desktop sync |
| Button UI change + WebSocket event name change | **L3** | Compound → highest level wins |
| Redis key change + frontend rendering change | **L2** | Compound → Redis is included |
| Log message change + JWT validation change | **L2** | Compound → JWT is included |
| Documentation change + environment variable default change | **at least L1** | Config is included, so not L0; escalate to L2/L3 if the variable affects Redis, Auth, KMS, or Desktop packaging |

---

## Decision Procedure for Uncertain Cases

1. List every file that will be modified.
2. Map each file to the criteria above.
3. Select the highest level found.
4. State the reason explicitly.

When still uncertain → **choose the higher level and state why**.

---

## Related Rules
- `AGENT_GUIDE.md` — Risk & Workflow Gate
- `AGENT_GUIDE.md` — Required Agent Reference Documents
