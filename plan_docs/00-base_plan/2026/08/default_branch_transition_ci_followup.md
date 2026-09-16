<!-- SAGE Phase 00 skeleton: fill the plan before governed source edits. -->
# [Base Plan] default_branch_transition_ci_followup

Cycle-Stem: `default_branch_transition_ci_followup`
Risk Level: L2
Status: APPROVED

ChatForYou-Component-Doc-Gate: v1
Component-Backend: N/A: GitHub Actions 호출 경로와 SAGE 릴리즈 ref만 수정하며 backend 제품 소스는 변경하지 않는다.
Component-Frontend: N/A: GitHub Actions 호출 경로와 SAGE 릴리즈 ref만 수정하며 frontend 제품 소스는 변경하지 않는다.

## 1. Context

`chatforyou_v2_sage` 첫 push에서 Hook Regression Tests와 SAGE Asset
Integrity가 실패했다. Codex hook과 verify smoke는 통과했으며 실패 범위는
Claude 테스트 호출 경로와 삭제된 SAGE 개발 브랜치 ref에 한정된다. 같은
push에서 pre-push hook이 존재하는 검증 스크립트를 실행 비트가 없다는 이유로
누락 파일로 오판해 커밋트리 검증을 생략한 문제도 확인됐다.

- 제품 소스는 변경하지 않는다.
- 커밋과 push는 사용자 확인 전 실행하지 않는다.
- 외부 cross-review 대신 간단 자체 리뷰를 적용한다.
- Desktop: N/A: Desktop 제품 소스와 릴리즈 동작은 변경하지 않는다.

## 2. Goal

Claude CI가 프로젝트의 보호용 호환 러너를 실행하고, SAGE CI가 ChatForYou에
적용된 정식 `v0.9.80` 릴리즈를 checkout하도록 복구한다. pre-push hook은
`bash`로 호출 가능한 일반 파일을 정상 검증 대상으로 인정한다.

## 3. Acceptance Criteria

- [x] `bash scripts/test-claude-hooks.sh`가 22/22로 통과한다.
- [x] SAGE 원격의 `v0.9.80` ref가 해석된다.
- [x] 실행 비트가 없는 `verify-committed-tree.sh`도 pre-push에서 실행된다.
- [x] GitHub Actions YAML 파싱과 `git diff --check`가 통과한다.
- [x] backend/frontend/Desktop 제품 소스 diff가 0건이다.
- [x] 변경 범위가 두 workflow, pre-push hook과 해당 회귀 테스트에 한정된다.
