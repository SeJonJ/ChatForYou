# CODEX.md

이 문서는 Codex 전용 실행 차이만 정의하는 thin wrapper다.
공통 규칙, workflow, output contract는 반드시 `AGENT_GUIDE.md`를 단일 기준으로 따른다.

## Start Order
1. `AGENT_GUIDE.md`
2. 관련 `plan_docs/00-base_plan/YYYY/MM/[기능]_plan.md`
3. 관련 컴포넌트 기준 문서
   - `docs/springboot_backend.md`
   - `docs/nodejs_frontend.md`
   - `docs/chatforyou_desktop.md`
4. Codex runtime assets
   - `.codex/config.toml`
   - `.codex/agents/*.toml`
   - `.codex/skills/*`

## Codex-Specific Rules
- 로컬 코드베이스 확인 결과와 실행한 명령을 근거로 작업 내용을 보고한다.
- 구현 시 최소 변경 원칙을 적용하고, 수정 파일과 검증 결과를 명확히 구분해 전달한다.
- 기존 작업트리 변경사항을 임의로 되돌리지 않고 요청 범위 내에서만 수정한다.
- `.codex/*` 경로는 Codex 런타임 자산이므로, 사용자가 명시적으로 요청한 경우를 제외하고 수정하지 않는다.
- 설계 또는 분석 작업에서는 기존 `plan_docs`와의 정합성 점검을 우선한다.
