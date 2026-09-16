# [기본 계획] sage_1_0_gate_adoption

Document-Language: ko
Cycle-Stem: `sage_1_0_gate_adoption`
Risk Level: L2
Status: DRAFT
Done-Criteria-Revision: 1
ChatForYou-Component-Doc-Gate: v1
Component-Backend: N/A: SAGE profile(`sage/project-profile.yaml`) 게이트 설정만 변경하며 backend 제품 코드와 계약은 변경하지 않는다.
Component-Frontend: N/A: SAGE profile(`sage/project-profile.yaml`) 게이트 설정만 변경하며 frontend 및 desktop 제품 코드와 계약은 변경하지 않는다.

## 0. Prior Knowledge

| Type | Note | Key Takeaway |
|------|------|--------------|
| session | `sage_upgrade_1_0_0` 사이클에서 `schema/profile.schema.json` diff로 1.0의 신규 pdca 옵션 4종(`base_plan.done_criteria_gate`, `review_loop.early_completion`, `fast_cycle`, `cycle_binding_visibility`)을 전수 확인, 유저에게 제시 | 유저가 이번 사이클에서 그중 3개를 즉시 켜기로 결정(done_criteria_gate=enforce, early_completion, fast_cycle), 1개(cycle_binding_visibility)는 AI 권장안 요청 |
| upstream | `sage_project/templates/project-profile.yaml`(1.0.0 공식 템플릿)의 주석이 각 옵션의 권장값·트레이드오프를 명시 | `done_criteria_gate`는 신규 프로젝트에 advisory 권장(유저는 enforce를 명시적으로 선택), `fast_cycle` 예시값(`minimum_rounds/minimum_lenses/lenses`)을 그대로 채용, `cycle_binding_visibility: all`은 "장수 브랜치에서 오결속을 눈으로 잡고 싶을 때" 권장 — ChatForYou는 실제로 이번 세션에서 `SAGE_CYCLE_STEM` 고착으로 반복 차단을 겪은 장수 브랜치 프로젝트라 이 권장이 직접 부합 |

## 1. 배경

SAGE 1.0.0 업그레이드(`sage_upgrade_1_0_0`)로 스키마에 추가된 4개의 신규 pdca 게이트/옵션이
모두 미설정(기본값) 상태였다. 이번 사이클은 그중 유저가 결정한 3개를 켜고, 1개는 AI가
권장안만 제시한다.

## 2. 목표

`sage/project-profile.yaml`의 `pdca` 블록에 다음을 추가한다:

1. `base_plan.done_criteria_gate: enforce` — Phase 00 Done Criteria 미해결 시 06 작성 BLOCK
2. `review_loop.early_completion.enabled: true` — 사람 승인 시 Loop A 조기 종료 허용(엔진 하한 1라운드, `minimum_completed_rounds` 미설정으로 하한 그대로 적용)
3. `fast_cycle.*` — L2/L3용 경량 사이클 활성화(상류 템플릿 권장값 그대로 채용)
4. `cycle_binding_visibility: all` — AI 권장(장수 브랜치 오결속 조기 가시화), 유저 최종 승인 대기 중 이 문서에 근거만 기록

## 3. 인수 기준

| ID | 요구사항 |
|---|---|
| A1 | `done_criteria_gate: enforce`가 profile에 반영되고 `sage validate --schema`가 구조적으로 PASS |
| A2 | `review_loop.early_completion.enabled: true`가 반영되고 기존 review_loop 하위 키(enabled/lenses/refuters 등) 회귀 없음 |
| A3 | `fast_cycle.*`가 상류 템플릿 권장값 그대로 반영되고 스키마 PASS (L2/L3 각 `lenses` 2개 이상, `minimum_lenses` 충족) |
| A4 | `cycle_binding_visibility: all` 반영(유저 승인 시) |
| A5 | `sage validate --schema --kind all --strict`가 새 FAIL 없이 통과(WARN은 기존과 동일 항목만) |

## 4. 최종 결론 및 UX 가이드

내부 거버넌스 정책 변경만 — 제품 코드/사용자 UX 영향 없음. 다음 사이클부터 Done Criteria
미해결 시 06 작성이 실제로 막히고(enforce), Loop A 조기 종료·Fast Cycle 명령을 쓸 수 있게 된다.

## 5. Done Criteria

- [x] A1: done_criteria_gate: enforce 반영 및 스키마 PASS
- [x] A2: review_loop.early_completion.enabled 반영, 기존 review_loop 키 회귀 없음
- [x] A3: fast_cycle.* 상류 템플릿 권장값 반영, 스키마 PASS
- [~] A4: cycle_binding_visibility 반영 (N/A: 유저가 "권장안만 말해달라"고 명시해 이번 사이클에서는 적용하지 않음 — 승인 시 별도 후속)
- [x] A5: sage validate --schema --kind all --strict 신규 FAIL 없이 통과

## 6. Done Criteria Revision Log

초기 revision 1. 재계획 기록 없음.
