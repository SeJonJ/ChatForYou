---
id: pre-phase4-checklist-gate
kind: hook
runtime_bindings:
  claude: { event: PreToolUse, matcher: "Write|Edit|MultiEdit", timeout: 10 }
  codex: { event: PreToolUse, matcher: "apply_patch", timeout: 10 }
---
## intent
When a 04-analyze document is written — the signal that Phase 3 is turning into Phase 4 — block
if the feature's 03-implementation or component plan_docs checklists still hold an unchecked
item (`- [ ]`). Exit 2 blocks; exit 0 passes, including the warning when no 03 is found.

## runtime_bindings
- claude: { event: PreToolUse, matcher: "Write|Edit|MultiEdit", input: tool_input.file_path }
- codex:  { event: PreToolUse, matcher: "apply_patch", input: per file block — Add and Update
  targets; a Move is represented by its destination only, excluding the source so a move-out is
  not misread; Delete is excluded }
- output: block = message + exit 2 on stderr for both hosts, the channel a host reads a block
  reason from; warn and ok = message + exit 0 plus hookSpecificOutput.additionalContext, both hosts.

## canonical — an IO-bound gate with a two-stage pure core
sage_harness/hooks/pre_phase4_checklist_gate_core.py
- `plan_reads(event, profile) -> {base, globs, exact}` produces the read candidates with no
  filesystem access.
- The adapter builds an fs_snapshot from those globs (glob_results plus files, root-relative).
- `decide(event, profile, snapshot) -> decision` reaches the gate verdict with no filesystem or
  clock dependency.
- The core is fully pure: every IO is injected by the adapter as a snapshot, which keeps replay
  and drift comparison straightforward.

## adapter_contract
- contract_version: "1"
- Standard event: { hook_id, hook_event_name, runtime, session_id, changes:[{path(rel), op}] }
  op: claude = write; codex = add | update | move. A Move contributes its destination only,
  since the source is not where the document comes into being; Delete is excluded.
- fs_snapshot: { glob_results: {glob: [path...]}, files: {path: text|null} }, root-relative paths.
- decision: { kind, status(block|warn|ok|skip), exit_code, base, total_unchecked, evidence[], message_key }
- Adapter responsibilities: extract input (file_path or apply_patch), run the fs adapter
  (glob and read into a snapshot), render output, and bind paths across hosts.

## profile_bound (of the eight categories)
- phase4_trigger_glob: "*plan_docs/04-analyze/*.md"
- checklist_scan_targets: [{label, glob, is_impl?}] — 03-implementation plus component plan_docs.
- suffixes: the naming of PDCA artifacts — the framework default (DEFAULT_SUFFIXES), overridable
  by the profile.

## reverse_extract classification
- structural_io_adapter: a single file_path versus apply_patch file blocks, with a Move
  represented by its destination.
- output_adapter: the WARN and OK render — both hosts use hookSpecificOutput and only the wording
  differs per runtime — and the block channel.
- token_adapter: the PROJECT_ROOT env var and paths.
- profile_bound: trigger, targets and suffixes.
- algorithm (shared, core): base extraction with repeated suffix stripping, find_match (exact
  first, then prefix in both directions), the unchecked-item scan, and the verdict.
- **No algorithm_delta** — claude and codex run the identical algorithm.
- read_error: tracked in evidence only; it does not feed the block verdict, preserving the
  original behavior.

## tests
sage_harness/hooks/tests/test_pre_phase4_checklist_gate.py (15 PASS)
- Core against an in-memory snapshot: ok, warn, block for both 03 and backend, suffix handling,
  exact-match precedence, read_error.
- Adapter against a temp tree: claude and codex produce the same exit for block and ok, a 04
  document created by a Move triggers the gate, and the block reason lands on the right channel.
