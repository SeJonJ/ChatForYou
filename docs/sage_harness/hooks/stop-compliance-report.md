---
id: stop-compliance-report
kind: hook
runtime_bindings:
  claude: { event: Stop, matcher: "", timeout: 15 }
  codex: { event: Stop, matcher: "", timeout: 15 }
---
## intent
At session end (Stop), aggregate session-{today}.jsonl into a compliance report
(compliance-{today}.md): activity summary, gate compliance (backend plus plan, L3 patterns,
convention guidance), the list of modified files, and policy_results. The report is always
written; separately, when the retro gate is set to enforce and catches an unfinished cycle, both
hosts block termination once. Claude uses exit 2; Codex uses stdout
`{"decision":"block","reason":"..."}` with exit 0. Codex then re-runs the same turn and sends
`stop_hook_active=true` on the next Stop input, and that retry is relaxed to a WARN and passes.
With enforce unset (off by default) everything passes as before.

## runtime_bindings
- claude: { event: Stop, input: .claude/logs/session-{today}.jsonl, output: file write plus the report path as plain text }
- codex:  { event: Stop, input: .codex/logs/session-{today}.jsonl, output: file write, silent on pass, decision:block on a block }
- Block wire: Claude uses exit 2 with the reason on stderr, keeping the report-saved notice on
  stdout. Codex uses exit 0 with a single JSON `decision:block` whose reason carries the
  compliance path. Codex Stop does not allow hookSpecificOutput.additionalContext — alone or
  combined, it is a hook failure.

## canonical — partial extraction, shared aggregation only
sage_harness/hooks/stop_compliance_report_core.py
- `decide(event, profile, snapshot) -> report_model` (pure)
- `render_markdown(report_model) -> str` (pure)
- snapshot = { entries[], today, branch, runtime }, injected by the adapter after reading the JSONL.
- report_model.sections = { header, activity_summary, gate_compliance{issues[]}, modified_files[], policy_results[] }
- Three shared gates: backend_without_plan (WARN), l3_pattern_detected (NOTICE),
  backend_convention_reminder (INFO).

## ⚠️ policy_delta — do not merge; the policy modules stay separate
Policy modules are never auto-merged into the canonical core. They live under policies/ and are
injected only through the policy_results extension slot:

- policies/output_contract_check.py — **Codex only**, since it joins the transcript. Tracked in
  manifest.unresolved as "promote_output_contract_semantics?".
- policies/knowledge_capture.py — **optional** (knowledge_capture, obsidian). N/A when vault_path
  is empty. Not CORE.
- policies/retro_gate.py — **shared across both hosts**, the Loop C gate. Governed by
  `pdca.retro.report_gate_enforce` (off | advisory | enforce), it confirms after the fact at
  session end whether `sage retro --check` ran. Under enforce it blocks the actual termination
  through each host's block wire. It appends success (retro_check_ok), incompletion
  (retro_check_missing) and note-skipped (retro_check_skipped, --no-vault) to the audit trail in
  retro_audit.jsonl. **Because it is enforcement, it is tracked by hook_runtime_hash** — unlike
  the two advisory policies above, its absence would leave the gate silently inert.
  Phase 06 detection is log-based ∪ the session baseline snapshot, which is writer-independent so
  that a 06 written by Bash is still caught. The baseline is recorded at SessionStart, with the
  first UserPromptSubmit as backup. If neither fires, or the baseline is corrupt so that
  writer-independent detection is impossible (including a missing session_id), **enforce is a
  fail-closed BLOCK** while advisory and the retry (stop_hook_active) are a WARN — a possibly
  missed Bash-written 06 is never passed over silently. The snapshot trusts only no-follow
  regular files, so a symlink or a non-regular file also counts as corrupt.
- policies/writeback_depth_gate.py — **shared across both hosts**. Governed by
  `pdca.writeback.depth_review_gate` (off | advisory | enforce), it confirms after the fact at
  session end whether an L2/L3 write-back deep note went through the host depth self-review. It
  checks whether this session's L2/L3 Phase 06 (a missing Risk Level is treated conservatively as
  L2) declared `Depth-Self-Review: performed` in its header meta block. It looks for evidence
  that the self-review ran, not for quality — depth is the skill's and the host's judgement, and
  claiming otherwise would be false assurance. Undeclared means BLOCK on the first Stop under
  enforce, and WARN under advisory or on a retry. It shares the retro gate's Phase 06 detection
  (log-based ∪ SessionStart snapshot) and its one-block limit; when both would block, the two
  messages are combined into a single block. **Because it is enforcement, it is tracked by
  hook_runtime_hash.** When update_after_dev (write-back) is off there is no note to enforce, so
  it is inert (INFO).

## profile_bound
- The L3 pattern has a single source: **reuse profile.risk.l3_filename_globs** with '*' stripped
  and a lowercase substring match. Same source as pre-implementation-gate, which prevents drift,
  though the meaning differs — pre-implementation blocks in advance, Stop audits after the fact.
  Severity and behavior are not shared.

## reverse_extract classification
- Shared core: JSONL aggregation, activity_summary, the three gates, report_model, markdown render.
- token_adapter: the log path (`.claude` versus `.codex`).
- output_adapter: Claude plain text with exit 2 versus Codex decision:block JSON or a silent pass.
- profile_bound: the L3 pattern, from the shared source.
- **policy_delta**: output_contract (Codex only), knowledge_capture (optional) and retro_gate
  (shared, enforcement) — preserved, never merged.

## tests
sage_harness/hooks/tests/test_stop_compliance_report.py plus test_retro_gate.py and test_retro_audit.py
- Core (the three gates, aggregation, an empty log, render), policy-module preservation, and
  adapter end to end on claude and codex.
- Retro gate: per-host block wire on the first Stop under enforce; the stop_hook_active retry
  passes; advisory and off do not block; session scope, standard `**` globs, Loop-Run binding and
  multi-marker skip; Codex decision:block and a silent passing retry; retro_audit ok and missing
  events with state-change dedup.
