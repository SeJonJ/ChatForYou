---
id: pre-implementation-gate
kind: hook
runtime_bindings:
  claude: { event: PreToolUse, matcher: "Write|Edit|MultiEdit", timeout: 10 }
  codex: { event: PreToolUse, matcher: "apply_patch", timeout: 10 }
---
## intent
Classify the risk level (L0–L3) of a source or configuration change and apply the gate before it
lands. Hard-block direct edits to synchronized artifacts and forbidden paths; hard-block L3 (a
high-risk domain from profile.risk) with no plan; confirm the L3 review; confirm the L2 plan.

It also enforces the mandatory PDCA phase structure: when profile.pdca is active, missing
mandatory phases before implementation BLOCK at L2/L3 and WARN at L1; a Phase 00 in the same cycle
with no valid risk declaration, or one lower than the risk of the current change, BLOCKs; and
before a report phase is written, the approve phase must be APPROVED. With pdca inactive the
config is None and the original risk/plan behavior applies, preserving backward compatibility.

When `pdca.base_plan.done_criteria_gate` is on, the three states and the revision of the exact
Phase 00 Done Criteria are checked at every phase boundary, and Phase 06 additionally requires
zero unresolved items and a Phase 05 / Loop approval hash-bound to the current Phase 00.

## runtime_bindings
- claude: { event: PreToolUse, matcher: "Write|Edit|MultiEdit", input: file_path plus content / new_string / edits }
- codex:  { event: PreToolUse, matcher: "apply_patch", input: multi-file command body plus content }
- output: block = message + exit 2 on stderr for both hosts, the channel a host reads a block
  reason from; warn and ok = exit 0 plus hookSpecificOutput.additionalContext for both hosts,
  because plain stdout under PreToolUse only reaches the debug log and is never promoted into
  context.
- The OK and WARN lines name the cycle stem the verdict used and where it came from: a
  declaration, a branch leaf inference, or a phase document. Showing it only for declarations
  would hide exactly the inferred binding that is dangerous. It appears on WARN too, because
  passing without a plan is where the binding is most suspect. With pdca inactive nothing is
  shown, and an L1/L0 pass has no message_key so no line is produced at all.

## canonical — partial extraction; an IO-bound gate with a two-stage pure core
sage_harness/hooks/pre_implementation_gate_core.py
- `classify_risk(event, profile) -> {risk, reason, is_l3_filename, declared_l3, file_short}`
- `decide(event, profile, snapshot, strategy_result) -> {status, exit_code, risk, message_key, safety_degraded?}`
- The core has no filesystem or clock dependency. Plan candidate contents arrive in the snapshot,
  and the L3 review match arrives as an injected strategy_result.

## algorithm_delta — the strategy slot
"Matching the L3 review doc" is a genuinely different algorithm per runtime, so both are preserved:
- sage_harness/hooks/strategies/pre_implementation_gate/claude_grep_first.py (grep-first)
- sage_harness/hooks/strategies/pre_implementation_gate/codex_feature_signal.py (token scoring)
- Common interface: **find_l3_review(signals, snapshot) -> {found, path}**.
- **The canonical choice is `codex_feature_signal`**, adopting the more precise feature-signal
  scoring. It is injected through profile.risk.l3_review_strategy — independent, never hardcoded
  in the engine — and the adapter loads and runs the strategy module from CORE_DIR.
  L3 with a plan and a review match is GATE OK; a failed match is WARN. No plan is still a hard block.
- With no strategy selected in the profile the result is BLOCK plus override-required plus
  safety_degraded — the safe floor, and the default for any other project.

## profile_bound — risk triggers are project-declared values
profile.risk: { desktop_block_glob, l0_pass_globs, l3_filename_globs, l2_path_globs, l1_path_globs,
                l3_content_keywords, l2_content_keywords, plan_glob }
