# verification-protocol.md

Gate policy for L1/L2/L3 changes. Concrete commands come from
`profile.verification` and `sage_harness/verify-changes.sh`; this document defines
the policy that hooks enforce.

| Level | Checks | Mode |
|-------|--------|------|
| L1 | syntax | advisory |
| L2 | build, test, lint | block |
| L3 | build, test, lint, review | block |

## L3 review

L3 changes require an independent review before merge. The reviewer runtime is
resolved by `sage doctor` from `profile.options.cross_model`:

- cross_model off → clean-context review in the same runtime.
- cross_model on + opposite runtime reachable → opposite-runtime review.
- cross_model on + opposite runtime unreachable → BLOCKED
  (fail-closed; surfaced by `sage doctor` and `sage cross-check`).

How an L3 change is matched to its review document is a project decision
(`profile.risk.l3_review_strategy`). Until selected, `pre-implementation-gate`
blocks L3 changes (safe default).

## Acceptance evidence

Deterministic commands prove that configured checks ran; they do not prove every
explicit user requirement was satisfied. When `profile.verification.acceptance`
is enabled:

- Phase 01 must define an acceptance matrix outside fenced code blocks for explicit user requirements.
- Phase 03 maps implementation and tests to those acceptance IDs.
- Phase 04 records `PASS`, `FAIL`, `NOT TESTED`, or `N/A` for each item with
  concrete evidence.
- Phase 01/04 must be selected by the exact same `Cycle-Stem`; every acceptance
  ID must be well formed and unique, every required Phase 01 ID needs Phase 04
  evidence, and Phase 04 cannot introduce unknown IDs.
- Phase 05 treats required `FAIL` or `NOT TESTED` items as blocking unless the
  report gate records an explicit exact-ID L3 waiver as residual evidence.
- Phase 06 defaults to advisory for L2 and enforce for L3/unknown. A local-only
  L3 `NOT TESTED` item may become a residual WARN only after an explicit
  `sage acceptance-waiver grant` for the exact cycle and required acceptance ID.
  `FAIL` is never waivable, and a waiver never changes the recorded status to PASS.
- Phase 05 approval requires exactly one `Final Status: APPROVED` declaration outside
  fenced code blocks, and Phase 06 is written separately from every 00–05 phase update.
