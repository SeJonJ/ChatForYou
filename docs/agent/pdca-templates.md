# PDCA Workflow and Phase Document Templates

Use this when a task requires Phase 00–06 planning, an optional prior-knowledge
scan, an independent/cross-model review, or a phase document template. The phase
set and per-level obligation are configured in `profile.pdca`; this document
defines the neutral methodology. Domain specifics (stacks, high-risk domains,
component names) come from the profile, never from this file.

---

## Workflow Overview

| Phase | Name | Key Tasks | Deliverable |
|:---:|:---|:---|:---|
| 00 | Base Plan | Strategy, impact analysis, prior knowledge, technical risk | `plan_docs/00-base_plan/.../[feature]_plan.md` |
| 01 | Plan | Requirements, data model, API specifications, acceptance matrix | `plan_docs/01-plan/[feature].md` |
| 02 | Design | Class/interface design, sequence diagrams, error codes | `plan_docs/02-design/[feature].md` |
| 03 | Implementation | Pre-code ownership/checklist skeleton, implementation, unit testing, verification evidence | `plan_docs/03-implementation/[feature].md` |
| 04 | Analyze | Design vs implementation gap analysis by **leader** + **qa** (coverage + acceptance evidence). No single verdict here. | `plan_docs/04-analyze/[feature].md` |
| 05 | Expert Review | Independent synthesis by **reviewer** (+ cross-model reviewer when enabled). Acceptance unresolved items block APPROVED. Final APPROVED/FAIL/BLOCKED issued here. | `plan_docs/05-expert-review/[feature].md` |
| 06 | Report | Final completion report by **leader**. **Written only after Phase 05 = APPROVED.** If 05 = FAIL/BLOCKED → rework → re-review → APPROVED → then 06. | `plan_docs/06-report/[feature].md` |

Roles (`leader` / `reviewer` / `qa`) are the neutral CORE roster — map to your
team in `profile.team`.

## Phase Separation Rules

### Mandatory Writing Rule
- Each phase's writing obligation follows the risk level in
  `AGENT_GUIDE.md` Risk & Workflow Gate and `profile.pdca`.
- An empty `plan_docs/{phase}/` directory is **not** a convention. Treat it as a
  prior task's omission, not a precedent for skipping.
- Skipping a mandatory phase requires an explicit reason in the plan body and
  user approval.
- The `pre-implementation-gate` hook **blocks** L2/L3 implementation when a
  required pre-implementation phase is missing, and blocks Phase 06 until the
  approve phase records `APPROVED`.

### 00 vs 01 — the most commonly confused boundary
| Dimension | 00 Base Plan | 01 Plan |
|:---|:---|:---|
| Nature | **CONTEXT** — why / what / impact | **CONTENT** — details of this feature |
| Covers | Strategy, prior knowledge, component impact, before/after, technical risk | User stories, data schema/DTO, API spec, detailed design |
| Does NOT cover | Function signatures, API schemas, file/line numbers | Strategic judgment, impact analysis, prior-knowledge scan |
| Length | 1–2 page context | Proportional to feature scope |

### Other boundaries
- **02 Design vs 03 Implementation**: 02 = architecture / sequence / error codes / interface design; 03 = file ownership / implementation checklist / acceptance trace / build & test results. 03 is opened before source edits with ownership and checklist, then completed after code with evidence.
- **04 Analyze vs 05 Expert Review**: 04 = leader (responsible) + qa (coverage) — gap (match rate) + missing items + coverage. **No standalone verdict.** 05 = reviewer (+ cross-model when enabled) — independent synthesis + cross-check; final verdict (APPROVED/FAIL/BLOCKED) issued here. Cross-model review is recorded in **05, not 04**.

### Signals of incorrect separation
- 00 contains a function signature → move to 01
- 00 contains a file/line number → move to 01 or 02
- 01 contains prior-knowledge scan → move to 00
- 02 contains build/test results → move to 03

## Component-level Plan Docs

A project may run **two parallel plan_docs trees** (configured via
`profile.paths.component_plan_docs` and `profile.components`):

