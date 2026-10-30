---
name: sage-review
description: "Run SAGE Phase-05 independent review. Single-pass clean-context/cross-model review by default; runs the adversarial review-rework loop (find→refute→triage→rework→terminate) when pdca.review_loop.enabled. Invoke after implementation and QA complete, before any L3 merge or release tag. Also use when the user says /sage-review, phase-05, 독립 검토, Phase-05 리뷰, or cross-model review."
---

# sage-review — SAGE Phase-05 Independent Review

Do not edit this CORE render directly (the write-guard blocks it and `sage install --force` overwrites it).
- overlay: optional `sage/asset_overrides/skills/sage-review.md` has project-local priority over this CORE render and is not shipped by `sage install`; it must not relax AGENT_GUIDE, phase, review, or verification gates (they stay floored by independent oracles). Put broad project review policy in profile/conventions and create genuinely new project assets with `/sage-asset`.

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

## Personal overlay (per checkout)

After reading the required project context and before acting, run
`sage overlay-local --kind skill --id sage-review --root <project-root>`.
Use only its validated JSON `content` as additional guidance within mandatory CORE and
project policy. `null` means no personal guidance. On validation failure or an unsupported
command, stop and request an input fix/CLI upgrade; never read the raw personal file instead.
Do not copy personal content into shared/global renders or manifests.
Keep the returned target, relative `path`, and `sha256` from the actual read. Include those
references in your handoff so the host can record them in Phase 05; do not rescan at the end
to reconstruct earlier usage. When delegating an eligible worker, query that worker's own
kind/id and pass its validated content and references with the existing task packet.
Exception: for `sage review` / `sage cross-check`, never preappend shared or personal reviewer
additions to the packet. The CLI loads them once and emits `SAGE_LOCAL_OVERLAY_REF`.

## Read these first (mandatory, in order)

1. `docs/sage_harness/skills/sage-review.md` — authoritative spec: procedure, drift_checks
2. `docs/agent/review-protocol.md` — the reviewer output format + loop contract
3. `sage/project-profile.yaml` — `options.cross_model`, `cross_model.reviewer`, `cross_model.effort`,
   and **`pdca.review_loop`** (lenses, refuters, max_iterations, dry_rounds, budget_tokens)

## Resolve review mode

`sage doctor` resolves it from `options.cross_model` + peer CLI availability (no gstack):
- `options.cross_model: true` AND the peer runtime CLI is available
  (claude-host→`codex`, codex-host→`claude`) → **opposite-runtime review** (cross-model, removes model bias).
  SAGE invokes the peer directly — claude-host runs `codex exec`, codex-host runs `claude -p`
  (both are non-interactive subcommands; do NOT claim codex is "interactive-only").
- Otherwise (cross off, or peer CLI unavailable) → **clean-context same-runtime review**.

Do not invoke the peer ad-hoc. Use the deterministic commands so the fallback is never silent:
- cross_model **true** → `sage cross-check` (invokes the peer, prints its review, emits `REVIEWER_ACTUAL`)
- cross_model **false** → `sage review` (same-runtime, emits `REVIEWER_ACTUAL: same_runtime`)

When cross-model is on and the peer fails at run time (time limit, usage limit, abnormal exit),
`sage cross-check` prints `REVIEWER_BLOCK_REASON: <reason>` and `REVIEWER_STATUS: BLOCKED` — or
`PARTIAL` with the findings the peer had already emitted — and exits non-zero. It does **not**
silently degrade. Falling back to a same-runtime review is a per-round decision the user makes:
rerun with `--on-peer-failure same-runtime`, which prints `REVIEWER_ACTUAL: same_runtime` and
`REVIEWER_STATUS: COMPLETE_DEGRADED` (with `REVIEWER_FALLBACK_REASON`). Read the outcome from
`REVIEWER_STATUS` only — the other `REVIEWER_*` lines are detail. Pass whatever `REVIEWER_ACTUAL` says to
`sage review-loop close --reviewer-actual` so the gate flags a degraded cross-model run. A
`*_degraded` actual means the reviewer did something its process controls should have made
impossible (e.g. spawned a sub-agent) — the round does not count as a clean review. State the
resolved mode before reviewing.

## Choose pass vs loop

- If `pdca.review_loop.enabled` is **false/absent**, OR the change risk is **L0/L1**
  → run the **single-pass review** (next section).
- If `pdca.review_loop.enabled: true` AND risk ∈ **{L2, L3}**
  → run the **adversarial review-rework loop** ("Loop A" section below).

The loop body (find/refute/rework) is judgement and runs here in the host runtime.
SAGE owns only the deterministic gates: the loop counters/budget are recorded via
`sage review-loop`, and the hard backstop is the existing **report←approve** hook
(Phase 06 is blocked until Phase 05 records `APPROVED`). This skill never bypasses
that gate.

---

## Single-pass review (default)

