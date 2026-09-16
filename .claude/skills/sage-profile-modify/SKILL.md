---
name: sage-profile-modify
description: "Modify an existing sage/project-profile.yaml through conversation — change risk tiers, verification commands, the Phase-05 review loop, vault outputs, components, or options. Reads the current value, proposes a diff, WARNS about governance-gate consequences, then edits the profile and re-validates. Invoke when the user says /sage-profile-modify, change the profile, 루프 설정 바꿔, 리뷰 루프 수정, risk 글롭 수정, profile 수정, or wants to tune review_loop iterations/budget/lenses."
---

# sage-profile-modify — Conversational Profile Modification

This skill **modifies an already-bootstrapped** `sage/project-profile.yaml` through
conversation. It is the third conversational entry point:
- `/sage-init` — first authoring (0→1).
- **`/sage-profile-modify` — edit existing profile / governance values (this skill).**
- `/sage-asset` — add/modify governance assets (hook/agent/skill).
- `/sage-asset-override` — overlay an existing CORE agent/skill (project-local, --force-safe).

Authoritative protocol: `docs/agent/bootstrap-authoring.md`. Rules: `AGENT_GUIDE.md`.

Do not edit this CORE render directly (the write-guard blocks it and `sage install --force` overwrites it). Self-overlay is unsupported: `skills/sage-profile-modify` is not in `COMPOSE_ALLOWED`. Put project values in the profile and create genuinely new project assets with `/sage-asset`.

> This skill is a **CORE framework bootstrap asset** — hand-shipped by `sage install`,
> NOT manifest-tracked. Its reference spec lives at
> `docs/sage_harness/skills/sage-profile-modify.md`. Claude reads this render from the
> repo (`.claude/skills/sage-profile-modify/`); Codex reads it from the user-global
> skills dir (`$CODEX_HOME/skills/sage-profile-modify/`).

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

## What this skill is NOT

The profile is **hand-authored SSOT**, not a generated artifact — so this skill edits
the YAML **directly** and re-validates. It does **not** run `sage generate` (that is for
assets, where the write-guard forces spec→generate). `/sage-asset` is for changing
hooks/agents/skills; this skill is only for the profile's values.

## Hard rules

- **Fill/change values, never add or remove schema keys.** The schema is fixed; only
  values change. Adding a top-level key, or any key under a closed section
  (`risk` / `pdca` / `output_contract`), is a schema violation `sage validate` rejects.
