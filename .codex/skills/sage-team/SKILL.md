---
name: sage-team
description: "Drive the implementation half of a SAGE PDCA cycle (Phases 03–06) after the plan exists — implementation, deterministic verification, QA, the Phase-05 review (via /sage-review), and completion, with file-ownership boundaries. Resumable. Invoke when the user says /sage-team (Claude) or $sage-team (Codex), 팀 개발, 팀 오케스트레이션, run the team, or after /sage-plan (or /sage-cycle) hands back an ownership map."
---

# sage-team — SAGE PDCA Team Orchestration

Invoke as `/sage-team` (Claude) or `$sage-team` (Codex).

Do not edit this CORE render directly (the write-guard blocks it and `sage install --force` overwrites it).
- overlay: optional `sage/asset_overrides/skills/sage-team.md` has project-local priority over this CORE render and is not shipped by `sage install`; it must not relax AGENT_GUIDE, phase, review, or verification gates (they stay floored by independent oracles). Put broad project rules in profile/conventions and create genuinely new project assets with `/sage-asset`.

This skill takes the plan + ownership map that `/sage-plan` produced and drives
the cycle to completion: implementation → deterministic verification → QA → Phase-05
review → completion report. It is the host-side orchestrator; SAGE still owns every
deterministic gate (`pre-implementation-gate`, `verify-changes`, `sage review-loop`
audit, the 06←05 report←approve backstop). This skill never reimplements a gate — it
makes sure the existing ones are actually invoked.

> **SOFT-ENFORCED, not a gate.** Following `/sage-team` makes the review loop and
> verification non-skippable *within this procedure*. It does NOT close the deterministic
> bypass: a host that skips `/sage-team` and hand-writes a Phase-05 doc with `APPROVED`
> still passes the 06←05 gate. True enforcement (the gate also checking loop-audit +
> test evidence) is a separate hardening step. State this limit if asked.

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

1. `docs/sage_harness/skills/sage-team.md` — authoritative spec: procedure, drift_checks
2. `AGENT_GUIDE.md` — PDCA phases, risk gate, phase-first rule
3. `docs/agent/pdca-templates.md` — phase roles (03 impl+tests, 04 gap+coverage, 05 verdict)
4. `sage/project-profile.yaml` — `components`, `team.core`, `verification`, `pdca.review_loop`

## Gate check

Confirm the profile is bootstrapped (`project.name` non-empty, `risk`/`components` set).
If not → stop: "Profile is not bootstrapped. Run `/sage-init` first."