### Gather inputs
1. Plan doc path(s) selected by one exact `Cycle-Stem` equal to each markdown basename
2. Changed files + unit test results (from implementers)
3. QA findings summary (from qa agent)
4. Same-stem Phase 01 acceptance matrix + Phase 04 acceptance evidence table, with
   well-formed unique IDs and exact required-ID coverage

Do not identify a cycle from branch-number substrings or recent-file/mtime fallback.

If any input is missing, block and request it.

### Invoke the reviewer
Hand off to the `reviewer` agent with: review mode, plan doc path, implementation
summary (changed files + test status), QA summary, and acceptance evidence. Instruct it
to produce a report per `docs/agent/review-protocol.md`. Present findings verbatim — do
not filter.

### Handle the verdict
The reviewer's verdict maps to the **Final Status marker the gate reads** (the report←approve
hook looks for the literal `APPROVED`; `Final Status: CLEAR` would be rejected). Always record
one of `APPROVED | FAIL | BLOCKED`:
- Missing/unknown/duplicate acceptance IDs or required items marked `FAIL` block
  `APPROVED`. `NOT TESTED` also blocks unless an exact active L3 waiver preserves
  the row as residual evidence; never convert it to PASS. Use `N/A` only with
  explicit out-of-scope/deferred/user-approved reasoning.
- **CLEAR** → record `Final Status: APPROVED` (reason note: `CLEAR`), proceed
- **BLOCK on L3** → record `Final Status: BLOCKED` (reason: `BLOCK`), STOP (no release/merge until cleared)
- **ADVISORY** → present to the leader; if they accept the result, record `Final Status: APPROVED`
  (reason: `ADVISORY`), otherwise `BLOCKED`

Record exactly one anchored `Final Status: <value>` line outside fenced code blocks.
Remove template placeholders, code examples, and duplicate status declarations. Write
Phase 06 later as a separate change from every 00–05 phase update.

---

## Loop A — adversarial review-rework (when `review_loop.enabled` + L2/L3)

Load `cfg = pdca.review_loop`. Open the audit trail and capture the run id, recording the
**intended** reviewer mode (`cross_model` if `options.cross_model: true`, else `same_runtime`):

```
REQ=$( [ cross_model true ] && echo cross_model || echo same_runtime )
RUN_ID=$(sage review-loop open --risk <L2|L3> --reviewer-requested $REQ --cycle-stem <stem>)
```

`sage review-loop` auto-discovers the project root (the dir holding `sage/project-profile.yaml`),
so it records to the one canonical `.sage/loop_audit.jsonl` regardless of the current working
directory — open/round/close stay on the same trail. Pass the captured `$RUN_ID` to every
subsequent `round`/`close`.

A run is always bound to a cycle. Without `--cycle-stem`, `open` uses the declared cycle
(`SAGE_CYCLE_STEM`, then `.sage/cycle.json` — the same resolution the gates use) and refuses
when none resolves. Rounds are counted **per cycle across every run**, so opening a new run does
not reset the count; the cycle cap is `cfg.max_cycle_rounds[risk]` (default L3 5 · L2 3).

**Write user decisions down where they are made.** A decision the user gives in conversation
goes into the current phase document's decision section in that same turn; decisions inside the
loop are recorded by `sage review-loop decide` or by the close itself. Context can be compacted
at any time, and a decision that lives only in the conversation is not recovered.

Then repeat each round until a termination rule fires (max `cfg.max_iterations[risk]`):

### 1. FIND (parallel lenses + cross-model peer)
Run **exactly one reviewer per lens** in `cfg.lenses` over the full diff (parallel,
divergent). Do NOT sub-divide a lens by component / file / module — that multiplies
subagents (e.g. 6 lenses × 2 components = 12) with no coverage gain; one lens reviewer
already sees the whole diff. For the cross-model peer,
**do not invoke the peer by hand** — write the review packet to a file following **Review packet**
in `docs/agent/review-protocol.md` (propositions, context map, verification summary — not raw rule
or plan documents) and run `sage cross-check --packet-file <f>`; it invokes the peer
(`codex exec`/`claude -p`) under process controls and prints the peer's findings. Keep the packet
body fixed across rounds and append each round's delta at the end (the unchanged prefix keeps the
peer's prompt cache warm). The packet's decisions-and-residuals section is **generated, not
rewritten by hand**: paste the output of `sage review-loop ledger render` (scope decisions,
known pre-existing defects, residuals and refuted findings carried across runs). Add an
out-of-scope decision or a known pre-existing defect (for example a Phase 04 `F-n`) with
`sage review-loop ledger add --kind out_of_scope|preexisting --source <doc or F-n> --reason <...>`
instead of retyping it into a new packet. Capture the `REVIEWER_ACTUAL: <mode>` line as `ACTUAL`
and the `REVIEWER_TOKENS:` line as `PEER_TOKENS`. Use the
**FIND prompt** (§ skeletons) for host lenses. Collect findings. **Dedup**: drop any finding whose key `(norm(file), line_bucket, lens, sha(norm(claim)))`
is already in `seen` (prevents tail/resurfacing churn).

