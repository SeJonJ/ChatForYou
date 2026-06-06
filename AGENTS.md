# AGENTS.md

이 저장소에서 작업하는 에이전트는 공통 규칙을 먼저 `AGENT_GUIDE.md`에서 확인한다.
Codex로 작업하는 경우 `CODEX.md`를 함께 읽고, Codex-specific 실행 차이는 `CODEX.md`를 따른다.

## Project-Local Codex
Run Codex for this repository with `CODEX_HOME=$PWD/.codex codex` or `./scripts/run-codex-local.sh`.

Project-local assets:
- custom skills: `.codex/skills/`
- custom agent configs: `.codex/config.toml` + `.codex/agents/*.toml`
- project Codex config: `.codex/config.toml`

Custom agents are registered in `.codex/config.toml` under `[agents.<name>]`.
Each registered role points to `.codex/agents/*.toml`, and each agent TOML points to its role markdown via `model_instructions_file`.

## Coding Tasks
When spawning Claude Code sessions for coding work, tell the session to use gstack skills.

Examples:
- security audit: "Load gstack. Run /cso"
- code review: "Load gstack. Run /review"
- QA test a URL: "Load gstack. Run /qa https://..."
- build a feature end-to-end: "Load gstack. Run /autoplan, implement the plan, then run /ship"
- plan before building: "Load gstack. Run /office-hours then /autoplan. Save the plan, don't implement."
