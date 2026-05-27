# Phase 04: Bug Patterns & Risk Assessment Guide

Extracted from the root guide. During Phase 04 (Analyze) gap analysis, proactively search
for the bug patterns below. All identified issues must be recorded in the analysis document
with their severity level.

> **Scope Note**: The patterns below are *additional* proactive checks for known high-risk
> areas in ChatForYou v2. They do not replace general gap analysis (design vs. implementation
> comparison). Any bug found **outside these domains** must still be recorded with the
> appropriate P0/P1/P2 classification — these domains are not an exhaustive taxonomy.

---

## Severity Matrix

| Level | Severity | Description | Action Required |
|:---:|:---:|:---|:---|
| **P0** | **Critical** | System crash, data loss, security breach, core WebRTC/Signaling failure | Immediate fix before proceeding to Phase 05 (Expert Review) |
| **P1** | **Major** | Performance bottleneck, significant UX degradation, inconsistent state in Redis/DB | Must be resolved or explicitly reported as a remaining risk |
| **P2** | **Minor** | Logging inconsistency, minor styling issues, non-critical documentation gaps | Can be scheduled for future improvement |

---

## Common High-Risk Bug Patterns (Domain Specific)

### 1. WebRTC & Signaling (High Risk)

> See also `docs/agent/webrtc-review-protocol.md`: two documented review rounds are
> required before any WebRTC/WebSocket change.

- **SDP/ICE Mismatch** — Discrepancies between Kurento Media Server and client-side signaling.
- **Resource Leak** — Failure to release `WebRtcEndpoint` or `MediaPipeline` after session termination.
- **Race Conditions** — Multiple signaling messages arriving out of order (e.g., ICE candidate arrives before offer).

### 2. Concurrency & Threading

- **MDC Data Loss** — TraceID not propagated to `@Async` threads or custom thread pools.
- **Thread-Unsafe Collections** — Using `HashMap` instead of `ConcurrentHashMap` in multi-threaded WebSocket handlers.

### 3. Security & Integrity

- **Token Bypass** — Missing `ChatForYouException` for unauthorized room access attempts.
- **Information Leakage** — Stack traces or internal system paths exposed in 500 error responses.
- **Validation Gaps** — Insufficient input sanitization leading to potential XSS or injection in chat/game messages.

### 4. Performance & Observability

- **Unbounded Logging** — Excessive logging in high-frequency loops (e.g., game tick, RTC stats).
- **Missing Metrics** — Failure to record critical events in Prometheus/Grafana.

---

## Related Rules
- `AGENT_GUIDE.md` — Risk & Workflow Gate
- `docs/agent/webrtc-review-protocol.md` — Detailed WebRTC-specific checklist
