# AGENT_GUIDE.md

This is the runtime-neutral single source of truth (SSOT) for common rules,
workflow, risk routing, safety boundaries, and Definition of Done. Both host
runtimes ({wrapper} = CLAUDE.md | CODEX.md) are thin overrides on top of this.

Shared project policy (paths, risk triggers, conventions, team) lives in
`sage/project-profile.yaml`. Machine capabilities and private paths live in the
Git-ignored `sage/project-profile.local.yaml`. This guide stays neutral.

## Mandatory read (session start)

1. `AGENT_GUIDE.md` (this file)
2. `sage/project-profile.yaml` — project values
3. `sage/project-profile.local.yaml` — machine values, when present
4. Relevant plan doc under `{paths.plan_docs}`
5. Relevant convention docs declared in `profile.conventions`

Read item 3 **before printing anything a person reads**: its `interface.language`
(`ko`|`en`, absent means `ko`) decides this session's conversation language, and a first
line already sent in the wrong language cannot be taken back. The language a Phase 00–06
document is written in is a separate per-cycle decision recorded as `Document-Language:`.
Both, plus what is never translated, are in `docs/agent/language-policy.md`.

## Project Bootstrap (conversational authoring)

Profile values, specs, and plan docs are **authored by the agent through
conversation, not hand-written by the user** — the user supplies intent and
approves; `sage generate` / `sage validate` then register and verify
deterministically (some assets, e.g. agent/skill renders, still need an
interpretive runtime render step). The profile schema is a guardrail (strict on
top-level + selected sections), not a total type-checker. When a project is first
set up, or a new component/asset is introduced, follow
`docs/agent/bootstrap-authoring.md`: interview → fill profile values → handoff
(`generate` + `validate`) → phase-first plan docs before any code. Never add
schema keys, never edit generated artifacts, never bypass a `validate` FAIL.

The conversational entry points are **`/sage-init`** for the first shared+local
bootstrap and **`/sage-init-local`** for a teammate's local-only setup. If the
shared profile is already bootstrapped, `/sage-init` is blocked. They and the other
**CORE framework bootstrap assets** — the `sage-cycle` / `sage-plan` / `sage-team` / `sage-review` / `sage-asset` /
`sage-profile-modify` / `sage-asset-override` / `sage-feedback` skills and the six CORE roster agent renders (`leader`, `implementer-a`,
`implementer-b`, `qa`, `reviewer`, `convention-checker`) — are hand-shipped by `sage install` like
this guide and `docs/agent/*`. They are NOT manifest-tracked: the
manifest/claims/`validate` loop is reserved for project-authored assets created
via `generate`/`extract`. **Do not edit these CORE renders directly** — the
write-guard blocks it and `sage install --force` would overwrite the edit anyway.
Customize eligible CORE assets — non-gate workers, or gate-bearing assets whose gates are floored by a registered independent oracle (FB23) — per-project via an **overlay** authored with
`/sage-asset-override`, stored at `sage/asset_overrides/{agents,skills}/<id>.md` (hand-authored, install never
ships it so `--force` preserves it). SAGE **materializes** an eligible overlay directly into its CORE render as a
managed block — do not read external overlay files by hand or edit renders directly. `sage validate` gates
materialization (drift/tamper); `--strict` and materialization preflight reject gate-relaxation hits. Framework
documents and other gate-bearing assets are blocked until an executable independent oracle is registered. The CORE skills ship
reference specs under `docs/sage_harness/skills/` (the two init skills have none), but those
specs are not manifest-registered. Until the profile is bootstrapped
(`project.name` set + `risk`/`components` configured), `sage generate` is BLOCKED
and `sage validate` WARNs — by design, so an empty profile cannot silently
disable the governance gate.

