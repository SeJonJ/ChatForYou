# Bootstrap Authoring Protocol (Conversational)

Use this when a project is first set up, or when a new component/asset must be
introduced. It defines **how an agent turns user intent into SAGE-conformant
assets through conversation** — the user supplies intent, the agent authors to
the spec, and a deterministic backend registers and verifies. The user does not
hand-write profile values or specs; the agent does, under the user's approval.
Some assets (agent/skill renders) still need an interpretive runtime render step
after scaffolding — this protocol marks where.

This document is runtime-neutral. Shared stack policy (languages, frameworks,
high-risk domains, component names) come from the conversation and land in
`sage/project-profile.yaml`. Machine capabilities and private paths land in the
Git-ignored `sage/project-profile.local.yaml` — never in this file.

---

## Principle

| Layer | Who | Determinism |
|:---|:---|:---|
| Intent | user (conversation) | — |
| Authoring (profile values, specs, plan docs) | agent, to the spec | interpretive |
| Registration (`sage generate`) + verification (`sage validate`) | deterministic | deterministic |
| Agent/skill render from scaffold | runtime AI | interpretive |

The agent renders; the backend checks. `sage validate` is the gate — it catches
schema and profile-semantic errors. Note the schema's strict unknown-key check
covers the top-level keys and selected sections (`risk`, `pdca`,
`output_contract`); most other nested sections are validated as loose objects,
and `--schema` (manifest JSON Schema) needs the optional `jsonschema` package.
So validate is a strong guardrail, not a total type-checker — author carefully.

The user stays **in the loop**: the agent authors per the rules and the user
approves. This is not a turnkey generator — intent's owner is the human.

---

## Stages

### 1. Install
`sage install --host claude --dest <project>` or
`sage install --host codex --skill-scope {global|project-local} --dest <project>` places the harness, the
neutral docs (this file included), and an **empty** `sage/project-profile.yaml`
(schema keys fixed, values blank). It installs the local schema and `.gitignore`
entry but does not create the local profile. Nothing is governed yet.

Codex scope is mandatory for normal installs. `global` owns the effective
`$CODEX_HOME/skills`; `project-local` owns `<project>/.codex/skills` and can travel
with a committed repository. Neither mode installs the SAGE CLI for another user;
each teammate installs the `sage`/`sage-hook` runtime separately. The generated
`docs/agent/sage-onboarding.md` records the selected onboarding path.

### 2. Interview → shared and local authoring
`sage-init` is allowed only while the shared bootstrap predicate is false:
`project.name` is empty, or both `components` and risk classification are unset.
It interviews the user, fills shared policy values in `project-profile.yaml`, and
creates `project-profile.local.yaml` for the current machine. If the predicate is
already true it stops and routes to `sage-init-local`.

`sage-init-local` requires a valid bootstrapped shared profile and creates or
updates only `project-profile.local.yaml`. It never modifies shared policy.

This is a **progressive conversation, not a form**: the agent takes one topic per
turn — proposing concrete values inferred from a repo scan, showing the signal it
inferred from, and asking a single focused confirm/correct question — rather than
dumping the whole list for the user to fill. Conduct the conversation **in Korean**
(English support is planned, not yet active); only the conversation is localized —
the **machine** profile values (paths, globs, commands, ids, schema keys) stay
language-neutral, while human-facing message values (e.g. `desktop_block_hint`)
are written in Korean too, matching the conversation. The agent only asks open-endedly when
the repo gives nothing to infer (e.g. which domains are security-sensitive). This
keeps authoring on the agent; the user supplies intent and approves. Surface the
decisions that genuinely need user intent; author the rest from them:

- `project.name` / `project.prefix`
- `components[]` — component boundaries (id + path globs + model). Filling this
  enables `sage generate --kind roster` to scaffold `implementer-<id>` specs.
  `model` is a work-intensity tier (`opus`=heavy / `sonnet`=standard), not a runtime
  model name: claude-host maps it to the Claude subagent model, codex-host treats it
  as a nominal tier (Codex uses its own model). On a codex-host project, present it as
  a tier choice, not a Claude-model recommendation.
  Then run `sage models --host <host>` for each installed host and ask the user to
  choose `components[].runtime_models.<host>` for every component that runs there.
  Codex cache candidates are cache-confirmed; Claude aliases are syntax-only and
  account entitlement remains unverified. Never present those aliases as guaranteed access.
