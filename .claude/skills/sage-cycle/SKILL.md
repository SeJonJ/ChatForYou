---
name: sage-cycle
description: "Run a full SAGE PDCA cycle (Phases 00–06) end to end from one entry point — delegates planning (00–02) to /sage-plan, then implementation→verification→QA→review→completion (03–06) to /sage-team. A thin umbrella that sequences the two halves and resumes at the right one; it never reimplements a gate. Invoke when the user says /sage-cycle (Claude) or $sage-cycle (Codex), 전체 사이클, PDCA 전체, run the whole cycle, start to finish, or wants to develop a feature end to end."
---

# sage-cycle — SAGE full PDCA cycle (Phases 00–06)

Invoke as `/sage-cycle` (Claude) or `$sage-cycle` (Codex).

Do not edit this CORE render directly (the write-guard blocks it and `sage install --force` overwrites it).
- overlay: optional `sage/asset_overrides/skills/sage-cycle.md` has project-local priority over this CORE render and is not shipped by `sage install`; it must not relax AGENT_GUIDE, phase, review, or verification gates (they stay floored by independent oracles). Put broad project rules in profile/conventions and create genuinely new project assets with `/sage-asset`.

This is the **umbrella** entry point for a whole PDCA cycle. It runs the two halves
in order:

- **`/sage-plan`** — Phases 00–02: plan docs + file-ownership map.
- **`/sage-team`** — Phases 03–06: implementation → deterministic verification →
  QA → Phase-05 review (via `/sage-review`) → completion.

sage-cycle is deliberately **thin**: it sequences the two sub-skills and resumes at
the correct half. It never reimplements a gate and never re-authors a plan or
re-drives orchestration — those belong to `/sage-plan` and `/sage-team`. SAGE still
owns every deterministic gate (`pre-implementation-gate`, `verify-changes`,
`sage review-loop` audit, the 06←05 report←approve backstop).

This umbrella does not run `sage cycle set` or `sage cycle clear` directly. The
ownership is deliberate and complete:

- `/sage-plan` runs `set` only after the 00–02 basename, `Cycle-Stem`, and risk
  declarations have passed validation.
- `/sage-team` runs `show` and reconciles `set` after resume context and cycle identity
  validation, then runs `clear` only after write-back, retro, snapshots, and closing
  gates have completed.
- `BLOCKED`, `FAIL`, or red verification retains the declaration for resume. An
  environment declaration also survives file `clear`; inspect with `sage cycle show`
  and remove it with `unset SAGE_CYCLE_STEM` when appropriate.

Do not duplicate those calls here. In Step 4, relay whether sage-team cleared the file
declaration or retained it, and whether an environment declaration remains effective.

> Prefer the sub-skills directly for partial or resume work: `/sage-plan` when you
> only need the plan, `/sage-team` to resume 03–06 on an existing plan. Use
> `/sage-cycle` when you want the whole thing driven from one call.

## Conversation language (mandatory)

Resolve it once, before the first turn, in this order:

1. an explicit `--lang ko|en` on this skill's invocation,
2. `interface.language` in `sage/project-profile.local.yaml`,
3. `ko`.

Conduct **every** question, proposal, progress note, warning and summary in that language.

Only the conversation takes it. Machine values are never translated — paths, globs, command
strings, component ids, strategy enums, statuses and the fixed schema keys. Phase 00–06 document
prose follows the cycle's `Document-Language:` marker, which is a **separate** decision and may
differ from the conversation. That document prose includes the **human-facing structure** — section
headings, list labels, table headers and checklist text — not just paragraphs; a Korean document
under English headings is the mixed state the marker exists to prevent. The two headings a parser
reads by their exact string, `## 5. Done Criteria` and `## 6. Done Criteria Revision Log`, stay
English in every language. Only `/sage-init` and `/sage-init-local` may persist a language
preference. Full rules: `docs/agent/language-policy.md`.

## Read these first (mandatory, in order)

1. `docs/sage_harness/skills/sage-cycle.md` — authoritative spec: procedure, drift_checks
2. `AGENT_GUIDE.md` — PDCA phases, risk gate, phase-first rule
3. `sage/project-profile.yaml` — `project.name`, `components`, `paths.plan_docs`

