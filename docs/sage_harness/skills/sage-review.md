---
id: sage-review
kind: skill
# CORE skill (neutral). Review mode resolved by sage doctor from profile.
# CORE framework bootstrap asset: hand-shipped by `sage install`, NOT manifest-tracked.
# The manifest/claims/validate loop is reserved for project-authored skills
# (spec + claims + render hash) created via the generate/extract flow.
---
## intent
Run Phase-05 independent review: invoke the reviewer agent (same-runtime clean
context or opposite-runtime cross-model, as resolved by sage doctor) and produce
a structured review report for the current implementation cycle.

## when_to_use
- After implementers complete code and qa completes testing (Phase-04 done)
- Before merging or tagging any L3 change (mandatory sign-off)
- When the user says "/sage-review", "phase-05", "독립 검토", "Phase-05 리뷰",
  or "cross-model review"

## procedure
0. Resolve the conversation language once: an explicit `--lang ko|en` on this skill's
   invocation, then `interface.language` in `sage/project-profile.local.yaml`, then `ko`.
   Every question, proposal, progress note, warning and summary uses it; machine values
   and Phase 00–06 `Document-Language:` prose do not. Document prose includes section headings
   and list labels, except `## 5. Done Criteria` and `## 6. Done Criteria Revision Log`, which a
   parser reads by their exact string. See `docs/agent/language-policy.md`.
1. Read the effective shared/local profile — `options.cross_model` resolves the review mode
   (`required` always uses `sage cross-check` and blocks when the peer is unavailable;
   recommended local opt-out or `off` uses clean-context same-runtime via `sage review`);
   `pdca.review_loop` resolves pass
   vs loop. `cross_model.reviewer.model` (optional) and `cross_model.effort` (default
   `high`) are passed to the peer CLI by `sage cross-check`; without reviewer.model the
   peer CLI default model remains in effect. Build one UTF-8 review packet containing the
   same-stem phase documents, implementation files, and verification evidence. Invoke exactly
   one deterministic command: `sage cross-check --packet-file <packet>` for cross-model, or
   `sage review --packet-file <packet> --host <active_host>` for same-runtime. A COMPLETE status
   requires review body plus `REVIEWER_PROCESS`, `REVIEWER_HOST`, `REVIEWER_MODEL`,
   `REVIEWER_ACTUAL`, and `REVIEWER_STATUS` evidence from the CLI.
2. Read `docs/agent/review-protocol.md` — the authoritative output format + loop contract.
3. Choose pass vs loop:
   - `review_loop.enabled` false/absent, or risk L0/L1 → single-pass reviewer invocation.
   - `review_loop.enabled: true` + risk L2/L3 → adversarial review-rework loop (Loop A).
4. Resolve exactly one cycle from the phase-document basename plus its single matching
   `Cycle-Stem` outside fenced code blocks; do not use branch-number substrings or recent-file/mtime fallback.
   Single-pass: invoke the `reviewer` agent in the resolved mode with same-stem plan doc path(s),
   implementer summary (changed files, unit tests), qa findings, and the Phase 01/04
   acceptance matrix/evidence. Report findings verbatim.
5. Loop A (find→refute→triage→rework→terminate): drive rounds per review-protocol.md;
   record each boundary with `sage review-loop` (open with exact `--cycle-stem <stem>`,
   then round/close). After each round call
   `sage review-loop next` for the deterministic continue/stop recommendation. Counters,
   budget, and termination are SAGE-owned (deterministic); judgement (find/refute/rework)
   runs in-host.
   architecture_change at L3 → BLOCKED_ARCH (human escalation), never auto-reworked.
6. Phase 01/04 acceptance IDs must be well formed, unique, and match exactly; missing
   required IDs, unknown Phase 04 IDs, and `FAIL` block `APPROVED`. `NOT TESTED` also
   blocks unless an exact active L3 waiver preserves the row as residual evidence;
   never convert it to PASS. Use `N/A` only with explicit reasoning.
7. Before an APPROVED close, require Phase 00 Done Criteria to be valid and fully resolved,
   and require every affected phase to carry the current `Done-Criteria-Revision`. BLOCK /
   BLOCKED on an L3 change → record in the plan doc and stop (no release until cleared).
   The report←approve hook (06←05 APPROVED) is the deterministic backstop — never bypass it.
8. Early completion (`review_loop.early_completion.enabled` only, and only while `next`
   recommends `CONTINUE`): `close --reason USER_AUTHORIZED_EARLY` with authorization-reason,
   confirmed-by and the confirmation token — all from the user in that turn, never inferred.
   Rounds carry `--survived-by-severity` whose total equals `--survived`. It waives no blocker:
   zero rounds, `severity_block` survivors, architecture escalation, unresolved Done Criteria,
   acceptance FAIL, a required NOT TESTED without an exact waiver, audit damage and binding
   mismatch all still block. Failed build/test/lint has no engine-readable receipt — the agent
   must not close early over one. The Phase-05 document records the four reduced-assurance
   markers with the audit's values, including the `(configured max: <max>)` ceiling; the value,
   not the presence, is what triggers the check.
9. Record the outcome under `## Phase-05 Review` (Loop A: include Review Loop Iterations
   table + audit run_id). Write exactly one `Loop-Run: <run_id>` line outside fenced code blocks in the Phase-05 doc so the
   06←05 audit gate (report_gate_enforce) can bind the report to this closed APPROVED run.
   Copy the exact `Phase00-Hash: sha256:...` printed by an APPROVED `review-loop close`
   into the same Phase-05 document; never calculate or infer a different hash in prose.
   Record exactly one anchored `Final Status: APPROVED | FAIL | BLOCKED` line outside
   fenced code blocks and replace every placeholder before Phase 06 is written in a separate change.

## advisory_scope
- role_boundary: does not implement or modify code; orchestrates reviewer/implementer only
- uses: reviewer agent, project-profile.yaml, review-protocol.md, `sage review-loop` CLI
- cross_model: resolved by policy; required peer failure is BLOCKED, while recommended local opt-out/off uses active-host headless
- review_loop: deterministic gates (counters/budget/termination/audit) SAGE-owned; the loop
  never bypasses the report←approve (06←05) backstop
- convention_doc: docs/agent/review-protocol.md
- overlay: optional `sage/asset_overrides/skills/sage-review.md` has project-local priority over this CORE render and is not shipped by `sage install`; it must not relax AGENT_GUIDE, phase, review, or verification gates (they stay floored by independent oracles)

## runtime_bindings
- claude: .claude/skills/sage-review/SKILL.md (repo — Claude Code auto-discovers)
- codex:  $CODEX_HOME/skills/sage-review/SKILL.md or .codex/skills/sage-review/SKILL.md (explicit global or project-local install scope)

## drift_checks
- conformance: procedure step 1 (profile check incl. review_loop) and step 4/5 (reviewer
  invocation / Loop A drive) must be present