Runtime discovery differs by host, and `sage install` picks one host (claude OR
codex), so each install deploys only that host's copies. On a **claude** host,
Claude auto-discovers repo-scoped skills and agents under `.claude/` (CORE skills →
`.claude/skills/`, CORE agents → `.claude/agents/`). On a **codex** host, normal
installation requires an explicit `--skill-scope global|project-local`: global owns
`$CODEX_HOME/skills/`, while project-local owns repo `.codex/skills/`. The selected
scope/version is recorded in the manifest receipt; `sage doctor` and `sage validate`
diagnose duplicate or stale global, `.codex/skills`, and legacy `.agents/skills` copies
without assuming undocumented host precedence. Since Codex
has no native subagent auto-discovery either, the CORE roster agent renders install
to repo `.codex/agents/<id>.md` (the SAGE-canonical asset path), which the Codex AI
references as role definitions via the `AGENTS.md` router rather than native
invocation. Codex users also follow `docs/agent/bootstrap-authoring.md` (see `CODEX.md`).
Project-local skill files can travel with a committed repository, but they do not install
the `sage`/`sage-hook` CLI runtime; teammates install that separately. See
`docs/agent/sage-onboarding.md` generated for the selected scope.

A project may install both discovery surfaces sequentially. This is a manual
double-host model: `runtime.installed_hosts` records desired surfaces and exactly
one `runtime.active_host` owns the current cycle execution. SAGE does not run hosts
concurrently or switch hosts/phases automatically. A handoff resumes from completed,
exact-Cycle-Stem phase documents after the user changes the active host. Set
`active_host: auto` to have the running host read from the environment instead of pinned
in the shared profile. Phase 05 cross-review always excludes the host that is actually
executing, so a stale pin cannot make a host review its own work — it is reported, not
enforced. Host-specific component models live in `components[].runtime_models`;
`cross_model.reviewer` names both the peer `host` and the `model` chosen for it, because a
model id belongs to one runtime — if the resolved peer differs, the model is skipped and the
peer CLI default is used. Use `sage models` and `sage doctor`
to distinguish cache-confirmed candidates from account-unverified aliases.

## Risk & Workflow Gate (PDCA)

Every change is classified before implementation. Compound changes use the
highest applicable level. Levels are classified from `profile.risk` (path globs +
content keywords), not from hardcoded domain knowledge — see
`docs/agent/risk-classification.md`.

### PDCA phases

Work proceeds through numbered phase documents under `{paths.plan_docs}`. The
phase set and per-level obligation are defined in `profile.pdca`; the standard
set is:

| Phase | Name | Nature |
|:---:|:---|:---|
| 00 | Base Plan | CONTEXT — why / what / impact / prior knowledge / risk |
| 01 | Plan | CONTENT — requirements, data model, API spec |
| 02 | Design | architecture, sequence, error codes |
| 03 | Implementation | file ownership, checklist, build/test evidence |
| 04 | Analyze | design↔implementation gap (no verdict here) |
| 05 | Expert Review | independent synthesis + final APPROVED/FAIL/BLOCKED |
| 06 | Report | completion report — only after 05 = APPROVED |

Phase definitions, separation rules (00 vs 01, 02 vs 03, 04 vs 05), templates,
and component-level plan_docs are in `docs/agent/pdca-templates.md`.

### Cross-session context

When `profile.context_management.compaction.enabled` is true, CORE cycle skills create
an integrity-bound packet with `sage context snapshot` after each completed phase
boundary. A resumed session uses `sage context restore` and reads the generated briefing
before continuing. This restores verified repository context only, never hidden model
conversation state; see `docs/agent/context-management.md`.

### Risk → mandatory phase range

| Level | Category (from `profile.risk`) | Required phases | Gate |
|:---:|:---|:---|:---|
| **L0** | docs / text only | none | summary + skipped-validation reason |
| **L1** | low blast radius (UI/markup) | 00–03 (lightweight note allowed) | advisory |
| **L2** | source/config | 00–05 | build + test + lint (block) |
| **L3** | high-risk domains | 00–06 | + independent review rounds before done |

### Mandatory Writing Rule

- The phase range for the level is **mandatory writing**, configured in
  `profile.pdca.pre_implementation_required` (phases required *before* a code
  change) and enforced by the `pre-implementation-gate` hook: a missing required
  phase **blocks** L2/L3 implementation (warns at L1).
- An empty `plan_docs/{phase}/` directory is **not** a convention — treat it as a
  prior task's omission, never a precedent for skipping.
- Skipping a mandatory phase requires an explicit reason in the plan and user approval.
- Phase 06 (report) must not be written until the approve phase (05) records
  exactly one `Final Status: APPROVED` line — enforced by the gate
  (`profile.pdca.report_phase`/`approve_phase`). Write all 00–05 updates first and
  write 06 in a separate change so the gate never validates a pre-write snapshot.

