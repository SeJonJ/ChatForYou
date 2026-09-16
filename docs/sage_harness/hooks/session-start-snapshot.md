---
id: session-start-snapshot
kind: hook
runtime_bindings:
  claude: { event: SessionStart, matcher: "", timeout: 10 }
  codex: { event: SessionStart, matcher: "", timeout: 10 }
---
## intent
At session start, snapshot the baseline of this session's Phase 06 documents — existence plus the
sha256 of their contents. The Stop hook's retro gate compares against that baseline to detect a
06 written during this session **regardless of which tool wrote it**. post-tool-logger only logs
Write/Edit (claude) and apply_patch (codex), so a 06 written through Bash slips past it;
comparing filesystem state closes that hole. A parse failure on input exits 0 silently, but
failing to record the claim that consumes the first opportunity exits 2 and blocks work from
starting, because a later baseline could otherwise absorb changes that already happened.

On host lifecycles where SessionStart is missing or delayed, the first UserPromptSubmit still runs
before any agent work, so `capture-declared-risk` calls the same write-once baseline helper as a
backup path. If a normal SessionStart already recorded one it is a noop, and a UserPromptSubmit
arriving after the backup path recorded one does not overwrite the baseline either.

A race between SessionStart and UserPromptSubmit is resolved by an `O_EXCL` first-opportunity
claim that admits a single winner. If the first opportunity hits a profile error or an inactive
gate, only the claim is left behind and no baseline is written. Even if the configuration is
enabled later, a late baseline will not absorb earlier 06 changes once agent work has begun, and
Stop treats a missing baseline as fail-closed.

The claim separates winner from loser but does not guarantee completion. The loser checks the
claim file's `resolved` marker (noop | written) to see whether the winner's attempt finished, and
blocks with exit 2 while it is undetermined — the winner may have stopped right after claiming,
which would risk agent work starting before the baseline was published. Both the marker and the
baseline record are re-verified against `session_id`, so a filename normalization collision
(sanitize plus truncate) never lets one session trust another's claim or baseline. Any other
truthy value of `resolved` is treated as a corrupt claim and blocks exactly as undetermined does.
A baseline that already exists, or that won a publish race, is only allowed to release agent
progress and mark `written` after its regular-file status, matching `session_id` and mapping
`sha256` have all been verified.

The snapshot is written **only when the gate is active** — mode advisory or enforce with a usable
retro_note — **and a session_id exists**. An inactive project writes just the small attempt claim
and skips hashing the 06 set entirely, and a missing session_id avoids polluting a shared file.
The record is published create-once by hard-linking a completed temp regular file to the
destination, so an existing baseline or symlink is never replaced. On a filesystem without hard
links the destination is created directly with `O_EXCL`; reading it before completion fails closed
as corrupt rather than overwriting an existing file or producing a false pass.
The Stop reader likewise trusts only no-follow regular files. The attempt claim and the baseline
bound to it are never auto-deleted on a TTL, which preserves a resume after a long pause; only a
legacy baseline with no claim, and an abandoned temp, are cleaned up by TTL.

## runtime_bindings
- claude: { event: SessionStart, matcher: "", input: session_id }
- codex:  { event: SessionStart, matcher: "", input: session_id }
- output: none — a create-once write of `.{host}/logs/session-snapshot-{session_id}.json` only.
- on_fail: an input, profile or baseline publish failure exits 0 and resolves into Stop's degraded
  verdict. Only a failure to create the first-opportunity claim exits 2, blocking work from
  starting on both SessionStart and UserPromptSubmit.

## canonical
sage_harness/hooks/session_start_snapshot_core.py → decide(event, snapshot) -> decision (pure)
- event = { session_id, now_utc }. snapshot = { exists, sha256:{normalized key: hash} }, observed
  by the adapter.
- decision = { action: write|noop, record }. Write-once: when it exists the result is noop, so a
  resume or a repeated SessionStart cannot overwrite the baseline and lose early changes. The
  runtime's exclusive claim blocks concurrent and late writers as well.
- The core carries no domain values: the 06 globs, hashing, paths and the gate-active decision all
  belong to the adapter.
- IO orchestration — hashing 06, gating on gate-active, atomic writes, snapshot cleanup — lives in
  hook_runtime._ensure_session_06_snapshot, shared by SessionStart and the UserPromptSubmit
  backup path.
- Manifest tracking: this core is stamped per hook by canonical_hash plus
  adapter_contract_version (CONTRACT_VERSION). The shared IO in hook_runtime.py is tracked
  separately by the top-level hook_runtime_hash.

## adapter_contract
- contract_version: "1"
- Standard input: { session_id }
- Adapter responsibilities: load the profile from $SAGE_PROFILE, resolve the root, and pass stdin
  through raw. Path binding across hosts is io.HOST_DIR.
- Fail-open: a profile or baseline publish failure does not block the session immediately; it
  resolves into Stop's degraded verdict and log-based detection. The one exception is a failure to
  create the first-opportunity claim, which cannot prove that a late baseline is prevented and so
  fails closed with exit 2. No failure may be silent.

## reverse_extract classification
- Shared core: the write-once decision (decide).
- Shared IO in hook_runtime: the SessionStart / UserPromptSubmit fallback, the exclusive
  first-opportunity claim, the claim `resolved` marker (noop | written), hashing the 06 globs,
  gating on gate-active, create-once atomic publication, no-follow regular-file reads,
  claim-preserving TTL cleanup, session_id filename normalization, and re-verification of the
  session_id inside the baseline and claim contents.
- token_adapter: the log path (`.claude` versus `.codex`, via io.HOST_DIR).
- profile_bound: the 06 glob (profile.pdca.phases) and gate-active (pdca.retro plus
  knowledge_capture). The core holds no domain values.

## tests
- test_hook_runtime.py: core decide (write / noop / write-once); _snapshot_changed_06 status
  (ok / absent / no_session / corrupt); detection of both content changes and new files; the
  UserPromptSubmit fallback and write-once; a single winner among concurrent claims; late-baseline
  blocking; snapshot symlink rejection; create-once publication; the `O_EXCL` fallback where hard
  links are unsupported; blocking on claim IO failure; claim and baseline preservation across a
  long-resumed session; blocking on an unresolved claim; a loser proceeding on resolved=noop;
  enforcement of the resolved enum; blocking on a claim/baseline session_id mismatch; and baseline
  re-verification after a publish race.
- test_stop_compliance_report.py: BLOCK on snapshot detection of a Bash-written 06; write-once
  preservation; the log-based fallback with no regression; degraded state surfaced rather than
  silent; and no noise when the baseline is healthy.
- test_retro.py and test_retro_audit.py: `--no-vault` skip recording, gate pass, rejection of a
  bypass with an arbitrary run_id, and rc 2 on a recording failure.