| Tree | Path | Purpose | Owner |
|:---|:---|:---|:---|
| Root (feature-wide PDCA) | `plan_docs/00–06/[feature].md` | Cross-component, project-wide design under standard templates | leader |
| Component-level | `{component}/plan_docs/[feature]_plan.md` | Code-level implementation design within one component boundary | component owner |

### Writing order for L2/L3 changes
1. Root `00-base_plan/` — strategy + impact
2. Root `01-plan/` — feature requirements + data schema + API (cross-component contract)
3. Root `02-design/` — architecture + sequence + error codes
4. Component `{component}/plan_docs/` — code-level design (free format)
5. Root `03-implementation/` — pre-code ownership/checklist skeleton + acceptance trace
6. Implementation + tests
7. Update root `03-implementation/` — files changed, checklist results, build/test evidence
8. Root `04-analyze/`, `05-expert-review/`, `06-report/` — gap + acceptance evidence + review + report

### Conventions
- Root tree is the **single source of truth for the cross-component contract** (DTO fields, event names, error codes).
- File-naming stem must match across root and component trees for the same feature.
- Component tree owns code-level decisions inside its boundary; cross-component impact must be reflected back into root `01-plan`/`02-design`.

## Prior-Knowledge Scan (optional — knowledge_capture)

When `profile.knowledge_capture.vault_path` is set and the knowledge provider is
available, scan prior knowledge before writing the base plan; otherwise record
N/A. Summarize findings under `## 0. Prior Knowledge` in the base plan.

```markdown
## 0. Prior Knowledge
Status: N/A
Reason: [knowledge_capture disabled / provider unavailable]
Decision: Proceeding from repository files only.
```

## Fast Cycle composite plan

Fast Cycle is available only when shared policy sets `pdca.fast_cycle.enabled: true` and only for
actual L2/L3 work. It does not weaken actual risk, verification, acceptance, independent approval,
write-back, retro, or snapshots. Before any write, collect Fast review level, lens count, and a
one-line reason. Use one physical Phase 00 document with exact ordered headings `## Phase 00` through
`## Phase 04`; embed the normal requirements, design, ownership, acceptance trace, implementation,
and verification content under those headings. Physical Phase 05 and 06 remain separate.

Open the audit only after every Phase 00 and Phase 03 pre-edit checklist item is checked. Bind every
review round to the configured lenses, close or abort the Fast audit before clearing the cycle, and
commit `.sage/fast_cycle.jsonl` with the delivery evidence. The standard `sage-cycle` skill never
selects this mode implicitly; use `sage-cycle-fast`, `sage-plan-fast`, and `sage-team-fast`.

## Independent / Cross-model Review Protocol

Independent review is **mandatory for L3** (recommended for L2). It is recorded in
**Phase 05**, not 04. The reviewer runtime is resolved by `sage doctor`
(same-runtime clean-context, or opposite-runtime when `profile.options.cross_model`
is on and reachable — `docs/agent/review-protocol.md`).

