# [Base Plan] ChatForYou 컴포넌트 03 문서 게이트

Cycle-Stem: chatforyou_dual_implementation_doc_gate
Risk Level: L2
ChatForYou-Component-Doc-Gate: v1
Component-Backend: N/A: ChatForYou governance hook만 변경하며 backend 제품 코드와 계약은 변경하지 않는다.
Component-Frontend: N/A: ChatForYou governance hook만 변경하며 frontend 및 desktop 제품 코드와 계약은 변경하지 않는다.

## 0. Prior Knowledge

| Type | Note | Key Takeaway |
|---|---|---|
| Product roadmap | `SPEC - ChatForYou 기능 개발 우선순위 로드맵` P0 #0 | 컴포넌트 문서 의무는 SAGE CORE가 아니라 ChatForYou가 소유한다. |
| SAGE roadmap | `SAGE - 앞으로 개발할 내용` 10-i | 범용 `checklist_scan_targets` 객체 계약을 먼저 정상화하되 ChatForYou의 컴포넌트 규칙은 포함하지 않는다. |
| Project policy | `docs/chatforyou-agent/pdca-extensions.md` | 실제 영향 컴포넌트만 문서를 작성하고 영향이 없으면 명시적 N/A 근거를 남긴다. |
| Runtime evidence | SAGE 0.9.76 live reproduction | 신규 hook ID는 manifest에 등록할 수 없고 unknown dispatch는 exit 0으로 통과한다. |

## 1. Summary (Goal & Scope)

ChatForYou 신규 개발 사이클이 Phase 04에 진입하기 전에 backend/frontend별 구현 문서 계약을
결정론적으로 검사하는 project-authored hook을 추가한다. 영향받는 컴포넌트는 같은 cycle-stem의
로컬 03 문서가 존재하고 완료돼야 한다. 영향이 없는 컴포넌트는 같은 stem의 00 base-plan에
비어 있지 않은 N/A 사유를 기록해야 한다.

기존 CORE `pre-phase4-checklist-gate`는 공통 root 03 문서 검사를 계속 소유한다. 새 hook은
ChatForYou 컴포넌트 영향 선언, N/A 근거, 컴포넌트 문서 존재 및 완료 여부만 독립적으로 소유한다.

## 2. Impact Analysis (Critical)

- Governance: 신규 hook spec, pure core, 양 host adapter/등록, manifest 및 hook 테스트가 영향 대상이다.
- Backend: N/A. 제품 소스, API, DB, 빌드 설정을 변경하지 않는다.
- Frontend/Desktop: N/A. 제품 소스, 브라우저 계약, desktop sync 자산을 변경하지 않는다.
- Git policy: hook 자산과 설정은 추적하지만 component plan docs와 Phase 01~06은 기존처럼 로컬 산출물로 유지한다.
- SAGE CORE: 직접 수정하지 않는다. 필요한 범용 기능은 upstream 릴리스 선행조건으로 분리한다.

## 3. Technology & Risks

### 3.1 Risk classification

`scripts/**`, `.claude/**`, `.codex/**`, `sage/**`는 profile상 L2다. hook이 Phase 전환을
차단하므로 오탐과 우회 모두 운영 위험이며 build, test, lint 검증을 차단 게이트로 적용한다.

### 3.2 Confirmed prerequisites

1. SAGE 10-i가 릴리스되고 ChatForYou에 역적용되어 `checklist_scan_targets`의 YAML, compiled JSON,
   schema, runtime 계약이 객체 배열로 일치해야 한다.
2. SAGE가 신규 project-authored hook에 대해 다음 세 동작을 공식 지원해야 한다.
   - orphan spec/core에서 manifest entry를 생성하는 등록 경로
   - 양 host canonical adapter를 제공하거나 생성하는 경로
   - 새 hook ID를 실제 core로 dispatch하는 범용 런타임 경로
3. 위 지원 없이 manifest, shared `run_hook.py`, generated host 설정을 직접 편집하는 우회는 금지한다.

현재 설치본 0.9.76에서 prerequisite 1과 2는 충족되지 않았다. 따라서 00~02 설계는 진행하되
03 구현은 upstream 계약이 릴리스되고 ChatForYou 설치본이 갱신될 때까지 차단한다.

### 3.3 New-cycle boundary

단순 opt-in marker 누락을 legacy로 간주하면 신규 사이클이 규칙을 우회할 수 있다. rollout 시점에
존재하는 Phase 04 stem을 정규화한 legacy allowlist를 hook core에 고정한다. allowlist에 없는 stem은
신규 사이클로 간주하고 00 base-plan marker와 component 선언 누락을 차단한다.

## 4. Final Conclusion & UX Guide

독립형 project-authored hook 설계는 ChatForYou의 소유권 경계와 일치한다. 다만 현재 SAGE 0.9.76의
신규 hook lifecycle은 문서와 런타임이 불일치하므로 구현을 강행하면 install 재실행 시 사라지거나
등록돼도 무조건 통과하는 장식용 gate가 된다. upstream 지원을 먼저 완성한 뒤 동일 사이클의 03에서
구현을 재개한다.

신규 00 base-plan 작성자는 다음 세 선언을 정확히 한 번 기록한다.

- `ChatForYou-Component-Doc-Gate: v1`
- `Component-Backend: REQUIRED` 또는 `Component-Backend: N/A: 구체적 사유`
- `Component-Frontend: REQUIRED` 또는 `Component-Frontend: N/A: 구체적 사유`

## 5. Document Mapping (Checklist)

- [x] Phase 00: 배경, 영향, 위험, 선행조건 및 컴포넌트 N/A 근거
- [x] Phase 01: 요구사항, 입력/출력 계약 및 acceptance matrix
- [x] Phase 02: pure core 구조, 파싱 규칙, 오류 및 테스트 설계
- [ ] Phase 03: upstream 선행조건 충족 후 spec/core/tests 작성과 generate
- [ ] Phase 04: 설계 대비 구현 및 acceptance 증거 분석
- [ ] Phase 05: 독립 리뷰와 최종 판정
- [ ] Phase 06: APPROVED 이후 완료 보고