A plan doc (Phases 00–02) for this cycle must already exist. If none → stop and direct
the user to `/sage-plan` (do NOT silently author the plan here — that is a
different skill's job).

## Resolve the cycle + resume point (presence ≠ completion)

On a resumed session with a user-supplied context packet, first run
`sage context restore --snapshot <path>` and read the generated briefing. A stale
or invalid packet is a hard stop; never use it as unverified advisory context or
claim hidden model conversation state was restored.

Identify the **single cycle** by its plan-doc stem (the feature name `/sage-plan`
used). Require every 00–06 markdown basename and its exactly-one `Cycle-Stem`
declaration to equal that stem. Match every phase doc and audit run to it — ignore stale
docs from other cycles and never use recency as identity. Missing/conflicting/ambiguous
stems are a hard stop.

Read the cycle's document language from Phase 00's `Document-Language:` line before writing
anything. Every phase document you author (03–06) carries that same line and is written in that
language. Phase 00 is the source of truth; if `.sage/cycle.json` disagrees with it, that is a
conflict to raise, not one to resolve by picking a side. If Phase 00 has no such line, the cycle
predates the marker — keep writing in the language the existing documents already use and do not
introduce the marker on some documents only. Talking to the user in another language is fine and
separate; see `docs/agent/language-policy.md`.

Only after that hard stop passes, reconcile the machine-local declaration before
choosing a resume point:

```bash
sage cycle show
sage cycle set <stem>   # only when show does not report this verified stem
```

Do not set a stem before identity validation. If `SAGE_CYCLE_STEM` wins, surface the
CLI warning and require `unset SAGE_CYCLE_STEM` when it names another cycle.

Then find the first incomplete stage using **evidence anchors**, not bare
file existence:

- **03 complete** = pre-code ownership/checklist exists, implementation files exist for
  the owned components, acceptance trace is recorded, AND `verify-changes` evidence
  (build/test results) is recorded in the 03 doc.
- **04 complete** = gap + qa coverage + acceptance evidence context recorded in the 04 doc.
- **05 state machine** (resolve from the 05 doc + `sage review-loop` audit for this cycle):
  - `05_started` — 05 doc records a `run_id` and `.sage/loop_audit.jsonl` has that run
    **open (not closed)** → resume by re-entering the review loop (do not restart it).
  - `05_closed_nonapproved` — the matching run is **closed with result ≠ APPROVED**
    (e.g. BLOCKED), or the 05 doc verdict is REJECTED/BLOCKED → resume by reworking
    (back to step 3) or stay blocked. **Do not enter 06.**
  - `05_approved` — the 05 doc has the `APPROVED` marker AND the matching `run_id` is a
    **closed run with result APPROVED** (audit `integrity_issues` clean: no orphan/dup)
    → the only state that allows 06.

Start at the first stage whose anchor is absent. A doc that merely exists without its
anchor is treated as incomplete (conservative).

### Phase 00 Done Criteria at every boundary

Before entering each remaining phase, re-open the exact-stem Phase 00. `[ ]` is normal
progress, `[x]` means demonstrated completion, and `[~]` resolves only with a same-line
`(N/A: reason)`. After Phase 03 implementation and Phase 04 verification, update only
the criteria proven by that evidence. Do not mark an item complete because work merely
started.

Changing criterion text/scope, adding or deleting an item, reverting completion, or
excluding an item is replanning. Increment `Done-Criteria-Revision`, append the required
Changed-At/Reason/Affected-Phases/Summary record, then rerun affected phases in order and
stamp each affected document with the current revision. A normal `[ ]` to `[x]` progress
update is not replanning. Any prior Phase 05 approval becomes stale; run a new review loop.
All criteria must resolve before APPROVED and must still resolve with the fresh hash-bound
approval immediately before Phase 06.

## Step 1 — Implementation (Phase 03)

Before source edits, open/update the 03 doc with file ownership, implementation
checklist, verification plan, and Phase-01 acceptance IDs. Then dispatch implementers by
ownership from `profile.components` / `team.core`. Each implementer edits ONLY its
component's paths (file-ownership boundary; the integration point is stated in the plan
doc).

Before dispatch, resolve `runtime.active_host` and each component's
`runtime_models.<active_host>`. Pass an explicit selection to the host delegation API
when that API supports model pinning. If it cannot pin the model, state
`MODEL_SELECTION_DEGRADED` and the actual host default; never report the configured model
as used merely because it appears in the profile.

> **No "scaffolding is exempt" shortcut.** Root scaffolding / config / glue files (build
> files, lockfiles, `local.properties`, `.gitignore`, `settings.*`) ARE source edits —
> they are classified by `profile.risk` globs, not by component ownership. "belongs to no
> component" ≠ "not implementation". Write 03 first regardless. For L2/L3 this is now a
> hard gate: `pdca.pre_implementation_required` lists `03`, so a source edit before the 03
> doc exists is BLOCKED — do not try to route around it.

- **Claude host**: spawn implementers as **parallel subagents** (one per component).
- **Codex host**: **sequential** delegation (no parallel-subagent model) — same ownership
  boundaries, one implementer at a time. This is a throughput difference only; the
  procedure, artifacts, and boundaries are identical. State "sequentialized execution,
  semantics preserved."

