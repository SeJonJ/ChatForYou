# CODEX.md

This document is a thin wrapper that defines Codex-specific execution differences only.
All common rules, workflow, risk levels, validation requirements, output contracts, and Definition of Done are governed by `AGENT_GUIDE.md`.

## Mandatory Read

Before responding to any request, read the following files in order:

1. `AGENT_GUIDE.md` — single source of truth for common rules, workflow, risk classification, validation, and output contract
2. `.local/local_agent_guide.md` — local-only configuration and context sources, if it exists

## Start Order
1. `AGENT_GUIDE.md`
2. `.local/local_agent_guide.md` (if it exists)
3. Relevant plan document, when the task requires planning or implementation:
   - `plan_docs/N월_[기능]_plan.md`
   - `plan_docs/00-base_plan/YYYY/MM/[feature]_plan.md`
4. Conditional agent reference documents from `AGENT_GUIDE.md` Required Agent Reference Documents, only when their trigger applies:
   - `docs/agent/risk-classification.md`
   - `docs/agent/webrtc-review-protocol.md`
   - `docs/agent/command-safety.md`
   - `docs/agent/wrapper-contract.md`
   - `docs/agent/pdca-templates.md`
   - `docs/agent/phase04-bug-patterns.md`
   - `docs/agent/output-contract.md`
5. Relevant component convention docs:
   - `docs/springboot_backend.md`
   - `docs/nodejs_frontend.md`
   - `docs/chatforyou_desktop.md`
   - `docs/git_commit_convention.md`
6. Codex runtime assets, only when needed:
   - `.codex/config.toml`
   - `.codex/agents/*.toml`
   - `.codex/skills/*`

## Finish Checks

Before the final response:

1. Verify the applicable Definition of Done items in `AGENT_GUIDE.md`.
2. Use the report format required by `docs/agent/output-contract.md`.
3. For any task that reaches Phase 03 or includes code changes, perform the vault knowledge capture check defined in `.local/local_agent_guide.md` when available.
4. If `.local/local_agent_guide.md` does not exist or the vault MCP is unavailable, report the vault capture result as `N/A` with the reason.

## Codex-Specific Rules
- Report findings and changes based on verified local files and the commands actually executed.
- Apply the minimum-change principle during implementation, and clearly separate file changes from verification results.
- Do not revert existing worktree changes unless the user explicitly requests it; stay within the requested scope.
- Do not modify `.codex/*` paths unless the user explicitly requests it, because they are Codex runtime assets.
- Before shell commands, apply `docs/agent/command-safety.md` when the command involves git, kubectl, npm, docker, deletion, secrets, production config, or long-running servers.
- Use `.codex/config.toml`, `.codex/agents/*.toml`, and `.codex/skills/*` only to understand Codex-local agent and skill registration; do not duplicate their contents into this wrapper.
- For design or analysis tasks, verify consistency with `AGENT_GUIDE.md`, relevant `plan_docs`, and relevant component convention docs before proposing new directions.
