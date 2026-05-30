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
| 04 | Analyze | Design vs implementation gap analysis by **chatforyou-lead** (책임) + **qa-expert** (보조 — QA Coverage Verification). Single verdict not issued here. | `plan_docs/04-analyze/[feature].md` |
| 05 | Expert Review | Independent synthesis by **chatforyou-external-expert** + **host 기준 external cross-model reviewer**. Final APPROVED/FAIL/BLOCKED verdict issued here. | `plan_docs/05-expert-review/[feature].md` |
| 06 | Report | Final completion report by **chatforyou-lead**. **Written only after Phase 05 APPROVED**. If 05 = FAIL/BLOCKED → rework or recover external review → re-review → APPROVED → then 06. | `plan_docs/06-report/[feature].md` |

## Phase Separation Rules

### Mandatory Writing Rule
- Each Phase's writing obligation follows the risk level in `AGENT_GUIDE.md §3 Risk & Workflow Gate`.
- An empty `plan_docs/{phase}/` directory is **not** a convention. Treat it as a prior task's omission, not a precedent for skipping.
- Skipping a mandatory Phase requires an explicit reason in the Plan body and user approval.

### 00 vs 01 — most commonly confused boundary
| Dimension | 00 Base Plan | 01 Plan |
|:---|:---|:---|
| Nature | **CONTEXT** — why / what / impact | **CONTENT** — details of this feature itself |
| Covers | Strategy, prior knowledge (Vault scan), Backend/Frontend/Desktop impact, before/after changes, technical risk | User Stories, Data Schema/DTO, API Spec, detailed feature spec, detailed design, detailed analysis |
| Does NOT cover | Function signatures, API schemas, file/line numbers | Strategic judgment, impact analysis, Vault scan |
| Length guide | 1–2 page context | Proportional to feature scope |

### Other commonly confused boundaries
- **02 Design vs 03 Implementation**
  - 02 = architecture / sequence / error codes / interface design
  - 03 = file ownership / implementation checklist / build & test results
- **04 Analyze vs 05 Expert Review**
  - 04 = **chatforyou-lead 책임** + qa-expert 보조. 설계-구현 gap (Match Rate) + Missing Items + QA Coverage Verification. **단독 판정(APPROVED/FAIL) 금지 — 판정은 05 에서**.
  - 05 = **chatforyou-external-expert + host 기준 external cross-model reviewer**. 독립 종합 + cross-model 교차검증. 최종 판정(APPROVED/FAIL/BLOCKED) 발생 위치.
  - external/cross-model 리뷰는 **04 가 아닌 05** 에 기록.

### Signals of incorrect separation
- 00 contains a function signature → move to 01
- 00 contains a file/line number → move to 01 or 02
- 01 contains Vault prior knowledge → move to 00
- 02 contains build/test results → move to 03

## Component-level Plan Docs

The project runs **two parallel plan_docs trees**:

| Tree | Path | Purpose | Owner |
|:---|:---|:---|:---|
| Root (feature-wide PDCA) | `plan_docs/00–06/[feature].md` | Cross-component, project-wide design under standard PDCA templates | `chatforyou-lead` |
| Backend (component-level) | `springboot-backend/plan_docs/[feature]_plan.md` | Code-level implementation design for Spring Boot side | `chatforyou-backend-expert` |
| Frontend (component-level) | `nodejs-frontend/plan_docs/[feature]_plan.md` | Code-level implementation design for Node.js frontend side | `chatforyou-frontend-expert` |
| Desktop | — | **Not used.** `chatforyou-desktop/` mirrors `nodejs-frontend/` via sync; record Desktop-affecting changes in the frontend or root tree | — |

### Writing order for L2/L3 changes
1. Root `00-base_plan/` — strategy + impact
2. Root `01-plan/` — feature-level requirements + Data Schema + API spec (cross-component contract)
3. Root `02-design/` — architecture + sequence + error codes
4. Component `springboot-backend/plan_docs/` and/or `nodejs-frontend/plan_docs/` — code-level implementation design (free format, may include code snippets, TDD plans, refactor diffs)
5. Root `03-implementation/` — file ownership table, checklist, build/test results
6. Implementation
7. Root `04-analyze/`, `05-expert-review/`, and `06-report/` — gap analysis + external review + final report

### Format conventions
- Root tree files: follow the templates below.
- Component tree files: free format. Recommended sections — Goal, Code-level Design, File-by-file Plan, Unit Test Plan, Sync/Verification Notes.
- File-naming stem must match across root and component trees for the same feature (e.g., `kurento_peer_error_fallback` in both `plan_docs/02-design/kurento_peer_error_fallback.md` and `springboot-backend/plan_docs/kurento_peer_error_fallback_plan.md`).

### Conflict resolution
- Root tree is the **single source of truth for cross-component contract** (DTO fields, event names, error codes).
- Component tree owns code-level decisions inside its own boundary; any cross-component impact must be reflected back into root `01-plan` or `02-design`.

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

External / cross-model expert review is **MANDATORY** for L3 (and recommended for L2) work. It is recorded in **Phase 05**, not 04.

1. Check whether `plan_docs/04-analyze/[feature].md` exists. If yes, read the lead's gap findings + QA coverage verification first; build on them.
2. Review from an external consultant perspective covering object-oriented design, security, and performance.
3. **Core WebRTC Architecture Gate**:
   - Before starting the review-rework loop, check whether the findings or proposed actions change WebRTC core architecture.
   - Scope: WebRTC, WebSocket, Signaling, Kurento, ICE/SDP, DataChannel, room lifecycle, media pipeline, signaling event contract, recovery/reconnect state machine.
   - If a new architecture change is detected, stop automatic rework and get user approval first.
   - Local bug fixes inside the already approved design are not blocked by this gate.