- Canonical matching is **case-insensitive**, since catching more L3 is the safe direction.
  Keywords and file patterns are compared in lowercase.

## PDCA phase enforcement (profile.pdca, independent)
profile.pdca: { enabled, phases[{id,glob}], pre_implementation_required{L1,L2,L3}, report_phase, approve_phase, approve_marker,
                base_plan.done_criteria_gate }
- The adapter scans the phase globs (root-relative, recursive) into
  snapshot.phase_docs = {id: [{path, content, recent}]}.
- `cycle_binding` verifies that the markdown basename actually matching a configured phase glob
  and the `Cycle-Stem` declared exactly once are identical. The declaration must sit outside a
  fenced code block. A file under a phase directory but outside the glob is not a cycle document.
  A recursive `**` is read identically as zero or more path segments at the start, middle or end.
  A full Write or Add must contain the declaration; a partial Edit or Update may use the snapshot
  identity only when it does not touch the existing declaration. A deleted, duplicated or
  corrupted declaration fails closed with no snapshot fallback.
  A phase write determines the current cycle from the changed path or declaration; a source write
  determines it from an explicit event stem or the exact final branch segment. Numeric substrings
  and recent/mtime are never used for cycle identity.
- An explicit event stem has two declaration channels: the `SAGE_CYCLE_STEM` env var and
  `<root>/.sage/cycle.json` (`sage cycle set|show|clear`). The adapter resolves them in the order
  env, then file, then none, and puts `cycle_stem` and `cycle_stem_origin` (`env` / `cli`) on the
  event. On a long-lived branch, inferring from the final branch segment never becomes correct
  again, so this is the normal path. `decide` stamps `cycle_stem`, `cycle_source`,
  `cycle_stem_declared` and `cycle_stem_origin` onto the verdict so that (1) the guidance points at
  the inference and the declaration channel, and (2) the adapter records each use of a declaration
  once per **session, stem and origin** as `cycle_stem_declared` in `.sage/override.jsonl`. Since a
  declared stem can point at an already-closed cycle and carry a change past every gate, a failure
  to record it does not permit a pass (`block_cycle_stem_audit_failure`).
  Display and audit name **only the channel actually read** (`SAGE_CYCLE_STEM 선언` /
  `.sage/cycle.json 선언`). Dropping the origin would let the screen say "declared in the file"
  while env actually won, which is definitively false.
- The declaration file is not committed, because `sage install`'s `/.sage/*` managed block covers
  it, and writing it directly with an editing tool is blocked by the generated-artifact write
  guard — that is the path by which an agent could point at a closed cycle and switch off its own
  gate. When the file exists but cannot be read, from corruption or a schema violation, the gate
  **degrades to "no declaration" but surfaces that fact** through `additionalContext`. If absence
  and corruption were equally silent, truncating a single byte would remove the closed-cycle block
  below.
