# Verification Protocol

Single source of truth for the **deterministic verification gate** referenced in `AGENT_GUIDE.md`.

The risk gate in `pre-implementation-gate.sh` confirms that plan and review **documents exist**.
It does not run build, test, or syntax checks. This protocol closes that gap: it defines how
deterministic checks (build / test / syntax) are actually executed and enforced.

---

## 1. Principle — Deterministic first, inferential second

Run cheap, deterministic computational checks **before** expensive inferential review
(cross-model / external-expert). A change that fails build or test must be fixed before any
LLM review round, so review tokens are never spent on broken code.

```
[Deterministic gate]  build → test → syntax     (scripts/verify-changes.sh)
   | FAIL → fix now, do NOT enter inferential stage
   ▼ PASS
[Inferential gate]    reviewer → external-expert → codex cross-model   (Phase 05)
```

---

## 2. Why this is a script, not a hook

`build`/`test` run in minutes; Claude/Codex hooks have a 5–15s timeout. Putting build/test in a
hook guarantees timeout failure. Therefore:

- **Hooks** do only fast checks (path/risk classification, document presence) — see `.claude/hooks/` and `.codex/hooks.json`.
- **Deterministic verification** runs as a workflow/agent step or manual command via
  `scripts/verify-changes.sh` — invoked at Phase 03 exit and before Phase 05.

---

## 3. Commands per component (ChatForYou actual)

| Component | build | test | syntax |
|---|---|---|---|
| `springboot-backend/` | `./gradlew compileJava compileTestJava` | `./gradlew test` (the `external` tag is excluded by `build.gradle`, so infra-dependent tests do not run) | covered by compile |
| `nodejs-frontend/` JS | — | no test runner exists | `node --check <file>` |
| `nodejs-frontend/` SCSS | `sass static/scss/main.scss` (compile check) | — | covered by compile |

Backend lint is not a runnable command (no checkstyle/spotless); the `backend-convention-checker` /
`frontend-convention-checker` agents cover convention enforcement separately.

---

## 4. Gate policy (level-tiered enforcement)

| Level | Required (block on FAIL) | Advisory (warn only) |
|:---:|:---|:---|
| **L1** | — | syntax, scss |
| **L2** | backend compile + test, frontend syntax | scss |
| **L3** | backend compile + test, frontend syntax (+ two-round WebRTC review, enforced separately) | scss |

- The level is the **maximum** across all changed files (compound change rule, same as `AGENT_GUIDE.md` §3).
- `scripts/verify-changes.sh` auto-detects the level from changed paths, or accepts `--level`.
- L3 additionally requires the two documented review rounds in `webrtc-review-protocol.md`; that part
  is enforced by the risk gate, not by this script.
- Documentation/configuration-only changes that do not touch a runnable backend/frontend verification
  target are treated as **PASS/N/A**. The script must still print a `## Verification Evidence` block
  and exit `0`, explicitly stating that there was no deterministic target to run.

---

## 5. Running it

```bash
scripts/verify-changes.sh                 # working tree changes, auto level
scripts/verify-changes.sh --level L3       # force level
scripts/verify-changes.sh --base chatforyou_v2   # include committed diff vs base
```

Exit codes:

| Code | Meaning | Action |
|:---:|---|---|
| 0 | PASS, PASS/N/A, or advisory (L1) | proceed to inferential stage |
| 2 | BLOCK — required check failed (L2/L3) | fix and re-run before Phase 05 |
| 3 | DEGRADE — verification infra missing (java/node absent, V6) | human must confirm build/test manually |

The script prints a `## Verification Evidence` block. Paste it into the feature's
`plan_docs/03-implementation/[feature].md` so the evidence is part of the PDCA record. Only then may
the corresponding checkboxes be marked `[x]` (`AGENT_GUIDE.md` §4.1).

---

## 6. Where it plugs into the team workflow

- **Phase 03 exit (STEP 2 Exit Gate)** — run the gate after backend/frontend implementation. A required
  FAIL keeps the work in Phase 03; do not advance to QA/analysis with broken build/test.
- **Phase 05 entry (STEP 5)** — re-run the gate before the cross-model loop. PASS is a precondition for
  spending external-expert / Codex review effort.

---

## 7. Working tree vs committed tree — "local PASS ≠ shippable"

`scripts/verify-changes.sh` verifies the **working tree, including untracked files**
(`git ls-files --others`). This is intentional: it catches "forgot to *create* a file."
But it has a structural blind spot — it **cannot** catch "created a file but forgot to *commit* it"
(a partial `git add`). The working tree compiles green because the file is physically present;
CI checks out only the committed tree (clean clone) and fails with `package … does not exist`.

These are opposite failure modes. The defense is layered:

| Layer | Tool | When | Strength |
|---|---|---|---|
| Early advisory | `verify-changes.sh` → `[COMMIT-RISK]` | every gate run | warns if untracked `.java`/`.js` source remains under `src/` — does not block |
| Hard gate | `scripts/verify-committed-tree.sh` | `git push` via `hooks/pre-push` | checks out the pushed commit into an isolated worktree and compiles it = CI clean-checkout equivalent; **blocks push** on FAIL (exit 2) |

- The committed-tree gate is what mirrors CI. Run it manually any time: `scripts/verify-committed-tree.sh [ref]`.
- Install the pre-push hook once per clone: `scripts/install-git-hooks.sh` (sets `core.hooksPath`).
- Emergency bypass: `git push --no-verify` (use only when knowingly safe).
- Regression evidence: the gate BLOCKs the real incident commit `297e46f` (missing `TurnCredentialOutVo.java`)
  and PASSes the fix commit `bb4eddc`.

**Rule:** a local `verify-changes.sh` PASS means the working tree is sound, not that what you committed is.
For anything being pushed, the committed-tree gate (pre-push hook) is the source of truth.

---

## Related Rules
- `AGENT_GUIDE.md` — Risk & Workflow Gate, Definition of Done
- `docs/agent/webrtc-review-protocol.md` — L3 two-round review (the inferential L3 requirement)
- `docs/agent/pdca-templates.md` — Phase 03 / Phase 05 templates
- `scripts/verify-changes.sh` — working-tree runner (advisory COMMIT-RISK layer)
- `scripts/verify-committed-tree.sh` — committed-tree runner (CI-equivalent)
- `hooks/pre-push` + `scripts/install-git-hooks.sh` — push-time hard gate
- `scripts/verify-doc-mapping.sh` — 00-base Document Mapping ↔ 실제 파일 대조 게이트 (06-report 진입 전)