### 2. REFUTE (adversarial false-positive filter)
Run **exactly `cfg.refuters` refuters for the whole round** — each refuter judges **ALL
fresh findings in one batched pass** (via the **REFUTE prompt**), NOT one refuter per
finding. This spawns `refuters` subagents per round regardless of finding count, and loads
each cited file's context once per refuter instead of once per finding×refuter (the old
per-finding fan-out re-read the same file for every finding in it). A finding is
**dropped only when refuting votes `> refuters/2`** (a strict majority of refuters marked it
refuted); a tie survives — with `refuters: 2`, one refuter alone cannot drop a finding. With
`refuters: 1` the single refuter decides. Tallied per finding, so the result is identical to
per-finding refutation. Refuters bias toward "refuted=true when uncertain" — this drops weak
findings; the backstop for a wrongly-dropped real issue is the human BLOCKED path.

**Peer-only P0/P1 cannot be dropped by host refuters alone.** A P0/P1 finding raised only by
the cross-model peer (no host lens found it) survives this round even if host refuters vote it
refuted. Put the refutation evidence into the **next** round's packet delta ("host refuters
dispute this finding because …") so the peer re-checks it in the call it already makes — no
extra peer call. It is dropped only if the peer withdraws it, or, in the last round, if a human
approves the drop; record either in the 05 document with the evidence. Add survivors to `seen`.

### 3. TRIAGE (human-escalation boundary)
For each survivor, run the **TRIAGE prompt**. If `scope == architecture_change` AND
`classify_risk(file)` is L3 → **stop the loop, escalate to a human**:
```
sage review-loop close --run-id $RUN_ID --result BLOCKED --reason BLOCKED_ARCH --iterations <n> --reviewer-actual $ACTUAL
```
Record the block in the Phase-05 doc and STOP. Architecture changes are not auto-reworked.

### 4. TERMINATION
Do not eyeball this. After recording each round (§ below), run:
```
sage review-loop next --run-id $RUN_ID
```
It reads the recorded rounds + profile cfg and prints the deterministic decision —
`NEXT: CONTINUE`, `NEXT: STOP result=<..> reason=<..>`, `NEXT: ASK kind=CYCLE_CAP
cycle_rounds=<n> cap=<m>`, or (blocking convergence only) `NEXT: ASK kind=CRITICAL_P2
findings=<ids>` — moving the continue/stop call from host judgment to SAGE. It is
advisory (writes nothing); on `STOP`, pass the printed `result`/`reason` straight to `close`.
On `ASK`, the user decides — see **Cycle cap** and **Blocking convergence** below. Do not open
another round first: `round` is refused while either question is open.