- Core `decide`:
  1. A missing, conflicting or ambiguous binding is `block_cycle_binding`.
  1b. If the bound stem belongs to a closed cycle — a report phase document exists for that stem
     **and** the approve phase document's `Final Status` equals the approve marker — a new source
     edit is blocked with `block_cycle_closed`. A closed cycle has a complete 00–06 and passed
     every gate, so the point is to stop new work on a long-lived branch from proceeding quietly
     with no plan document. The report document must be stem-bound: counting any 06 at all would
     make every stem look closed the moment the repository contains a single 06.
     **There is exactly one exemption: at least one change, and all of them phase documents** —
     correcting the 05 or 06 of a finished cycle is normal work. The binding origin is not an
     exemption condition: an env declaration died with the shell and was harmless, but a file
     declaration survives across sessions, so a stale one would switch this block off entirely.
     Writing the exemption with `any()` would let a single documentation line mixed into a source
     edit clear the block, and zero changes (an adapter extraction failure) is not an exemption
     either. The block reason distinguishes the binding origin (declared versus inferred from the
     branch); sending a user who is blocked by a stale declaration to the branch would make the
     recovery guidance useless. Eligibility is decided by **computed risk**, not by path tier, so a
     session risk declaration (`declared_max`) that raises an L0 path brings documentation edits
     into scope too.
     If either condition cannot be determined the cycle is not treated as closed — failing closed
     here would block legitimate progress. What the approval condition actually filters out is not
     "a 06 being written" (the report gate already blocks writing a 06 without approval, so a 06
     existing implies approval) but a 06 that predates the gate, an overridden 06, and an approval
     revoked after the fact. This block is overridable, since the escape hatch — declare, or start
     a new cycle — is clear.
  2. Phase 00 risk gate: before Phases 01–06, or any L1/L2/L3 non-phase change, select the current
     stem's Phase 00 exactly and require exactly one `Risk Level: L1|L2|L3` declaration outside a
     fence. Missing, placeholder, malformed, duplicate, unreadable or ambiguous is
     `block_cycle_risk_declaration`. When the current `classify_risk` result — the maximum of path
     and content detection and `declared_max` — exceeds the Phase 00 declaration, it blocks with
     `block_cycle_risk_reconciliation`. A change touching only Phase 00 is exempt so it can repair
     itself, but a change mixing source or a later phase is judged against the pre-write snapshot
     and blocked, so raise Phase 00 first and retry separately. Neither block can be bypassed with
     a generic override; a Phase 00-only repair is the only way forward.
  3. The report←approve gate, ahead of the L0 shortcut: if the current stem's 05 does not carry
     exactly one `Final Status: APPROVED`, block with block_report_without_approval. A placeholder,
     a duplicate status, a fenced code example and a substring `APPROVED` are not approval
     evidence. Modifying 06 and another phase in the same change cannot be verified against the
     pre-write snapshot, so it is blocked and separate writes are required.
  4. Mandatory phases before implementation, on the exact current stem: a gap is
     block_phase_incomplete at L2/L3 and warn_phase_incomplete at L1.
  5. The Done Criteria gate: `off` or a missing key skips for backward compatibility, `advisory`
     WARNs and `enforce` BLOCKs. A Phase 00-only repair does not block itself. Mixing Phase 00 and
     01–06 into the same write makes the post-write revision unprovable from the pre-write
     snapshot, so it BLOCKs or WARNs by mode and requires separate writes. Valid `[ ]` items in
     01–04 only produce a progress WARN and are allowed, while a malformed, duplicate or empty
     section, an invalid `[~]`, and revision-log errors are handled per mode. For a revision 2 or
     later replan, the preceding affected phase documents must declare the current revision before
     the next phase may proceed. Phase 06 requires zero unresolved items, and the exact
     `Phase00-Hash: sha256:...` in Phase 05, the `Loop-Run` in that same document, and the
     `phase00_hash` of the corresponding closed APPROVED loop record must all equal the hash of the
     current Phase 00's full text. The hash normalizes only CRLF and CR to LF and includes
     whitespace, ordering, statuses and the revision log. These blocks are not subject to a generic
     override.
- enabled=false or no phases → `_pdca_cfg` is None → enforcement is skipped, preserving the
  original behavior. Report and phase writes are judged with the same configured glob semantics as
  the snapshot.

### Fast Cycle virtual phase contract

When `pdca.fast_cycle.enabled=true` and the current stem's Phase 00 carries `Cycle-Mode: FAST`, the
adapter parses `## Phase 01` through `## Phase 04` out of the composite 00 and injects them as a
virtual phase snapshot. Physical 01–04 documents are never generated automatically, and if
physical documents for the same stem also exist the ambiguity is not hidden. A source edit
requires a clean active run in the strict-chain `.sage/fast_cycle.jsonl`, the exact stem, and a
bound plan hash. A missing or corrupt audit, and a terminal or mismatched state, are blocked with
`block_fast_cycle_audit` and are not overridable.

