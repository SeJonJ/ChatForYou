# AGENT_GUIDE.md

## 0. Purpose

This document is the **single source of truth** for all AI coding agents working on ChatForYou v2.
It defines only shared policy: rule precedence, risk routing, mandatory safety boundaries, required reference documents, and Definition of Done.

Detailed procedures live in `docs/agent/*` and component conventions live in `docs/*.md`.
Agent-specific wrappers such as `CODEX.md`, `CLAUDE.md`, and `GEMINI.md` must stay thin.

Project overview, architecture map, and common commands are maintained in `docs/README.md`.

## 1. Rule Precedence

User instructions are respected within the non-negotiable safety boundaries below.

### 1.1 Non-Negotiable Safety Boundaries

- Do not run `git commit` or `git push`; the user owns Git writes.
- Do not edit `chatforyou-desktop/src` directly; change shared web assets in `nodejs-frontend` and sync through `docs/chatforyou_desktop.md`.
- Do not run destructive commands without explicit user request; apply `docs/agent/command-safety.md`.
- WebRTC / WebSocket / Signaling / Kurento changes require the L3 two-round review in `docs/agent/webrtc-review-protocol.md` before implementation.
- Backend / Frontend / Desktop impact analysis is mandatory for every implementation or design change.

### 1.2 Conflict Priority

When documents conflict within the safety boundaries:
1. The user's explicit current-task instruction
2. This `AGENT_GUIDE.md`
3. `.local/local_agent_guide.md`
4. Component convention docs under `docs/*.md`
5. Agent-specific wrappers such as `CODEX.md`, `CLAUDE.md`, and `GEMINI.md`

## 2. Startup Checklist

Before working:
1. Read this `AGENT_GUIDE.md`.
2. Read `.local/local_agent_guide.md` if it exists.
3. Read the relevant plan document when the task depends on prior design or implementation context.
   - Standard plan paths: `plan_docs/N월_[기능]_plan.md` or `plan_docs/00-base_plan/YYYY/MM/[feature]_plan.md`
   - Phase documents follow `docs/agent/pdca-templates.md`.
4. Read `docs/README.md` when project context is unfamiliar or the task spans multiple components.
5. Select and read the required `docs/agent/*` documents from Required Agent Reference Documents.
6. Select and read the component convention docs from Component Reference Documents.

If `.local/local_agent_guide.md` activates the Obsidian vault, use it when the task has feature, bug, design, architecture, behavior, or historical-decision impact.

## 3. Risk & Workflow Gate

Every change must be classified before implementation. Compound changes use the highest applicable risk level.

| Level | Category | Required Workflow | Notes |
|:---:|:---|:---|:---|
| **L0** | Documentation / text only | No PDCA phase required | Summary, changed files, and skipped validation reason are enough. |
| **L1** | UI / non-critical logic | Phase 00-03, or lightweight implementation note | No backend state, Redis, Auth, WebRTC, WebSocket, Desktop runtime, or security-sensitive behavior. |
| **L2** | Backend state / Redis / Auth / persistence | Phase 00-05 | Plan file plus build/test evidence required. |
| **L3** | WebRTC / WebSocket / Signaling / Kurento / Desktop sync or runtime | Phase 00-06 | Two documented design-review rounds required before implementation. |

Escalate to the higher level when uncertain.
Detailed examples and decision rules: `docs/agent/risk-classification.md`.

### 3.0 Independent PDCA Cycle Rule (MANDATORY)

When the user explicitly requests an L3 (or equivalent) flow or a new PDCA development flow, the agent MUST start a new, independent 00-base_plan — even if the bug or feature is technically adjacent to a recently completed cycle.

**"Technically adjacent" is never a reason to reuse or extend a prior cycle.**

- Adjacent = same file, same service, same module → does NOT justify cycle reuse
- A new PDCA cycle requires: new `plan_docs/00-base_plan/YYYY/MM/[feature]_plan.md`, new phase documents 01–06, and independent log analysis

Violation pattern to avoid: skipping 00 and reusing a prior cycle's documents because the code is in the same area. This pattern caused the 2026-05 session expiry + call drop incident PDCA to be processed incorrectly.

The Phase range listed under Required Workflow is **mandatory writing**.
An empty `plan_docs/{phase}/` directory is not a convention — interpret it as a prior task's omission.
Phase role boundaries (e.g., 00 vs 01) and component-level plan_docs responsibility are defined in
`docs/agent/pdca-templates.md` under Phase Separation Rules and Component-level Plan Docs.

### 3.1 Pre-Implementation Compliance Gate

Before writing implementation code, explicitly declare:
- Risk Level: L0 / L1 / L2 / L3
- Compound rule applied: yes / no
- Applicable phase range
- Plan file path for L2 or higher
- L3 review status when WebRTC / WebSocket / Signaling / Kurento / Desktop runtime is involved
- Backend / Frontend / Desktop impact
- Required reference docs read

