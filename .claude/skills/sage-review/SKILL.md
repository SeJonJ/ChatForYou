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

If `sage cross-check` cannot reach the peer it prints `REVIEWER_ACTUAL: same_runtime` (not silent) — pass
that to `sage review-loop close --reviewer-actual` so the gate flags the degraded cross-model run. State the
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

Then repeat each round until a termination rule fires (max `cfg.max_iterations[risk]`):

### 1. FIND (parallel lenses + cross-model peer)
Run **exactly one reviewer per lens** in `cfg.lenses` over the full diff (parallel,
divergent). Do NOT sub-divide a lens by component / file / module — that multiplies
subagents (e.g. 6 lenses × 2 components = 12) with no coverage gain; one lens reviewer
already sees the whole diff. For the cross-model peer,
**do not invoke the peer by hand** — write the review packet (diff + 05 context) to a file and run
`sage cross-check --packet-file <f>`; it invokes the peer (`codex exec`/`claude -p`) and prints the
peer's findings. Capture its last line `REVIEWER_ACTUAL: <mode>` as `ACTUAL` (it is `same_runtime`
if the peer was unreachable — fold those findings into the host's same-runtime review). Use the
**FIND prompt** (§ skeletons) for host lenses. Collect findings. **Dedup**: drop any finding whose key `(norm(file), line_bucket, lens, sha(norm(claim)))`
is already in `seen` (prevents tail/resurfacing churn).

### 2. REFUTE (adversarial false-positive filter)
Run **exactly `cfg.refuters` refuters for the whole round** — each refuter judges **ALL
fresh findings in one batched pass** (via the **REFUTE prompt**), NOT one refuter per
finding. This spawns `refuters` subagents per round regardless of finding count, and loads
each cited file's context once per refuter instead of once per finding×refuter (the old
per-finding fan-out re-read the same file for every finding in it). A finding **survives**
only if refuting votes `< ⌈refuters/2⌉` (majority of refuters marked it refuted → dropped) —
tallied per finding, so the result is identical to per-finding refutation. Refuters bias
toward "refuted=true when uncertain" — this conservatively drops weak findings; the backstop
for a wrongly-dropped real issue is the human BLOCKED path. Add survivors to `seen`.

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
`NEXT: CONTINUE` or `NEXT: STOP result=<..> reason=<..>` — moving the continue/stop call
from host judgment to SAGE. It is advisory (writes nothing); on `STOP`, pass the printed
`result`/`reason` straight to `close`.

Evaluation order it uses (highest precedence first — budget/iteration limits win over
convergence, matching what `close` accepts):
1. architecture escalation recorded (`arch > 0`) → **BLOCKED** (`BLOCKED_ARCH`)
2. cumulative tokens ≥ `cfg.budget_tokens[risk]` → **BLOCKED** (`BUDGET_TOK`)
3. iteration ≥ `cfg.max_iterations[risk]` → **APPROVED** (`CONVERGED`) if last-round survivors == 0, else **BLOCKED** (`BUDGET_ITER`)
4. last-round survivors == 0 → **APPROVED** (`CONVERGED`)
5. otherwise → **CONTINUE**

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
```
sage review-loop round --run-id $RUN_ID --iteration <n> \
  --found <N> --survived <N> --accepted <N> --arch <N> --tokens <cumulative>
```

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
`sage review-loop next` still recommends `CONTINUE` — a loop that already reached `STOP` or
`CONVERGED` closes normally instead.

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
`severity_block` severity, architecture escalation or `BLOCKED_ARCH`, unresolved Done Criteria or
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
있었다. CORE의 FIND/REFUTE/TRIAGE/REWORK 골격은 그대로 두고, 아래 세 지점만 추가한다.

### REWORK — 부류 단위 수정 (CORE §REWORK 에 추가)

CORE `[TASK]` 뒤에 다음을 추가로 지킨다:

- **[CLASS]** 수정은 지적된 입력이 아니라 그 입력이 속한 **부류**를 닫아야 한다.
  1. 부류 경계를 한 줄로 적는다(예: "경로 case 비교" 이지 "이 파일 하나의 경로"가 아니다).
  2. 회귀는 부류의 양 끝을 덮는다 — 집합이면 증가/감소 양방향, 판정이면 관할/충돌 양쪽처럼
     대칭되는 두 축을 최소한으로 삼는다.
  3. 제출 전 **수정하기 전 코드로 그 회귀를 돌려 실제로 실패하는지** 확인하고 결과를
     `[OUTPUT]` 표에 한 줄로 보고한다 — 실패하지 않는 회귀는 아무것도 고정하지 않은 것이므로
     그 자체가 finding 미해결로 취급된다.

### TRIAGE — severity 분기 (CORE §TRIAGE 출력에 필드 추가)

CORE 는 scope(within_design/architecture_change)만 분기한다. 여기에 severity 분기를 더한다
— **REFUTE 를 생략하거나 finding 을 폐기하는 것이 아니라**, TRIAGE 이후 REWORK 로 보낼지를
가르는 추가 분기다. survivor 는 여전히 전부 `[OUTPUT]` 표에 남고 05 문서에 기록된다.

- `severity` 가 `profile.pdca.review_loop.severity_block`(예: `[P0, P1]`) 에 **없으면**
  기본 `disposition` 은 **`accept`** 다 — 05 잔여 위험 절에 근거와 함께 기록하고 REWORK 로
  보내지 않는다.
- `disposition: rework` 로 올리려면 (a) 한 줄 수정으로 끝나거나 (b) 리더가 명시 승인해야
  한다. 승인 근거는 이 자산의 **위협 모델**(상대가 누구인가)을 한 줄로 명시하는 것으로 한다.
- `[OUTPUT]` 에 `severity`(finding 원본에서 그대로 가져옴)와 `disposition: rework|accept`
  두 필드를 추가한다: `{ "finding_id":"{id}", "scope":"...", "severity":"P0|P1|P2|P3",
  "disposition":"rework|accept", "reason":"..." }`

### FIND (lifecycle/correctness 렌즈) — 04 의 미검증 주장 탐지 (CORE §FIND 에 추가)

lifecycle·correctness 렌즈는 위 두 렌즈에 한해 다음을 추가로 본다:

- `04-analyze` 의 커버리지 공백 절을 읽고, 각 항목의 근거가 **실행 증거**(테스트/로그/실측)
  인지 **추정**(서술만 있고 검증 없음)인지 판별한다.
- 추정이면서 그 항목이 게이트 판정(risk 등급, 통과/차단)에 실제로 영향을 주면, 그 자체를
  하나의 finding 으로 낸다 — `claim`: "04 §X 의 '위험 낮음' 주장이 실행 증거 없이 게이트
  판정에 쓰인다". 저자가 갭을 찾아놓고 크기를 잘못 매기는 것이 갭을 아예 못 찾는 것보다
  흔하다.
<!-- <<< SAGE OVERLAY v1 END -->