1. If `plan_docs/04-analyze/[feature].md` exists, read the lead's gap findings + coverage first; build on them.
2. Review from an independent perspective: design intent vs implementation, stack fitness, lifecycle/edge-case/security/test/UX risk.
3. **High-risk architecture gate**: before a review-rework loop, check whether findings change a high-risk domain declared in `profile.risk` (L3). If a new architecture change is detected, stop automatic rework and get user approval. Local fixes inside the approved design are not blocked.
4. **Independent invocation**: build one packet carrying all phase documents + implementation files. When cross-model is enabled, run `sage cross-check --packet-file <packet>` (claude-host → `codex exec`, codex-host → `claude -p`) at `cross_model.effort` (default `high`). For recommended local opt-out or policy off, run `sage review --packet-file <packet> --host <active_host>` to start a fresh active-host process. Request context-based external review and APPROVED / FAIL / BLOCKED. Missing process evidence or `REVIEWER_STATUS: BLOCKED` is not a completed review.
5. **Acceptance gate**: read the Phase 01 acceptance matrix and Phase 04 evidence table outside fenced code blocks. Required `FAIL` always blocks. Required `NOT TESTED` blocks L3 unless an exact active waiver records user confirmation, reason, scope, and remaining evidence; even then keep the item unresolved and report a residual WARN, never PASS. Use `N/A` only with an explicit out-of-scope/deferred reason.
6. **Review-rework loop**: L3 = mandatory iterations (default 3) in Phase 05; L2 = recommended (ask first); L1/L0 = none. One iteration = review → faithful findings record → triage → accepted rework → update 03/04.
7. **Stop rule**: if after the final L3 iteration the status is not APPROVED, record `Final Status: BLOCKED`, do not write 06, and report to the user.
8. **Fallback**: retry the peer path or a fresh session; if context is too large, retry with 04 Review Context + 01/02/03 + core files; if a mandatory L3 review cannot complete, record BLOCKED in 05 and do not write 06.
9. Identify the reviewer: `Reviewer: [tool] via [host]`. Record the opinion under `## External / Cross-model Review` in the **Phase 05** document — **faithful, no summarization**.
10. Cross-model agreement is a recommendation, not a decision — final verdict is reviewer + user.

---

## Template language

The templates below are written in English because this file is the contract every reviewer
reads. **They are not literals to copy into a Korean document.** A cycle whose Phase 00 declares
`Document-Language: ko` writes its human-facing structure in Korean too — section headings, table
headers, list labels and checklist text — because a Korean body under English headings is exactly
the mixed state the marker exists to prevent. Copying this file's English headings verbatim into a
`ko` cycle is the most common way that happens. `docs/agent/language-policy.md` is the SSOT.

Three groups behave differently.

**Never translated — a parser reads the exact string.** Translating one of these does not change
presentation, it removes the thing a gate looks for:

```text
## 5. Done Criteria                     ### Done Criteria              (fast)
## 6. Done Criteria Revision Log        ### Done Criteria Revision Log (fast)
## Phase 00 … ## Phase 04               (Fast composite headings, exact and in order)
Status: PENDING — implementation not started        (Fast Phase 04 open marker)
- [x] Phase 00 context complete … - [x] Phase 03 ownership, implementation checklist, and
  verification plan ready                           (Fast Phase 00 mapping items)
- [x] File ownership assigned before source edits … - [x] Verification command plan recorded
                                                    (Fast Phase 03 pre-implementation items)
Cycle-Stem  Document-Language  Risk Level  Done-Criteria-Revision  Loop-Run  Phase00-Hash
Final Status  Source-05  Fast-Run  Fast-Audit-Run  Fast-Review-Level  Fast-Lenses  Fast-Reason
Review-Assurance  Review-Close-Reason  Review-Rounds  Residual-Findings
REDUCED_BY_USER_AUTHORIZATION  USER_AUTHORIZED_EARLY  FAST-CONVERTED
APPROVED  FAIL  BLOCKED  PASS  WARN  NOT TESTED  N/A  L0  L1  L2  L3
```

The Phase 03 checklist items are ordinary translatable prose in a **standard** cycle and fixed
literals in a **Fast composite** plan, where `sage fast-cycle open` matches them by exact string.

**Translated, but one keyword has to survive.** The acceptance gate finds these two sections by
scanning heading text for `acceptance matrix` / `acceptance evidence` — or, in Korean, for `수용`
or `인수`. A heading such as `## 4. 승인 기준표` contains neither and the gate reads the document as
having no acceptance table at all. Their column headers are matched the same way, so keep one
recognized word per column: `id`/`acceptance`, `required`/`필수`, `status`/`상태`,
`evidence`/`근거`, `reason`/`사유`, `notes`/`비고`. Keep the evidence column `필요 증거`, not
`필수 증거` — two columns starting with `필수` make the `Required?` lookup ambiguous.

**Everything else is ordinary prose.** Translate it. The mapping below is the expected Korean
form; deviate where the feature reads better, but keep the numbering and the parser keywords.

