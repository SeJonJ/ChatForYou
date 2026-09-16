<!-- SAGE Phase 00 skeleton: fill the plan before governed source edits. -->
# [Base Plan] required_status_checks_hardening

Cycle-Stem: `required_status_checks_hardening`
Risk Level: L2
Status: APPROVED

ChatForYou-Component-Doc-Gate: v1
Component-Backend: N/A: GitHub Actions PR 트리거만 수정하며 backend 제품 소스는 변경하지 않는다.
Component-Frontend: N/A: GitHub Actions PR 트리거만 수정하며 frontend 제품 소스는 변경하지 않는다.

## 0. Prior Knowledge

| Type | Note | Key Takeaway |
|---|---|---|
| Local cycle | `default_branch_transition_ci_followup` | Hook/SAGE workflow 자체 검증은 통과했지만 Required Check 강제는 별도 서버 설정이다. |
| GitHub behavior | Required status checks | workflow-level path filter로 workflow가 생략되면 Required Check가 Pending으로 남을 수 있다. |

## 1. Summary (Goal & Scope)

사용자가 `chatforyou_v2_sage` 보호 규칙에 등록한 네 Required Status Check가
모든 대상 PR에서 항상 생성되도록 `Hook Regression Tests`와
`SAGE Asset Integrity`의 PR 트리거를 안정화한다.

- push의 기존 경로 필터는 유지한다.
- pull request는 `chatforyou_v2_sage`를 대상으로 항상 workflow를 생성한다.
- 사용자가 이미 등록한 job check 이름은 변경하지 않는다.
- commit과 push는 실행하지 않는다.

## 2. Impact Analysis (Critical)

- GitHub Actions: 두 검증 workflow의 `pull_request` 트리거만 변경한다.
- Branch protection: 기존 네 Required Check 이름과 호환성을 유지한다.
- Backend: N/A: 제품 코드와 빌드 정의를 변경하지 않는다.
- Frontend: N/A: 제품 코드와 빌드 정의를 변경하지 않는다.
- Desktop: N/A: Desktop 제품 코드와 릴리즈 workflow를 변경하지 않는다.

## 3. Technology & Risks

- `.github/workflows/**`는 profile상 L2이다.
- workflow-level `paths`를 PR에 유지하면 관련 없는 변경에서 required check가
  생성되지 않아 병합이 막힐 수 있다.
- job 이름을 바꾸면 사용자가 이미 등록한 Required Check와 불일치하므로 이번
  사이클에서는 이름을 유지한다.
- PR 검증 빈도는 증가하지만 각 workflow는 저장소 거버넌스 무결성 검증이므로
  보호 브랜치의 항상 실행 계약과 일치한다.

## 4. Final Conclusion & UX Guide

코드 변경은 두 workflow의 PR event filter에 한정한다. push 후 사용자는 작업
브랜치에서 `chatforyou_v2_sage` 대상으로 PR을 열어 네 Required Check가 모두
생성되고 통과하는지 확인한다.

## 5. Document Mapping (Checklist)

- [x] Phase 00: 범위, L2 위험, 컴포넌트 N/A
- [x] Phase 01: Required Check acceptance matrix
- [x] Phase 02: event 및 branch protection 연동 설계
- [x] Phase 03: 변경 전 파일 소유권과 검증 계획
- [x] Phase 04: 구현 후 gap/coverage 분석
- [x] Phase 05: 간단 자체 리뷰
- [x] Phase 06: 승인 후 완료 보고