Each implementer records its files, checklist, acceptance trace, and **unit tests** into
the 03 doc.

## Step 2 — Deterministic verification

Invoke verification per `profile.verification` for the change's risk level:
```
sage_harness/verify-changes.sh        # build / test / lint at the risk gate
```
SAGE owns the policy, gate levels, and result format (`verification-protocol.md`); this
skill only triggers the run. (`pre-implementation-gate` is the edit/phase hook — it is
**not** the verification executor; do not conflate them.) Record results in the 03 doc.
If the gate is red, STOP — do not advance to review on a failing build/test/lint.

## Step 3 — QA (Phase 04)

Invoke the `qa` agent: assess design↔implementation gap + **test coverage** (covered /
not covered / intentionally excluded; recommended additional scenarios) + acceptance
evidence (`PASS`/`FAIL`/`NOT TESTED`/`N/A`). Record in the 04 doc. **No verdict here** —
that belongs to Phase 05.

## Step 4 — Review (Phase 05) — via /sage-review (mandatory)

Hand off to **`/sage-review`** (`$sage-review` on Codex). Do NOT hand-write a Phase-05
doc. `sage-review` resolves the review mode (cross-model opposite-runtime when reachable,
else clean-context) and, when `pdca.review_loop.enabled` + risk ∈ {L2, L3}, runs the
adversarial find→refute→triage→rework loop, recording every round to
`.sage/loop_audit.jsonl` via `sage review-loop`.

- If the loop's FIND lenses cannot run in parallel (Codex), run them **sequentially** —
  same lenses, same artifacts, same audit; mark "sequentialized execution, semantics
  preserved." Sequentialization must not change ownership, review independence, or the
  recorded outcome.
- The verdict maps to the 05 doc's Final Status (`APPROVED | FAIL | BLOCKED`). Required
  `FAIL` items block APPROVED. `NOT TESTED` also blocks unless an exact active L3
  acceptance waiver preserves the row as residual evidence; never convert it to PASS.
  On BLOCKED, STOP — no completion until cleared.
- Ensure the 05 doc carries a `Loop-Run: <run_id>` line (sage-review writes it). The 06←05
  audit gate (`pdca.review_loop.report_gate_enforce`) binds the report to that closed
  APPROVED run; without it, Step 5 is blocked (enforce) or warned (advisory).

## Step 5 — Completion (Phase 06)

**Before the 06 report is written, reconcile this cycle's risk tier.** Re-classify the actual
changed paths/content with `profile.risk`, take `max(00's declared tier, that classification)`,
and if it exceeds 00's `Risk Level` line, raise that line to match. Doing this *before* 06 keeps
the 06 acceptance-evidence report gate (which reads the tier via `_cycle_risk`) and the later
knowledge write-back from acting on a stale L1 when the work turned out L2/L3. The
pre-implementation gate enforces this throughout implementation: make the Phase 00
risk-only edit first, then retry the source or later-phase write. Do not combine both in
one patch because the gate validates the pre-write snapshot.

Only when the cycle is `05_approved` (see resume state machine), the `leader` writes the
06 completion report. The existing 06←05 gate enforces this deterministically, and
`verification.acceptance.report_gate_enforce` can warn/block if 04 acceptance evidence is
missing or unresolved — never bypass it.

The 06 doc must declare `Loop-Run: <run_id>` at its top — copy the `run_id` from the
APPROVED 05 doc (and add `Source-05: <05 doc path>`). The Stop-time retro gate reads this
line to confirm `sage retro --check` ran for that run; if 06 omits it the retro gate
cannot bind the report (advisory warns, enforce blocks). This survives session resume
because the report carries its own cycle id rather than inferring it from disk.

After 06 is written, run the configured knowledge write-back when it is enabled:

1. If `knowledge_capture.update_after_dev: true` and `knowledge_capture.vault_path`
   is set, create `.sage/knowledge_writeback_summary.md`. This note is the durable,
   cross-project distillation that outlives the workspace — **not** a build log. Synthesize
   (do not transcribe) from PDCA 00~06, and **write to the depth of the vault's own hand-written
   deep notes**: open two or three existing notes of the same prefix and match that bar. The
   goal — someone reading the vault alone, months later, sees *what* was built, *which* parts
   changed, *where* a future bug/improvement is likely, and *how* it was verified.

   **Depth scales with this cycle's risk tier.** Read the `Risk Level: Lx` line from this cycle's
   00 base plan — that is the durable per-cycle tier (it survives session resume, unlike your
   in-session memory), already reconciled to the actual work at the start of Step 5. A missing,
   placeholder, malformed, or duplicate declaration must be repaired before this phase; do not
   infer or default the tier. (`profile.risk` is only the glob/keyword *mapping* that yields a tier,
   not the tier itself — do not read a per-cycle tier out of it.)
   - **L1 (only when the change is plainly trivial):** a few sentences suffice — what changed and
     the one thing to remember. Do not pad; skip the section skeleton below, and pass
     `--skip-structure-check` on the write-back command so the advisory skeleton check does not WARN
     on a note that intentionally has none.
   - **L2 / L3 (meaningful work):** write a deep note using the vault guide's own headers/callout
     syntax. When `note_convention.required_structure` is configured (non-empty) for this prefix,
     the CLI's advisory check WARNs on any missing marker — treat a warning as unfinished; when it
     is unset (the default `{}`), no marker check runs and only the host depth self-review (step 3)
     remains. Either way the check confirms only that the skeleton *markers exist*, never that each
     section is deep enough — that judgment is the host depth self-review (step 3). Cover each as its
     own section:
     1. **핵심 Takeaway (리드 콜아웃)** — the vault's top callout (e.g. `> [!abstract]`): 2–3 lines,
        what was built + the single most important outcome/lesson. Never blank.
     2. **배경 · 근본 원인** — why the work existed: the problem, the root cause, and (for a bug) the
        falsification that confirmed it (from 00/02).
     3. **설계 결정** — the alternatives weighed and why this path won; module boundaries, each
        module's responsibility, and dependency direction (from 00/02).
     4. **변경 내역** — the actual files/functions touched, **named explicitly** (`파일:함수:line`).
        Represent the code *proportional to change volume*: a small change → the actual before/after
        snippet; a large change → pseudocode or a prose walkthrough anchored by `파일:함수:line`.
        Always say *where*; never only describe in the abstract.
     5. **검증** — how it was proven: tests added/run and their results, Loop A findings + accepted
        rework and *why* it mattered (from 04/05), manual checks.
     6. **재발 방지 · 향후 · 잔여 리스크** — L3 security / risk posture and mitigations (from 00 risk +
        05 security), where a future bug or improvement is likely, and what was left undone.
     7. **관련 문서** — cross-links. Vault notes as `[[...]]` wikilinks. The PDCA docs this cycle
        produced (00~06) and any `plan_docs` as **plain filenames only** — no wikilink, no path —
        because they die with the workspace; a later reader still learns which file to look for, and
        a missing file is tolerated.
2. **Match the vault's own authoring guide, then run.** Before writing, check the vault
   root for an authoring guide — first found of `CLAUDE.md` / `AGENTS.md` / `GEMINI.md` /
   `AGENT_GUIDE.md`. If present, follow its note conventions: pick domain-appropriate **tags**
   and the right **prefix** (e.g. `BUG`/`FEAT`/`PLAN`/`TECH`), and format the body per its
   rules (e.g. a `[!summary]` callout). Pass them to the CLI; **omit the flags to fall back to
   defaults** when the vault has no guide:
   ```bash
   python -m sage knowledge write-back --title "[cycle stem]" --prefix <PREFIX> --tags "<t1,t2,…>" --summary-file .sage/knowledge_writeback_summary.md --append-log
   ```
   (The CLI still owns deterministic placement — path, `tags_style`, index/log append — from
   the profile; the guide only informs the *judgment* values you pass, per SAGE's determinism
   boundary.)