- `risk.*` — derived from the stack and the high-risk domains the user names
  (e.g. secrets, auth, payments → `l3_*`). Cover the tier globs
  (`l0_pass_globs` / `l1_path_globs` / `l2_path_globs` /
  `l3_filename_globs` + `l3_content_keywords`), the `plan_glob`, and the
  `desktop_block_glob` / `desktop_block_hint` for generated/sync outputs. For L3 to
  be reviewable rather than hard-blocked, also set `risk.l3_review_strategy` (e.g.
  `claude_grep_first` | `codex_feature_signal`) — the review protocol blocks L3
  until one is selected.
- `verification.commands` — the deterministic build/test/lint commands for the stack.
- `file_type_map` — `{ glob, type }` first-match classification used for logging.
- `governance_docs` (optional) — project governance/reference docs the agent should
  discover at session start: architecture notes, security policy (including hidden
  paths like `.github/SECURITY.md`), domain protocol or convention docs **not already
  wired** through `risk.domains[].protocol_pointer`. Each entry is
  `{ doc: <relative path that exists>, label: <short one-line description ≤80 chars> }`.
  It renders into the AGENT_GUIDE **project routing block** as a read-pointer — path +
  label only, **never** classification triggers (`path_globs`/`content_keywords` stay in
  `risk.*`). Propose entries inferred from the repo scan for a single confirm; leave empty
  (`[]`) if none. Editing it later requires `sage sync-overlays` to re-materialize the block.
- `options.cross_model` — when true, Phase 05 review is opposite-runtime **only
  when reachable**; `sage doctor` resolves reachability from peer CLI availability
  (`which codex` / `which claude`) and blocks Phase 05 when the peer CLI is
  unavailable. No third-party tool is needed — SAGE calls
  the peer runtime directly (`codex exec` / `claude -p`). It is **not** resolved
  from `runtime.external_reviewer` (which records the intended preference only) — no code
  currently reads this key for a decision, so it is not part of the interview. The same is
  true of `runtime.asset_ssot`: a reserved schema key with no consuming logic today. Do not
  ask about either; if a later audit flags them as "missing from the interview," this note is
  the answer — they have nothing to configure yet.
- `runtime.installed_hosts` / `runtime.active_host` — configure every desired SAGE
  discovery surface, but keep exactly one active host. A double-host project does
  not run both hosts concurrently and SAGE never switches hosts or phases automatically.
  To hand off, finish durable exact-Cycle-Stem phase documents, switch runtimes
  manually, and resume from those documents. Prefer `active_host: auto`, which reads
  the running host at execution time: the key lives only in the shared committed
  profile (a machine-local file cannot override it), so a pinned value has to be
  edited — through the L2 gate — every time a developer changes hosts.
  `runtime.host` is a legacy alias; do not author both keys with different values.
  Double-host projects should set `options.cross_model: true` so Phase 05 calls the
  runtime opposite the active host.
- `cross_model.reviewer` — when cross-model is enabled, ask the user for the reviewer
  `host` and `model`. The `host` scopes the model — model ids belong to one runtime, so SAGE
  skips the model when the resolved peer differs. The reviewer itself is resolved at run time by
  excluding the host that is actually executing, so a host handoff needs no reviewer edit.
  `sage validate` checks `host` is an installed host and is not the pinned `active_host`;
  `sage doctor` checks the local catalog.
- **Review loop (Phase 05)** — the optional adversarial review-rework loop. Use the
  shared interview set below (§ Review loop + vault interview set). Both `sage-init`
  (first authoring) and `sage-profile-modify` (later editing) drive the *same* set.

`context_management.compaction` (what to preserve across session compaction, max snapshot
bytes) is intentionally **not** interviewed. Its shipped default is a reasonable choice for
nearly every project; treat it as advanced internal tuning reachable through
`sage-profile-modify` if a project genuinely needs to change it, not a bootstrap question.

`pdca.cycle_binding_visibility` (`gated` default / `all`) is likewise **not** a bootstrap
question. It only widens which gate *pass* lines disclose the bound cycle stem — `all` adds a
line on every L1/L0 pass, which is the highest-frequency edit tier, so it is a deliberate
opt-in for projects running many cycles on one long-lived branch rather than a default worth
asking about. Nothing is lost while it is off: the binding is recorded in
`.sage/override.jsonl` regardless. Reachable through `sage-profile-modify`.