### 3.0 Independent PDCA Cycle Rule (MANDATORY)

When the user explicitly requests a new PDCA flow (or an L3-equivalent flow), the
agent MUST start a new, independent `00-base_plan` — even if the change is
technically adjacent to a recently completed cycle. "Technically adjacent" (same
file / service / module) is never a reason to reuse or extend a prior cycle. A new
cycle requires a new 00 base plan, new phase documents, and independent analysis.
Every phase document in that cycle MUST declare exactly one `Cycle-Stem` outside
fenced code blocks and equal to its markdown basename. All phase, review, and acceptance evidence lookups bind to
that exact stem; branch-number scans and recent-file/mtime fallback are not cycle
identity. Missing, conflicting, or ambiguous bindings block governed work.

For edits outside phase documents there is no path to bind to, so the stem is
inferred from the git branch's last segment. On a long-lived branch shared by many
cycles that inference never matches, and governed edits are blocked as "phase
documents missing" while the documents exist. Declare the cycle explicitly with
`sage cycle set <phase-document-basename>` (`sage cycle show` / `sage cycle clear`
to inspect and release it); the gate then enforces every requirement against that
stem, and the first use in a session is recorded in `.sage/override.jsonl`. The
declaration outlives the shell, so release it when the cycle ends — a stale
declaration blocks the next cycle instead of the one you meant. See
`docs/agent/pdca-templates.md`.

### Pre-implementation declaration

Before writing implementation code, declare: risk level, compound rule applied,
applicable phase range, plan/phase doc paths, independent-review status (L3),
component impact, and reference docs read.

If `profile.pdca.enabled` is false (non-PDCA project), the gate falls back to
plan-doc + risk checks only; the phase machinery is inert.

## Non-negotiable safety boundaries

- Do not run `git commit` or `git push` unless the user explicitly asks.
- Do not perform destructive or outward-facing actions without confirmation.
- Do not directly edit generated artifacts (`{host}/agents`, `{host}/skills`,
  `{host}/hooks`) — edit the spec under `docs/sage_harness/` and regenerate.
  The hand-shipped CORE bootstrap renders (the `sage-*` skills and the six CORE roster
  agent renders) are also write-guarded: don't edit them directly — customize per-project
  only when eligible via an overlay at `sage/asset_overrides/{agents,skills}/<id>.md`
  (`/sage-asset-override`). Framework documents and oracle-unbacked gate-bearing assets (qa, sage-profile-modify) are not overlay-eligible.
  See the bootstrap section above.
- Report outcomes faithfully: if tests fail, say so with the output.

These are inherited by every agent/skill claim set as
`AGENT_GUIDE.non_negotiable_boundaries` (referenced, never copied).

## Definition of Done

- Plan doc updated; implementation matches the plan.
- `sage_harness/verify-changes.sh` passes at the required gate level.
- Conventions in `profile.conventions` satisfied.
- No direct edits to generated artifacts.

<!-- >>> SAGE PROJECT ROUTING v1 START (sage/project-profile.yaml 에서 생성, 여기서 수정 금지) -->
## 프로젝트 라우팅 (sage/project-profile.yaml 에서 생성)

세션 시작 시 아래 경로의 프로젝트 고유 문서를 확인하라. 이 블록은 결정론 생성되며 손편집은 `sage install --force` 시 사라진다 — 규칙 본문은 각 경로 문서에 있다.

### 거버넌스 문서
- `docs/chatforyou-agent/output-contract.md` — 출력 계약 및 컴포넌트 영향 규약
- `docs/chatforyou-agent/verification.md` — verify-changes.sh 실행 규약
- `docs/chatforyou-agent/pdca-extensions.md` — PDCA 확장 규약
- `docs/chatforyou-agent/knowledge-capture-fallback.md` — 지식 write-back fallback 규약

### 중요 도메인 (프로토콜 포인터)
- `webrtc` (L3) — `sage/critical-domains/webrtc.md`
- `security` (L3) — `sage/critical-domains/security.md`
- `recording` (L3) — `sage/critical-domains/recording.md`
- `game` (L3) — `sage/critical-domains/game.md`
<!-- <<< SAGE PROJECT ROUTING v1 END -->
