# AGENT_GUIDE.md

## Prerequisites

Before starting any task, check for local configuration:

**If `.local/local_agent_guide.md` exists:**
1. Read it immediately.
2. It may activate additional context sources (e.g., Obsidian vault knowledge base).
3. Use those sources to enrich your response before proceeding with the standard workflow.

**If it does not exist:**
Proceed with the standard workflow below.

---

## 1. Project Overview

ChatForYou v2 is a WebRTC-based video conferencing and gaming platform with three main components:
- `nodejs-frontend/`: Node.js frontend server (port 3000)
- `springboot-backend/`: Spring Boot backend API (port 8080)
- `chatforyou-desktop/`: Electron desktop application

The platform supports N:M video conferencing, real-time chat, file sharing, CatchMind game, text overlay, subtitles, and screen sharing.

## 2. Development Commands

### Frontend (Node.js)
```bash
cd nodejs-frontend
npm run local      # Build for local environment
npm run prod       # Build for production environment
npm run start      # Start server on port 3000
npm run sass       # Watch SCSS compilation
npm run dev        # Run with SCSS watching
```

### Backend (Spring Boot)
```bash
cd springboot-backend
./gradlew clean build
./gradlew test
java -Dkms.url=ws://[KMS_IP]:[PORT]/kurento -jar build/libs/*.jar
```

### Desktop (Electron)
```bash
cd chatforyou-desktop
npm run sync
npm run start
npm run dev
npm run build:win
npm run build:mac
npm run build:linux
npm run scss:build
npm run scss:watch
```

## 3. Architecture & Key Technologies

### WebRTC Communication
- Kurento Media Server handles N:M video conferencing using `kurento-client`
- WebSocket signaling uses the `/signal` endpoint with Spring WebSocket/STOMP
- DataChannel is used for real-time chat and file transfer
- coturn is used for ICE/TURN-based NAT traversal

### Backend Architecture
- Controllers in `controller/` expose room, signaling, and file APIs
- Services in `service/` manage room lifecycle, participants, and business logic
- Redis supports room persistence, counters, and search
- JPA entities persist user and daily information

### Frontend Architecture
- jQuery-based client with Bootstrap 5
- SCSS source under `src/static/scss/`
- `kurento-service.js` manages WebRTC peer connections
- Realtime features include DataChannel chat, CatchMind canvas, subtitles, and screen sharing

### Desktop Architecture
- Electron assets are synced from `nodejs-frontend`
- Multi-platform packaging uses `electron-builder`
- Web config is converted for the Electron runtime

## 4. Key Configuration Files

### Frontend
- `nodejs-frontend/config/config.local.js`
- `nodejs-frontend/config/config.prod.js`
- `chatforyou-desktop/src/config/config.js`

### Backend
- `springboot-backend/src/main/resources/application.properties`
- Runtime environment variables for KMS, Redis, and database access

## 5. Change Risk Level

All change requests must be classified into one of the four levels below. The required phase range is determined by the risk level — not every change requires the full cycle.

| Level | Category | Required Phases | Notes |
|:---:|:---|:---:|:---|
| **L0** | Documentation / Text Only | None | Summary + changed file list only. No code changes. |
| **L1** | UI / Non-critical Logic | 00–03 | Phase 04 optional if behavior changes. |
| **L2** | Backend State / Redis / Auth | 00–05 | Build and test evidence required. |
| **L3** | WebRTC / WebSocket / Signaling / Desktop Sync | 00–06 | Minimum 2 design reviews required. Implementation is blocked until both reviews are documented. |

## 6. Workflow (PDCA Detailed Lifecycle)

All agents in the `chatforyou-dev-team` must strictly adhere to the following 6-phase PDCA cycle. Each phase's results must be documented in the specified paths.

### 6.1 Phase-by-Phase Execution Guide

