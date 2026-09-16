# [기본 계획] sage_upgrade_1_0_0

Document-Language: ko
Cycle-Stem: `sage_upgrade_1_0_0`
Risk Level: L2
Status: DRAFT
Done-Criteria-Revision: 1
ChatForYou-Component-Doc-Gate: v1
Component-Backend: N/A: SAGE CORE 엔진 버전만 갱신하며 backend 제품 코드와 계약은 변경하지 않는다.
Component-Frontend: N/A: SAGE CORE 엔진 버전만 갱신하며 frontend 및 desktop 제품 코드와 계약은 변경하지 않는다.

## 1. 배경

SAGE 엔진이 0.9.80 → 1.0.0으로 메이저 업그레이드되었다. 앞서 합의한 "SAGE 업데이트마다
짧은 브랜치 → PR 머지" 워크플로를 이번에 처음 적용한다. 1.0.0의 핵심 변경은 (1) 전
CLI/훅의 언어 중립화(한/영 이중언어, `interface.language`·`Document-Language:` 마커
도입 — 미설정 시 `ko` 기본, 기존 프로젝트/닫힌 사이클 불변), (2) 신규 `sage upgrade`
트랜잭션 명령(스냅샷+install+generate+sync-overlays+validate를 하나로 묶고 실패 시
전체 롤백), (3) `sage status`/`sage explain`/통합 `sage audit show` 등 진단 명령
추가. `pdca.base_plan.done_criteria_gate`, `pdca.fast_cycle` 등 신규 profile 필드는
모두 미설정 시 `off`로 기본값이 안전함을 사전 확인했다.

## 2. 목표

pipx로 SAGE CLI를 1.0.0으로 올리고, `sage upgrade` + 양 호스트(`claude`, `codex`)
`install --force` + `generate --kind hook --write --target both`로 CORE 자산을
1.0.0 기준으로 재설치·재생성한 뒤, `sage validate --schema --kind all --strict`로
회귀 없음을 확인한다.

## 3. 인수 기준

| ID | 요구사항 |
|---|---|
| A1 | pipx SAGE CLI가 1.0.0으로 갱신됨 |
| A2 | `sage/project-profile.yaml`의 `required_version`이 1.0.0으로 갱신됨 |
| A3 | `sage validate --schema --kind all --strict`가 새 FAIL 없이 통과(WARN은 사이클 이전과 동일한 기존 항목만 허용) |
| A4 | codex CORE skill scope가 이 프로젝트의 기존 선언(`global`)과 일치 — 중복 설치로 인한 scope 충돌 WARN 없음 |
| A5 | CI(`sage-asset-integrity.yml`)가 실제 GitHub Actions에서 그린 |

## 4. 최종 결론 및 UX 가이드

`sage upgrade`(신규 1.0 명령)를 축으로, 그 명령이 소유하지 않는 install/generate
바이트는 명시적으로 먼저 갱신한 뒤 최종적으로 `sage upgrade --apply`로 마무리한다.
UX 영향 없음(내부 거버넌스 자산 갱신, 제품 코드 변경 없음).

## 5. Done Criteria

- [x] A1~A4가 로컬에서 PASS로 확인된다 — 05 Acceptance Gate PASS (plan_docs/05-expert-review/sage_upgrade_1_0_0.md)
- [ ] A5는 push 후 실제 CI 결과로 별도 확인(잔여 위험으로 수용 가능) — 아직 push 전, 미완료

## 6. Done Criteria Revision Log

초기 revision 1. 재계획 기록 없음.

### 2026-09-16 재적용 사유

`agents/145`에서 `chatforyou_v2_sage` pull 반영을 위해 실행한 rebase가 이 브랜치의
미커밋 추적 파일 변경분(이 사이클의 `required_version` 갱신 포함)을 전부 되돌렸다
(git reflog `13:03:18 rebase (finish)`). `.sage/loop_audit.jsonl`·05 리뷰 문서는
git 비추적 경로라 그대로 보존되어 있어 재검토 없이 재적용한다. 재적용 시점에
SAGE 1.1.1이 이미 배포되어 있고(이 사이클이 신고한 project hook adapter 레이아웃
버그가 1.1.1에서 수정 확인됨) 중간 버전(1.0.0/1.1.0)을 거칠 필요가 없어, A2의
목표 버전을 `1.0.0` 대신 `1.1.1`로 재적용한다.
