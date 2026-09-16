---
id: generated-artifact-write-guard
kind: hook
runtime_bindings:
  claude: { event: PreToolUse, matcher: "Write|Edit|MultiEdit", timeout: 10 }
  codex: { event: PreToolUse, matcher: "apply_patch", timeout: 10 }
---
## intent
Deterministically block direct edits to generated artifacts (agents, hooks and skills under
`.claude` and `.codex`) and redirect to the spec under `docs/sage_harness`. This is the lock on
the SSOT model. Without it, "always edit the spec" is only advice, and an AI fixing an artifact
in passing during development produces silent drift.

## runtime_bindings
- claude: { event: PreToolUse, matcher: "Write|Edit|MultiEdit" }
- codex:  { event: PreToolUse, matcher: "apply_patch" }
- on_fail: block — the adapter maps it to exit 2 on stderr, or to JSON.

## canonical
sage_harness/hooks/generated_artifact_write_guard_core.py
- Input: stdin JSON — tool_input.file_path or path, or the multiple targets of an apply_patch command.
- The core algorithm is the single classification "is this path a generated artifact".
- Both hosts run the same Python core through `sage-hook` → `runtime/run_hook.py` → `hook_runtime`.
- A core that fails to load or run blocks with exit 2, fail-closed.

## enforcement
- block (exit 2): `*.claude/{agents,hooks,skills}/*`, `*.codex/{agents,hooks,skills}/*`, the
  repository `.mcp.json`, the CORE framework documents (`AGENT_GUIDE.md`, `CLAUDE.md`,
  `CODEX.md`, `AGENTS.md`), and the cycle declaration state `*/.sage/cycle.json`.
- pass (exit 0): anything outside those ownership boundaries — in particular the
  `docs/sage_harness/**` and `scripts/sage_harness/**` sources.
- No path, or a parse failure → pass. It is not a guard target, and failing loudly here would be
  a silent malfunction of a different kind.
- No exemption is needed for the CLI: `sage generate` and `sage cycle` do not go through an
  editing tool (Write / Edit / apply_patch), so they never reach this PreToolUse guard.
- `.sage/cycle.json` is matched on the **path tail**. Matching on basename, as `.mcp.json` does,
  would block any `cycle.json` anywhere in the project. This is the file the gate reads to answer
  "which cycle is this edit part of", so being able to write it with an editing tool would let
  someone point at a completed cycle and switch their own gate off. That was impossible while the
  channel was an env var; the hole appeared when it moved to a file. The matcher covers editing
  tools only, so Bash is still not covered — but anyone with Bash can write the source itself
  without a gate, so this is not a new lever.

## Blocking CORE bootstrap renders, with eligibility-aware guidance (exit 2)
Hand-shipped CORE renders (CORE skills and roster agents) are blocked exactly like any other
artifact. They used to be exempt on the grounds that they are not spec→generate output, but that
left direct edits to CORE renders unprotected and `sage install --force` silently overwrote them.

The project-local overlay path is only suggested for assets whose executable eligibility is
proven. That currently covers the non-gate workers `implementer-a` and `implementer-b` on both
hosts, plus the reclassified gate-bearing assets `leader` and `reviewer` (agents) and
`sage-cycle`, `sage-plan`, `sage-review` and `sage-team` (skills). These redirect to the
canonical lowercase overlay path (`core_overlay_hint`; a skill overlay id is the skill directory
name). Overlay synthesis is safe for them because an asset-independent deterministic oracle
floors their gates. The remaining `qa` and `convention-checker` (agents) and `sage-init`,
`sage-asset`, `sage-asset-override` and `sage-profile-modify` (skills) have no independent
oracle, so the guidance states that overlays are not currently supported for them
(`is_blocked_core_render`). That keeps users from creating an overlay file which would be
preserved but could never be synthesized.

A codex CORE skill installs to `$CODEX_HOME/skills` when the chosen scope is global, and to the
repository `.codex/skills/` when it is project-local. A project-local CORE name is identified as
an install-owned CORE render rather than an ordinary generated skill, so direct edits are blocked
and the guidance points at reinstalling with the same `--skill-scope project-local --force`, or
at the supported overlay flow. A non-CORE `.codex/skills/` entry is blocked as before, as an
ordinary project skill artifact with spec→generate guidance. Non-CORE renders redirect to
`docs/sage_harness/<kind>s/<id>.md` as before. Eligible overlay authoring goes through the
`/sage-asset-override` skill, which only offers currently eligible non-gate assets, and gate
relaxation hard-fails in materialization preflight and `sage validate --strict`.

## Blocking AGENT_GUIDE.md, the CORE framework document, with a project-profile redirect (exit 2)
`AGENT_GUIDE.md`, at the root or in a subpath, is itself a CORE render and a `core_renders`
anchor target, so `sage install --force` overwrites it. A direct edit therefore disappears
silently on upgrade, and it is also the tampering path for re-injecting overlay-read instructions
into a render — `sage validate` detects that at L2 as an anchor mismatch. Framework overlays are
blocked because they have no independent gate oracle. Project values belong to
`sage/project-profile.yaml`, and rules belong to the conventions, critical-domain and
project-local documents.

## Scope note
- The guard covers the agents, hooks and skills directories.
- Guarding settings.json and hooks.json (the registration artifacts) is deferred, because
  bootstrap needs to edit them directly. Still to be decided.

## tests
sage_harness/hooks/tests/ (cases.tsv: path → expected exit)

## mode (forward-compat)
- In SAGE mode the guard blocks, which assumes the spec SSOT exists.
- Applied ahead of that, in an environment without specs, it degrades to warn mode with mirror
  drift detection (profile `guard.mode`).