For L1 changes that qualify for lightweight mode, confirm all L1 conditions in §3 hold before applying it. The implementation note must include scope, impact analysis, modified files, validation result, and remaining risks.

## 4. Mandatory Rules

### 4.1 Common Rules

- Check whether a project skill or custom agent should be used before starting.
- Follow the default coding behavior principles in `docs/agent/coding-principles.md`.
- Keep comments and JavaDoc minimal and focused on WHY, not line-by-line narration.
- Do not change code during design-only or analysis-only work unless the user explicitly asks for implementation.
- When the task is explicitly scoped to one component, do not modify other components without notifying the user and receiving approval.
- Any task that includes code changes, regardless of risk level, must check the vault knowledge capture requirement in `.local/local_agent_guide.md`.
- Re-read relevant component docs before implementation.
- Run relevant build, syntax, test, and convention checks after implementation.
- Update plan or implementation-guide checkboxes only after the corresponding validation actually ran.
- Remove temporary exception handling, debug traces, and placeholder notes before final delivery, or report them as remaining risks.
- Use `chatforyou_v2` as the PR base branch when discussing PRs.

### 4.2 WebRTC / WebSocket Changes

Any modification to WebRTC, WebSocket, signaling, Kurento, ICE/SDP, DataChannel, room lifecycle, or related client/server flows is L3.

Implementation is blocked until both review rounds in `docs/agent/webrtc-review-protocol.md` are documented:
- Round 1: flow correctness
- Round 2: failure and lifecycle behavior

P0 issues block implementation. Remaining P1 issues require a fix or explicit user acceptance.

### 4.3 Desktop Sync

`chatforyou-desktop/src` is generated from shared frontend assets and must not be edited directly.
Apply web changes in `nodejs-frontend`, then follow `docs/chatforyou_desktop.md` and `docs/nodejs_frontend.md` for sync and SCSS validation.

## 5. Required Agent Reference Documents

Files under `docs/agent/` are conditionally mandatory, not optional reference links.

| Condition | Required Document |
|---|---|
| Before implementation work or multi-step changes | `docs/agent/coding-principles.md` |
| Risk level is unclear, compound, or disputed | `docs/agent/risk-classification.md` |
| WebRTC / WebSocket / Signaling / Kurento changes | `docs/agent/webrtc-review-protocol.md` |
| Before git / kubectl / npm / docker / destructive / server commands | `docs/agent/command-safety.md` |
| Writing or modifying wrapper docs | `docs/agent/wrapper-contract.md` |
| Phase documents, vault scan procedure, or external consultant protocol needed | `docs/agent/pdca-templates.md` |
| Phase 04 gap analysis | `docs/agent/phase04-bug-patterns.md` |
| Final result report | `docs/agent/output-contract.md` |

## 6. Component Reference Documents

| Scope | Required Document |
|---|---|
| `springboot-backend/` changes | `docs/springboot_backend.md` |
| `nodejs-frontend/` web changes | `docs/nodejs_frontend.md` |
| Desktop sync, Electron runtime, packaging, preload/main boundaries | `docs/chatforyou_desktop.md` |
| Commit message recommendation | `docs/git_commit_convention.md` |
| Multiple components | Read every applicable document above |

## 7. Wrapper Contract

Wrappers may define only:
- Startup read order
- Available tools
- Runtime path differences
- Agent-specific tool routing
- Command execution differences
- Agent role descriptions

Wrappers must not redefine risk levels, PDCA phases, Definition of Done, WebRTC/WebSocket review requirements, Git policy, Desktop sync policy, command safety, or test requirements.

Full contract: `docs/agent/wrapper-contract.md`.

## 8. Output Contract

Use `docs/agent/output-contract.md` for final reporting.

All implementation or design reports must include:
- Task summary
- Risk level and reason
- Scope and assumptions
- Backend / Frontend / Desktop impact
- Completed and skipped phases, with reasons
- Modified files
- Validation commands and results
- Remaining risks
- Next action

For L0 documentation-only work, use the L0 report and state why build/test validation was skipped.

## 9. Definition of Done

- Applicable startup, risk, and reference-document gates were followed.
- No non-negotiable safety boundary was violated.
- Backend / Frontend / Desktop impact was reviewed.
- Required plan or phase documents were created or updated when the risk level requires them.
- Implementation work has relevant build, syntax, test, and convention evidence.
- WebRTC / WebSocket / Signaling / Kurento L3 work has two documented review rounds.
- Desktop-impacting web work followed the sync policy and avoided direct `chatforyou-desktop/src` edits.
- Temporary debug code, placeholders, and unfinished cleanup are removed or reported.
- Vault knowledge capture was completed or marked N/A when `.local/local_agent_guide.md` requires it.
- Final response follows `docs/agent/output-contract.md`.
- No commit or push was performed.
