# [기본 계획] base_plan_month_folder_support

Document-Language: ko
Cycle-Stem: `base_plan_month_folder_support`
Risk Level: L2
Status: DRAFT
Done-Criteria-Revision: 1
ChatForYou-Component-Doc-Gate: v1
Component-Backend: N/A: SAGE 프로젝트 훅(`scripts/sage_harness/hooks/*`) 로직만 변경하며 backend 제품 코드와 계약은 변경하지 않는다.
Component-Frontend: N/A: SAGE 프로젝트 훅(`scripts/sage_harness/hooks/*`) 로직만 변경하며 frontend 및 desktop 제품 코드와 계약은 변경하지 않는다.

## 0. Prior Knowledge

| Type | Note | Key Takeaway |
|------|------|--------------|
| session | `plan_docs/00-base_plan`이 2026-04~07까지는 `YYYY/MM/` 하위 폴더로 정리됐다가 8월부터 flat으로 바뀐 걸 발견 | 원인은 SAGE CLI(`sage cycle set --create --path <DIR>`)로 Phase 00 생성이 넘어간 뒤 아무도 `--path`에 월 경로를 안 준 것 — SAGE 코어 자체(`00-base_plan/**/*.md`)는 원래도 재귀라 문제없음 |
| session | 프로젝트 전용 훅 `chatforyou-dual-implementation-doc-gate`가 `BASE_PLAN_DIR + "/" + stem + ".md"`로 00 경로를 하드코딩한 걸 코드로 확인 | 월 폴더로 옮기면 이 훅이 이후 그 stem의 Phase 04를 다시 건드릴 때 "00이 없다"고 오차단함 — 재배치 전에 반드시 먼저 고쳐야 함 |

## 1. 배경

`plan_docs/00-base_plan/**/*.md`는 SAGE 코어 phase glob이라 원래 재귀적이다. 하지만 이
프로젝트가 직접 작성한 `chatforyou-dual-implementation-doc-gate` 훅은 00 문서 경로를
`plan_docs/00-base_plan/<stem>.md`(flat)로 하드코딩해 월별 하위 폴더(`2026/09/` 등)에 있는
00 문서를 찾지 못한다. 유저가 기존 flat 파일들을 월 폴더로 재배치하고 앞으로도 월 경로를
쓰기로 결정했으므로, 이 훅을 먼저 깊이-무관(재귀)하게 고쳐야 재배치가 안전하다.

## 2. 목표

`chatforyou_dual_implementation_doc_gate_core.py`가 00 base-plan을 `plan_docs/00-base_plan/`
아래 **임의 깊이**에서 stem 기준으로 찾도록 바꾼다. flat 배치와 월 폴더 배치를 동일하게
지원하고, 동일 stem이 두 곳에 중복 존재하면 모호함으로 차단한다(새 실패 모드).

## 3. 인수 기준

| ID | 요구사항 |
|---|---|
| A1 | flat 배치(`00-base_plan/<stem>.md`)의 기존 동작이 회귀 없이 그대로 동작 |
| A2 | 월 폴더 배치(`00-base_plan/2026/09/<stem>.md`)에서도 동일하게 동작(missing/marker/component 검사 전부) |
| A3 | 동일 stem이 flat과 월 폴더에 동시 존재하면 새 실패 모드(`ambiguous_base_plan`)로 명확히 차단 |
| A4 | `_conflicting_paths`(Phase 00+04 동시 변경 차단)가 월 폴더 경로에서도 동일하게 동작 |
| A5 | 기존 492줄 테스트 스위트 전부 GREEN(회귀 없음) + 신규 케이스(월 폴더, 모호함) 테스트 추가 |
| A6 | `docs/sage_harness/hooks/chatforyou-dual-implementation-doc-gate.md` 스펙 문서가 새 동작을 반영 |

## 4. 최종 결론 및 UX 가이드

내부 거버넌스 훅 로직 변경 — 제품 코드/사용자 UX 영향 없음. 완료 후 기존 flat Phase 00
문서들을 월 폴더로 안전하게 재배치할 수 있고, 앞으로 `sage cycle set --create --path
plan_docs/00-base_plan/<YYYY>/<MM>`로 만든 사이클도 이 훅이 정상 인식한다.

## 5. Done Criteria

- [x] A1~A6가 로컬에서 확인된다 — 04 Acceptance Evidence Review, 05 Acceptance Gate PASS

## 6. Done Criteria Revision Log

초기 revision 1. 재계획 기록 없음.
