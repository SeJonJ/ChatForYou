---
id: capture-declared-risk
kind: hook
runtime_bindings:
  claude: { event: UserPromptSubmit, matcher: "", timeout: 5 }
  codex: { event: UserPromptSubmit, matcher: "", timeout: 5 }
---
## intent
Capture an explicit risk level declaration (L0–L3) from the user prompt and store it per session.
pre-implementation-gate reads it and applies the gate at an effective level of
max(detected, declared) — a declaration can only raise the level, so the safety floor holds.

Capture deliberately stays narrow, because a single false positive pins the whole session to that
level. A level mentioned inside a question or a hypothetical is not a declaration, and when two
different levels are declared together the prompt reads as a comparison or an explanation and
nothing is captured. A bare mention with no declarative suffix — a cache level, a filename, a
code block — is not an attempted declaration and does not count toward the ambiguity verdict.
When a prompt is rejected as ambiguous the user is told, because passing over it silently leaves
them working in the belief that they declared a level. A wrong capture is cleared immediately
with `위험도 선언 해제` (or `risk 선언 취소`). Clearing runs through the same sentence filter, so a
question (`해제해야 하나요?`) or a negation (`해제하지 마`) does not trigger it.

At the start of UserPromptSubmit handling this also calls the write-once helper of
`session-start-snapshot`, so that a missing or delayed SessionStart can fall back to the first
prompt as the Phase 06 baseline. It takes an exclusive first-opportunity claim: it never
overwrites an existing baseline, and it never creates a late baseline after the first attempt was
inactive or failed. The claim is not auto-deleted on a TTL, so resuming after a long pause does
not reopen the first opportunity. If the claim file itself cannot be created, late-baseline
blocking cannot be guaranteed, so UserPromptSubmit is blocked with exit 2 ahead of risk capture.
A profile or baseline publish failure after the claim exists preserves the claim and is judged
degraded and fail-closed at Stop.

## runtime_bindings
- claude: { event: UserPromptSubmit, input: stdin JSON(prompt, session_id), output: plain text on stdout }
  UserPromptSubmit promotes plain stdout on exit 0 into context, so no envelope is needed —
  unlike PreToolUse.
- codex:  { event: UserPromptSubmit, input: stdin JSON(prompt, session_id), output: hookSpecificOutput JSON }
- on_fail: capture and noop exit 0. Only a failure to create the shared baseline helper's
  first-opportunity claim exits 2.

## canonical
sage_harness/hooks/capture_declared_risk_core.py  →  decide(event) -> decision
- Shared algorithm: two risk-level regex patterns; rejection on strong question or hypothetical
  markers combined with a sentence-final ending; rejection of multiple declared levels; the clear
  pattern with an immediately following negation excluded; session sanitize; a two-day cleanup
  declaration; state {level, ts, excerpt}.
- Sentence-final endings (`나요`, `가요` and the like) are only recognised at the **end** of a
  sentence. Matching them without that boundary catches ordinary verbs such as `지나가요` and
  throws away a legitimate declaration in the same sentence.
- Sentence splitting does not break on decimals or version notation (`3.5`, `L3.java`). A split
  there separates the hypothetical marker from the level and produces a false positive.
- Order of judgement: clear first, then capture. If a prompt that explains what was wrongly
  captured is itself captured as a declaration, the escape hatch closes.
- The core performs no IO and makes no clock calls; now_utc is injected by the adapter.

## adapter_contract
- contract_version: "1"
- Standard event: { hook_id, hook_event_name, runtime, session_id, prompt, now_utc }
- Standard decision: { kind, action(capture|clear|noop), level, session_key, state_file, state, cleanup, exit_code, message_key }
- action=clear: the adapter deletes state_file, ignoring its absence. The core never touches files.
- message_key=risk_declaration_ambiguous: the ambiguity rejection notice. The state file is left alone.
- Three adapter responsibilities:
  1. Extract input: runtime stdin JSON → the standard event.
  2. Render output: claude plain text, codex hookSpecificOutput JSON. The message text is part of
     the runtime protocol and belongs to the adapter.
  3. Bind paths and env, and perform file IO: CLAUDE_PROJECT_DIR/.claude/logs versus
     CODEX_PROJECT_ROOT/.codex/logs.
- Shared preceding IO: call `_ensure_session_06_snapshot` with the same session_id as the
  SessionStart fallback, write-once. Return that helper's nonzero result as-is so risk capture
  does not continue after a claim IO failure.

## reverse_extract classification (7 categories)
- token_adapter: the PROJECT_ROOT env var name and the log path (`.claude` versus `.codex`).
- output_adapter: plain text versus hookSpecificOutput JSON, and the message text including
  emoji and punctuation.
- algorithm (shared): the level regexes, cleanup and state — promoted into the core.
- noise (normalized away): comments, quote style, import order.
- algorithm_delta / policy_delta / unresolved: **none.** This hook is a pure token and output
  adapter with no drift.

## tests
sage_harness/hooks/tests/test_capture_declared_risk.py
- Core decision parity across three fixtures.
- Capture precision: observed false-positive prompts are not captured, genuine declarations still
  are, and questions, hypotheticals and multiple levels are rejected.
- Clear path: state is deleted on both runtimes, absence is not an error, and clear takes
  precedence over capture.
- Ambiguity notice: shown on both runtimes, and not shown for unrelated prompts or hypothetical
  questions.
- Adapter end-to-end exit, state and output snapshots for claude and codex.
- Timestamp determinism by pinning now_utc through SAGE_NOW_UTC.
- test_hook_runtime.py and test_stop_compliance_report.py cover the UserPromptSubmit baseline
  fallback when SessionStart is absent.