Present the filled profile (or the consequential choices) for user approval.

#### Phase 00 Done Criteria gate (shared policy)

Ask this in every first-authoring `/sage-init` conversation and when
`/sage-profile-modify` targets PDCA completion policy:

> "How strictly should Phase 00 done criteria be checked at each phase and in the final report?"
> · `off` — keep existing documents and behavior; run no check
> · `advisory` (recommended for a new project) — warn on structure, progress, replanning and
>   stale-approval problems, but allow the run to continue
> · `enforce` — block invalid structure or replanning, and unresolved or stale Phase 05 approval

Write the answer to `pdca.base_plan.done_criteria_gate`. Explain that `[ ]` is allowed
during Phases 01–04, but APPROVED/06 requires every item resolved. Replanning increments
`Done-Criteria-Revision`, records a reason and affected phases, reruns those phases, and
invalidates the old Phase 05 approval. This is shared governance: `sage-init-local` must
not ask for or modify it. When upgrading an existing profile without an explicit choice,
preserve legacy behavior as `off`; do not silently opt it into a new block.

#### Review loop + vault interview set (shared by sage-init and sage-profile-modify)

Single source of the loop questions so the two skills never drift. Every toggle gets
a one-line plain explanation (same style as the L0–L3 explanation). One topic per turn.

**Loop toggle** (default off — `pdca.review_loop.enabled`):
> "Should the Phase 05 review run as an adversarial loop? (default: a single pass)"
> · single pass (off) — one reviewer, once. Light and fast.
> · loop (on) — find → refute → rework, repeated until convergence, on L2/L3 only. Filters false
>   positives and reduces misses, but costs more.

If **off**, leave `review_loop.enabled: false` and skip the rest. If **on**, author
`pdca.review_loop` (each value with its one-line meaning):

| ask | key | one-line meaning |
|---|---|---|
| Which perspectives? (propose from the stack) | `lenses` | FIND lenses, from the engine vocabulary: correctness / security / concurrency / convention / lifecycle / performance / error_handling / data_integrity / api_contract. **One lens = one reviewer subagent per round.** |
| Maximum rounds for L2 and L3? | `max_iterations` | Without convergence the run is BLOCKED at this count (defaults L2:1, L3:3) |
| Token budget? | `budget_tokens` | BLOCKED once the cumulative total is exceeded (defaults L2:150k, L3:600k) |
| How many refuters? | `refuters` | Refuters **per round**. They judge all findings in one batch, so the count is independent of how many findings there are. A finding is dropped only when more than half of them disprove it — a tie survives, so with the default 2 one refuter cannot drop a finding alone |
| Consecutive dry rounds? | `dry_rounds` | K consecutive rounds with zero new findings means convergence (default 1) |
| Severities that block approval? | `severity_block` | APPROVED is blocked while any of these is unresolved (default [P0,P1]) |
| Cross-model refutation? | `cross_model` | Tied to `options.cross_model` — reuse that answer if it was already asked; do not ask again |
| Strictness of the 06←05 audit gate? | `report_gate_enforce` | When 06 is written, check that the loop run 05 points at is clean, closed, APPROVED, sequentially numbered and not degraded. `advisory` (default, WARN) / `enforce` (BLOCK — safe only when every 05 runs the loop) / `off` (marker only). Prefer advisory until things settle, then enforce once the team agrees |

> **Getting a feel for the token cost — these values *are* the subagent count.** Two terms
> first: a **finding** is a problem the review found, and a **refuter** is an inspector that
> argues whether that problem is real. The Phase 05 loop runs on subagents:
> - **FIND**: one reviewer per entry in `lenses` spawns in parallel each round — six lenses means
>   six per round. Each reads the code independently.
> - **REFUTE**: `refuters` inspectors spawn each round. **However many findings there are, the
>   inspector count per round is fixed at this number**, because they judge the whole set in one
>   batch. Default 2.
> - **cross_model**: adds one peer reviewer on the opposite runtime (codex/claude) **to the FIND
>   stage** via `sage cross-check`, on its own token budget. It *finds* problems, so its role
>   differs from a refuter — it does not raise the refuter count.
> - This repeats for at most `max_iterations` rounds and stops once cumulative tokens exceed
>   `budget_tokens`.
>
> So **raising lenses, refuters, rounds or cross_model raises the token cost.** For anything that
> is not safety-critical, cutting to three or four lenses saves a great deal of budget for the
> coverage given up.

