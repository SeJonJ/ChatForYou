---
name: sage-init
description: Use at the start of a SAGE project to author both the shared project policy and the first machine-local capability profile. Block when the shared profile is already bootstrapped and route to sage-init-local.
---

# sage-init — Conversational SAGE Bootstrap

Invoke as `/sage-init` (Claude) or `$sage-init` (Codex).

Do not edit this CORE render directly (the write-guard blocks it and `sage install --force` overwrites it). Self-overlay is unsupported: `skills/sage-init` is not in `COMPOSE_ALLOWED`. Put project values in the profile and create genuinely new project assets with `/sage-asset`.

This skill turns user intent into a SAGE-conformant shared `sage/project-profile.yaml`
and machine-local `sage/project-profile.local.yaml`
**through conversation**, then hands off to the deterministic backend
(`sage generate` / `sage validate`). The user supplies intent and approves; you
author the profile values to the schema. This is the designed entry point — the
governance gate is inert until the profile is bootstrapped, and `sage generate`
is BLOCKED while the profile is unbootstrapped (`project.name` empty, or
`risk`/`components` unset).

Authoritative protocol: `docs/agent/bootstrap-authoring.md`. Rules: `AGENT_GUIDE.md`.
This skill is the active driver of that protocol.

> This skill is a **CORE framework bootstrap asset**: hand-shipped by `sage install`,
> NOT a spec-generated skill (no `docs/sage_harness/skills/` spec, not manifest-tracked).
> Deploy location is runtime-specific: Claude reads it from the repo
> (`.claude/skills/sage-init/`, write-guard exempt); Codex auto-discovers it from the
> user-global skills dir (`$CODEX_HOME/skills/sage-init/`, default `~/.codex/skills/`)
> because Codex does not auto-discover repo-scoped skills. To change it, edit the
> framework template, not a spec.

## Hard rules

- **Run only for an unbootstrapped shared profile.** Bootstrap is complete only when
  `project.name` is non-empty and at least one of these is configured: non-empty
  `components`, or a non-empty L0-L3 `risk` classification glob. File existence alone
  is not completion because `sage install`
  places an empty shared template. If the shared profile is already bootstrapped,
  stop with **BLOCKED** and direct the user to `/sage-init-local` or `$sage-init-local`.
- **Write both ownership layers.** Shared project policy goes to
  `sage/project-profile.yaml`; machine capabilities and private paths go to
  `sage/project-profile.local.yaml`. Never compile local values into JSON or manifests.

- **Fill values, never add/remove schema keys.** The schema is fixed; only values
  change (determinism constraint). Adding a top-level key, or any key under
  `risk` / `pdca` / `output_contract`, is a schema violation that `sage validate`
  rejects.
- **Never edit generated artifacts** (`.claude/settings.json`, hook shims, agent
  renders). Edit the spec and run `sage generate`.
- **Never bypass a `sage validate` FAIL.** A FAIL is the guardrail working — fix
  the value it points to and re-run.
- **The user owns intent.** Author per the rules, present the consequential
  choices, and get approval before handoff. This is not a turnkey generator.

## Step 0 — Read context first

Read, in order, before asking anything:
1. `sage/project-profile.yaml` — confirm the full bootstrap predicate is false
2. `sage/project-profile.local.yaml` — read only if a partial prior attempt exists
3. `AGENT_GUIDE.md` — risk gate, PDCA phases, safety boundaries
4. `docs/agent/bootstrap-authoring.md` — the full protocol and signals of incorrect bootstrap
5. The repo itself — list top-level dirs, detect stack/build files (package.json,
   build.gradle, pyproject.toml, etc.) so you can propose values instead of asking blind.

If the complete bootstrap predicate is true, stop with **BLOCKED** and route to
`sage-init-local`. Shared policy changes use `sage-profile-modify`; this flow must
not overwrite an established shared profile.

## Step 1 — Interview (progressive conversation, one topic at a time)

