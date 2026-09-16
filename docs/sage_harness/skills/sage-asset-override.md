---
id: sage-asset-override
kind: skill
# CORE skill (neutral). Project specifics come from the overlay files, not this spec.
# CORE framework bootstrap asset: hand-shipped by `sage install`, NOT manifest-tracked.
# The manifest/claims/validate loop is reserved for project-authored skills
# (spec + claims + render hash) created via the generate/extract flow.
---
## intent
Author a project-local overlay for an eligible EXISTING CORE agent through conversation:
show the current CORE base behavior, interview the desired local deviation, and stop if
it looks like it relaxes a governance gate, then write
directly to sage/asset_overrides/{agents,skills}/<id>.md — a hand-authored file that
`sage install` never ships and `sage install --force` preserves (no `sage generate`
step). The overlay-authoring counterpart to /sage-asset (new assets) and
/sage-profile-modify (profile values).

## when_to_use
- After a retro/absorb proposal suggests an agent/skill-target learning to keep for this
  project without editing the CORE render (which --force would overwrite)
- When the user wants to customize a currently eligible non-gate CORE worker
  (`implementer-a` or `implementer-b`) for THIS project only
- When the user says "/sage-asset-override", "오버레이 작성", "CORE 커스터마이즈"

## procedure
0. Resolve the conversation language once: an explicit `--lang ko|en` on this skill's
   invocation, then `interface.language` in `sage/project-profile.local.yaml`, then `ko`.
   Every question, proposal, progress note, warning and summary uses it; machine values
   and Phase 00–06 `Document-Language:` prose do not. Document prose includes section headings
   and list labels, except `## 5. Done Criteria` and `## 6. Done Criteria Revision Log`, which a
   parser reads by their exact string. See `docs/agent/language-policy.md`.
1. Read context: profile bootstrapped; the target CORE base spec
   (docs/sage_harness/{agents,skills}/<id>.md) or its render; AGENT_GUIDE.
2. Identify kind + id — MUST be currently listed by `sage.overlay_classify.COMPOSE_ALLOWED`.
   A new, framework, or gate-bearing asset is not an overlay target → route or stop.
3. Show the current CORE base content relevant to the requested change (so the user sees
   what the overlay adds on top of, not replaces).
4. Interview: what local deviation, and why (e.g. a retro pattern to stop recurring).
5. Draft the overlay text, then inspect it with the deterministic `sage.overlay_lint.scan_text`
   rules before writing. If it looks like it relaxes AGENT_GUIDE/phase/review/
   verification, **do not write it**. Explicit confirmation is not a bypass; revise or remove it.
6. Write sage/asset_overrides/{agents,skills}/<id>.md directly (no `sage generate`).
7. Run `sage sync-overlays`, then `sage validate --strict`: confirm the file exists and no
   `overlay-gate-relaxation` failure remains; note it survives `sage install --force`.

## advisory_scope
- role_boundary: overlays an EXISTING CORE roster asset only (new assets → /sage-asset);
  writes the hand-authored overlay directly, NOT via generate; never proceeds
  past a gate-relaxation hit; the overlay must not relax AGENT_GUIDE, phase, review,
  or verification gates
- uses: retro/absorb overlay proposals (asset_id hint), docs/sage_harness/{agents,skills}/*.md,
  sage validate (overlay gate-relaxation lint)
- convention_doc: AGENT_GUIDE.md
- self_overlay: unsupported; this gate-bearing CORE skill is not in `COMPOSE_ALLOWED`

## runtime_bindings
- claude: .claude/skills/sage-asset-override/SKILL.md (repo — Claude Code auto-discovers)
- codex:  $CODEX_HOME/skills/sage-asset-override/SKILL.md or .codex/skills/sage-asset-override/SKILL.md (explicit global or project-local install scope)

## drift_checks
- conformance: procedure step 5 (gate-relaxation hard stop), step 2 (COMPOSE_ALLOWED only),
  and step 7 (sync before strict validate) must be present