4. **External cross-model invocation**:
   - Invoke the host-specific external consult skill or CLI defined by that agent/runtime with all phase documents:
     `plan_docs/00-base_plan/[feature].md` + `01-plan/[feature].md` + `02-design/[feature].md` + `03-implementation/[feature].md` + `04-analyze/[feature].md` + component plan docs (`springboot-backend/plan_docs/`, `nodejs-frontend/plan_docs/`) + implementation files.
   - Request: "단순 코드 리뷰가 아닌 개발 맥락 기반 외부자 검토. 04의 Review Context를 포함하여 설계 의도와 구현의 일치 여부, 프로젝트 기술 스택 적합성, lifecycle/edge case/security/test/UX 리스크, 06-report 진입 가능 여부를 검토하라. 최종 권고는 APPROVED / FAIL / BLOCKED."
5. **Review-rework loop policy**:
   - L3: 3-iteration review-rework loop is mandatory and automatic in Phase 05.
   - L2: review-rework loop is recommended only. Ask the user before starting it.
   - L1/L0: do not use the Phase 05 review-rework loop.
   - One iteration means: external consult (`$claude consult` in Codex), faithful findings record, external-expert + dev-team triage, accepted rework, then `03-implementation` and `04-analyze` update.
   - Rework is limited to the already approved design and scope. New requirements, new architecture, and risky migration go to `Needs User Approval`.
6. **3-iteration stop rule**:
   - After the 3rd L3 review, if the Final Status is not `APPROVED`, record `Final Status: BLOCKED`.
   - Also record `06-report 작성 금지`, stop the workflow, and report the blocked state to the user.
7. **Fallback**:
   - Retry the host-specific consult path or start a fresh session.
   - If context is too large, retry with 04 Review Context + 01/02/03 + core implementation files.
   - Emit a copy-pastable host-specific consult command or raw CLI prompt for the user to run.
   - Ingest the user's run output into the 05 doc.
8. Identify the reviewer clearly: `Reviewer: [cross-model tool] via [host agent]`.
9. Record the opinion under `## External / Cross-model Review` in the **Phase 05** document. Output must be **faithful (no summarization)**.
10. Cross-model agreement is a recommendation, not a decision — final verdict is external-expert + user.
11. If mandatory L3 external review cannot be completed after fallback, record `Final Status: BLOCKED` in 05 and do not write 06.
12. Propose improvements. Obtain user approval before adding new scope, architecture changes, or risky migration.

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

**Reviewer:** chatforyou-lead (책임)
**QA Contributor:** chatforyou-qa-expert (보조 — 커버리지 검증)
**Date:** {YYYY-MM-DD}

> 04 는 단독 판정(APPROVED/FAIL)을 내리지 않는다. 판정은 05 에서 external-expert + host 기준 external cross-model reviewer 가 종합.

## 1. Design vs. Implementation Gap (Match Rate: X%)

## 2. Missing Items & Deviations

## 3. QA Coverage Verification (qa-expert 보조 의견)
- 커버한 케이스 / 미커버한 케이스 / 의도적으로 제외한 케이스
- 설계 요구사항 대비 테스트 커버리지의 충분성 판단
- 추가 권장 시나리오 (있다면)

## 4. Review Context for External Model

### Original User Intent

### Key Decisions During Implementation

### Scope Changes / Deferred Items

### Design vs Implementation Notes

### QA Coverage Summary

### Known Risks / Open Questions

### Files External Reviewer Must Inspect
```

---

## [Phase 05: Expert Review Template]

```markdown
# [Expert Review] {Feature Name}

**Reviewer Role:** chatforyou-external-expert (synthesis) + host 기준 external cross-model reviewer
**Review Date:** {YYYY-MM-DD}
**Final Status:** APPROVED / FAIL / BLOCKED
**Source:** 04-analyze gap findings + 팀 결과물

## External / Cross-model Review

**Reviewer:** [cross-model tool] via host agent
**Invocation:** host agent 기준 cross-model invocation
**Inputs:** plan_docs/00·01·02·03·04 + component plan docs + implementation files
**Status:** COMPLETED / BLOCKED

### External Findings
[external reviewer 출력 원문 그대로 — 요약 금지]

### Review Loop Iterations
| Iteration | External Result | Triage Result | Accepted Rework | Rejected / Deferred | 03/04 Updated | Status |
|:---:|:---|:---|:---|:---|:---:|:---|
| 1 | | | | | | |
| 2 | | | | | | |
| 3 | | | | | | |

### external-expert Interpretation
- 채택: [external reviewer 가 잡은 것 중 채택]
- 반론: [프로젝트 맥락상 이미 의도된 결정 — 제외 사유]
- 보류: [판단 보류 항목]

### Needs User Approval
| Item | Reason | Proposed Owner | Status |
|---|---|---|---|
| Core WebRTC architecture change / new scope / risky migration | 자동 rework 범위 밖 | user + lead | pending / approved / rejected |

### Final Status
APPROVED / FAIL / BLOCKED

> L3에서 3-iteration 후에도 `APPROVED`가 아니면 `Final Status: BLOCKED`로 기록한다. 이 경우 `06-report 작성 금지`이며, Phase 06으로 진행하지 않는다.

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