Evaluation order it uses (highest precedence first — budget/iteration limits win over
convergence, matching what `close` accepts):
1. architecture escalation recorded (`arch > 0`) → **BLOCKED** (`BLOCKED_ARCH`)
2. cumulative tokens ≥ `cfg.budget_tokens[risk]` → **BLOCKED** (`BUDGET_TOK`)
3. (`converge_on: blocking` only) a critical P2 in the last round has no decision → **ASK** (`CRITICAL_P2`)
4. iteration ≥ `cfg.max_iterations[risk]` → **APPROVED** if the loop has converged (below), else **BLOCKED** (`BUDGET_ITER`)
5. cycle rounds (every run of this cycle) ≥ the cycle cap → **APPROVED** if converged, else **ASK** (`CYCLE_CAP`) — also on a fresh run with no round yet
6. converged → **APPROVED** (`CONVERGED` when nothing survived; `CONVERGED_RESIDUAL` when only non-blocking findings survived under `converge_on: blocking`)
7. otherwise → **CONTINUE** (also when the loop converged below a Fast run's minimum rounds)

"Converged" means zero survivors under the default `converge_on: all`. Under `converge_on:
blocking` it means no blocking finding is left (see below).

### Cycle cap (`NEXT: ASK kind=CYCLE_CAP`)
Stop and ask the user, showing the cycle rounds, what still survives (`sage review-loop ledger
show`) and the three choices. Never pick one yourself:
1. **Continue** — `sage review-loop decide --run-id $RUN_ID --cycle continue --extend <N>
   --reason "<the user's words>" --decided-by "<the name the user states>"`, then `next` again.
   `N` is 1–10; the decision is refused unless the cycle is at its cap.
2. **Approve the residual** — the early-completion close below (needs a completed round in this
   run and the `early_completion` opt-in).
3. **Stop** — `sage review-loop close --run-id $RUN_ID --result BLOCKED --reason CYCLE_CAP
   --iterations <n> --reviewer-actual $ACTUAL`.

`--cycle continue` raises the cycle cap, not the run's own `max_iterations`. When the run itself
has stopped (`NEXT: STOP result=BLOCKED reason=BUDGET_ITER`) and the user wants another round
rather than early completion, close that run with the result and reason `next` printed, open a
new run on the same `--cycle-stem` and follow its `next`. Rounds keep counting per cycle, so a
cycle already at its cap asks `CYCLE_CAP` again on the new run, and the continue decision is made
there.

### Blocking convergence (`converge_on: blocking`)
The default `converge_on: all` keeps every rule above as it is — skip this section. When the run
was opened under `cfg.converge_on: blocking` (the open snapshot decides; changing the profile
mid-run changes nothing), these rules apply on top:

- **Critical P2.** A P2 in one of the `cfg.critical_p2` categories (default:
  `unauthenticated_crash`, `exposure_or_traversal`, `wrong_content_served`, `deploy_breakage`,
  `security_setting_regression`, `silent_data_corruption`) is marked `critical: true` with its
  `critical_category`. Refuters may dispute the category. `critical` is for P2 only.
- **`NEXT: ASK kind=CRITICAL_P2`.** Do not rework. Report each listed finding to the developer —
  the verbatim claim, the evidence and the expected fix scope — and let them choose per finding.
  Record each answer in that turn with the user's own words, passing the `[claim <hash>]` shown
  next to that finding so an answer never lands on a claim that changed since:
  `sage review-loop decide --run-id $RUN_ID --finding <id> --claim <hash> --fix|--accept
  --reason "<…>" --decided-by "<…>"`. `--fix` → rework, then the next round. `--accept` → the finding becomes an
  accepted residual. A decision binds to that round's finding text; if the same critical
  survives the next round, it is asked again.
- **Convergence.** Nothing surviving is still a normal `CONVERGED` close (no receipt needed).
  With findings surviving, `CONVERGED`/`DRY` closes are refused — the only approval is the
  residual one below.
  When findings survive, blocking = survivors at P0, P1 (always) and the other `cfg.severity_block`
  severities + undecided
  critical P2 + critical P2 decided `--fix`. The loop converges when that is zero, no refutation
  is pending, no lens is unexplored, and the last round has a sidecar receipt (a round recorded
  without `--findings-file` cannot show it). `next` then prints `STOP result=APPROVED
  reason=CONVERGED_RESIDUAL`; close with that reason. The close records reduced assurance
  `REDUCED_BY_POLICY`.
- **Phase 05 and 06 markers.** After a `CONVERGED_RESIDUAL` close write, outside fenced code,
  exactly one each of `Review-Assurance: REDUCED_BY_POLICY`, `Review-Close-Reason:
  CONVERGED_RESIDUAL`, `Review-Rounds: <n> (configured max: <m>)` and `Residual-Findings:
  P0=<n>, P1=<n>, P2=<n>, P3=<n>` (from the close), plus the residual list from `sage review-loop
  ledger show`. The 06←05 gate and CI check them against the audit.
- **Rework scope.** Fix a non-blocking survivor only when the fix is local — no new state, lock,
  marker, protocol or background process. Otherwise set `disposition: residual`. When a
  blocking finding's fix needs such a mechanism, triage it `architecture_change`.
  A rework lists the findings it closes in `closes`; the next round's FIND checks each and
  writes the result to the sidecar `closure` list.
- **Pre-existing defects.** Mark a finding that also exists at the cycle base `preexisting: true`
  with `exposure`: `touched` (this diff edits that line), `reach_changed` (this diff changes who
  can reach it, its input or its permission) or `unchanged`. Refuters drop a pre-existing finding
  with `drop_reason: out_of_scope_preexisting` — it goes to the ledger as a known pre-existing
  defect (a separate issue candidate). A P0/P1 or critical finding may be dropped that way only
  when `exposure` is `unchanged`; `round` refuses otherwise.

`DRY` (dry-convergence: `cfg.dry_rounds` consecutive rounds with 0 new findings) remains a
valid `close` reason for a resolved loop, but `next` reports resolved loops as `CONVERGED`
and does not emit `DRY` separately.

On any terminal state, record the round then close (pass the **actual** reviewer mode so the gate
can flag a degraded cross-model run — `$ACTUAL` from `sage cross-check`, or `same_runtime`):
```
sage review-loop close --run-id $RUN_ID --result <APPROVED|BLOCKED> --reason <REASON> --iterations <n> --reviewer-actual $ACTUAL
```

Before an APPROVED close, resolve every Phase 00 Done Criteria item and verify every
affected Phase document declares the current `Done-Criteria-Revision`. The close command
prints `Phase00-Hash: sha256:...`; copy that exact line into the Phase-05 document with
`Loop-Run: $RUN_ID`. A later Phase 00 edit makes this approval stale and requires a new loop.

**Record the run id in the Phase-05 doc** — add exactly one line `Loop-Run: $RUN_ID` outside
fenced code blocks in this cycle's exact-`Cycle-Stem` Phase-05 document. The 06←05 audit gate
(`pdca.review_loop.report_gate_enforce`) binds the report to this exact run: it reads that
single `Loop-Run` + the `APPROVED` marker from the *same* 05 doc
and confirms the run closed APPROVED. Without this line the gate cannot bind the report to a
loop and (in advisory) warns or (in enforce) blocks.

### 5. REWORK + re-validate (only `within_design` survivors)
Hand the accepted findings to the relevant implementer with the **REWORK prompt** (do not
exceed the approved design in `02-design`). Then **re-validate** before the next round:
`sage_harness/verify-changes.sh` (build/test/lint at the risk gate) and `sage validate` must
PASS; if either fails, retry the round (within the iteration cap). If the rework changes
acceptance coverage, update Phase 03 and Phase 04 before the next review pass.

### Record the round (every iteration)
Write the round's findings to a sidecar file and pass it; the counts and the per-severity
receipt are then derived from it, so you do not type them:
```
sage review-loop round --run-id $RUN_ID --iteration <n> --findings-file <round.json> \
  --tokens <cumulative> --peer-tokens "$PEER_TOKENS"
```
The sidecar (`sage.review-round/1`) is JSON:
`{"schema": "sage.review-round/1", "run_id": "<RUN_ID>", "iteration": <n>, "findings": [...]}`.
Each finding carries `id` (unique in the round), `source` `{"kind": "lens"|"peer", "name": ...}`,
`file`, `line`, `severity` (`P0`–`P3`), the **verbatim** `claim`, `status` (`survived` |
`refuted`), the refuters' votes in `refute` (`refuter`, `verdict` `refuted|upheld|uncertain`,
`reason`, `drop_reason` `not_a_defect|out_of_scope_preexisting|insufficient_evidence|null`),
`triage` (`local` | `architecture_change`), `disposition` (`fix` | `residual` | `rejected` |
`pending`), `closes` (ids `<run_id>:<iteration>:<id>` this rework claims to close) and
`ledger_ref` (`L-<n>` when it re-raises a ledger entry). Blocking convergence adds
`critical_category`, `preexisting` and `exposure` (see above). Optional round fields: `packet`
(`path`, `sha256`), `usage` (`host`, `peer` copied verbatim from `PEER_TOKENS`, per-lens
`lenses`), `unexplored` (lenses that were truncated or timed out) and `closure`
(`{"ref": "<run_id>:<iteration>:<id>", "closed": true|false, "note": ...}` per finding a
previous rework claimed to close). SAGE stores it under
`.sage/review-rounds/`, records its sha256 in the audit, and saves the working-tree delta since
the previous round as a patch. Counts you also pass by hand must match the sidecar or nothing is
written.