| Phase | Name | Key Tasks | Deliverables (Documents) | Check & Report |
|:---:|:---:|:---|:---|:---|
| **00** | **Base Plan** | Strategy formulation, impact analysis (Backend/Frontend/Desktop), tech risk identification | `plan_docs/00-base_plan/.../[feature]_plan.md` | Business value & risk balance report |
| **01** | **Plan** | Detailed requirements, data model (Entity/DTO) design, API specifications | `plan_docs/01-plan/[feature].md` | Req. consistency & data design validation |
| **02** | **Design** | Class/Interface design, sequence diagrams, error code definitions | `plan_docs/02-design/[feature].md` | Architecture & convention compliance |
| **03** | **Do** | Implementation, unit testing, updating guide checklists | `plan_docs/03-implementation/[feature].md` | Code review & build/test results |
| **04** | **Analyze** | Check existing 04 file, Design vs. Implementation gap analysis, **External Consultant Review (on request)** | `plan_docs/04-analyze/[feature].md` | Gap analysis & expert review report (include Agent attribution) |
| **05** | **Expert Review** | Holistic review from a 5-year full-stack expert (Backend/Frontend/Infra) perspective: code quality, design patterns, Spring Boot & Node.js best practices, security, performance, infra/deploy concerns, and convention compliance | `plan_docs/05-expert-review/[feature].md` | Scored review report with prioritized action items |
| **06** | **Report** | Final completion report, Lessons Learned, future tasks | `plan_docs/06-report/[feature].md` | Final report & Executive Summary |

### 6.1.1 Phase 00 — Vault Scan Procedure

Before writing the base plan, scan the Obsidian vault for all prior knowledge
related to the feature when `.local/local_agent_guide.md` and the available
Obsidian vault MCP tools are available. This prevents repeating past decisions
and surfaces known risks before planning begins. If the local guide or vault MCP
tools are unavailable, record the vault scan as N/A with the reason and proceed.

1. Read `wiki/index.md` to identify relevant notes
2. Search across all note types for the feature topic:
   - `[BRAINSTORM]` — prior ideation and direction
   - `[SPEC]` — prior design decisions (avoid duplication)
   - `[TECH]` — technology choices and rationale
   - `[BUG]` — known issues related to the feature area
   - `[POSTMORTEM]` — past incidents to avoid repeating
3. Read relevant notes found (1–5 files maximum)
4. Summarize findings under `## 0. Prior Knowledge` in the base plan
5. If no relevant notes exist, omit the section and proceed

### 6.2 External Consultant Protocol

When a user requests **"External Expert Review"** or **"Consultant Validation"**, the agent must execute the following process:
1. **Context Initialization**: Check if `plan_docs/04-analyze/[feature].md` already exists. If it does, **read it first** and perform a cumulative analysis building upon previous findings.
2. **Role Transition**: Shift perspective from a developer to an External Consultant specializing in Object-Oriented Design, Security, and Performance.
3. **Analysis & Attribution**: Critically analyze the work and **clearly identify which agent is providing the review** (e.g., Reviewer: Gemini, Reviewer: Claude Code).
4. **Documentation**: All expert opinions must be recorded in the `## External Expert Review` section of the `plan_docs/04-analyze/[feature].md` file.
5. **Action Plan**: Propose improvement plans for identified issues and obtain user approval.

### 6.3 Document Templates

#### [Phase 00: Base Plan Template]
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

#### [Phase 01: Plan Template]
```markdown
# [Plan] {Feature Name}
## 1. User Stories & Requirements
## 2. Data Schema (Entities, DTOs)
## 3. API Specifications
```

#### [Phase 02: Design Template]
```markdown
# [Design] {Feature Name}
## 1. Architecture & Interface Design
## 2. Sequence Diagrams
## 3. Error Codes & Exception Strategy
```

#### [Phase 03: Implementation Guide Template]
```markdown
# [Implementation] {Feature Name}
## 1. File Ownership (Modified Files)
## 2. Implementation Checklists
- [ ] Development feature list
- [ ] Test scenarios and validation method
- [ ] Code conventions
## 3. Build & Test Results
```

#### [Phase 04: Gap Analysis Template]
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

#### [Phase 05: Expert Review Template]
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

#### [Phase 06: Final Report Template]
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

### 6.4 Phase 04: Bug Patterns & Risk Assessment Guide

During the Phase 04 (Analyze) gap analysis, agents must proactively search for the following bug patterns. All identified issues must be listed in the analysis document with their respective severity.

#### Severity Matrix (Priority Levels)
| Level | Severity | Description | Action Required |
|:---:|:---:|:---|:---|
| **P0** | **Critical** | System crash, data loss, security breach, core WebRTC/Signaling failure | Immediate fix before proceeding to Phase 05 (Expert Review) |
| **P1** | **Major** | Performance bottleneck, significant UX degradation, inconsistent state in Redis/DB | Must be resolved or explicitly reported as a remaining risk |
| **P2** | **Minor** | Logging inconsistency, minor styling issues, non-critical documentation gaps | Can be scheduled for future improvement |

