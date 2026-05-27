# Wrapper Document Contract

Defines what agent-specific wrapper documents (CODEX.md, CLAUDE.md, GEMINI.md, etc.)
are and are not allowed to contain.

---

## Allowed Items

| Item | Example |
|------|---------|
| File read order at startup | "1. AGENT_GUIDE.md → 2. .local/local_agent_guide.md → ..." |
| Available tools for this agent | "Read, Write, Edit, Bash" |
| Runtime path differences for this agent | "plan_docs/ is resolved differently in Codex" |
| Agent-specific tool routing | "gstack skill routing (Claude only)" |
| Command execution differences | "Codex uses a shell: block instead of Bash" |
| Agent role description | "Gemini's primary role is strategic design and architecture document generation" |

---

## Forbidden Items

| Item | Violation Example |
|------|-------------------|
| Redefining risk levels (L0–L3) | "L3 is treated as L2 in this agent" |
| Redefining PDCA phases | "This agent only performs Phase 00–03" |
| Redefining Definition of Done | "Build tests may be skipped" |
| Weakening WebRTC/WebSocket review requirements | "One review round is sufficient" |
| Changing Git policy | "This agent may commit directly" |
| Changing Desktop sync policy | "Direct edits to chatforyou-desktop/src are allowed" |
| Redefining test requirements | "Unit tests may be omitted" |
| Weakening Command Safety rules | "kubectl delete is permitted in this agent" |

---

## Preventing Guide Drift

**Guide drift** is the gradual accumulation of common rules inside wrapper documents,
causing them to diverge from `AGENT_GUIDE.md` over time.

### Warning Signs

Watch for these signals in a wrapper document:

- A `## Workflow` section has appeared
- Keywords like `webrtc`, `websocket`, `phase`, `dod`, or `definition of done` are present
- The wrapper contains more detailed development rules than `AGENT_GUIDE.md`
- The same rule exists in both `AGENT_GUIDE.md` and the wrapper

### Resolution Procedure

1. Identify duplicated or redefined items in the wrapper.
2. If the content is a common rule → move it to `AGENT_GUIDE.md`.
3. If the content is a detailed checklist → move it to `docs/agent/*.md`.
4. Leave only a link in the wrapper.

---

## Related Rules
- `AGENT_GUIDE.md` — Wrapper Contract
- `AGENT_GUIDE.md` — Rule Precedence