3. Record the command output in 06. If it reports `N/A` or fails, record the exact
   skipped/failed reason; do not claim vault capture completed. **Then run the host depth self-review
   checklist — the marker check cannot judge depth, so this is where hollow sections are caught (it is
   your own review, not an independent human gate):** for an L2/L3 note, re-open the written note and
   confirm each skeleton section carries *real content* — not an empty header the advisory marker check
   would still pass. Specifically verify 변경 내역 names actual `파일:함수:line` and 검증 states concrete
   test results. If any section is a hollow placeholder, rewrite and re-run before declaring the cycle
   done. **Then attest the outcome in the 06 header metadata block (the top block with `Loop-Run:` /
   `Risk Level:`, before the first `##` heading) as a deterministic line: `Depth-Self-Review: performed`
   for an L2/L3 note, or `Depth-Self-Review: skipped` for an L1 note.** The Stop-hook
   `writeback_depth_gate` (`pdca.writeback.depth_review_gate`) reads that line — an L2/L3 06 with no
   `performed` attestation warns or blocks at session end, so an unreviewed shallow note cannot silently
   close the cycle. Write `performed` only after you actually re-read the note and each section holds
   real content; the line attests the review happened, not merely that headers exist.
4. **Planning-interview note (if it exists).** If `.sage/plan_interview.md` exists (a
   planning interview ran in `sage-plan`) AND vault is enabled, capture it as a **separate**
   vault note via the same single write path. This is a raw-requirements note, **not** a deep tech
   note, so pass `--skip-structure-check` — the deep skeleton does not apply to it:
   ```bash
   python -m sage knowledge write-back --title "[cycle stem] 기획 인터뷰" --prefix <PREFIX> --tags "<t1,t2,…>" --summary-file .sage/plan_interview.md --append-log --skip-structure-check
   ```
   The **same vault-guide rules apply** — derive the prefix/tags for this note from the guide
   too (omit the flags for defaults when no guide). This preserves the user's raw requirements
   intent durably (like the prior 대화 기록 notes), kept separate from the distilled tech
   summary. Skip when the file is absent or no vault.

> **Single write path (do NOT freelance).** The vault note, `wiki/log.md`, and any index
> are written ONLY by `sage knowledge write-back` (it resolves the vault path, note convention,
> tags style, and index from the profile). Never hand-write a vault note or use an obsidian
> MCP to create cycle notes — that produced the 6th-test misplaced `<project>/sage/*.md`.
> Likewise the loop audit (`.sage/loop_audit.jsonl`) is written ONLY by `sage review-loop`
> open/round/close — never append or edit it by hand (the gate validates record sequence and
> rejects hand-written rounds).
>
> **One allowed hand-edit (guide-driven):** `write-back` appends a single wikilink *line* to
> `log.md`/index. If the vault's authoring guide keeps a **history table** there (a row per
> note), add that row yourself — the CLI's line-append does not produce table rows. This
> exception is limited to the **existing hub table in log/index**; never hand-create notes or
> place them outside the vault-resolved path.

After write-back, capture the cycle's learning as an asset-improvement proposal (Loop C —
advisory, does not auto-apply; closes the 6th-test gap where loop findings never fed back
into framework assets):

```bash
python -m sage retro --run-id <RUN_ID> --feature <cycle-stem>   # --vault if retro_note enabled
```

Always pass `--feature` (the plan-doc stem). Without it the note title is derived from the
sole Phase-05 doc, or falls back to the run_id — which reads as a random hash and hides
which cycle the note belongs to.

**A note is only written when the vault is enabled** (`knowledge_capture.vault_path` plus
`retro_note: true`, or an explicit `--vault`). Both are off by default.

