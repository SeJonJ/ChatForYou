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
- [ ] Authentication and authorization are enforced at the WebSocket handler level
- [ ] Client state recovers correctly after abnormal termination (server restart, network drop)

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

---

## Related Rules
- `AGENT_GUIDE.md` — WebRTC / WebSocket Changes
- `AGENT_GUIDE.md` — Risk & Workflow Gate