**Early completion by user authorization** — ask **only if the loop is on** (one turn,
default off — `pdca.review_loop.early_completion`):
> "May the reviewer close the loop early when the user explicitly authorizes it? (default: off)"
> · off — the loop closes only on convergence or the configured maximum
> · on — with an explicit user authorization the loop may close before convergence, recording
>   the reason, the approver, and the per-severity residual receipt to `.sage/loop_audit.jsonl`.
>   Left unset, one completed round is enough for such a close
> · what it never waives: zero rounds, unresolved `severity_block` findings, architecture
>   escalation, failed verification, unresolved Done Criteria, acceptance FAIL, audit damage

If on, author `pdca.review_loop.early_completion` (`enabled: true`, and optionally
`minimum_completed_rounds` — an engine floor of 1 that a project may raise). State the gate
consequence plainly: the Phase 05 approval is still `APPROVED`, but it carries
`Review-Assurance: REDUCED_BY_USER_AUTHORIZATION` so a later reader and the CI authority can
tell it apart from a converged approval.

**Never infer the authorization.** The confirmation token, the reason and the approver come
from the user in that turn; a skill may not supply them from context, from a previous run, or
from its own judgement that the remaining findings look harmless. When upgrading an existing
profile without an explicit choice, leave the block absent — absence is off.

**Vault outputs** — ask **only if the loop is on AND `knowledge_capture.vault_path` is set**
(one turn; otherwise skip entirely):
> "Should loop output also be written to the Obsidian vault? (vault_path detected)"
> · audit dashboard — per-round found/accepted/convergence trends as a vault note, using a plain
>   table so no plugin is required → `knowledge_capture.loop_audit_dashboard`
> · retro note — `sage retro` output as an `approved:false` note, reviewed and approved in the
>   vault as a human gate → `knowledge_capture.retro_note`
> · [both / dashboard only / retro only / neither]

These flags require `vault_path` (the master gate); `sage validate` WARNs if a flag is
true while `vault_path` is empty.

#### Fast Cycle interview set (shared by sage-init and sage-profile-modify)

Raise Fast Cycle as a separate shared-policy topic, default off:

> "Should Fast Cycle be allowed for urgent or small-scope L2/L3 work? (default: off)"
> · the standard risk, build, test, lint, acceptance, 05 and 06 requirements all stay
> · physical Phases 01–04 collapse into a composite 00, with review minimums configured separately
> · every run records its reason and chosen lenses to the shared audit at `.sage/fast_cycle.jsonl`

If off, keep `pdca.fast_cycle.enabled: false` and preserve the template defaults.
If on, propose these values from the project's risk domains and ask for one focused
approval turn:

| key | contract |
|---|---|
| `reason_required` | always `true`; never offer a disabling choice |
| `minimum_rounds.L2/L3` | integer floor 1; a project may strengthen it |
| `minimum_lenses.L2/L3` | integer floor 2; a project may strengthen it |
| `lenses.L2/L3` | ordered, unique candidates from the closed engine vocabulary; each list must satisfy its minimum |

Explain that actual `Risk Level` remains independent from `Fast-Review-Level`.
An actual L3 run may choose L2 Fast but remains L3 for deterministic verification
and acceptance and receives the explicit downgrade warning. The selected N lenses
are the first N configured candidates; run-time arbitrary lens strings are not accepted.

**Standard→Fast conversion** — ask **only if Fast is on** (one turn, default off —
`pdca.fast_cycle.standard_transition`):
> "May a Standard Cycle already in progress be converted to Fast? (default: off)"
> · off — a Fast run starts only from a composite Phase 00 authored up front
> · on — `sage fast-cycle convert` switches a cycle that is already past Phase 00 to the Fast
>   contract, recording the reason, the approver and the phases already produced
> · the conversion writes no document: existing Phases 00–04 are neither deleted, moved,
>   merged nor rewritten, and no conversion metadata is inserted into them
> · the converted run waives only the pre-implementation phases it can show it already has

Write `standard_transition.enabled`. State the gate consequence: with this on, a cycle can
reach Fast review minimums without ever authoring a composite plan, so the audit record — not
a document — becomes the only evidence of how the run entered Fast.