- **No note path printed** → the vault is disabled. Record `retro note skipped: vault
  disabled` in 06 and stop here; there is nothing to fill or check. The Loop C gate
  (`pdca.retro.report_gate_enforce`) is inactive when `retro_note` is off, so skipping is fine.
- **A note path printed** → it is written **empty on purpose**. `retro` gathers the evidence
  deterministically and leaves distillation to you (the CLI has no LLM). Open that note, run
  the distiller prompt from its `<details>` block over the evidence, and write:
  - `## 요약` — 1–2 human-readable lines on what this host systematically missed;
  - `## 제안` — the JSON array, one object per pattern, each with a `target` of
    `profile`/`hook`/`agent`/`skill` and a concrete `proposed_change`.

  Then verify, and fix whatever it reports:

  ```bash
  python -m sage retro --check "<note path printed above>" --run-id <RUN_ID>
  ```

  It exits non-zero while `## 요약` is still the blank placeholder, `## 제안` does not parse,
  a proposal lacks a valid `target`/`proposed_change`, or the note belongs to a different
  run. An empty proposal array passes only with a written summary — if you conclude there
  were no systematic patterns, say so and why. This `--check` is **mandatory when
  `report_gate_enforce` is advisory/enforce**: without a passing `--check`, the session-end
  Stop hook records the cycle as unfinished and (under `enforce`, claude host) blocks stopping once.

Never set `approved: true` yourself; that is the human gate. Record that retro ran (or why
it was skipped) in 06. Applying any proposal is a separate human-gated step.

## Done

When `context_management.compaction.enabled: true`, run
`sage context snapshot --cycle-stem <stem> --phase <id>` after each completed
03, 04, 05, and 06 boundary. A boundary requires that phase's evidence anchor, not
mere file presence. Include every packet path in the final artifact inventory.

The cycle is complete when 06 exists and reflects an APPROVED Phase 05 with a clean,
closed loop-audit run for this cycle, and **both** closing captures are accounted for in 06
(neither may be silently omitted):
- knowledge write-back has completed or 06 records a concrete skipped/failed reason;
- `sage retro` has run, and — when it wrote a note — that note passes
  `sage retro --check … --run-id <RUN_ID>`; otherwise 06 records why it was skipped (vault
  disabled counts). A note left as the blank template does not count as retro having run.

After every required write-back, retro check, phase-boundary snapshot, and closing gate
check succeeds, release the file declaration immediately before reporting `## Done`:

```bash
sage cycle clear
```

Do not clear on red verification, `BLOCKED`, or `FAIL`; the unfinished cycle must remain
resumable. Relay the command output. When an environment declaration still wins, tell the
user to run `unset SAGE_CYCLE_STEM` because `clear` removes only `.sage/cycle.json`.

Report to the user:
- per-phase outcome + the recorded review `run_id`;
- generated artifact inventory: plan docs, code/config files, vault notes, loop-audit
  dashboard, retro note, and any installed/generated SAGE assets — name each by path;
- verification commands and results;
- a summary of the retro proposals (pattern → target → proposed change) so the user can
  judge them without opening the note;
- pending human action. If a retro human-gate note was created, explicitly ask the user to
  review the note and set `approved: true` before running `sage absorb --from-retro`. Do
  not imply retro proposals were applied automatically.

---

> This skill is a **CORE framework bootstrap asset**: hand-shipped by `sage install`,
> NOT a manifest-tracked skill (no claims file, no render hash, not gated by
> `sage validate`/`sage asset-check`). Its reference spec lives at
> `docs/sage_harness/skills/sage-team.md`. To change it, edit the framework template,
> not via `sage generate`. Deploy location is runtime-specific: Claude reads it from the
> repo (`.claude/skills/sage-team/`); Codex reads it from the user-global skills dir
> (`$CODEX_HOME/skills/sage-team/`).
