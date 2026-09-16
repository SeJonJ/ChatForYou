---
id: post-tool-logger
kind: hook
runtime_bindings:
  claude: { event: PostToolUse, matcher: "Write|Edit|MultiEdit", timeout: 5 }
  codex: { event: PostToolUse, matcher: "apply_patch", timeout: 5 }
---
## intent
On completion of Write/Edit (Claude) or apply_patch (Codex), record changes to tracked source
and plan files in the session JSONL log. stop-compliance-report aggregates that log.

## runtime_bindings
- claude: { event: PostToolUse, matcher: "Write|Edit|MultiEdit", input: tool_input.file_path, single }
- codex:  { event: PostToolUse, matcher: "apply_patch", input: multi-file parse of the command body }
- output: none — append only. on_fail: none; always exits 0.

## canonical
sage_harness/hooks/post_tool_logger_core.py  →  decide(event, profile) -> decision
- The core defaults to zero domains. The profile (file_type_map) must be injected from outside.

## adapter_contract
- contract_version: "1"
- Standard event: { hook_id, hook_event_name, runtime, session_id, tool, branch, now_utc, changes:[{path(rel), op}] }
- Standard decision: { kind, action(log|noop), log_file, log_entries:[{ts,tool,file,type,branch,session}], exit_code }
- Profile (second argument): { file_type_map:[{glob,type}] first match wins, skip_untyped, log_schema_version }
- Adapter responsibilities: extract input (claude single file_path → changes[1]; codex apply_patch
  → changes[N]), inject the observed branch and now_utc, load the profile from $SAGE_PROFILE,
  append JSONL, and bind paths across `.claude` and `.codex`.

## reverse_extract classification (8 categories, profile_bound added)
- structural_io_adapter: input extraction — a single Claude file_path versus the Codex
  apply_patch multi-file regex.
- profile_bound (new): file_type_map path globs such as backend source paths are project-declared
  values and belong to the profile. They do not exist in the core.
- token_adapter: the PROJECT_ROOT env var name and the log path (`.claude` versus `.codex`).
- algorithm (shared, core): changes[] → profile classification → log entries, honouring skip_untyped.
- noise (normalized away): comments, quoting, import order.

## unresolved — surfaced drift, needs a human decision
1. **plan-doc glob drift**: Claude uses `*/plan_docs/*` (anywhere), Codex uses `^plan_docs/`
   (root only). They diverge on component plan_docs ({component}/plan_docs). Canonical is
   `*plan_docs/*`, which matches both and fits the intent of covering plan_docs and
   {component}/plan_docs. **Needs human confirmation.**
2. **type=other logging drift**: Claude records unclassified files as type=other; Codex skips
   them. Canonical is to skip (skip_untyped: true), matching the intent of logging tracked types
   only. The Claude behavior is treated as a regression. **Needs human confirmation.**

## tests
sage_harness/hooks/tests/test_post_tool_logger.py (7 PASS)
- Core classification across the six types plus the canonical plan-doc drift; skip_untyped; multi_changes.
- Adapter end to end for a single Claude change and multiple Codex changes; skip parity; behavior parity.
- Determinism is held by pinning now_utc and branch.
