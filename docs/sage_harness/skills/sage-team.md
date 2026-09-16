---
id: sage-team
kind: skill
# CORE skill (neutral). Project specifics come from profile, not this spec.
# CORE framework bootstrap asset: hand-shipped by `sage install`, NOT manifest-tracked.
# The manifest/claims/validate loop is reserved for project-authored skills
# (spec + claims + render hash) created via the generate/extract flow.
---
## intent
Orchestrate a full PDCA cycle after the plan exists: drive implementation, deterministic
verification, QA, the Phase-05 review (via sage-review), and completion, honoring file
ownership. SAGE owns the deterministic gates; this skill only ensures they are invoked.

## when_to_use
- After `sage-plan` (or `/sage-cycle`) produced a plan + ownership map, to run the cycle to completion
- When the user says "/sage-team" (Claude), "$sage-team" (Codex), "팀 개발",
  "팀 오케스트레이션", "run the team", or asks to drive the team through implementation→review

## procedure
0. Resolve the conversation language once: an explicit `--lang ko|en` on this skill's
   invocation, then `interface.language` in `sage/project-profile.local.yaml`, then `ko`.
   Every question, proposal, progress note, warning and summary uses it; machine values
   and Phase 00–06 `Document-Language:` prose do not. Document prose includes section headings
   and list labels, except `## 5. Done Criteria` and `## 6. Done Criteria Revision Log`, which a
   parser reads by their exact string. See `docs/agent/language-policy.md`.
1. Read `sage/project-profile.yaml` — confirm bootstrapped; confirm a plan doc (Phases
   00–02) for this cycle exists. If not bootstrapped → direct to `/sage-init`; if no plan
   → direct to `/sage-plan` (do not author the plan here).
2. Resolve the cycle by its plan-doc stem and find the resume point by evidence anchors,
   not file presence or recency. Require every 00–06 basename and its single `Cycle-Stem`
   declaration to equal that plan-doc stem; ambiguity is a hard stop. 03 complete = pre-code checklist + impl + recorded verify evidence;
   04 complete = gap + qa coverage + acceptance evidence context; 05 state ∈ {started (open run), closed-nonapproved, approved
   (APPROVED marker + matching closed APPROVED run, audit integrity clean)}. Only
   `05_approved` permits Phase 06. Start at the first stage whose anchor is absent.
   On a resumed session with a user-supplied context packet, first run
   `sage context restore --snapshot <path>` and read the generated briefing. Reject a
   failed/stale packet instead of using it as advisory context.
   Read the cycle's document language from Phase 00's `Document-Language:` line and author
   every 03–06 document with that same line, in that language. Phase 00 is the source of
   truth; disagreement with `.sage/cycle.json` is a conflict to raise, not one to resolve by
   choosing a side. A cycle with no marker predates it — follow the language the existing
   documents use and do not mark only some of them.
   Before every 01–05 phase boundary, re-open Phase 00 and inspect its exact Done Criteria:
   `[ ]` is normal progress, `[x]` is demonstrated completion, and `[~]` is resolved only
   with a same-line `(N/A: reason)`. Update only criteria actually satisfied by the completed
   work. A text/item/scope change is replanning: increment `Done-Criteria-Revision`, record
   Changed-At/Reason/Affected-Phases/Summary, rerun each affected phase in order, and stamp
   those phase documents with the current revision. Normal `[ ]`→`[x]` progress does not
   increment the revision. Any earlier APPROVED 05 becomes stale and must be reviewed again.
3. Implementation (03): before source edits, open/update the 03 doc with ownership,
   checklist, and Phase-01 acceptance IDs. Root scaffolding/config/glue (build files,
   `local.properties`, `.gitignore`, `settings.*`) are source edits too — classified by
   `profile.risk`, not component ownership; no "scaffolding is exempt" shortcut. For L2/L3
   this is a hard gate (`pre_implementation_required` includes `03`): a source edit before
   03 exists is BLOCKED. Then dispatch implementers by ownership (Claude = parallel
   subagents, Codex = sequential, semantics preserved). Resolve each component's
   `runtime_models.<active_host>` and pass it when the delegation API supports model
   pinning; otherwise report `MODEL_SELECTION_DEGRADED` and the actual host default.
   Never claim the configured model ran without execution evidence. Each edits only its
   component paths and records files, checklist, acceptance trace, and unit tests into 03.
4. Verification: invoke `sage_harness/verify-changes.sh` per `profile.verification` at the risk
   gate. SAGE owns policy/gates/result format; this skill only triggers the run
   (pre-implementation-gate is not the executor). Record results in 03; stop if red.