- **Consequence-aware (this skill's core duty).** A profile change can *loosen or
  tighten the governance gate*. Before applying, state the effect in plain terms
  (see "Consequence warnings"). Editing the profile is editing the gate.
- **Never bypass a `sage validate` FAIL.** A FAIL is the guardrail working — fix the
  value it points to and re-run.
- **The user owns intent.** Propose the diff, surface the consequence, get explicit
  approval before writing.

## Step 0 — Read context first

Read, in order, before asking anything:
1. `sage/project-profile.yaml` — the file you will edit. If the profile is **not
   bootstrapped** — `project.name` empty, OR `risk` globs and `components` both unset
   (the same predicate `sage generate` enforces; a name-only profile is still toothless) —
   **stop and route to `/sage-init`**. This skill edits an established profile; it does
   not bootstrap.
2. `AGENT_GUIDE.md` — risk gate, PDCA phases, safety boundaries.
3. `docs/agent/bootstrap-authoring.md` — interview style + the shared "Review loop +
   vault interview set" (reuse it for review_loop/vault edits).

## Step 1 — Identify what to change (one focused turn)

From the user's stated intent, or by asking, pin the **section**:
- `project` (name/prefix) · `components[]` · `verification.commands` ·
  `risk.*` (L0–L3 globs / content keywords / `l3_review_strategy`) ·
  **`pdca.base_plan.done_criteria_gate`** (`off|advisory|enforce`) ·
  **`pdca.cycle_binding_visibility`** (`gated|all` — `all` also discloses the bound cycle
  on L1/L0 gate passes) ·
  **`pdca.review_loop`** (the Phase-05 loop — use the shared interview set) ·
  **`pdca.fast_cycle`** (`enabled`, `reason_required`, `minimum_rounds`,
  `minimum_lenses`, ordered L2/L3 lenses, `standard_transition`) ·
  `options.*` · **`knowledge_capture`** (vault_path + `loop_audit_dashboard` / `retro_note`) ·
  `file_type_map` · `compliance` / `output_contract` · `conventions` ·
  **`governance_docs`** (session-start read-pointers rendered into the AGENT_GUIDE routing block) ·
  **`team.core.<role>.runtime`** (`model` / `effort`) ·
  **`components[].runtime_models`** · **`cross_model.reviewer` / `.effort`**.

**`team.core.<role>.runtime.{model,effort}`** are injected into `.claude/agents/<id>.md`
frontmatter, so editing the profile alone does nothing — finish with
`sage install --force` to re-render, then `sage doctor` to confirm no agent reports
`stale`. They bind on a claude host only. A bare `model:`/`effort:` directly on the role
(no `runtime:`) is a legacy dead field; move it under `runtime:`.

**`governance_docs`** entries render into the AGENT_GUIDE **project routing block**, so editing
the profile alone does nothing until the block is re-materialized — finish with
`sage sync-overlays` (or `sage install --force`), then `sage validate --kind all` to confirm no
render drift. Each entry is `{ doc, label }`: `doc` a project-relative path that must exist,
`label` a short (≤80 chars) one-line description. Only path + label render — **never**
classification triggers (`path_globs`/`content_keywords` stay in `risk.*`).

**`cross_model.effort`** only matters when `options.cross_model: true`. Unset → `high`.
The vocabulary is peer-specific (`codex`: `minimal|low|medium|high|xhigh`;
`claude`: `low|medium|high|xhigh|max`) and a wrong value is rejected by `sage validate`.

For component or reviewer model edits, run `sage models --host <host>` before proposing
the value and preserve its verification label. `cross_model.reviewer.host` declares which peer
the model was chosen for; the reviewer itself is the runtime opposite whichever host is actually
executing, so a host handoff needs no reviewer edit — if the resolved peer differs, the model is
skipped. `host` must be an installed host other than a pinned `runtime.active_host`.
`sage doctor` reports cache-confirmed, syntax-only/account-unverified, or unknown selections.

For **`pdca.review_loop`** and the **vault outputs**, drive the *same* questions as
`/sage-init` via `docs/agent/bootstrap-authoring.md` (§ Review loop + vault interview
set) — single source, so init and modify never diverge. (Vault turn applies only when
the loop is on AND `knowledge_capture.vault_path` is set.)

`pdca.review_loop.early_completion` belongs to that same set and is asked only when the loop
is on; default off. Turning it **on** loosens the gate — the loop may close before convergence
on an explicit user authorization — so state that consequence and get approval like any other
loosening. The approval stays `APPROVED` but carries
`Review-Assurance: REDUCED_BY_USER_AUTHORIZATION`. Turning it **off** is a tightening and
does not invalidate approvals already recorded. Never infer the close-time authorization from
this profile value: enabling the feature is not the authorization.

For **`pdca.fast_cycle`**, drive the same Fast Cycle question set as `/sage-init`:
default off; when enabled, confirm L2/L3 `minimum_rounds`, `minimum_lenses`, and
ordered lens candidates while keeping engine floors 1 round and 2 lenses and
`reason_required: true`. State that lowering this policy reduces review breadth
but does not lower actual risk, deterministic verification, acceptance, or 05/06.
`pdca.fast_cycle.standard_transition` is asked only when Fast is on; default off. Turning
it **on** loosens the gate — a cycle already past Phase 00 may switch to the Fast contract
through `sage fast-cycle convert`, so the audit record rather than a composite document
becomes the evidence of how the run entered Fast. Turning it **off** does not invalidate runs
already converted. Never infer the conversion confirmation from this profile value: enabling
the feature is not the confirmation.

When a vault is available, include `knowledge_capture.fast_cycle_dashboard` in
   the same output turn.

For **`pdca.base_plan.done_criteria_gate`**, use the shared question in
`bootstrap-authoring.md`. Show the current value and explain that `advisory` adds warnings,
while `enforce` blocks malformed/replanned phase transitions and unresolved or stale final
approval. Changing to `off` removes this completion backstop. Do not edit historical plans
or infer N/A reasons as part of a profile change.

## Step 2 — Propose diff + consequence, get approval

1. **Show the current value** of the target key(s) verbatim.
2. **Propose the new value** as a before→after diff.
3. **State the consequence** in plain terms (this is mandatory, not optional):

### Consequence warnings (state before applying)

| change | consequence to surface |
|---|---|
| remove `risk.l3_filename_globs` / `l3_content_keywords` | that domain no longer gets the L3 gate/review (loosened) |
| empty/changed `risk.l3_review_strategy` | L3 becomes hard-blocked, or review matching changes |
| remove a phase from `pdca.pre_implementation_required` | that phase no longer required before code (gate loosened) |
| `done_criteria_gate` → `off` | Phase 00 completion/replanning/stale-approval checks are disabled |
| `done_criteria_gate` → `enforce` | malformed or stale active cycles can block until their Phase 00 and affected phases are repaired |
| empty a `verification.commands` entry | that check (build/test/lint) is skipped |
| `review_loop.enabled` → false | Phase 05 reverts to single-pass review |
| lower `review_loop.max_iterations[L3]` | fewer rework rounds before BLOCKED (e.g. 1 ≈ single-pass) |
| lower `review_loop.budget_tokens` | the loop may BLOCK earlier on budget |
| narrow `review_loop.severity_block` | lower-severity findings no longer block APPROVED |
| `review_loop.early_completion.enabled` → true | the loop may close before convergence on a user authorization; the verdict stays `APPROVED` but carries `Review-Assurance: REDUCED_BY_USER_AUTHORIZATION` (loosened) |
| raise/lower `early_completion.minimum_completed_rounds` | changes how few rounds an authorized early close may stand on (engine floor 1) |
| `fast_cycle.standard_transition.enabled` → true | a cycle already past Phase 00 may adopt the Fast contract without a composite plan; the audit record, not a document, becomes the evidence of how it entered Fast (loosened) |
| empty `knowledge_capture.vault_path` | all vault features OFF |
| remove a `governance_docs` entry | agents no longer discover that doc at session start (not a gate change) |

4. Get **explicit approval** before writing.

## Step 3 — Apply the edit

Edit `sage/project-profile.yaml` **in place** — change only the approved value(s),
preserving the fixed schema keys. Do not reformat unrelated sections.

## Step 4 — Re-validate (always)

If you changed a routing-block source (`governance_docs` or `risk.domains`), first
re-materialize the AGENT_GUIDE routing block with `sage sync-overlays` — otherwise
`validate` reports drift.

```
sage sync-overlays          # only if governance_docs / risk.domains changed
sage validate --schema --kind all
sage doctor
```
`validate` re-checks schema + semantics (typo / missing-tier / silent-disable);
`doctor` re-checks option dependencies (cross_model reachability, vault_path,
review_loop) and reviewer resolution. A FAIL points at the value to fix — **fix and
re-run, never bypass**. If you changed `components[]`, note that
`sage generate --kind roster` may be needed to scaffold new `implementer-<id>` specs
(that is an asset step — hand off or route to `/sage-asset`).

## Done

The change is complete when the profile reflects the user's intent, the consequence was
surfaced and accepted, the routing block was re-synced if `governance_docs`/`risk.domains`
changed (`sage sync-overlays`), and `sage validate` passes (or only WARNs the user accepted). If
the change tuned the review loop, the next Phase-05 run picks it up; if it touched a
gate-affecting section, the new gate behavior is live immediately.