| Phase | English | Korean |
|---|---|---|
| 00 | `## 0. Prior Knowledge` / `Type` `Note` `Key Takeaway` | `## 0. 사전 지식` / `유형` `메모` `핵심 시사점` |
| 00 | `## 1. Summary (Goal & Scope)` | `## 1. 요약 (목표와 범위)` |
| 00 | `## 2. Impact Analysis (Critical)` | `## 2. 영향도 분석 (중요)` |
| 00 | `## 3. Technology & Risks` | `## 3. 기술과 리스크` |
| 00 | `## 4. Final Conclusion & UX Guide` | `## 4. 최종 결론 및 UX 가이드` |
| 01 | `## 1. User Stories & Requirements` | `## 1. 사용자 스토리와 요구사항` |
| 01 | `## 2. Data Schema (Entities, DTOs)` | `## 2. 데이터 스키마 (엔티티, DTO)` |
| 01 | `## 3. API / Interface Specifications` | `## 3. API / 인터페이스 명세` |
| 01 | `## 4. Acceptance Matrix` | `## 4. 인수 매트릭스` |
| 01 | `ID` `User Requirement` `Required Evidence` `Owner` `Required?` | `ID` `사용자 요구사항` `필요 증거` `담당` `필수?` |
| 02 | `## 1. Architecture & Interface Design` | `## 1. 아키텍처 및 인터페이스 설계` |
| 02 | `## 2. Sequence Diagrams` | `## 2. 시퀀스 다이어그램` |
| 02 | `## 3. Error Codes & Exception Strategy` | `## 3. 오류 코드 및 예외 전략` |
| 03 | `## 0. Pre-Implementation Checklist` | `## 0. 구현 전 체크리스트` |
| 03 | `## 1. File Ownership (Modified Files)` | `## 1. 파일 담당 (변경 파일)` |
| 03 | `## 2. Implementation Checklists` | `## 2. 구현 체크리스트` |
| 03 | `## 3. Acceptance Implementation Trace` | `## 3. 인수 구현 추적` |
| 03 | `Acceptance ID` `Implementation Task` `Test / Manual Evidence Planned` `Status` | `인수 ID` `구현 작업` `계획한 테스트/수동 증거` `상태` |
| 03 | `## 4. Build & Test Results` | `## 4. 빌드 및 테스트 결과` |
| 04 | `## 1. Design vs. Implementation Gap (Match Rate: X%)` | `## 1. 설계 대비 구현 갭 (일치율: X%)` |
| 04 | `## 2. Missing Items & Deviations` | `## 2. 누락 항목과 이탈` |
| 04 | `## 3. Coverage Verification (qa)` | `## 3. 커버리지 검증 (qa)` |
| 04 | `## 4. Acceptance Evidence Review` | `## 4. 인수 증거 검토` |
| 04 | `Acceptance ID` `User Requirement` `Status` `Evidence` `Notes` | `인수 ID` `사용자 요구사항` `상태` `근거` `비고` |
| 04 | `## 5. Review Context for External Model` | `## 5. 외부 모델용 리뷰 컨텍스트` |
| 05 | `## External / Cross-model Review` | `## 외부 / 교차 모델 리뷰` |
| 05 | `### External Findings` | `### 외부 지적` |
| 05 | `### Review Loop Iterations` | `### 리뷰 루프 반복` |
| 05 | `### Reviewer Interpretation` | `### 리뷰어 해석` |
| 05 | `### Acceptance Gate` | `### 인수 게이트` |
| 05 | `### Needs User Approval` | `### 사용자 승인 필요` |
| 05 | `### Final Status` (heading only — the `Final Status:` line never changes) | `### 최종 상태` |
| 05 | `## 1. Code Quality` … `## 5. Convention Compliance` | `## 1. 코드 품질` … `## 5. 컨벤션 준수` |
| 05 | `## 6. Review Scorecard` / `## 7. Action Items` | `## 6. 리뷰 스코어카드` / `## 7. 조치 항목` |
| 06 | `## 1. Completion Summary` | `## 1. 완료 요약` |
| 06 | `## 2. Value Delivered` | `## 2. 전달한 가치` |
| 06 | `## 3. Lessons Learned & Future Tasks` | `## 3. 배운 점과 후속 과제` |
| 06 | `## 4. Knowledge Capture (optional)` | `## 4. 지식 캡처 (선택)` |