5. QA (04): invoke the qa agent for design↔implementation gap + test coverage +
   acceptance evidence (`PASS`/`FAIL`/`NOT TESTED`/`N/A`). No verdict.
6. Review (05): hand off to `/sage-review` (never hand-write 05). It records the loop to
   `.sage/loop_audit.jsonl`, resolves cross-model, and always blocks APPROVED for `FAIL`.
   `NOT TESTED` also blocks unless an exact active L3 waiver preserves it as residual
   evidence; never convert it to PASS. All Done Criteria must be resolved before APPROVED.
   On BLOCKED, stop.
7. Completion (06): **before writing 06, reconcile the risk tier** — re-classify actual changed
   paths/content with `profile.risk`, take `max(00's tier, that)`, and raise 00's `Risk Level` line
   if it grew. The pre-implementation gate enforces this throughout implementation: update only
   Phase 00 first, then retry the source or later-phase write; a combined patch is checked against
   the pre-write snapshot and blocked. Doing it before 06 keeps the 06 acceptance-evidence report
   gate and write-back off a stale L1. Then, only when `05_approved`,
   the leader writes 06. Re-open Phase 00 first and require all Done Criteria resolved plus
   a fresh 05/Loop approval bound to the current Phase 00 hash. The 06←05 gate enforces this deterministically. The 06 doc must declare
   `Loop-Run: <RUN_ID>` at its top (copy the run_id from the APPROVED 05 doc; add `Source-05: <05 doc
   path>`) — the Stop-time retro gate reads this line to bind the report to its cycle, so it survives
   session resume. Omitting it leaves the retro gate unable to bind (advisory warns, enforce blocks).
8. Knowledge write-back: if `knowledge_capture.update_after_dev: true` and
   `knowledge_capture.vault_path` is set, write the final cycle summary to
   `.sage/knowledge_writeback_summary.md` — a durable cross-project distillation
   (synthesize, do not transcribe), written to the depth of the vault's own hand-written deep
   notes of the same prefix. **Depth scales with this cycle's risk tier** (read the `Risk Level: Lx`
   line from the 00 base plan, already reconciled to the actual work in step 7; `profile.risk` is
   only the glob mapping that yields a tier, not a per-cycle tier; missing, placeholder, malformed,
   or duplicate Phase 00 declarations must be repaired before completion, not inferred): L1 (only when plainly trivial)
   → a few sentences (what changed + the one thing to remember) and pass `--skip-structure-check` so
   the advisory does not WARN on an intentionally skeleton-less note; L2/L3 → a deep note in the
   vault guide's own headers/callout syntax covering
   핵심 Takeaway (lead callout), 배경·근본 원인, 설계 결정, 변경 내역 (name `파일:함수:line`; code
   proportional to change volume — small=before/after snippet, large=pseudocode/walkthrough), 검증
   (tests + Loop A rework + reasoning), 재발 방지·향후·잔여 리스크, and 관련 문서 (vault notes as
   `[[...]]`; PDCA 00~06 and `plan_docs` as plain filenames only — no wikilink, deletion-tolerant).
   When `note_convention.required_structure` is configured (non-empty), the CLI's advisory check
   WARNs on any missing marker but verifies **marker existence only, not section depth** — treat a
   warning as unfinished; when it is unset (default `{}`) no marker check runs and only the host
   depth self-review remains. First check the vault root for an authoring guide
   (`CLAUDE.md`/`AGENTS.md`/`GEMINI.md`/`AGENT_GUIDE.md`, first found) and follow its note
   conventions — choose tags/prefix and body format per it — then run `python -m sage knowledge write-back --title "<cycle-stem>" --prefix <PREFIX> --tags "<t1,t2,…>" --summary-file .sage/knowledge_writeback_summary.md --append-log` (omit --tags/--prefix for defaults when no guide).
   Record the output or skipped reason in the completion report. **Then run the host depth self-review
   checklist (the host's own review — the marker check cannot judge depth, so this is where hollow
   sections are caught):** for L2/L3, re-open the note and confirm each section has real content
   (변경 내역 names actual `파일:함수:line`, 검증 states concrete results) — not an empty header the
   marker check still passes; rewrite and re-run if hollow. **Then attest the outcome in the 06 header
   metadata block (the top block with `Loop-Run:`/`Risk Level:`, before the first `##` heading) as a
   deterministic line: `Depth-Self-Review: performed` for L2/L3, or `Depth-Self-Review: skipped` for
   L1.** The Stop-hook `writeback_depth_gate` (`pdca.writeback.depth_review_gate`) reads that line — an
   L2/L3 06 with no `performed` attestation warns/blocks at session end, so an unreviewed shallow note
   cannot silently close the cycle; write `performed` only after genuinely re-reading the note. This is an explicit
   host step, not hidden automatic mutation. **One allowed hand-edit only:** if the vault's
   guide keeps a history *table* in `log.md`/index, add that row by hand (CLI appends a line,
   not a row) — limited to that existing hub table; never hand-create notes and never write
   outside the vault-resolved path. If `.sage/plan_interview.md` exists (a planning interview
   ran) and vault is enabled, also capture it as a **separate** note via the same path (same
   guide-derived prefix/tags apply; it is a raw-requirements note, so pass `--skip-structure-check`):
   `python -m sage knowledge write-back --title "<cycle-stem> 기획 인터뷰" --prefix <PREFIX> --tags "<t1,t2,…>" --summary-file .sage/plan_interview.md --append-log --skip-structure-check`.