**Never infer the conversion.** `--confirm FAST-CONVERTED`, `--reason` and `--confirmed-by`
come from the user in that turn. A skill may not derive them from context or reuse them from an
earlier run. When upgrading an existing profile without an explicit choice, leave the block
absent — absence is off.

When Fast is enabled and an effective vault path exists, include
`knowledge_capture.fast_cycle_dashboard` in the vault-output turn. This shared flag
controls only the derived project dashboard; the private vault path remains in the
local profile. `sage-init-local` must not ask for or write `pdca.fast_cycle`.

**PDCA knowledge scan/write-back** — ask when `knowledge_capture.vault_path` is set
(one turn, default both on):
> "Should vault knowledge capture run automatically before and after development?"
> · lookup beforehand — `/sage-plan` searches the vault before implementation and refreshes
>   `.sage/knowledge_scan.md` → `knowledge_capture.scan_before_dev`
> · update afterwards — `/sage-team` refreshes vault notes and `wiki/log.md` on completion
>   → `knowledge_capture.update_after_dev`
> · [both / lookup only / update only / neither]

These are explicit host-side steps backed by `sage knowledge scan` and
`sage knowledge write-back`; they are not hidden background writes.

**Note convention — follow the vault's existing rules (do not impose).** When `vault_path` is set,
inspect the vault before proposing `knowledge_capture.note_convention`: list the target folder,
read 2–3 existing notes, and check for an index/log. Propose values from what you observe, then
confirm with the user (do not auto-detect at write time — record the decision in the profile):
> - `folder` / `filename_pattern` / `prefixes` — match the vault's existing layout & title prefix (e.g. `TECH - {title}.md`); if the vault uses no prefix, leave it empty.
> - `tags_style` — `frontmatter` / `inline` / `none`. Match how existing notes tag (YAML frontmatter vs a `태그:` line vs none). Default `frontmatter`.
> - `index` — the vault's index filename (e.g. `index.md`) if one exists and the user wants new notes linked there; **empty if the vault has no index** (write-back then skips index updates).
> - `required_structure` — per-PREFIX required body markers (line-start strings) the vault expects for each note
>   type. Inspect the vault's authoring rules (or ask the user) and record the markers each prefix must contain.
>   These markers should describe the vault's **deep-note skeleton** for meaningful (L2/L3) work — e.g.
>   `BUG: ["> [!abstract]", "## 배경", "## 설계 결정", "## 변경 내역", "## 검증", "## 재발 방지", "## 관련 문서"]`. On
>   write-back of a **new** note, `sage knowledge write-back` advisory-checks that these markers exist and WARNs
>   (never blocks) on omission. It verifies marker **existence only** (a deterministic skeleton check); content
>   depth is left to the skill prompt + the write-back skill's host depth self-review (advisory), not the CLI. PREFIX is matched exactly first, then
>   case-insensitively. **Leave `{}` (default) if the vault imposes no fixed structure — the check is then fully
>   off.** Confirm the markers with the user; do not invent a structure the vault does not actually require.

