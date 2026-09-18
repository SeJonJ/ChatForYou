# [기본 계획] codex_agent_role_config_migration

Document-Language: ko
Cycle-Stem: `codex_agent_role_config_migration`
Risk Level: L2
Status: IMPLEMENTED
Done-Criteria-Revision: 1
ChatForYou-Component-Doc-Gate: v1
Component-Backend: N/A: Codex 역할 설정만 변경하며 backend 제품 코드와 계약은 변경하지 않는다.
Component-Frontend: N/A: Codex 역할 설정만 변경하며 frontend/desktop 제품 코드와 계약은 변경하지 않는다.

## 0. 사전 지식

| 유형 | 메모 | 핵심 시사점 |
|---|---|---|
| 재현 | 이슈 #150: Codex 0.154.0이 프로젝트 역할 TOML 9개를 `developer_instructions` 누락으로 무시 | 역할 정의가 실제로 로드되는지 Codex 자체 진단으로 확인해야 한다 |
| 자산 경계 | SAGE 1.1.1의 agent 생성 대상은 `.codex/agents/*.md`이고, 쓰기 가드는 해당 디렉터리 전체를 보호 | Codex 역할 TOML은 기존 `.codex/agents/` 위치를 유지하고, SAGE의 생성 자산 경계와 충돌하지 않는 수정 절차가 필요하다 |

## 1. 배경

Orca 워크트리에서 Codex가 기존 수동 역할 TOML 9개를 무시한다. 이 파일들은 필수
`name`·`description`·`developer_instructions`가 없고, 원본 저장소의 절대 경로를
가리킨다. 새 프로젝트의 SAGE 1.1.1 설치 문제는 아니며 ChatForYou 저장소 설정 문제다.

## 2. 목표

역할 이름·모델·추론 강도·샌드박스 설정값과 기존 `.codex/agents/` 위치를 유지하면서,
새 워크트리에서 9개 역할의 형식 경고를 없앤다. SAGE 관리 Markdown은 그대로 둔다.

## 3. 인수 기준

| ID | 요구사항 |
|---|---|
| A1 | 새 워크트리에서 9개 역할의 형식 경고가 없다 |
| A2 | 역할 설정이 현재 워크트리의 역할 Markdown을 참조한다 |
| A3 | 기존 역할 이름·모델·추론 강도·샌드박스 설정을 유지한다 |
| A4 | SAGE 자산 검증과 프로젝트 L2 검증에 새 실패가 없다 |

## 4. 최종 결론 및 UX 가이드

기존 `.codex/agents/*.toml`에 Codex 필수 필드를 보정한다. 별도 역할 레지스트리나
`sage/codex-roles/` 이관은 범위에서 제외한다. 제품 UX 영향은 없다. 실제 역할 호출은
워크트리 신뢰 등록 후 Codex 런타임에서 확인해야 한다.

## 4-1. 영향과 위험

- 합성 위험도 L2: `.codex/**`, `sage/**` 설정 경로 변경. L3 도메인·제품 코드는 영향 없음.
- 필수 필드 누락이나 잘못된 역할 Markdown 경로는 역할을 계속 무시하게 만든다. Codex 자체 진단과 9개 ID 전수 검증으로 확인한다.
- 신뢰되지 않은 신규 워크트리는 Codex가 프로젝트 설정을 읽지 않는다. Orca에서 워크트리를 신뢰 대상으로 등록해야 역할을 호출할 수 있다.
- `.codex/agents/` 하위 TOML은 SAGE write guard의 보호 대상으로 잘못 분류된다. 사용자 지시로 Git patch를 사용해 native Codex 설정 9개만 보정했다. SAGE가 native TOML의 정식 수정 경로를 제공하지 않는 문제는 별도 개선 대상이다.
- 사용자 지시에 따라 이번 Phase 05는 Codex 설정 자체 검토로 수행하며 Claude 교차 검토와 별도 리뷰 에이전트는 사용하지 않는다. 독립 검토 부재를 결과에 명시한다.

## 5. Done Criteria

- [x] A1: `codex doctor` 역할 형식 경고 0건 확인
- [x] A2: 9개 역할의 현재 워크트리 역할 Markdown 경로 및 필수 필드 확인, `chatforyou-lead` 실제 호출 성공
- [x] A3: 기존 역할별 실행 설정 비교
- [x] A4: `sage validate --check --schema --kind all --strict` exit 0, `bash scripts/verify-changes.sh --level L2` PASS/N/A

## 6. Done Criteria Revision Log

revision 2: 별도 `sage/codex-roles/` 이관은 Codex 버그 수정의 필수 조건이 아니므로 철회했다. 기존 `.codex/agents/*.toml` 위치에서 스키마만 보정한다.

revision 3: 기존 위치에서의 스키마 보정도 SAGE generated-asset write guard가 차단하며, 재생성·override 경로가 이를 해제하지 못함을 확인했다. SAGE 수정 경로가 제공될 때까지 `BLOCKED`로 기록한다.

revision 4: 사용자 지시로 native Codex TOML 9개에 필수 필드와 상대 역할 문서 경로만 보정했다. `codex doctor` 형식 경고 0건, 실제 `chatforyou-lead` 호출 성공, SAGE 및 프로젝트 검증 성공으로 `IMPLEMENTED`로 갱신한다.