A source edit under an active Fast run emits `warn_fast_cycle` without lowering the actual risk.
Only the standard L3 pre-review strategy is replaced by the Fast review contract; the acceptance,
verification and report gates all remain. An exception while reading the Fast audit snapshot
renders as the same fail-closed input as corruption.

## report←approve audit gate (profile.pdca.review_loop.report_gate_enforce)
When review_loop.enabled is set and report_gate_enforce ∈ {advisory, enforce}, then in addition to
the marker check, writing a 06 sends `_audit_gate` through: select the current `Cycle-Stem`'s
single 05 document exactly, read from that same document both the APPROVED marker and exactly one
`Loop-Run: <run_id>` outside a fenced code block, and check that the injected
`snapshot.loop_audit` and its `runs[run_id]` satisfy all of the following — the raw file is
`file_ok≠False`; the run is clean (one open plus at most one close, so no orphan close and no
duplicate or reused open or close; an open-only run is clean=True and is caught instead by the
separate `closed` check); `seq_ok≠False` for round sequence continuity; `chain_ok≠False` for the
per-run strict hash chain; closed; result=APPROVED; and not degraded, meaning the reviewer
intended equals the reviewer actual. A violation is warn_report_without_audit (exit 0) under
advisory and block_report_without_audit (exit 2) under enforce. report_gate_enforce defaults to
advisory when the key is absent, so a project that turned the loop on gets at least a WARN; an
explicit off, or an inactive loop, skips for backward compatibility. Injecting loop_audit is the
adapter's job (`hook_runtime.build_snapshot` → `loop_audit.audit_summary`), keeping the core pure.
Stale pairing is prevented by reading the marker and the Loop-Run from the *same* selected document.

- **Sequence plus strict hash chain**: inside an OS-owned process lock, the writer computes `seq`,
  the per-run `prev_hash` and `record_hash`, and appends one line. `audit_summary.seq_ok` checks
  continuity over [0..n-1]; `chain_ok` verifies the immediate predecessor and each record's
  canonical SHA-256 self-hash. A corrupt JSON or non-object line is not skipped — the whole file is
  surfaced as `file_ok=False`. Older runs stay backward compatible with `chain_ok=None` and are
  linked from the first new record to the record immediately preceding the legacy span. Edits,
  insertions, mid-file deletions and reorderings that do not recompute the hash are detected while
  the v1 fields remain, but this does not authenticate an attacker who recomputes the entire file
  and chain. Likewise, removing all three chain fields from an entire run leaves no external
  provenance to distinguish it from a legitimate legacy run, which is the `chain_ok=None` boundary.
  Closing that gap requires a tip in a separate artifact, a Git baseline, a signed head, or an
  external witness. Problems with the raw file are reported as `file_issues` and a failure of the
  runtime or module summary as `snapshot_error`, so the gate names the real cause.
- **Reviewer degraded**: when an open records an explicit reviewer_requested but the close's
  reviewer_actual *differs or was never recorded* — for closed runs only — the run is degraded. A
  cross-model request that silently fell back to same-runtime, or that cannot be confirmed, must
  not pass in silence, so an unrecorded actual also fails closed. Automatic recording of
  reviewer_actual is wired by the cross-model invocation; until then, an unset request means
  degraded=False and no false positives.
- **report_gate_enforce defaulting to advisory** is an intentional change. With the key absent,
  off → advisory is a *non-blocking* WARN (exit 0), so it is not a hard break: a new warning
  appearing in an existing project that turned the loop on is the intended advisory-first
  behavior. An explicit off still skips, and the migration is to observe under advisory before
  switching to enforce.

