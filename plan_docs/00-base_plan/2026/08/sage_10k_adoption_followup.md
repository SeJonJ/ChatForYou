# [Base Plan] SAGE 10-k ChatForYou 적용 후속 정리

Cycle-Stem: `sage_10k_adoption_followup`
Risk Level: L2
ChatForYou-Component-Doc-Gate: v1
Component-Backend: N/A: SAGE 거버넌스·검증 자산만 정리하며 backend 제품 코드와 계약은 변경하지 않는다.
Component-Frontend: N/A: SAGE 거버넌스·검증 자산만 정리하며 frontend 및 desktop 제품 코드와 계약은 변경하지 않는다.

## 0. Prior Knowledge

| Type | Source | Key takeaway |
|---|---|---|
| 사용자 지시 | 현재 대화 | 1→2→3→4→6 순서로 구현하고 5번 기본 브랜치 전환은 설계만 수행한다. |
| 사용자 지시 | 현재 대화 | Claude 토큰 제약으로 L2 추가 cross-review는 생략하고 필요 시 짧은 자체 리뷰만 수행한다. |
| live verification | `sage doctor` | Codex 의도 scope는 global이며 프로젝트 로컬 CORE 10종이 중복되고 `sage-cycle`·`sage-plan`·`sage-team`은 내용 충돌 상태다. |
| live verification | hook/adoption tests | Claude 러너는 stderr를 누락하고 adoption fixture는 옛 `cycle_id`/`cycle_ids` 계약을 사용한다. |
| knowledge scan | `.sage/knowledge_scan.md` | 10-k v0.9.80 릴리스와 ChatForYou 역적용 완료 기록을 확인했다. 이번 범위는 소비자 후속 정리다. |

## 1. Goal and Scope

SAGE 0.9.80/10-k 핵심 기능은 유지하면서 ChatForYou 소비자 저장소에 남은 scope 중복과 검증
계약 드리프트를 제거한다. 기존 `sage_cycle_declaration_adoption` 사이클 문서는 실제 산출물과
사용자 승인에 맞게 정합화한다.

구현 순서는 고정한다.

1. 프로젝트 로컬 Codex CORE 10종 삭제
2. Claude hook 테스트의 stdout+stderr 판정 수정
3. adoption 회귀의 exact `cycle_stem` 계약 갱신
4. 기존 10-k 00 문서의 01/02 N/A 승인 및 03~06 체크 정합화
5. 기본 브랜치 전환은 설계만 작성하고 저장소·GitHub 상태를 변경하지 않음
6. 전체 fresh verification

## 2. Impact and Boundaries

- **변경 대상**: `.codex/skills/sage-*` 추적 사본 삭제, 프로젝트 소유 Claude hook 러너,
  verification profile 명령, `scripts/test-sage-adoption.sh`, 기존 10-k 00 문서, 이 follow-up의 00~06 문서.
- **보존 대상**: 전역 `~/.codex/skills/sage-*`, 프로젝트 고유 Codex 스킬 7종,
  `.codex/skills/.system`, ChatForYou 제품 소스, component-local 03 문서.
- **설계 전용**: workflow, ship/convention skill spec, generated render, GitHub 기본 브랜치·보호 정책.
- **감사 정책**: `.sage/override.jsonl` 추적 유지, loop/retro audit JSONL Git 제외 유지.
- **금지**: commit, push, reset, reinstall, 생성 자산 손편집.

## 3. Risk and Review Decision

- `.codex/**`, `.claude/**`, `scripts/**` 변경이므로 compound risk는 L2다.
- 삭제 대상은 manifest receipt의 반대 scope인 추적 CORE 사본으로 한정하며 삭제 전후 목록을 비교한다.
- 테스트 수정은 기존 실패를 RED 증거로 사용하고 최소 변경 후 GREEN을 확인한다.
- 추가 Claude cross-review는 사용자 승인으로 생략한다. Phase 05는 diff와 fresh verification을
  근거로 짧은 자체 리뷰만 기록한다.

## 4. Acceptance Summary

- A1: doctor에서 Codex CORE duplicate/content conflict 및 연쇄 overlay 실패가 사라진다.
- A2: Claude 원본 hook runner 22/22, Codex 23/23이 통과한다.
- A3: `scripts/test-sage-adoption.sh`가 assertion 없이 전체 통과한다.
- A4: 기존 10-k 00 문서가 01/02 N/A 사용자 승인과 03~06 실재 상태를 정확히 기록한다.
- A5: 기본 브랜치 전환 설계는 남지만 관련 구현 파일 diff는 0건이다.
- A6: strict validate, 전용 hook 61건, 프로젝트 검증, diff check가 통과하고 제품 diff가 0건이다.

## 5. Document Mapping

- [x] 00 Base Plan — this file
- [x] 01 Plan — `plan_docs/01-plan/sage_10k_adoption_followup.md`
- [x] 02 Design — `plan_docs/02-design/sage_10k_adoption_followup.md`
- [x] 03 Implementation — local PDCA artifact
- [x] 04 Analyze — local PDCA artifact
- [x] 05 Expert Review — local brief self-review; cross-review N/A by user approval
- [x] 06 Report — local PDCA artifact after APPROVED

## 6. Ownership

- Primary implementer: Codex root — all scoped governance/test/document changes, strictly sequential.
- Backend implementer: N/A — no backend product impact.
- Frontend implementer: N/A — no frontend/desktop product impact.
- Integration point: `sage doctor` and the profile-declared verification commands after all scoped fixes.