Role names (`leader`, `reviewer`, `qa`) are profile identifiers, not words — they stay as written
in both languages.

---

## [Phase 00: Base Plan Template]

> A `ko` cycle translates these headings and table headers — see **Template language** above.

```markdown
# [Base Plan] {Feature Name}

Cycle-Stem: `{phase-document-basename}`
Risk Level: <L1|L2|L3>
Done-Criteria-Revision: 1
<!-- required: this cycle's max risk — the higher of the user-declared level and the risk the change globs
     imply. Replace <...> with one of L1/L2/L3. Knowledge write-back reads this exact `Risk Level: Lx` line
     to size the note (the durable per-cycle tier that survives session resume); the 06 acceptance-evidence
     report gate (`_cycle_risk`) also scans it as a fallback when no session-level risk was declared. Keep it current: if
     implementation grows past what 00 planned, raise this line. An unfilled placeholder reads as unknown,
     and write-back then defaults to a deep note. -->

## 0. Prior Knowledge
| Type | Note | Key Takeaway |
|------|------|--------------|

## 1. Summary (Goal & Scope)

## 2. Impact Analysis (Critical)
- [Component A]: ...
- [Component B]: ...

## 3. Technology & Risks

## 4. Final Conclusion & UX Guide

## 5. Done Criteria
- [ ] {concrete user-visible or engineering completion outcome}

## 6. Done Criteria Revision Log

Initial revision 1. No replanning record.

<!-- For revision 2+, increment Done-Criteria-Revision and append:
### Revision 2
- Changed-At: Phase 04
- Reason: {why the approved plan was insufficient}
- Affected-Phases: 02, 03, 04, 05
- Summary: {what changed and what must be rerun}
-->
```

---

## [Phase 01: Plan Template]

> A `ko` cycle translates these headings and table headers — see **Template language** above.

```markdown
# [Plan] {Feature Name}

Cycle-Stem: `{phase-document-basename}`
Done-Criteria-Revision: 1
## 1. User Stories & Requirements

## 2. Data Schema (Entities, DTOs)

## 3. API / Interface Specifications

## 4. Acceptance Matrix
| ID | User Requirement | Required Evidence | Owner | Required? |
|---|---|---|---|---|
| A1 | | test / manual smoke / screenshot / log / N/A reason | | yes |
```

---

## [Phase 02: Design Template]

> A `ko` cycle translates these headings and table headers — see **Template language** above.

```markdown
# [Design] {Feature Name}

Cycle-Stem: `{phase-document-basename}`
Done-Criteria-Revision: 1
## 1. Architecture & Interface Design

## 2. Sequence Diagrams

## 3. Error Codes & Exception Strategy
```

---

## [Phase 03: Implementation Guide Template]

> A `ko` cycle translates these headings and table headers — see **Template language** above.

```markdown
# [Implementation] {Feature Name}

Cycle-Stem: `{phase-document-basename}`
Done-Criteria-Revision: 1
## 0. Pre-Implementation Checklist
- [ ] File ownership assigned before source edits
- [ ] Acceptance IDs from Phase 01 mapped to implementation tasks
- [ ] Verification command plan recorded

## 1. File Ownership (Modified Files)

## 2. Implementation Checklists
- [ ] Feature list
- [ ] Test scenarios and validation method
- [ ] Code conventions

## 3. Acceptance Implementation Trace
| Acceptance ID | Implementation Task | Test / Manual Evidence Planned | Status |
|---|---|---|---|

## 4. Build & Test Results
```

---

## [Phase 04: Gap Analysis Template]

> A `ko` cycle translates these headings and table headers — see **Template language** above.

