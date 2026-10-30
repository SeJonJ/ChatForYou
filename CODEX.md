# CODEX.md

Thin Codex-specific execution override. All common rules, workflow, and the
output contract are governed solely by `AGENT_GUIDE.md`.

## Mandatory read (session start)

1. `AGENT_GUIDE.md` — single source of truth
2. `sage/project-profile.yaml` — project values
3. `sage/project-profile.local.yaml` — machine values, when present
4. Relevant plan doc + convention docs (per profile)

## Codex-specific

- Use the Codex runtime asset ecosystem (`.codex/agents`, `.codex/skills`,
  `.codex/hooks`) which are generated from `docs/sage_harness/` specs.
- Do not modify generated artifacts directly. A hook: edit its spec and run
  `sage generate`. A project agent/skill: write both renders as drafts under
  `sage/asset_drafts/` and place them with `sage generate --from-drafts` (`$sage-asset`
  drives this; the sandbox keeps `.codex/` read-only, so run the placement with approval
  outside it). (The hand-shipped CORE bootstrap skills — `sage-init`, `sage-init-local`,
  `sage-cycle`, `sage-plan`, `sage-team`, `sage-review`, `sage-asset`, `sage-profile-modify`,
  `sage-asset-override`, `sage-feedback` — install to the explicit global `$CODEX_HOME/skills/` or
  project-local `.codex/skills/` scope. They are install-owned CORE renders, not generated
  project assets; update them with `sage install --host codex --skill-scope <scope> --force`.)
- The CORE roster agent renders (`leader`, `implementer-a`, `implementer-b`, `qa`,
  `reviewer`, `convention-checker`) are hand-shipped to repo `.codex/agents/<id>.md`
  (install-owned and write-guarded, not generated). Codex has no native subagent invocation, so
  reference them as role definitions via this router; update them via reinstall.