Without a sidecar the round is still recorded (marked `sidecar: absent`), with the counts by hand:
```
sage review-loop round --run-id $RUN_ID --iteration <n> \
  --found <N> --survived <N> --accepted <N> --arch <N> --tokens <cumulative> \
  --peer-tokens "$PEER_TOKENS"
```
`--tokens` is the host's own cumulative estimate. `--peer-tokens` is the peer's measured usage,
copied verbatim from `sage cross-check` (`unknown` when it could not be measured — never `0`);
the budget check adds it to the host total. Omit it only when no peer ran this round.

When `pdca.review_loop.early_completion.enabled` is true, also pass the per-severity residual
receipt so the surviving findings are counted by severity, not just totalled:
```
  --survived-by-severity P0=0,P1=0,P2=2,P3=1
```
The receipt must name every severity and its total must equal `--survived` exactly. Writing
`P0=0` alone while findings survive is the failure this exists to prevent, and the command
rejects a receipt whose sum disagrees.

### Early completion by user authorization
Available only when `pdca.review_loop.early_completion.enabled` is true, and only while
`sage review-loop next` still recommends `CONTINUE`, or stopped at a ceiling where the user is
the one to decide: `STOP` with `BUDGET_ITER` (the run's iteration cap) or `ASK` with `CYCLE_CAP`
(the cycle cap). A loop that `CONVERGED` closes normally instead, and `BUDGET_TOK` or
`BLOCKED_ARCH` cannot be closed by an authorization at all, and neither can `ASK CRITICAL_P2`
(decide the critical findings first). Under `converge_on: blocking` the "no blocking finding"
check uses the blocking count above instead of `severity_block` alone. Before asking, show the
user what stays open: the close prints the residual findings from the last round's sidecar.

If that key is absent or false, early completion is unavailable: say so, keep running the loop
to convergence or its configured maximum, and **never propose editing the profile mid-loop to
unlock it**. Enabling the feature is a policy decision for `/sage-profile-modify` outside a
running loop, and it is not the authorization for this close.

Inside a Fast run the effective floor is higher than this feature's own: `sage fast-cycle
review` requires at least `pdca.fast_cycle.minimum_rounds[<level>]` rounds, so the usable floor
is `max(minimum_completed_rounds, fast_cycle.minimum_rounds[<level>])`. Closing the loop below
that spends the user's authorization on a run the Fast gate will then refuse.

```
sage review-loop close --run-id $RUN_ID --result APPROVED --reason USER_AUTHORIZED_EARLY \
  --iterations <n> --reviewer-actual $ACTUAL \
  --authorization-reason <why the residual risk is accepted> \
  --confirmed-by <approver> --confirm USER_AUTHORIZED_EARLY
```

**The authorization is the user's, not yours.** The reason, the approver and the confirmation
token come from the user in that turn. Never supply them from context, from an earlier run, or
from your own judgement that the remaining findings look harmless. Missing any one of them and
the command exits before appending anything.

`--confirmed-by` is the name the user states in that turn. Do not read it from `git config`,
the profile, the host account, or any other metadata — those record who is typing, not who
accepted the residual risk. `--authorization-reason` carries the user's own words; do not
summarise, translate, or improve them into a reason they did not give.

What an authorization can never waive — the command refuses each of these and appends nothing:
zero completed rounds (or fewer than `minimum_completed_rounds`), unresolved findings at a
`severity_block` severity, architecture escalation or `BLOCKED_ARCH`, a token budget overrun
(`BUDGET_TOK`), unresolved Done Criteria or
a revision rerun that has not happened, acceptance `FAIL`, a required `NOT TESTED` without an
active exact waiver, audit damage or chain/sequence failure, and a binding mismatch. The
acceptance judgment is the same policy and the same parser the Phase-06 report gate uses, so a
state that gate would refuse cannot pass here first.

**Failed build/test/lint is on you.** Those results live in Phase 03 prose, so no gate can read
them — the engine has no receipt to check. Closing early on top of a failing required check is a
state the engine cannot see and will not stop; do not do it, and say so plainly to the user
rather than treating an accepted close as evidence the state was fine.

The verdict token stays `APPROVED` for compatibility, so the Phase-05 document must say how it
was reached. Record all four markers outside fenced code blocks, exactly once each, matching
the audit record:
```
Review-Assurance: REDUCED_BY_USER_AUTHORIZATION
Review-Close-Reason: USER_AUTHORIZED_EARLY
Review-Rounds: <completed> (configured max: <max>)
Residual-Findings: P0=0, P1=0, P2=2, P3=1
```
What triggers the check is **the value, not the presence** of a marker: writing either
`Review-Assurance: REDUCED_BY_USER_AUTHORIZATION` or `Review-Close-Reason: USER_AUTHORIZED_EARLY`
claims reduced assurance. Once claimed — or once the audit itself closed early — all four must be
there with the audit's values, and a document carrying only some of them is rejected either way.
Neutral lines such as a plain `Review-Rounds:` count on a converged run are fine; what is refused
is claiming a reduced assurance the audit does not show, or hiding one the audit does.

`(configured max: <max>)` is part of that match, not decoration — it is the denominator that says
how much review was skipped. Write the ceiling the close disclosure printed; a project with no
ceiling configured writes `unbounded`, the same word the audit records.

### After close — Obsidian dashboard (optional)
If `knowledge_capture.loop_audit_dashboard` is true and `knowledge_capture.vault_path` is set,
`sage review-loop close` automatically refreshes the per-project vault dashboard
(filename derived from `note_convention` + `project.name`):
```
<vault>/<folder>/TECH - <project.name> loop audit.md
```
You may still run `sage review-loop show --vault` to inspect/regenerate it manually. The
dashboard is a side artifact, never a gate; a vault write failure is reported as a warning
without invalidating the audit close.

### Advisory-first rollout
Until the loop is trusted on this project, run it in **advisory mode**: drive the rounds and
record the audit trail, but keep the report←approve sign-off manual (the human/leader confirms
the recorded `APPROVED` before Phase 06). The deterministic 06←05 backstop is unchanged; the
loop adds the audited find→refute→rework rounds in front of it.

---

## Prompt skeletons (Loop A)

### FIND — per-lens reviewer
```
[ROLE] You review ONLY through the "{lens}" lens. Ignore other lenses.
[INPUT] changed files; 00/01/02 design; 03 implementation; 04 analysis.
[TASK] Find defects/risks in the "{lens}" dimension ONLY.
  - No speculation: only issues with a code citation (file:line).
  - Exclude already-handled or design-intended items.
[SEVERITY] P0=data loss/security/legal · P1=functional bug · P2=latent bug · P3=quality/convention
[OUTPUT] JSON array (empty [] if none):
[{ "lens":"{lens}", "file":"...", "line":N, "severity":"P0|P1|P2|P3",
   "claim":"what is wrong and why, 1-2 sentences", "repro":"evidence (optional)", "fix":"suggestion (optional)" }]
```

### REFUTE — skeptical verifier (batched: all fresh findings in one pass)
```
[ROLE] You are a skeptical verifier. Your job is to REFUTE these findings, not accept them.
[INPUT] findings: {findings_json[]}; relevant code (union of cited files): {file_context}; design docs.
[TASK] Judge EACH finding INDEPENDENTLY. For each, actively try to disprove it: is there
  already a guard? is the premise wrong? is it non-reproducible? does the cited file:line not match reality?
[RULE] Per finding: if you cannot clearly refute it, refuted=false. If uncertain, refuted=true
  (conservative — drop weak findings). Do not let one finding's verdict bias another.
[OUTPUT] one verdict per finding, in an array:
  [ { "finding_id":"...", "refuted": true|false, "reason":"1-2 sentences (file:line)" }, ... ]
```

### TRIAGE — scope classifier
```
[ROLE] You triage a surviving finding's fix scope.
[INPUT] finding: {finding_json}; approved design: 02-design.
[TASK] Is the fix (a) local within the approved design, or (b) changing the design/architecture?
  (b) signals: new component / interface or signature change / data-model change / new external dependency / state-machine transition change.
[OUTPUT] { "finding_id":"{id}", "scope":"within_design|architecture_change", "reason":"..." }
```

### REWORK — implementer
```
[ROLE] You are the "{component}" implementer.
[INPUT] accepted findings (within_design only): {accepted[]}; current code; 03-implementation; 04-analyze.
[TASK] Fix each finding in code. Do NOT exceed the approved design (02).
  If a fix truly needs to exceed scope, STOP and report it as "architecture_change suspected" (do not edit).
  Update 03 (file ownership/checklist) and 04 (gap).
[OUTPUT] diff + updated 03/04 + table: | finding_id | applied/deferred/rejected | reason |
```

## Record the outcome

Read the cycle's document language from Phase 00's `Document-Language:` line (the composite
Phase 00 for a Fast run) before writing. The `## Phase-05 Review` section carries that same
line and is written in that language; a cycle with no marker predates it — follow the
language the existing documents already use. See `docs/agent/language-policy.md`.

Add a `## Phase-05 Review` section to the plan doc. For Loop A, include the iteration table:
```markdown
## Phase-05 Review

Mode: [clean-context same-runtime | opposite-runtime cross-model]
Loop: [enabled | disabled]
Final Status: [APPROVED | FAIL | BLOCKED]    # the gate reads the literal APPROVED marker
Reason: [CONVERGED | DRY | BUDGET_ITER | BUDGET_TOK | BLOCKED_ARCH | (single-pass: CLEAR | BLOCK | ADVISORY)]
Acceptance Gate: [PASS | FAIL | NOT TESTED items unresolved | N/A with reason]
Review Loop Iterations:
| iter | found | survived | accepted | arch | tokens |
|-----:|------:|---------:|---------:|-----:|-------:|
| 1    | 7     | 3        | 3        | 0    | 48000  |
| 2    | 1     | 0        | 0        | 0    | 60000  |
Audit: .sage/loop_audit.jsonl (run_id [rl-...])
Date: [YYYY-MM-DD]
```

If Final Status is BLOCKED, **do not write Phase 06** — the report←approve hook enforces this.

---

> This skill is a **CORE framework bootstrap asset**: hand-shipped by `sage install`,
> NOT a manifest-tracked skill (no claims file, no render hash, not gated by
> `sage validate`/`sage review`). Its reference spec lives at
> `docs/sage_harness/skills/sage-review.md`. To change it, edit the framework
> template, not via `sage generate`. Deploy location is runtime-specific: Claude
> reads it from the repo (`.claude/skills/sage-review/`); Codex reads it from the
> user-global skills dir (`$CODEX_HOME/skills/sage-review/`). The loop runs
> identically on both hosts — same skill body, same `sage review-loop` CLI.

<!-- >>> SAGE OVERLAY v1 START (edit sage/asset_overrides/, not here) -->
## Project-Local Additions (sage/asset_overrides/skills/sage-review.md)
아래는 이 프로젝트 로컬 추가 지침이며 CORE 기본 지침에 **더한다**.
AGENT_GUIDE·phase·review·verification·안전 경계를 **완화할 수 없다**.
## Loop A 프롬프트 보강 (project-local)

`chatforyou_dual_implementation_doc_gate` 사이클(2026-08-02~08, 1~4차 리뷰)에서 반복 관찰된
패턴에 대한 보강이다: REWORK가 지적된 사례 하나만 닫고 그 사례가 속한 부류를 안 닫아서,
같은 결함이 다음 라운드에 형태만 바꿔 재지적되는 일이 4연속(F1→G3, F4→G2, G3→H2, H3→S1)
있었다. CORE의 FIND/REFUTE/TRIAGE/REWORK 골격은 그대로 두고, 아래 두 지점만 추가한다.

### REWORK — 부류 단위 수정 (CORE §REWORK 에 추가)

CORE `[TASK]` 뒤에 다음을 추가로 지킨다:

- **[CLASS]** 수정은 지적된 입력이 아니라 그 입력이 속한 **부류**를 닫아야 한다.
  1. 부류 경계를 한 줄로 적는다(예: "경로 case 비교" 이지 "이 파일 하나의 경로"가 아니다).
  2. 회귀는 부류의 양 끝을 덮는다 — 집합이면 증가/감소 양방향, 판정이면 관할/충돌 양쪽처럼
     대칭되는 두 축을 최소한으로 삼는다. 부류에 실패 형태가 여럿이면(예: 요청 대상 해석 실패
     = 예외·null 반환·빈 값) 대칭 두 축 대신 각 형태를 하나씩 덮고, 3번의 실패 확인도 형태마다
     한다.
  3. 제출 전 **수정하기 전 코드로 그 회귀를 돌려 실제로 실패하는지** 확인하고 결과를
     `[OUTPUT]` 표에 한 줄로 보고한다 — 실패하지 않는 회귀는 아무것도 고정하지 않은 것이므로
     그 자체가 finding 미해결로 취급된다.

### FIND (lifecycle/correctness 렌즈) — 04 의 미검증 주장 탐지 (CORE §FIND 에 추가)

lifecycle·correctness 렌즈는 위 두 렌즈에 한해 다음을 추가로 본다:

- `04-analyze` 의 커버리지 공백 절을 읽고, 각 항목의 근거가 **실행 증거**(테스트/로그/실측)
  인지 **추정**(서술만 있고 검증 없음)인지 판별한다.
- 추정이면서 그 항목이 게이트 판정(risk 등급, 통과/차단)에 실제로 영향을 주면, 그 자체를
  하나의 finding 으로 낸다 — `claim`: "04 §X 의 '위험 낮음' 주장이 실행 증거 없이 게이트
  판정에 쓰인다". 저자가 갭을 찾아놓고 크기를 잘못 매기는 것이 갭을 아예 못 찾는 것보다
  흔하다.

`bug_143_deploy_reconnect_kick` 사이클(2026-09-17, 3라운드)에서 추가로 확인된 누락과 비용 문제에
대한 보강이 아래에 이어진다. 역시 CORE 골격은 그대로 두고 더하기만 한다.

### FIND (lifecycle/correctness 렌즈) — 집합·기준값 불일치 (위 FIND 절에 추가)

- 한 결정의 **입력 집합**(예: 복구 대상으로 표시한 방)과 그 결정을 **실행하는 대상 집합**(예:
  초기화하는 방)이 같은 반환값·스냅샷에서 오는지 확인한다. 실행 단계에서 다시 조회·검색하면
  두 시점 사이의 생성·복제 지연으로 집합이 어긋나므로 finding 으로 낸다.
- master/primary 에서 확정한 값(owner, 상태)과 replica 에서 읽은 파생 데이터(라우팅·쿠키·매핑)를
  함께 쓰는 곳은, 파생 데이터가 확정 값과 일치하는지 검증하거나 master 에서 다시 읽는지
  확인한다. 검증이 없으면 복구·페일오버 직후 stale 값 사용 finding 으로 낸다.

### REWORK — 불변식 회귀 (위 REWORK 절에 추가)

- 불변식(A 이면 반드시 B)을 지키는 수정에는 **A·B 가 어긋난 입력으로 실패해야 하는** 회귀
  테스트를 함께 추가한다. 기존 테스트가 어긋난 입력을 정상으로 전제하고 있으면 테스트 계약부터
  고친다.

`feat_151_frontend_vite_esm_phase1` 사이클(2026-09-24, run 7개)에서 확인된 보강이 아래에
이어진다. 라운드 범위·종료 판정·트리아지 기준은 CORE 리뷰 규칙을 따르고 여기서 다루지 않는다.

### Review packet — 리뷰 중 검증 누적 (CORE Review packet 에 추가)

- 리뷰 라운드 중 새로 돌린 실험(빌드·watch·스트레스 등)은 packet 본문이 아니라 그 라운드
  delta 에 누적해 적는다(본문 고정 규칙 유지). 항목마다 실험 결과와 그 실험이 의존하는 파일을
  적고, 리뷰어는 유효한 항목을 다시 돌리지 않는다.
- 이후 재작업이 그 파일 중 하나라도 건드리면 해당 항목은 무효이고 다시 검증 대상이 된다.

### REWORK — 메타 테스트 금지 (위 REWORK 절에 추가)

- 테스트를 검증하는 테스트(판정기·정규식 계약 검사기를 다시 검사하는 테스트)는 만들지 않는다.
  회귀는 제품 코드의 동작을 직접 확인한다. 판정기를 새로 만들면 그 판정기가 다음 라운드의
  finding 표면이 된다.
<!-- <<< SAGE OVERLAY v1 END -->