**Note depth scales with risk tier (skill-driven, not CLI-enforced).** The write-back skill authors L1
(trivial) notes as a few sentences and L2/L3 (meaningful) notes to the vault's full deep skeleton above,
matching the depth of the vault's own hand-written notes. In the 변경 내역 section, code is shown proportional
to change volume — small changes as the actual before/after snippet, large changes as pseudocode/walkthrough
anchored by `파일:함수:line`. `required_structure` (when configured) only checks that the L2/L3 skeleton markers
are present; the CLI never judges whether each section is deep enough (that stays with the skill prompt and the
skill's host depth self-review (advisory) — an independent human review only if the project sets one up separately).

This makes `sage knowledge write-back` honor the user's vault conventions deterministically instead of
imposing SAGE defaults (6th-test: frontmatter/index assumptions didn't match the vault).

### 3. Handoff to the deterministic backend
On approval, hand off — do not keep authoring registration artifacts by hand:

```
# Existing CORE hook regeneration can target one host.
sage generate --kind hook --write --target claude
# A new project-authored hook must be registered for both hosts in one transaction.
sage generate --kind hook --id <id> --write --target both
# component implementer SCAFFOLDS (only if components[] is set):
sage generate --kind roster --write
# materialize the AGENT_GUIDE routing block from the profile (risk.domains + governance_docs):
sage sync-overlays
# verification (default --kind hook; use --kind all to also check agent/skill):
sage validate --check --schema --kind all
```

Notes that matter:
- `sage generate --kind hook` writes registration for `--target` (default
  `claude`). For a cross-model project that runs both runtimes, use
  `--target both`; `hooks.register` in the profile is documentation, not a
  generate input.
- `sage generate --kind roster` only **scaffolds** `docs/sage_harness/agents/
  implementer-<id>.md`. Those specs still need a runtime AI render (both
  `.claude/agents/<id>.md` and `.codex/agents/<id>.md`), then
  `sage generate --kind agent --id <id> --write` reverse-extracts spec+claims and
  registers them in the manifest (render_hash for both runtimes) — roster alone
  does not complete them.
- `sage sync-overlays` materializes the AGENT_GUIDE **project routing block** from the
  profile (`risk.domains` + `governance_docs`). Run it before `validate` — otherwise the
  block stays empty while the profile has entries and `validate` reports drift.
- `sage validate` defaults to `--kind hook`; pass `--kind all` to also validate
  agent/skill renders (only meaningful once they are registered).

A `validate` FAIL is the guardrail working — fix the value/spec the message
points to and re-run; never bypass.

### 4. Phase-first authoring (before any code)
The `pre-implementation-gate` blocks L2/L3 code edits until the phases required by
`profile.pdca.pre_implementation_required` exist. Author the plan docs first,
through the same conversation:

- `00-base_plan` — CONTEXT (why / what / impact / risk)
- `01-plan` — CONTENT (requirements / data schema / API contract)
- `02-design` — architecture / sequence / error codes
- For L2/L3 work scoped to one component, also author `{component}/plan_docs/`
  (code-level design) before root `03-implementation` — see `pdca-templates.md`
  "Writing order for L2/L3 changes".

Templates and the 00↔01 / 02↔03 boundaries are in `pdca-templates.md`. Only after
the required phases exist does the gate admit implementation; the cycle then
continues 03 → 04 → 05 (review, cross-model when enabled) → 06.

### 5. Asset additions later
The same loop applies to introducing a new hook/agent/skill after bootstrap, and
the **`/sage-asset` skill** drives it conversationally:
- **hook**: author the spec under `docs/sage_harness/hooks/<id>.md` + the canonical
  `sage_harness/hooks/<id>_core.py`, then `sage generate --kind hook --write`.
- **agent/skill** (interpretive): author BOTH runtime renders (`.claude/...` and
  `.codex/...` — codex included), then `sage generate --kind <agent|skill> --id <id>
  --write` reverse-extracts spec+claims and registers them (render_hash for both
  runtimes). It fails closed if either render is missing.
  - For a skill codex must discover, add `--deploy-codex` (copies the repo-canonical
    `.codex/skills/<id>/SKILL.md` to `$CODEX_HOME/skills/<prefix>-<id>/`; the manifest
    still tracks only the repo canonical). codex-host + non-empty `project.prefix` required.
Never edit a generated artifact directly (see AGENT_GUIDE safety boundaries).

---

## Signals of incorrect bootstrap
- Profile authored with a new top-level key → schema violation. Keys are fixed,
  fill values. (Nested unknown keys are strictly blocked only in `risk` / `pdca`
  / `output_contract`; other sections are looser — still do not invent keys.)
- Registration artifacts (`{host}/settings.json`, shims, agent renders) edited by
  hand → regenerate / re-render from spec instead.
- Cross-model project registered with `--target claude` only → codex side missing;
  use `--target both`.
- Code edited before the required phases exist for an L2/L3 change → the gate will
  block; author the phases first.
- `validate` FAIL bypassed to "move on" → the guardrail was right; fix the value.

## Related rules
- `AGENT_GUIDE.md` — Risk & Workflow Gate, safety boundaries
- `docs/agent/pdca-templates.md` — phase templates + separation + component-level order
- `docs/agent/review-protocol.md` — reviewer resolution (`sage doctor`), L3 review
- `docs/agent/risk-classification.md` — how `profile.risk` maps to levels
- `sage/project-profile.yaml` — committed shared policy SSOT
- `sage/project-profile.local.yaml` — Git-ignored machine capability and private-path layer