9. Retro (Loop C): run `python -m sage retro --run-id <RUN_ID> --feature <cycle-stem>`
   — always pass `--feature` (the plan-doc stem) so the note title names the cycle instead of
   being derived from the sole 05 doc or falling back to the run_id. A vault human-gate note is
   written only when the vault is enabled (`vault_path` + `retro_note`, both off by default);
   if no note path is printed, record `retro note skipped: vault disabled` and stop — the Loop C
   gate (`pdca.retro.report_gate_enforce`) is inactive when `retro_note` is off, so skipping is
   fine. When a note is written it is **empty on purpose**: the CLI gathers evidence
   deterministically and leaves distillation to you. So open the note, run the printed distiller
   prompt over the evidence, and fill `## 요약` (1–2 human-readable lines) and `## 제안` (the JSON
   proposal array). Verify with `python -m sage retro --check <note path> --run-id <RUN_ID>`; it
   exits non-zero while the note is still the blank template, a proposal lacks a valid
   `target`/`proposed_change`, or the note belongs to another run. This `--check` is **mandatory
   when `report_gate_enforce` is advisory/enforce**: without a passing `--check`, the session-end
   Stop hook records the cycle as unfinished and (under `enforce`, both hosts) blocks stopping
   once. Never leave the note unfilled and never set `approved: true` yourself. Record that retro
   ran, or why it was skipped, in the completion report — a required completion axis, not optional.
10. Final user report: include the per-phase outcome, review `run_id`, generated artifact
   inventory (plan docs, code/config files, vault notes, loop-audit dashboard, retro note),
   verification commands/results, and any human action still required. Summarize the retro
   proposals inline (pattern → target → proposed change) so the user can judge them without
   opening the note. If a retro human-gate note was created, explicitly ask the user to review
   it and approve `approved: true` before `sage absorb --from-retro`; do not imply the proposal
   has been applied.
11. When `context_management.compaction.enabled: true`, run
   `sage context snapshot --cycle-stem <stem> --phase <id>` after each completed 03,
   04, 05, and 06 boundary and include the packet paths in the artifact inventory.
   Snapshot only after that phase's evidence anchor is complete; a file's mere presence
   is not a phase boundary.

## advisory_scope
- role_boundary: does not implement code; orchestrates leader/implementers/qa/reviewer
- uses: leader, implementer agents, qa agent, sage-review skill, verify-changes, project-profile.yaml
- convention_doc: AGENT_GUIDE.md, docs/agent/pdca-templates.md
- overlay: optional `sage/asset_overrides/skills/sage-team.md` has project-local priority over this CORE render and is not shipped by `sage install`; it must not relax AGENT_GUIDE, phase, review, or verification gates (they stay floored by independent oracles)

## enforcement
- SOFT-ENFORCED: the loop/verification are non-skippable only when this skill is followed.
  It does not close the deterministic bypass (a hand-written 05 + APPROVED still passes
  06←05). True enforcement (gate checks loop-audit + test evidence) is a separate step.

## runtime_bindings
- claude: .claude/skills/sage-team/SKILL.md (repo — Claude Code auto-discovers)
- codex:  $CODEX_HOME/skills/sage-team/SKILL.md or .codex/skills/sage-team/SKILL.md (explicit global or project-local install scope)

## drift_checks
- conformance: procedure step 6 (Phase-05 via sage-review, not hand-written), step 7
  (06 only when 05_approved), step 8 (knowledge write-back when enabled), and step 9
  (retro run with `--feature`; when a note was written, it is filled and `retro --check
  --run-id` is clean — otherwise a recorded skip) must be present. The final report must name
  created artifacts, summarize the retro proposals, and state any pending retro human-gate review.