```markdown
# [Analyze] {Feature Name}

Cycle-Stem: `{phase-document-basename}`
Done-Criteria-Revision: 1
**Reviewer:** leader (responsible)  **Contributor:** qa (coverage)  **Date:** {YYYY-MM-DD}

> 04 issues no standalone verdict. The verdict is issued in 05.

## 1. Design vs. Implementation Gap (Match Rate: X%)

## 2. Missing Items & Deviations

## 3. Coverage Verification (qa)
- covered / not covered / intentionally excluded cases
- sufficiency vs design requirements; recommended additional scenarios

## 4. Acceptance Evidence Review
| Acceptance ID | User Requirement | Status (PASS/FAIL/NOT TESTED/N/A) | Evidence | Notes |
|---|---|---|---|---|

## 5. Review Context for External Model
### Original User Intent
### Key Decisions During Implementation
### Scope Changes / Deferred Items
### Known Risks / Open Questions
### Files the Reviewer Must Inspect
```

---

## [Phase 05: Expert Review Template]

> A `ko` cycle translates these headings and table headers — see **Template language** above.

```markdown
# [Expert Review] {Feature Name}

Cycle-Stem: `{phase-document-basename}`
Done-Criteria-Revision: 1
Loop-Run: {run_id}
Phase00-Hash: sha256:{hash printed by APPROVED sage review-loop close}
**Reviewer Role:** reviewer (synthesis) (+ cross-model reviewer when enabled)
**Review Date:** {YYYY-MM-DD}
**Final Status:** {APPROVED | FAIL | BLOCKED — replace with exactly one value}
**Source:** 04-analyze gap findings + team output

## External / Cross-model Review
**Reviewer:** [tool] via host
**Inputs:** plan_docs/00·01·02·03·04 + component plan docs + implementation files
**Status:** COMPLETED / BLOCKED

### External Findings
[reviewer output verbatim — no summarization]

### Review Loop Iterations
| Iteration | External Result | Triage | Accepted Rework | Rejected/Deferred | 03/04 Updated | Status |
|:---:|:---|:---|:---|:---|:---:|:---|
| 1 | | | | | | |

### Reviewer Interpretation
- accepted / disputed (already-intended decisions) / deferred

### Acceptance Gate
| Acceptance ID | 04 Status | Reviewer Finding | Decision |
|---|---|---|---|

Required acceptance items with `FAIL` block `APPROVED`. `NOT TESTED` also blocks unless
an exact active L3 waiver is recorded; a waived row remains `NOT TESTED` and the review
must preserve its waiver ID, reason, scope, confirmer, and remaining evidence. Use `N/A`
only when the item was explicitly out of scope, deferred, or user-approved.

### Needs User Approval
| Item | Reason | Owner | Status |
|---|---|---|---|

### Final Status
{APPROVED | FAIL | BLOCKED — replace with exactly one value}

> If not APPROVED after the final L3 iteration → `Final Status: BLOCKED`,
> do not write Phase 06.
> The report gate accepts exactly one anchored `Final Status: APPROVED` line outside fenced code blocks.
> Placeholder options, duplicate declarations, and free-text occurrences do not approve a cycle.

## 1. Code Quality (SOLID, naming, dead code)
## 2. Domain/Architecture (per profile.components)
## 3. Security (auth, input validation, info leakage)
## 4. Performance & Concurrency
## 5. Convention Compliance (profile.conventions)

## 6. Review Scorecard
| Category | Score (1–5) | Key Issues |
|:---|:---:|:---|

## 7. Action Items
| Priority | Issue | Recommendation |
|:---:|:---|:---|
```

---

## [Phase 06: Final Report Template]

> A `ko` cycle translates these headings and table headers — see **Template language** above.

```markdown
# [Report] {Feature Name}

Cycle-Stem: `{phase-document-basename}`
Done-Criteria-Revision: 1
Loop-Run: {run_id}
Source-05: {root-relative path of the APPROVED Phase 05 doc}

## 1. Completion Summary

## 2. Value Delivered
| Problem | Solution | Effect | Core Value |
|:---|:---|:---|:---|

## 3. Lessons Learned & Future Tasks

## 4. Knowledge Capture (optional)
| Note | Action | Reason |
|:---|:---|:---|
```