#### Common High-Risk Bug Patterns (Domain Specific)
1. **WebRTC & Signaling (High Risk)** — See also §8.1 Rule 8: minimum 2 critical design reviews required before any change
   - **SDP/ICE Mismatch**: Discrepancies between Kurento Media Server and Client-side signaling.
   - **Resource Leak**: Failure to release `WebRtcEndpoint` or `MediaPipeline` after session termination.
   - **Race Conditions**: Multiple signaling messages (e.g., candidate arrival before offer) arriving out of order.

2. **Concurrency & Threading**
   - **MDC Data Loss**: TraceID not being propagated to `@Async` threads or custom thread pools.
   - **Thread-Unsafe Collections**: Using `HashMap` instead of `ConcurrentHashMap` in multi-threaded WebSocket handlers.

3. **Security & Integrity**
   - **Token Bypass**: Missing `ChatForYouException` for unauthorized room access attempts.
   - **Information Leakage**: Stack traces or internal system paths exposed in 500 error responses.
   - **Validation Gaps**: Insufficient input sanitization leading to potential XSS or injection in chat/game messages.

4. **Performance & Observability**
   - **Unbounded Logging**: Excessive logging in high-frequency loops (e.g., game tick, RTC stats).
   - **Missing Metrics**: Failure to record critical events in Prometheus/Grafana.

## 7. Component Conventions
(이하 기존 내용 유지)

- Backend(Spring Boot)
  - Reference: `docs/springboot_backend.md`
  - Test conventions must follow the active agent's backend test convention guide
- Frontend(Node.js/Electron Web Layer)
  - Reference: `docs/nodejs_frontend.md`
- Desktop(Electron)
  - Reference: `docs/chatforyou_desktop.md`
  - Preserve the web code sharing principle
  - `chatforyou-desktop/src` is synced from `nodejs-frontend` and must not be edited directly
  - Do not weaken security boundaries such as QR login restrictions and Main/Preload separation
- Git Commit & Push
  - Reference: `docs/git_commit_convention.md`

Agent-specific startup order, runtime paths, and tool ecosystems are defined only in the wrapper documents such as `CODEX.md` and `CLAUDE.md`.

## 8. MANDATORY Rules

### 8.1 Common Rules
1. Check whether an appropriate agent or skill should be used before starting.
2. Do not commit or push. The user handles all Git writes.
   - Commit message recommendations must follow `docs/git_commit_convention.md`
   - Split recommendations by area (frontend / backend / infra / agent / docs / security)
3. Keep comments and JavaDoc minimal and focused on WHY, not narration.
   - Prefer short plain-text JavaDoc over HTML tags such as `<p>`, `<ol>`, and `<li>`
   - For methods, explain what the method does in one line and add why only when the ordering or intent is not obvious
   - Do not restate the code line-by-line
4. Follow the Output Contract for design and analysis responses.
5. Perform a second self-review before final delivery.
6. Confirm the base plan file under `plan_docs/00-base_plan/YYYY/MM/` before implementation work.
7. Before implementation, re-read `AGENT_GUIDE.md` and the relevant `docs/*.md` files for every component in scope.
8. **[WebSocket/WebRTC MANDATORY]** Any modification to WebSocket or WebRTC related code (signaling, `KurentoHandler`, `KurentoMessageSender`, `KurentoRoomManager`, `KurentoUserSession`, `kurento-service.js`, DataChannel, ICE/SDP flows, etc.) requires **at minimum 2 rounds of critical design analysis and review** before the change is applied. Each round must independently evaluate correctness, race conditions, resource lifecycle, and security. Implementation is blocked until both rounds are complete and documented.

### 8.2 Execution Rules
1. Always evaluate impact across Backend, Frontend, and Desktop.
2. If a component mentioned by the design is excluded from scope, explain why and get user confirmation.
3. Do not change code during design-only or analysis-only work unless the user explicitly asks for implementation.
4. After implementation, run the relevant module build or tests to verify integrity.
5. **[Post-Phase 03 — MANDATORY per task]** After every task that reaches or passes Phase 03, run the following checks for each modified component before the task is considered done:
   - **Node.js (Frontend)**: Run syntax check against changed files (e.g., `node --check <file>` or the project lint script).
   - **Spring Boot (Backend)**: Run build test (`./gradlew clean build`).
   A task is **not complete** until all applicable checks pass. If a check fails, fix the issue and re-run before reporting completion.
