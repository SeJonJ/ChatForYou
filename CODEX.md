# CODEX.md

This document is a thin wrapper that defines Codex-specific execution overrides only.
All common rules, workflow, and output contracts are governed solely by `AGENT_GUIDE.md`.

## Mandatory Read

Before responding to any request:
1. Read `AGENT_GUIDE.md` first.
2. If `.local/local_agent_guide.md` exists, read it and apply its local-only rules and context.

## Start Order
1. `AGENT_GUIDE.md`
2. `.local/local_agent_guide.md` (if it exists)
3. Relevant `plan_docs/00-base_plan/YYYY/MM/[feature]_plan.md`
4. Relevant component convention docs
   - `docs/springboot_backend.md`
   - `docs/nodejs_frontend.md`
   - `docs/chatforyou_desktop.md`
   - `docs/git_commit_convention.md`
5. Codex runtime assets
   - `.codex/config.toml`
   - `.codex/agents/*.toml`
   - `.codex/skills/*`

## Codex-Specific Rules
- Report work based on verified local codebase findings and the commands you actually ran.
- Apply the minimum-change principle during implementation, and clearly separate file changes from verification results.
- Do not revert existing worktree changes unless the user explicitly requests it; stay within the requested scope.
- Do not modify `.codex/*` paths unless the user explicitly requests it, because they are Codex runtime assets.
- For design or analysis tasks, verify consistency with existing `plan_docs` before proposing new directions.
