# WebRTC/WebSocket Two-Round Review Protocol

This protocol is mandatory for all L3 WebRTC/WebSocket changes.
**Implementation is blocked until both rounds are fully documented.**

---

## Round 1 — Flow Correctness Review

### Checklist

- [ ] Message ordering is correct (offer → answer → ICE candidate sequence)
- [ ] SDP/ICE exchange is consistent between Kurento Media Server and the client
- [ ] Room ownership and session attribution are unambiguous
- [ ] Client/server state transitions cover all paths (happy and error)
- [ ] Message schema changes are backward-compatible with existing clients
- [ ] Missing or duplicate message delivery is handled safely

### Record Format

```
## Round 1 — Flow Correctness Review
Reviewer: [agent name or author]
Date: [YYYY-MM-DD]
Reviewed files:
  - [file path]

Findings:
  P0: [none / issue description]
  P1: [none / issue description]
  P2: [none / issue description]

Decision: APPROVED / APPROVED_WITH_RISK / BLOCKED
```

---

## Round 2 — Failure & Lifecycle Review

### Checklist

- [ ] Race conditions are addressed (duplicate offer, concurrent candidate delivery, simultaneous disconnect)
- [ ] Reconnect and disconnect handling covers all edge cases
- [ ] Kurento `MediaPipeline` and `WebRtcEndpoint` resources are always released on session end
- [ ] Duplicate events (e.g., same ICE candidate re-delivered) are handled idempotently
- [ ] Timeout and retry logic cannot produce infinite loops
- [ ] **Idle/timeout-threshold logic does not kill a NORMAL case that exceeds the threshold.** Signaling WS is idle during a stable call (messages only on join/leave/SDP/ICE; media flows over a separate UDP path) — so "signaling idle == failure" is a wrong assumption. Raising the threshold never fixes a structural false-positive; use an explicit event or round-trip ping/pong instead.
- [ ] **Client-side timeouts are cross-checked against the matching server setting** (e.g. `server.rtc.session-idle-timeout`). A 10x–100x mismatch (client kills far earlier than server allows) is a red flag → re-examine.
- [ ] Authentication and authorization are enforced at the WebSocket handler level
- [ ] Client state recovers correctly after abnormal termination (server restart, network drop)
- [ ] Long-lived stable-state scenarios (idle past every threshold) are exercised — not just immediate event tests. Time-elapsed logic (heartbeat/timeout) needs a "quiet call for N minutes" test.

### Record Format

```
## Round 2 — Failure & Lifecycle Review
Reviewer: [agent name or author]
Date: [YYYY-MM-DD]
Reviewed files:
  - [file path]

Findings:
  P0: [none / issue description]
  P1: [none / issue description]
  P2: [none / issue description]

Decision: APPROVED / APPROVED_WITH_RISK / BLOCKED
```

---

## Decision Criteria

| Decision | Condition |
|----------|-----------|
| **APPROVED** | No P0 or P1 issues found |
| **APPROVED_WITH_RISK** | P1 issues exist but explicitly accepted by the user |
| **BLOCKED** | Any P0 issue found — implementation must not proceed until fixed |

- **P0 found** → Stop immediately. Fix and re-run the affected round.
- **P1 remaining** → Obtain explicit user acceptance or fix before proceeding.
- **Both rounds APPROVED or APPROVED_WITH_RISK** → Implementation is unblocked.

### Severity must not be downgraded by design inertia
When a cross-model / external reviewer flags a **principle-level** defect (the logic is wrong, not just mistuned), **do NOT lower its severity because the component is "core to the approved design."** A core component with a wrong principle is more dangerous, not less. Downgrading to a comment fix + "Remaining Risk" is how a real P0 ships under an APPROVED stamp. To lower severity you must (a) prove with code/domain evidence why the reviewer is wrong, AND (b) get explicit user sign-off recorded in 05-expert-review. Default action for a principle-level flag is **remove/redesign the component**, not tune it.

> Case (2026-06): `webrtc_ws_auto_reconnect` STEP5 — Codex flagged heartbeat idle-close as a normal-call false-positive; it was downgraded to a doc-fix + Remaining Risk because "heartbeat is core to A′." It detonated in the real environment as an infinite reconnect loop. See memory `cross-model-p0-no-downgrade` / `threshold-logic-normal-case`.

---

## Cross-model Unavailable — Intra-model Adversarial Review Fallback

When the host cross-model consult path is unavailable for a Phase 05 L3 review (after the retries in `docs/agent/pdca-templates.md` step 7 fail), the **Intra-model Adversarial Review Loop** is the only sanctioned substitute. It is a **degraded fallback, not an equivalent**: a single model shares its own blind spots, which is exactly how the 2026-06 heartbeat P0 above shipped under internal confidence.

- Run the two-round protocol above 3–5 times via `chatforyou-external-expert` in a fresh context, rotating the adversarial lens each round (R1 flow · R2 failure/lifecycle · R3 security/auth · R4 ops/config/deadlock · R5 regression/edge), then a separate feedback-validity pass.
- Full procedure and recording rules live in `docs/agent/pdca-templates.md` → "Intra-model Adversarial Review Loop".
- The loop can only produce **`APPROVED_WITH_RISK`** (never clean `APPROVED`), with the risk "independent cross-model verification not performed" recorded and **explicit user sign-off** required before Phase 06.

---

## Related Rules
- `AGENT_GUIDE.md` — WebRTC / WebSocket Changes
- `AGENT_GUIDE.md` — Risk & Workflow Gate
