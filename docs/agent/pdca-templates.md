# PDCA Workflow and Phase Document Templates

Use this document when a task requires Phase 00-06 planning, a vault scan, an
external consultant review, or a phase document template.

---

## Workflow Overview

| Phase | Name | Key Tasks | Deliverable |
|:---:|:---|:---|:---|
| 00 | Base Plan | Strategy, impact analysis, prior knowledge, technical risk | `plan_docs/00-base_plan/.../[feature]_plan.md` |
| 01 | Plan | Requirements, data model, API specifications | `plan_docs/01-plan/[feature].md` |
| 02 | Design | Class/interface design, sequence diagrams, error codes | `plan_docs/02-design/[feature].md` |
| 03 | Do | Implementation, unit testing, guide checklist updates | `plan_docs/03-implementation/[feature].md` |
| 04 | Analyze | Existing 04 file check, design vs implementation gap, external review on request | `plan_docs/04-analyze/[feature].md` |
| 05 | Expert Review | Full-stack review for code quality, security, performance, infra, conventions | `plan_docs/05-expert-review/[feature].md` |
| 06 | Report | Final report, lessons learned, future tasks | `plan_docs/06-report/[feature].md` |

## Phase 00 Vault Scan Procedure

Before writing the base plan, scan the Obsidian vault for prior knowledge when
`.local/local_agent_guide.md` exists and the Obsidian vault MCP tools are available.

1. Read the Obsidian vault's `AGENT_GUIDE.md` (vault-internal note, separate from the project root file).
2. Read `wiki/index.md`.
3. Search for relevant prefixed notes:
   - `[BRAINSTORM]`
   - `[SPEC]`
   - `[TECH]`
   - `[BUG]`
   - `[POSTMORTEM]`
4. Read only the relevant notes needed for the task, normally 1-5 files.
5. Summarize findings under `## 0. Prior Knowledge (Vault Scan)` in the base plan.

When vault scan is not possible, record N/A:

```markdown
## 0. Prior Knowledge (Vault Scan)
Status: N/A
Reason: [`.local/local_agent_guide.md` not found / Obsidian MCP tools unavailable / vault path inaccessible]
Decision: Proceeding with standard workflow based on repository files only.
```

## External Consultant Protocol

When the user requests "External Expert Review" or "Consultant Validation":

1. Check whether `plan_docs/04-analyze/[feature].md` already exists. If it exists, read it first and build on previous findings.
2. Review from an external consultant perspective covering object-oriented design, security, and performance.
3. Identify the reviewer agent clearly, for example `Reviewer: Codex`.
4. Record the opinion under `## External Expert Review` in the Phase 04 document.
5. Propose improvements. Obtain user approval before adding new scope, architecture changes, or risky migration.

---

## [Phase 00: Base Plan Template]

```markdown
# [Base Plan] {Feature Name}

## 0. Prior Knowledge (Vault Scan)
| Type | Note | Key Takeaway |
|------|------|--------------|
| BRAINSTORM | — | — |
| SPEC | — | — |
| TECH | — | — |
| BUG | — | — |
| POSTMORTEM | — | — |

## 1. Summary (Goal & Scope)

## 2. Impact Analysis (Critical)
- [Backend]: ...
- [Frontend]: ...
- [Desktop]: ...

## 3. Technology & Risks

## 4. Final Conclusion & UX Guide

## 5. Document Mapping (Checklist)
```

---

## [Phase 01: Plan Template]

```markdown
# [Plan] {Feature Name}

## 1. User Stories & Requirements

## 2. Data Schema (Entities, DTOs)

## 3. API Specifications
```

---

## [Phase 02: Design Template]

```markdown
# [Design] {Feature Name}

## 1. Architecture & Interface Design

## 2. Sequence Diagrams

## 3. Error Codes & Exception Strategy
```

---

## [Phase 03: Implementation Guide Template]

```markdown
# [Implementation] {Feature Name}

## 1. File Ownership (Modified Files)

## 2. Implementation Checklists
- [ ] Development feature list
- [ ] Test scenarios and validation method
- [ ] Code conventions

## 3. Build & Test Results
```

---

## [Phase 04: Gap Analysis Template]

```markdown
# [Analyze] {Feature Name}

**Reviewer:** {Agent Name (e.g., Gemini, Claude Code, Codex)}
**Date:** {YYYY-MM-DD}

## 1. Design vs. Implementation Gap (Match Rate: X%)

## 2. Missing Items & Deviations

## 3. External Expert Review (Required on request)
- [Architectural Review]: ...
- [Security & Performance]: ...
- [Critical Issues & Suggestions]: ...
```

---

## [Phase 05: Expert Review Template]

```markdown
# [Expert Review] {Feature Name}

**Reviewer Role:** 5-Year Full-Stack Expert (Backend / Frontend / Infra)
**Review Date:** {YYYY-MM-DD}

## 1. Code Quality
### 1.1 SOLID & Design Patterns
### 1.2 Naming, Readability, Modularity
### 1.3 Dead code / unnecessary complexity

## 2. Backend (Spring Boot)
### 2.1 Layer Separation (Controller / Service / Repository)
### 2.2 Transaction & Exception Handling
### 2.3 Security (Auth, Input Validation, Info Leakage)
### 2.4 Performance (N+1, Index, Caching, Concurrency)

## 3. Frontend (Node.js / jQuery)
### 3.1 UI/UX Consistency
### 3.2 Ajax / WebSocket error handling
### 3.3 Bundle size & asset optimization

## 4. Infra / Deploy
### 4.1 Environment config separation (local / prod)
### 4.2 Logging & Observability
### 4.3 Failure recovery & graceful degradation

## 5. Convention Compliance
### 5.1 Project conventions (springboot_backend.md / nodejs_frontend.md)
### 5.2 Language & framework standards

## 6. Review Scorecard
| Category | Score (1–5) | Key Issues |
|:---|:---:|:---|
| Code Quality | | |
| Backend Architecture | | |
| Frontend Quality | | |
| Infra / Deploy | | |
| Convention Compliance | | |

## 7. Action Items
| Priority | Category | Issue | Recommendation |
|:---:|:---:|:---|:---|
| P0 | | | |
| P1 | | | |
| P2 | | | |
```

---

## [Phase 06: Final Report Template]

```markdown
# [Report] {Feature Name}

## 1. Completion Summary

## 2. Value Delivered Table
| Problem | Solution | UX Effect | Core Value |
|:---|:---|:---|:---|

## 3. Lessons Learned & Future Tasks

## 4. Vault Knowledge Capture
| Prefix | Note Title | Action | Reason |
|:---|:---|:---|:---|
| [BUG] / [TECH] / [SPEC] / [BRAINSTORM] / [POSTMORTEM] | — | created / updated / checked-no-update / N/A | — |
```

`checked-no-update` means the vault capture check was performed, but no capture
trigger applied and no note needed to be created or updated.

---

## Related Rules
- `AGENT_GUIDE.md` — Risk & Workflow Gate
- `AGENT_GUIDE.md` — Required Agent Reference Documents
