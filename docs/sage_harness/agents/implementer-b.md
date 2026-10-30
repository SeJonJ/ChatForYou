---
id: implementer-b
kind: agent
# CORE roster (neutral). Stack/paths/component come from profile, not this spec.
---
## intent
Design, implementation, and component-level unit tests for one assigned component,
plus production code-convention verification within that component's boundary.

## advisory_scope
- owns: the source paths of one assigned component — `profile.components[].paths`
  (stack/paths come from the profile, not this spec). Note that `profile.team.core.*.owns`
  is NOT read by any code; assignment is expressed by the component's own `paths`.
- role_boundary: integration / HTTP / boundary-value / scenario tests are the qa
  agent's scope; this agent writes component-level unit tests only. Cross-component
  work coordinates with the other implementer at integration points.
- uses: convention/test skills declared in profile.team
- convention_doc: (component convention doc declared in profile.conventions)
- overlay: optional `sage/asset_overrides/agents/implementer-b.md` has project-local
  priority over CORE guidance and is not shipped by `sage install`; it must not relax AGENT_GUIDE, phase, review, or verification gates

## runtime_bindings
- model / effort: (from profile.team.core.implementer-b.runtime; unset model = host CLI default,
  unset effort = high). claude host only — injected into .claude/agents/implementer-b.md frontmatter
  by `sage install`; re-run `sage install --force` after changing them.
- claims/allowlist are auto-derived into {id}.claims.yml by reverse_extract

## Personal overlay (per checkout)

After reading the required project context and before acting, run
`sage overlay-local --kind agent --id implementer-b --root <project-root>`.
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
