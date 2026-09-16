---
id: sage-feedback
kind: skill
# CORE skill (neutral). Project specifics come from the profile, not this spec.
# CORE framework bootstrap asset: hand-shipped by `sage install`, NOT manifest-tracked.
# The manifest/claims/validate loop is reserved for project-authored skills
# (spec + claims + render hash) created via the generate/extract flow.
---
## intent
Resolve developer feedback markers left in completed-cycle code. The developer marks a spot
they doubt with `sage-feedback ::`; this skill finds every marker, identifies which cycle
produced that code, reads that cycle's own plan documents, and judges whether the doubt is
real — then fixes, dismisses, or asks. Feedback is NOT accepted unconditionally: the cycle's
`00~06` documents (plus vault notes when available) are the evidence base.

Runs independently of the PDCA cycle — it never re-runs a cycle and never edits completed
`06` reports or plan documents.

## when_to_use
- After a cycle completes, when the developer suspects some code diverges from the original
  plan and wants it checked without re-running the cycle
- When `pre-implementation-gate` blocks with `block_feedback_unresolved`
- When the user says "/sage-feedback", "피드백 확인", "마커 해소"

## procedure
0. Resolve the conversation language once: an explicit `--lang ko|en` on this skill's
   invocation, then `interface.language` in `sage/project-profile.local.yaml`, then `ko`.
   Every question, proposal, progress note, warning and summary uses it; machine values
   and Phase 00–06 `Document-Language:` prose do not. Document prose includes section headings
   and list labels, except `## 5. Done Criteria` and `## 6. Done Criteria Revision Log`, which a
   parser reads by their exact string. See `docs/agent/language-policy.md`.
1. Read context: profile bootstrapped and `feedback.enabled: true`. If the section is absent
   or false → stop and route to `/sage-profile-modify` (the scan is a no-op otherwise).
2. Run `sage feedback --output json` for the deterministic marker inventory. Never hand-grep:
   the CLI owns scan scope (git-tracked files, `paths.plan_docs` excluded) so the skill and the
   gate always see the same markers.
3. For each marker, identify the producing cycle: `git log` the file → the commits that touched
   it → the `paths.plan_docs` documents of that period, matched by `Cycle-Stem`. Read the plan
   (`00` design / `01` requirements) and, when a vault is configured, the related notes.
4. Judge each marker against that evidence and take exactly one of three branches:
   - **Real divergence** → fix the code to match the plan, remove the marker, state in one line
     what changed and why.
   - **Not a divergence** (intentional/justified) → remove the marker, cite which part of the
     plan justifies the current code, leave the code untouched.
   - **Undecidable** (no basis in the plan, or the design intent itself is ambiguous) → leave
     BOTH code and marker untouched and ask the user what is unclear. NEVER guess-fix.
5. Report per marker: path:line, verdict, action taken.
6. Record each outcome with `sage feedback --record --path <p> --line <n> --verdict
   fixed|intentional|undetermined --note "<one line>" --cycle-stem <stem>`. Call it
   unconditionally — the profile decides: `feedback.record: false` (default) is a no-op that
   says so. When on, the `.sage/feedback.jsonl` append-only audit is always written and
   `record_target` only adds the human-readable per-cycle vault note (`auto` = when
   `vault_path` is set, `sage` = never, `vault` = required).

## advisory_scope
- This skill may edit source code — unlike `/sage-review`'s reviewer, which is read-only by
  governance design. The three-branch rule is what prevents unfounded edits, not read-only-ness.
- It MUST NOT edit completed `06` reports or plan documents, and MUST NOT start or resume a cycle.
- Marker severity is the developer's choice: `sage-feedback ::` is advisory, `!sage-feedback ::`
  blocks `03` implementation entry on files that carry it.
- convention_doc: AGENT_GUIDE.md
- self_overlay: unsupported; this gate-bearing CORE skill is not in `COMPOSE_ALLOWED`

## runtime_bindings
- claude: .claude/skills/sage-feedback/SKILL.md (repo — Claude Code auto-discovers)
- codex:  $CODEX_HOME/skills/sage-feedback/SKILL.md or .codex/skills/sage-feedback/SKILL.md (explicit global or project-local install scope)
- agent: reuse `reviewer` (diagnosis is its proven strength); no new role.
- profile: `feedback.{enabled,block_release,record,record_target}`, `paths.plan_docs`,
  `knowledge_capture.vault_path`.
- CLI: `sage feedback [--blocking-only] [--exit-code] [--release-gate] [--output json]`
  and `sage feedback --record --path/--line/--verdict/--note [--cycle-stem] [--vault]`.
- release CI: `sage feedback --release-gate` — always callable, blocks only when
  `feedback.block_release` is true (the policy read lives in the profile, not in each CI file).

## drift_checks
- The marker token and severity rule are owned by
  `sage_harness/hooks/runtime/feedback_markers.py` (single source shared with the gate
  and the CLI). If this spec ever states a different token, the spec is wrong.
- A blocking marker cannot be bypassed by editing around it: the gate allows a write only when
  it removes the marker. The recorded escape hatch is `sage override grant --gate
  pre-implementation-gate` (reason + TTL, audited in `.sage/override.jsonl`).