**This is a conversation, not a form.** Do NOT dump every question at once and ask
the user to answer a numbered list — that pushes the authoring work back onto the
user, which is exactly what this skill exists to avoid. Instead, walk one topic at
a time and drive each turn yourself.

### Language (mandatory)

Conduct the entire interview in the **resolved conversation language** — every
proposal, question, acknowledgement, and progress summary. Resolve it once, before
the first question: an explicit `--lang ko|en` on this skill's invocation, then
`interface.language` in `sage/project-profile.local.yaml`, then `ko`.

Only the **conversation** takes that language: the **machine** values you write into
`project-profile.yaml` stay language-neutral as-is — paths, globs, command strings,
component ids, strategy enums, and the fixed schema keys are never translated. The one
exception is **human-facing message values** such as `desktop_block_hint` (a notice shown
to the developer): write those in the conversation language too — they are prose, not
machine tokens.

This skill is one of only two flows (with `/sage-init-local`) permitted to **persist** a
language preference, and only to the local profile after explicit user approval. Full
rules: `docs/agent/language-policy.md`.

### Interview style (mandatory)

For **each** topic below, in order, take a single conversational turn:

1. **Propose, don't ask blind.** From your Step 0 repo scan, infer concrete values
   and state them as a proposal (e.g. "I see `build.gradle` and `src/main/java` —
   I'll set `verification.commands.build` to `./gradlew build`. Sound right?").
2. **Show the signal.** Name the file/evidence you inferred from in one short
   clause, so the user can judge the inference.
3. **Ask one focused question about *this topic only*.** The test is the user's
   *burden*, not the field count: a turn may present several **inferred** values at
   once for a single confirm (e.g. "here are the three risk globs I derived —
   correct?"), because the user only has to confirm. But never ask the user to
   *produce* more than one unknown per turn — when a value can't be inferred and
   the user must supply it (e.g. which domains are security-sensitive), that is its
   own turn with one open question. Batched *proposals* are fine; batched *demands*
   are the form-dump this skill forbids.
4. **Acknowledge and record**, then move to the next topic. Do not advance while
   the current topic is unsettled, and do not look ahead to later topics in the
   same turn.

Only when the repo scan gives you nothing to infer (e.g. which domains are
security-sensitive) do you ask open-endedly — and even then, one topic per turn.
Every 2–3 topics, briefly echo the profile-so-far so the user sees it taking shape.
Be thorough across the full topic list — a sparse profile means a weak gate — but
reach thoroughness through the back-and-forth, not a wall of questions.

### Topic order (one turn each)

1. **`project.name` / `project.prefix`** — propose from the dir name / package
   metadata. Name is the bootstrap signal; settle it first.
2. **`components[]`** — propose the partition from top-level dirs (e.g. backend /
   frontend) as `{ id, paths: [globs], model }`. Filling this enables
   `sage generate --kind roster` to scaffold `implementer-<id>` specs.
   - **`model` is a work-intensity tier, not a runtime model name.** `opus` = heavy
     (design-heavy / high-complexity components), `sonnet` = standard. On a **claude-host**
     project this tier maps to the Claude subagent model; on a **codex-host** project it is
     just a nominal intensity hint (Codex uses its own model regardless). So when the host
   is Codex, do **not** frame this as "recommending a Claude model" — say "heavier tier
   (`opus`)" or "standard tier (`sonnet`)". Allowed values stay `opus | sonnet`.
   - After the tier, run `sage models --host <host>` for each installed host and show
     candidates with their verification label. Ask the user to choose an actual
     `runtime_models.<host>` value for each component. Codex cache candidates are
     cache-confirmed; Claude aliases are syntax-only/account-unverified. Do not claim
     that an alias proves account entitlement or silently probe a paid endpoint.
   - **Same turn, propose `checklist_scan_targets`.** Once components are settled, propose
     the derived default: `[{label: "03 implementation", glob: "plan_docs/03-implementation/**/*.md",
     is_impl: true}]` plus one entry per component (`{label: "<id> plan", glob: "<component
     path>/plan_docs/**/*.md"}`). This is what `pre-phase4-checklist-gate` scans before
     Phase 04 — leaving it empty silently disables that gate. Single confirm, not an open
     question.
3. **`verification.commands`** — propose the real `build` / `test` / `lint` (and
   `syntax` for L1) from the build files you found. Empty = that check is skipped,
   so confirm only commands that genuinely exist.
4. **`verification.acceptance`** — its own short turn right after `verification.commands`.
   State that the requirement-level acceptance-evidence matrix is on by default
   (`enabled: true`) and already enforces L3 (`require_for_risk: [L2, L3]`,
   `report_gate_by_risk.L3: enforce` — Phase 06 is blocked on an unresolved `FAIL`/`NOT
   TESTED` unless an exact waiver exists). Most projects keep the default — this turn is a
   single confirm surfacing that the gate exists, not a new open question. Only dig into
   `require_for_risk`/`waiver` if the user wants to change scope.
5. **`risk` tiers L0–L2** — propose from file types. **Briefly explain what the
   levels mean as you set them**, since the user is choosing how strict each kind
   of file is gated. The levels are a blast-radius ladder — the gate enforces more
   the higher you go:
   - **L0** `l0_pass_globs` — docs / plan files. Passes instantly, no checks.
   - **L1** `l1_path_globs` — low blast radius (e.g. UI/markup). Light checks only.
     Also set `plan_glob` here (e.g. `plan_docs/**/*.md`) — where plan docs live.
   - **L2** `l2_path_globs` — source / config. Requires build + test + lint to pass.
6. **`risk` L3 (high-risk domains)** — the top of the ladder, reserved for areas
   where a mistake is costly. **Explain that L3 is the strictest tier: the gate
   requires the plan phases to exist before you may edit, and the change also needs
   independent review later in the workflow before it's considered done.** Here you
   usually must *ask*, not infer: which areas are
   security-sensitive (auth, payment, crypto, secrets)? From the answer set
   `l3_filename_globs` + `l3_content_keywords`, and the `desktop_block_glob` /
   `desktop_block_hint` for generated/sync outputs that must never be hand-edited.
   In the same turn, also ask whether any L1/L2 area should escalate on content alone —
   if so, collect `l2_content_keywords` (L1→L2 escalation) alongside `l3_content_keywords`;
   both are optional and default to empty.
   - Mention the **escalation rule** so the user understands the tiers interact:
     an L1/L2 file whose content matches `l3_content_keywords` is treated as L3,
     an L1 file matching `l2_content_keywords` is treated as L2, and the effective
     level is `max(detected, what the user declared)` — i.e. the gate always picks
     the stricter of the two.
   - **`l3_review_strategy`** — REQUIRED for L3 to be reviewable rather than
     hard-blocked. Confirm `claude_grep_first` or `codex_feature_signal` (or a
     module name). The review protocol blocks L3 until this is set.
7. **`file_type_map`** — propose `{ glob, type }` first-match classification for
   logging from the stack you've established.
8. **`governance_docs`** (optional) — project governance/reference docs the agent should
   discover at session start. Propose from the Step 0 repo scan: architecture notes,
   security policy (including hidden paths like `.github/SECURITY.md`), domain protocol
   docs, or convention docs **not already wired** through `risk.domains[].protocol_pointer`.
   Each entry is `{ doc: <relative path>, label: <short one-line description ≤80 chars> }`
   and renders into the AGENT_GUIDE **project routing block** as a read-pointer —
   **path + label only, never classification triggers** (`path_globs`/`content_keywords`
   stay in `risk.*`, the hook's authoritative source). One turn: propose the entries you
   inferred for a single confirm; leave empty (`[]`) if the project has none. `doc` must be
   a project-relative path that exists; the label says why the agent should read it.
9. **`team.core.<role>.runtime.model` / `.effort`** — optional per-agent runtime settings
   for the CORE roles (`leader`, `implementer-a`, `implementer-b`, `qa`, `reviewer`,
   `convention-checker`). Ask once, as a single topic; leaving both unset is a fine answer.
   Note the nesting: these live under `runtime:`, *not* directly on the role — a bare
   `model:` on the role is a legacy dead field and `sage validate` warns about it.
   - `model`: `opus | sonnet | haiku | fable | inherit`, or a full model id (`claude-…`).
     **Unset → the host CLI's own model.** SAGE never picks a model for you.
   - `effort`: `low | medium | high | xhigh | max`, or a positive integer.
     **Unset → `high`**, which SAGE injects rather than deferring to the CLI default.
   - These bind on a **claude host only** — they are injected into
     `.claude/agents/<id>.md` frontmatter. On a codex host `.codex/agents/*.md` has no
     mechanism to read them, so SAGE does not inject them and `sage validate` reports any
     that are set as inert. Don't offer the topic on a codex-host project.
   - Do not quietly pin `reviewer` or `qa` to a cheaper model or a lower effort than the
     rest — that silently weakens Phase 05 review. If the user wants that, make the trade
     explicit.
   - Changing these later requires `sage install --force` to re-render the agent files;
     `sage doctor` reports the un-re-rendered files as `stale` until you do.

Keep `pdca.*` at the standard 00–06 unless the user runs a different phase set
(don't raise it as a question unless they bring it up).

## Step 2 — Shared policy and local capabilities

Same one-topic-per-turn style as Step 1: raise each toggle on its own turn, propose
a default, and only dive into the matching section if the user enables it. Skip a
toggle's detail entirely when it stays off.

- **`runtime.active_host`** — propose `auto` (detects the executing host at run time) as the
  default and get a single confirm. Only offer pinning to a fixed `claude`/`codex` if the
  user explicitly wants one, and explain the cost: a pinned value lives in the shared
  committed profile (a local profile cannot override it), so changing which host a
  developer runs from later means an L2-gated edit to `project-profile.yaml`. If the project
  is double-host, connect this to the `cross_model.policy` turn right after — a double-host
  project should also set `options.cross_model: true` so Phase 05 calls the runtime opposite
  the active host.
- **`cross_model.policy`** — shared policy is `required | recommended | off`.
  `required` forces the local value on, `recommended` defaults on but permits a local
  opt-out, and `off` forces it off. Record the machine decision in
  `project-profile.local.yaml` as `cross_model.enabled`.
  - If policy is `required`, do not offer `false`. An explicit request for local
    `false` is **BLOCKED** because a local profile cannot weaken shared policy.
- **Effective cross-model behavior** — when enabled, Phase 05 review runs opposite-runtime
  **only when reachable**. `sage doctor` resolves reachability from peer CLI
  availability: claude-host checks `which codex`, codex-host checks `which claude`.
  No third-party tool is required — SAGE calls the peer runtime directly
  (`codex exec` / `claude -p`). If enabling, confirm that `hooks.register`
  should be `[claude, codex]`.
  - **If the user enables cross_model**, confirm the peer CLI is installed:
    - claude-host: `codex` must be on PATH (`which codex`)
    - codex-host: `claude` must be on PATH (`which claude`)
    If the peer CLI is unavailable, `sage doctor` falls back to
    `clean_context_same_runtime` (same model, fresh context). Run `sage doctor`
    to verify the resolved reviewer mode before starting a cycle. If the user
    prefers not to install the peer CLI, that's fine — leave cross_model on with
    the same-runtime fallback, and say so.
  - **Only when cross_model is on**, optionally offer `cross_model.effort` — the reasoning
    effort SAGE passes to the peer CLI for its review. **Unset → `high`.** The vocabulary
    differs per peer and a wrong value is **silently ignored by codex**, so `sage validate`
    and `sage cross-check` reject it:
    - peer `codex` (claude-host): `minimal | low | medium | high | xhigh`
    - peer `claude` (codex-host): `low | medium | high | xhigh | max`
    Ask the user for `cross_model.reviewer.host` and `.model`. The `host` says which peer
    the model was chosen for — model ids belong to one runtime — and the reviewer itself is
    resolved at run time by excluding the host that is actually executing. If the resolved
    peer differs from this `host`, SAGE skips the model and uses the peer CLI default. Show
    that host's `sage models` output first. If the reviewer block is omitted, explain that
    SAGE uses the peer CLI default model.
- **`options.obsidian` / `knowledge_capture`** — shared automation flags and note
  conventions stay in `project-profile.yaml`. Machine availability and the private
  vault path go in `project-profile.local.yaml` as `enabled` and `vault_path`.
  If a vault path is set, explicitly confirm the PDCA boundary
  automation flags:
  - `knowledge_capture.scan_before_dev` — `/sage-plan` runs `sage knowledge scan`
    before leader planning and refreshes `.sage/knowledge_scan.md`.
  - `knowledge_capture.update_after_dev` — `/sage-team` runs `sage knowledge write-back`
    after 06 completion to create/update the vault note and `wiki/log.md`.
  Default to both on unless the user declines; these are explicit host-side steps,
  not hidden background writes.
- **Review loop (`pdca.review_loop`)** — the optional Phase 05 adversarial loop.
  Raise it as its own toggle (default off). Drive it with the **shared interview set**
  in `docs/agent/bootstrap-authoring.md` (§ Review loop + vault interview set) so this
  skill and `sage-profile-modify` never diverge: loop on/off → if on, author
  `lenses` / `max_iterations` / `budget_tokens` / `refuters` / `dry_rounds` /
  `severity_block` / `cross_model` (reuse the `options.cross_model` value already
  settled above — do not re-ask). Each value gets its one-line meaning, per that set.
  Also surface that set's **token-cost note** (each lens = one reviewer subagent per round;
  `refuters` = reviewers per round batched over findings; cross_model adds a peer) so the
  user sets `lenses`/`refuters` knowing the subagent/token impact.
  - **Early completion (conditional)**: only when the loop is **on**, ask the one turn from
    the shared set for `pdca.review_loop.early_completion` (default off). If enabled, author
    `enabled: true` and optionally `minimum_completed_rounds` (engine floor 1). State the gate
    consequence: the approval stays `APPROVED` but carries
    `Review-Assurance: REDUCED_BY_USER_AUTHORIZATION` so a later reader and the CI authority
    can tell it apart. Say plainly that the authorization reason, approver and confirmation
    token always come from the user at close time and are never inferred by a skill.
  - **Vault outputs (conditional)**: only when the loop is **on AND
    `knowledge_capture.vault_path` is set**, ask the one vault turn from the shared set —
    `loop_audit_dashboard` (감사 대시보드) and/or `retro_note` (회고 human-gate 노트).
    Skip this turn entirely when the loop is off or no vault is configured.
- **Fast Cycle (`pdca.fast_cycle`)** — raise this shared-policy toggle in its own
  turn, defaulting to `enabled: false`. If enabled, propose and confirm
  `minimum_rounds` (L2/L3, engine floor 1), `minimum_lenses` (L2/L3, engine floor
  2), and ordered L2/L3 `lenses` from the closed engine vocabulary. Keep
  `reason_required: true`; never offer a switch that disables per-run reasons.
  Explain that actual risk and its verification/acceptance remain unchanged and
  that Fast consolidates physical 01 through 04 into the composite 00.
  - **Standard→Fast conversion (conditional)**: only when Fast is **on**, ask the one turn
    from the shared set for `pdca.fast_cycle.standard_transition` (default off). If enabled,
    `sage fast-cycle convert` may switch a cycle already past Phase 00 to the Fast contract.
    State the gate consequence: the run can reach Fast review minimums without a composite
    plan, so the audit record — not a document — becomes the only evidence of how it entered
    Fast. The conversion writes no document, and its confirmation token, reason and approver
    always come from the user at conversion time and are never inferred by a skill.
  If effective vault capability/path is configured, ask in the vault-output turn
  whether `knowledge_capture.fast_cycle_dashboard` should be enabled. This is a
  shared automation flag; the private path remains local. `sage-init-local` does
  not own or modify `pdca.fast_cycle`.
- **Phase 00 Done Criteria gate (`pdca.base_plan.done_criteria_gate`)** — always ask
  the shared `off | advisory | enforce` question from `bootstrap-authoring.md` during
  first authoring. Recommend `advisory` for a new project, but record the user's actual
  choice. Explain that 01–04 may retain `[ ]`, while APPROVED/06 requires resolved
  criteria and a fresh approval after reasoned replanning. This is shared policy and is
  never delegated to `sage-init-local`.
- **Feedback (`feedback.*`)** — raise as its own toggle, default `enabled: false`. Explain
  in one line: when on, developers can leave `sage-feedback ::` markers in completed-cycle
  code for `/sage-feedback` to resolve later; `!sage-feedback ::` markers additionally block
  starting new implementation over them. If enabled, confirm three values in the same turn:
  `record` (default `false` — leave off for chat-only responses with no audit trail, or turn
  on to persist every resolution), `block_release` (CI blocks a release on unresolved blocking
  markers when it calls `sage feedback --release-gate`), and — **only if `record` is
  true** — `record_target` (`auto` — a vault note only if `knowledge_capture.vault_path` is
  set, `sage` — audit log only, `vault` — vault note required). `record_target` never writes
  anything by itself: `.sage/feedback.jsonl` is written only when `record: true`, and
  `record_target` then decides only whether a human-readable vault note is written in
  addition. Do not present `record_target` as controlling whether the audit log itself is
  written.
- **`options.codegraph` / `codegraph`** — toggle and MCP name.

MCP servers themselves are governed as the `mcp` asset kind
(`docs/sage_harness/mcps/{id}.md`); options here are just the toggles.

## Step 3 — Present for approval

Show both the filled shared profile and the separate local profile, then get explicit approval.
Call out anything you inferred rather than were told, so the user can correct it.

## Step 4 — Handoff + asset generation (ask auto vs manual)

After approval, ask the user how to generate the registration artifacts:

**Auto** — you run the handoff now (`--target` = this project's host runtime; use
`--target both` for cross-model):
```
sage generate --kind hook --write --target <claude|codex|both>
sage generate --kind roster --write                   # only if components[] is set
sage sync-overlays                                    # profile(risk.domains + governance_docs)을 AGENT_GUIDE 라우팅 블록에 반영
sage validate --check --schema --kind all
```

**Manual** — you print the exact commands and let the user/agent run them.

Notes that matter (from bootstrap-authoring.md):
- `--kind hook` default `--target claude`; match it to the host (`--target codex`
  on a Codex project), and use `--target both` for cross-model projects.
- `--kind roster` only **scaffolds** `implementer-<id>` specs; they still need a
  runtime AI render + `extract_agent --register` before they are manifest-registered.
- `sage validate` defaults to `--kind hook`; pass `--kind all` to also check
  agent/skill renders.
- `sage sync-overlays` materializes the AGENT_GUIDE **project routing block** from the
  profile (`risk.domains` + `governance_docs`). Run it after authoring, before `validate` —
  otherwise the block is empty while the profile has entries and `validate` reports drift.
- A `validate` FAIL points at the value/spec to fix — fix and re-run, never bypass.

## Step 5 — Asset additions later (same loop)

To add a hook/agent/skill after bootstrap: interview → author the spec under
`docs/sage_harness/{hooks,agents,skills}/{id}.md` → register. Hooks register
deterministically via `sage generate`; agent/skill specs need an interpretive
runtime render plus extraction/registration.

## Done

Bootstrap is complete when `project.name` is set, the profile reflects the user's
intent, the routing block is synced (`sage sync-overlays`), and `sage validate` passes
(or only WARNs the user has accepted). From
here the normal PDCA phase-first flow applies — author plan docs before any L2/L3
code, or the `pre-implementation-gate` will block.