## Gate check (do this before anything else)

Confirm the profile is bootstrapped (`project.name` non-empty, `risk`/`components`
set). If not → stop: "Profile is not bootstrapped. Run `/sage-init` first."

## Step 1 — Identify the cycle

If the user asks mid-cycle to "go faster" or to drop documents, the only entrance is
`/sage-cycle-fast`, which requires the `pdca.fast_cycle` opt-ins and an explicit user
confirmation. Do not improvise a shortcut, and do not propose editing the profile to open one.

Establish the single cycle this run is about, the same way the sub-skills do — by
its plan-doc stem (the feature name) under `paths.plan_docs`. If the user has not
named the task, ask for a one-sentence description and derive/confirm the stem.
Match only that stem; ignore stale docs from other cycles (never treat another
feature's plan as this cycle's).

When this is a resumed session and the user supplies a context packet, run
`sage context restore --snapshot <path>`, then read the generated briefing before
resolving the half/stage. A failed restore is a hard stop. This restores verified
repository context only, never hidden conversation state.

## Step 2 — Planning half (00–02)

Check whether a **real, non-empty** plan doc for *this* cycle's stem already exists.

- **No usable plan for this stem** (absent, empty, or only a stub) → invoke
  **`/sage-plan`** (`$sage-plan` on Codex). It runs the gate check, scopes the task **via
  the planning interview** (`docs/agent/plan-interview.md` — elicits requirements/design
  before writing 00/01), invokes the leader to author the plan (00–02), and hands back an
  ownership map.
- **A real plan for this stem exists** → skip planning. Do NOT re-author or duplicate
  it (that would fork the cycle). Go straight to Step 3.

Do not make a finer completeness judgment here — that is the sub-skills' job, and
this umbrella must not reimplement their gates. `/sage-plan` owns the 00–02 gate, and
`/sage-team` re-validates on entry: it refuses to run without a real plan and its
resume logic treats **presence ≠ completion**. So even a wrong guess here cannot
advance an unplanned cycle — sage-team is the deterministic backstop.

## Step 3 — Implementation half (03–06)

Invoke **`/sage-team`** (`$sage-team` on Codex). It takes the plan + ownership map
and drives the cycle to completion using its own evidence-anchored resume logic
(**presence ≠ completion**): implementation (03) → deterministic verification →
QA (04) → Phase-05 review via `/sage-review` → completion (06). Do not duplicate any
of sage-team's steps here — this umbrella only invokes it.

The sub-skills own phase-boundary `sage context snapshot` calls when
`context_management.compaction.enabled: true`; this umbrella does not duplicate them.

- **Claude host**: sage-team spawns implementers as parallel subagents.
- **Codex host**: sage-team sequentializes the same steps (semantics preserved).

## Step 4 — Report

Relay the same completion summary `/sage-team` reports: per-phase outcome, recorded
review `run_id`, generated artifact inventory (name each artifact by path), a summary of
the retro proposals (pattern → target → proposed change), verification results, and any
pending human action (especially retro human-gate review/approval). If the cycle stopped
early (gate block, BLOCKED review, red verification), report where it stopped and what is
required to continue — do not claim the cycle completed.

## Done

The cycle is complete when `/sage-team` reports 06 written against an APPROVED
Phase 05 with a clean, closed loop-audit run and both closing captures accounted for:
a completed (or explicitly recorded skipped/failed) knowledge write-back, and retro
run with its note — when the vault wrote one — filled and passing `sage retro --check`
(or a recorded skip reason, including "vault disabled"). A retro note left as the blank
template does not count as retro having run. sage-cycle adds no completion criterion of
its own beyond sequencing the two halves.

---

> This skill is a **CORE framework bootstrap asset**: hand-shipped by `sage install`,
> NOT a manifest-tracked skill (no claims file, no render hash, not gated by
> `sage validate`/`sage asset-check`). Its reference spec lives at
> `docs/sage_harness/skills/sage-cycle.md`. To change it, edit the framework
> template, not via `sage generate`. Deploy location is runtime-specific: Claude
> reads it from the repo (`.claude/skills/sage-cycle/`); Codex reads it from the
> user-global skills dir (`$CODEX_HOME/skills/sage-cycle/`).
