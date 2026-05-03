---
name: chatforyou-agent-registry
description: Use when the user refers to a project custom agent, asks to delegate work by role, or wants Codex to use agent definitions registered in `.codex/config.toml` and implemented by `.codex/agents/*.toml`.
---

# ChatForYou Agent Registry

This skill explains the project-local custom agent setup for Codex.

## When to use

Use this skill when:
- the user names a custom project agent such as `chatforyou-lead` or `external-consultant`
- a project skill says to use a custom agent
- a task should be delegated using agent definitions registered under `[agents.<name>]` in `.codex/config.toml`

## Workflow

1. Read `.codex/config.toml`.
2. Find the matching `[agents.<name>]` entry.
3. Read the `config_file` it points to under `.codex/agents/*.toml`.
4. Treat the `model_instructions_file` in that TOML as the full role prompt.
5. Use the registered custom agent when delegating that role.

## Rules

- Treat `.codex/config.toml` as the registration source of truth.
- Treat the markdown file referenced by `model_instructions_file` as the full role prompt.
- Let the registered agent TOML control model and sandbox behavior.
- If the requested custom agent does not exist, say so briefly and continue with the closest built-in role.
