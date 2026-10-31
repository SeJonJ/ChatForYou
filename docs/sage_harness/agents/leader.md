---
id: leader
kind: agent
# CORE roster (neutral). Project specifics come from profile, not this spec.
---
## intent
Requirement analysis, plan authoring, file-ownership distribution, and team
coordination. Decomposes work into parallel tasks with ownership boundaries and
synthesizes results.

## advisory_scope
- owns: plan docs under `{paths.plan_docs}` (coordination artifacts)
- role_boundary: does not implement component code; delegates to backend /
  frontend / qa and integrates their results
- uses: (project agents/skills, resolved at render from profile.team)
- convention_doc: AGENT_GUIDE.md
- overlay: optional `sage/asset_overrides/agents/leader.md` has project-local priority over CORE guidance and is not shipped by `sage install`; it must not relax AGENT_GUIDE, phase, review, or verification gates (they stay floored by independent oracles)

## runtime_bindings
- model / effort: (from profile.team.core.leader.runtime; unset model = host CLI default,
  unset effort = high). claude host only — injected into .claude/agents/leader.md frontmatter
  by `sage install`; re-run `sage install --force` after changing them.
- claims/allowlist are auto-derived into {id}.claims.yml by reverse_extract

## Personal overlay (per checkout)

After reading the required project context and before acting, run
`sage overlay-local --kind agent --id leader --root <project-root>`.
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