`Loop-Run` copies the `run_id` from the APPROVED Phase 05 doc so the report declares
which review cycle it closes. The Stop-time retro gate reads this line to verify
`sage retro --check` ran for that run; omit it and the gate cannot bind the report
(warned under advisory, blocked under enforce).

Every phase document must declare exactly one `Cycle-Stem` outside fenced code blocks and equal to its markdown
filename without `.md`. Phase selection is exact by this stem; branch-number scans
and recent-file fallback are not cycle identity. A missing, conflicting, or ambiguous
stem blocks governed work.

### Declaring the current cycle on a long-lived branch

When you edit a phase document, the stem comes from its path, which cannot be
forged. Every other edit has no such anchor, so the gate infers the stem from the
last segment of the git branch name. That is correct when each cycle gets its own
branch and permanently wrong when many cycles share one long-lived branch: the
inferred stem never matches any phase document, so every governed edit is blocked
as "phase documents missing" even though all of them exist.

Declare the cycle instead of renaming the branch:

```bash
sage cycle set <phase-document-basename>   # e.g. sage_project_profile_refresh
sage cycle set <new-stem> --create --risk L2  # create Phase 00 when none exists
sage cycle show                            # what is declared, and where it was read
sage cycle clear                           # release it when the cycle ends
```

This does not weaken the gate — it supplies the cycle identity the gate could not
infer, and every phase, review, and acceptance requirement is still enforced
against that stem. Because a declaration can point at an already-completed cycle
whose evidence is fully in place, each session's first use is recorded in
`.sage/override.jsonl` as a `cycle_stem_declared` entry; if that log cannot be
written, the gate blocks rather than passing unaudited.

The declaration is a single file, `<project-root>/.sage/cycle.json`, kept out of
version control by the `/.sage/*` block that `sage install` writes into
`.gitignore`. `sage cycle set` prints the absolute root and file it wrote, checks
that git actually ignores it, and warns when the compiled profile the gate reads
is absent — check that output rather than assuming the declaration landed where
the gate looks.

Do not write the file with an editor; the write guard blocks that, because a
hand-planted declaration can point at a completed cycle and switch the gate off.

Switching to `set B` changes only the pointer. It does not edit cycle A's phase
documents, evidence, or audits, and `set A` restores A's evaluation. `--create`
creates only Phase 00; complete required Phases 01-03 before source edits. For an
urgent waivable phase gap, use a short grant such as
`sage override --reason "hotfix" --ttl 1h`. Phase 00 risk declaration and
reconciliation blocks are non-waivable.

**Release it when the cycle ends.** Unlike a shell export, the declaration
survives every session, and a stale one binds new work to a finished cycle. The
gate blocks that case and names `sage cycle clear` in the block message.

Lifecycle ownership belongs to the sub-skills, not the thin `sage-cycle` umbrella.
`sage-plan` runs `set` after validating the 00–02 identity. `sage-team` runs `show`
and reconciles `set` after resume context and cycle identity validation, then runs
`clear` only after write-back, retro, snapshots, and closing gates. It retains the
declaration on red verification, `BLOCKED`, or `FAIL`. The umbrella delegates these
steps and reports their outcome; it does not duplicate them.

`SAGE_CYCLE_STEM=<stem>` still works and takes precedence over the file. Prefer it
only where a single process needs the stem, such as CI; in an interactive shell the
export outlives the cycle with none of the file's visibility. `sage cycle clear`
removes only the file declaration; use `sage cycle show` and
`unset SAGE_CYCLE_STEM` when the environment remains effective.

Write Phase 06 only after all 00–05 updates have completed. A single change that
co-modifies 06 with any other phase is blocked because the pre-write evidence snapshot
cannot prove the resulting state.

---

## Related Rules
- `AGENT_GUIDE.md` — Risk & Workflow Gate
- `docs/agent/review-protocol.md` — reviewer resolution
- `profile.pdca` — phase set, per-level obligation, report/approve gate
