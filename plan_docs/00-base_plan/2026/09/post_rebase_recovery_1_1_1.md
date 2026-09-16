# [기본 계획] post_rebase_recovery_1_1_1

Document-Language: ko
Cycle-Stem: `post_rebase_recovery_1_1_1`
Risk Level: L2
Status: DRAFT
Done-Criteria-Revision: 1
ChatForYou-Component-Doc-Gate: v1
Component-Backend: N/A: SAGE 거버넌스 자산(profile/hook/문서)만 변경하며 backend 제품 코드와 계약은 변경하지 않는다.
Component-Frontend: N/A: SAGE 거버넌스 자산(profile/hook/문서)만 변경하며 frontend/desktop 제품 코드와 계약은 변경하지 않는다.

## 0. Prior Knowledge

| Type | Note | Key Takeaway |
|------|------|--------------|
| session | `agents/145`에서 `chatforyou_v2_sage` pull 반영을 위해 실행한 rebase(`git reflog`: `2026-09-16 13:03:18 rebase (finish)`)가 이 브랜치의 미커밋 추적 파일 변경분을 전부 되돌렸다 | rebase는 git이 추적하는 파일만 되돌린다 — 커밋 없이 작업한 내용은 보호되지 않는다는 걸 실제로 확인 |
| session | `.sage/loop_audit.jsonl`·`.sage/retro_audit.jsonl`·`plan_docs/00-base_plan/2026/09/*.md`는 git 비추적(gitignore) 경로라 rebase에 영향받지 않고 그대로 보존됨 | 되돌아간 것은 "추적 파일에 반영된 결과물"뿐이고, 그 결과물을 승인한 리뷰 근거(Loop-Run·Phase00-Hash)는 살아있다 — 동일 내용 재적용이지 재판단이 아니다 |
| session | rebase 시점에 SAGE 1.1.1이 이미 PyPI에 배포되어 있었고, 이 세션이 신고한 project hook adapter 레이아웃 버그가 1.1.1에서 수정된 것을 diff로 확인함 | 중간 버전(1.0.0/1.1.0)을 거쳐 재적용할 이유가 없어 목표 버전을 1.1.1로 상향한다 |

## 1. 배경

같은 브랜치(`agents/145`)에서 진행하던 SAGE 0.9.80→1.0/1.1 적용 작업이 한 번도 커밋되지
않은 상태에서, `chatforyou_v2_sage`의 새 커밋(`#147 Security`)을 받기 위한 rebase가
실행되어 추적 파일의 미커밋 변경분이 전부 committed 상태로 되돌아갔다. 되돌아간
항목은 다음과 같다(이 사이클이 아니라 이전 세션에서 이미 설계·리뷰를 마친 항목들):

- `sage/project-profile.yaml` — `required_version` 승격 + `pdca.base_plan.done_criteria_gate`
  / `pdca.review_loop.early_completion` / `pdca.fast_cycle` / `pdca.cycle_binding_visibility`
  4종 게이트 설정 (원 사이클: `sage_upgrade_1_0_0`, `sage_1_0_gate_adoption`,
  `cycle_binding_visibility_adoption` — 전부 `.sage/loop_audit.jsonl`에 `loop_close APPROVED`
  기록 보존)
- `README.md` — 최신 프로젝트 상태 갱신
- `scripts/sage_harness/hooks/chatforyou_dual_implementation_doc_gate_core.py` +
  테스트 + 스펙 문서 — 월 폴더(재귀) 지원 (원 사이클: `base_plan_month_folder_support`,
  `loop_close APPROVED` 기록 보존, phase00_hash 일치 확인됨)
- `plan_docs/00-base_plan/*.md` → `2026/08/`, `2026/09/` 월 폴더 재배치(`git mv`)

## 2. 목표

위 항목을 **동일 내용으로 재적용**하되, SAGE 버전 목표만 1.1.1로 맞춘다. 새로운 설계
판단이 필요한 변경이 아니므로 이 사이클의 Phase 05는 "diff가 원 사이클 승인 내용과
동일함"을 확인하는 짧은 검증에 집중한다.

## 3. 인수 기준

| ID | 요구사항 |
|---|---|
| A1 | `sage/project-profile.yaml`의 `required_version`이 1.1.1로 갱신되고 4개 게이트가 원 사이클과 동일 값으로 복원됨 |
| A2 | `sage validate --schema --kind all --strict`가 새 FAIL 없이 통과 |
| A3 | `chatforyou_dual_implementation_doc_gate_core.py`가 월 폴더 재귀 지원으로 복원되고, 관련 테스트(`test_chatforyou_dual_implementation_doc_gate.py`) 전부 GREEN |
| A4 | 스펙 문서(`docs/sage_harness/hooks/chatforyou-dual-implementation-doc-gate.md`)가 새 동작을 반영 |
| A5 | 8월 폴더 재배치(`git mv`)가 복원됨 |
| A6 | README.md 갱신분이 복원됨 |

## 4. 최종 결론 및 UX 가이드

내부 거버넌스 자산 복원 — 제품 코드/사용자 UX 영향 없음.

## 5. Done Criteria

- [x] A1~A6가 로컬에서 확인된다 — profile 4종 게이트+버전 복원, `sage validate --schema --kind all --strict` WARN만(기존과 동일), 훅 테스트 66/66 GREEN, 스펙 문서 갱신, 8월 폴더 재배치, README 복원

## 6. Done Criteria Revision Log

초기 revision 1. 재계획 기록 없음.