6. Before implementation is treated as complete, run `backend-convention-checker` or `frontend-convention-checker` for every changed component in scope.
7. Update `springboot-backend/plan_docs/*.md` and `nodejs-frontend/plan_docs/*.md` TODO checkboxes only after the corresponding validation actually ran.
8. Remove temporary exception handling, debug traces, and placeholder implementation notes before final delivery, or report them explicitly as remaining risks.
9. Do not edit `chatforyou-desktop/src` directly. Apply shared web changes in `nodejs-frontend` first, then use the sync workflow required by `docs/chatforyou_desktop.md`.
10. Use `chatforyou_v2` as the PR base branch.

### 8.3 Coding Behavior Principles

These principles apply as default behavior where §8.1 and §8.2 do not specify otherwise.
Project-specific mandatory rules (§8.1, §8.2) always take precedence when they conflict.

1. **Think Before Coding**: State assumptions explicitly before implementing. If multiple interpretations exist, surface them — don't pick silently. If something is unclear, stop and ask before writing any code.
2. **Simplicity First**: Write the minimum code that solves the problem. No features beyond what was asked, no abstractions for single-use code, no configurability that wasn't requested. If 50 lines suffice, don't write 200.
3. **Surgical Changes**: Touch only what the request requires. Don't improve adjacent code, comments, or formatting unless asked. Match existing style. If you notice unrelated dead code, mention it — don't delete it. Remove only imports/variables/functions that *your* changes made unused.
4. **Goal-Driven Execution**: For multi-step tasks, state a brief plan with a verifiable check per step before starting (e.g., `1. [Step] → verify: [check]`). Loop until each check passes.

## 9. RECOMMENDED Rules

1. Compare design and analysis output against practical industry references when it helps clarify trade-offs.
2. Review the relevant component convention documents before implementation.
3. Use `.test-temp/` for temporary frontend test artifacts and clean them up afterward.
4. Report changes grouped by component impact.

## 10. Output Contract

### For design or analysis work
1. Related file analysis
2. Design validity assessment
3. Reflected changes, or "no changes"
4. Practical reference comparison
5. Improvement proposals and trade-offs

### For implementation work
1. Change summary by component
2. Modified files
3. Validation results, including executed builds/tests
4. Remaining risks or follow-up items

## 11. Definition of Done

1. The workflow was followed in order.
2. No mandatory rule was violated.
3. Backend, Frontend, and Desktop impact were reviewed.
4. Implementation work includes build or test evidence.
5. Post-Phase 03 per-task validation passed: Node.js syntax check and/or Spring Boot build test executed for each modified component.
6. Convention validation was executed for each changed component.
7. The relevant implementation-guide checklist is updated to match the current validation state.
8. Unfinished cleanup or placeholder implementation notes are either resolved or reported as a remaining risk.
9. The result was reported using the Output Contract.
10. No commit or push was performed.
11. Vault knowledge capture check was completed or explicitly marked N/A.
    After any task that reaches Phase 03 or includes code changes, check whether
    vault updates are required. Trigger conditions and procedure are defined in
    `.local/local_agent_guide.md` when available.

## 12. Appendix

Quick checklist:
- Before start: §8.3 — state assumptions, confirm scope is minimal, define success criteria
- Before start: check `plan_docs/00-base_plan/YYYY/MM/[feature]_plan.md`
- Before implementation: re-read `AGENT_GUIDE.md` and the relevant `docs/*.md`
- During work: review component impact and conventions
- Before finish: run build/tests, **run Post-Phase 03 checks** (Node.js syntax check + Spring Boot `./gradlew clean build`), run convention validation, update implementation-guide checklists, perform vault knowledge capture check (§11.11), self-review, and verify the Output Contract

Common rules must be maintained in `AGENT_GUIDE.md`.
`CODEX.md`, `CLAUDE.md`, `GEMINI.md` and similar wrappers must stay thin and define only agent-specific startup differences.