## acceptance evidence gate (verification.acceptance.report_gate_by_risk)
Passing build, test and lint does not automatically prove that user requirements are met. This
gate closes that gap per risk level. When `verification.acceptance.enabled=true`, writing a 06
sends `_acceptance_gate` through: if the cycle risk — the maximum of declared, injected, and the
declarations in 00–05 of the same stem — falls within require_for_risk (L2/L3 by default), select
the current stem's 01 acceptance matrix and 04 acceptance evidence exactly and compare only the
tables outside fences. A violation is any of: 01 or 04 not selected; a missing matrix ID, a
malformed one, or a duplicate; no evidence table; a missing required ID; an undefined or duplicate
evidence ID; an unresolved status (unresolved_statuses defaults to FAIL and NOT TESTED); an
unrecognised status value; or an N/A without an explicit reason. An all-optional matrix with zero
required IDs is itself valid.

The default policy is advisory (WARN) at L2 and enforce (BLOCK) at L3, and `unknown` enforces like
L3. `report_gate_by_risk` also permits only L2 advisory and L3 enforce, so a profile alone cannot
lower L3. A legacy `report_gate_enforce: enforce` keeps enforce at every risk level. Legacy
`advisory` and `off` are safely promoted to L2 advisory and L3 enforce so that L3 is never
lowered, and doctor and validate explain the migration. L3 cannot be removed from
`require_for_risk` either: validate FAILs such a profile and the runtime forces L3 back in.
Acceptance statuses are closed at PASS / FAIL / NOT TESTED / N/A, and only PASS and an N/A with a
reason count as resolved. A custom status added by a profile is a validate FAIL and is treated as
unresolved at runtime.

A single L3 `NOT TESTED` ID that can only be confirmed in production or an external environment
may be lowered to a residual WARN, but only through `sage acceptance-waiver grant` recording the
exact cycle stem, the exact required acceptance ID, a reason, a scope, the remaining evidence and
user confirmation. The TTL is at most 24 hours, and it does not apply to `FAIL`, to wildcards, to
an unknown risk, or to an expired, revoked, malformed, duplicate or conflicting grant. It does not
change the status to PASS, and `warn_report_with_l3_waiver` prints the remaining evidence. Before
writing the report the hook appends a `use` record to `.sage/acceptance-waivers.jsonl` and BLOCKs
if that record cannot be written.

The gate skips when acceptance is disabled, under legacy off, and when the cycle risk is *known*
and outside require_for_risk. The core makes no judgement of its own — it only reads the
structured statuses in 04.

## reverse_extract classification
- Shared core: risk classification (path, content, declared), the Desktop block, the plan-existence
  gate, and the L2/L3 verdict structure.
- structural_io_adapter: a single file_path versus apply_patch multi-file plus content.
- output_adapter: channels and messages.
- profile_bound: every risk trigger.
- **algorithm_delta**: L3 review matching, in the strategy slot; never merged.
- Minor drift: content keyword case — Claude fixed-case versus Codex `(?i)` — with canonical
  case-insensitive. This drift is **intentional**: case-insensitive catches more L3 than the
  original, which is the safe direction. The cost is that an L3 keyword appearing in ordinary
  documentation or tests can also raise the level. The L0 pass for documents
  (plan_docs, docs/*.md) runs first, which keeps documentation false positives limited.

## tests
sage_harness/hooks/tests/test_pre_implementation_gate.py
- classify (L0–L3, escalation, desktop, declared, case-insensitive) plus decide branching, plus
  both strategy candidates including inline flags and invalid patterns.
- PDCA enforcement: mandatory phase block and pass, L3 review preservation, the report gate, and
  backward compatibility when inactive; adapter coverage for an L3 block and an L1 pass.
- Audit gate branching over file_ok, seq_ok, chain_ok and degraded, plus report_gate_enforce
  defaulting to advisory.
- Acceptance evidence gate: matrix against evidence, unresolved block and warn, and skipping when
  the risk is out of scope.
- Done Criteria: the exact parser, progress, revisions, a mixed 00-plus-later-phase write, affected
  phases, and Phase 00 hash-bound approval.
