<!-- SAGE Phase 00 skeleton: fill the plan before governed source edits. -->
# [Base Plan] sage_cycle_declaration_adoption

Cycle-Stem: `sage_cycle_declaration_adoption`
Risk Level: L2
ChatForYou-Component-Doc-Gate: v1
Component-Backend: N/A: SAGE 프레임워크 버전 동기화만 수행하며 backend 제품 코드와 계약은 변경하지 않는다.
Component-Frontend: N/A: SAGE 프레임워크 버전 동기화만 수행하며 frontend 및 desktop 제품 코드와 계약은 변경하지 않는다.

## 0. Prior Knowledge

| Type | Note | Key Takeaway |
|------|------|--------------|
| memory | `chatforyou-doc-gate-shipped-10k-pending` | 10-k 의 구체 블로커: 어떤 스킬도 `sage cycle` 을 언급하지 않아 `clear` 의 주인이 없었고, `SAGE_CYCLE_STEM` env 가 세션에 남으면 파일 선언을 이긴다는 것을 실측함 |
| upstream | `sage_project` `2ead8fe feat :: 사이클 선언 사용성 완성` (v0.9.80) | `sage cycle use`→`set` 교체, `--create` Phase00 생성 경로, `sage-cycle`/`sage-plan`/`sage-team` 스킬에 선언·대조·해제 배선 추가 |

## 1. Summary (Goal & Scope)

SAGE 프레임워크를 0.9.79 → 0.9.80 으로 역적용한다. 이번 릴리스(10-k)는 지난 사이클
(`chatforyou_dual_implementation_doc_gate`, 06 §5)에서 명시적으로 남긴 잔여 항목 —
"어떤 스킬도 `sage cycle` 을 호출하지 않아 해제의 주인이 없다" — 를 상류에서 해소한 것이다.

범위는 **프레임워크 자산 동기화**로 한정한다: `sage install --force`(claude+codex)로
CORE 템플릿(스킬/AGENT_GUIDE/PDCA 템플릿/hook 런타임)을 받고, `required_version` 을
올리고, `sage generate`/`validate` 로 재정합한다. ChatForYou 제품 소스(백엔드/프론트/
데스크톱)는 이 사이클의 범위가 아니며 실제로 0건 변경된다.

## 2. Impact Analysis (Critical)

- **거버넌스 자산** (AGENT_GUIDE.md, docs/agent/pdca-templates.md, `.claude/skills/{sage-cycle,sage-plan,sage-team}`,
  hook 런타임 3개 파일): CORE 렌더 갱신. 이 프로젝트가 자체 authored 한
  `chatforyou-dual-implementation-doc-gate` 훅은 CORE 변경 대상이 아니므로 영향 없음 —
  회귀 61건으로 별도 확인함(§ 03).
- **제품 컴포넌트** (springboot-backend / nodejs-frontend / chatforyou-desktop): 영향 없음.
- **프로젝트 governance 상태** (`.sage/cycle.json`, `sage/project-profile.{yaml,json}`):
  `required_version` 1줄 변경 + manifest 재스탬프.

## 3. Technology & Risks

- 위험은 낮다 — 상류 SAGE 자체가 자기 저장소에서 별도 PDCA/테스트로 이미 검증한 릴리스를
  "받아 적용"하는 것이지, 이 프로젝트에서 새로 설계하는 로직이 아니다.
- 실측된 잔여 마찰(신규 위험 아님, 이미 문서화됨): `SAGE_CYCLE_STEM` env 가 세션에 고착되면
  파일 선언(`sage cycle set`)보다 우선한다 — 이번 사이클 진행 중에도 실제로 재현됐고
  `sage override`(감사됨, 2h TTL)로 우회했다. 이 우선순위는 10-k 의 설계 의도이지 결함이
  아니다(CI 오버라이드 용도) — CLI 가 `sage cycle show`/`sage cycle set` 양쪽에서 그 사실을
  명시적으로 출력하는 것이 10-k 가 개선한 지점이다(§ 03 참조).
- 유저 승인: L2 review-rework 루프(profile `pdca.review_loop.enabled: true`)는 AGENT_GUIDE
  원칙상 L2 에서 "권장(먼저 물어보기)"이다 — 사전 확인 결과 유저가 별도 리뷰 루프 생략을
  명시 승인함(Phase 05 에 기록).
- 후속 정합화 승인(2026-08-09): 사용자가 순수 프레임워크 버전 동기화라는 기존 사유를
  재확인하고 01/02 를 별도 문서 없이 N/A로 유지하는 것을 승인했다. 이 승인은
  `sage_10k_adoption_followup` 사이클에서 기록했으며 과거 구현 범위를 확장하지 않는다.

## 4. Final Conclusion & UX Guide

사용자에게 보이는 변화 없음(순수 내부 거버넌스 프레임워크). 팀이 체감하는 차이는 다음
사이클부터: `sage cycle set <stem>` 이 `sage-plan`/`sage-team` 스킬 흐름에 배선돼 있어
수동으로 `sage cycle clear` 를 기억할 필요가 줄어든다.

## 5. Document Mapping (Checklist)

- [x] 00 Base Plan (this file)
- [x] 01 Plan — **N/A (user-approved)**: 신규 요구사항/데이터모델/API 계약이 없다(순수 버전 동기화이므로
  01 이 다룰 대상 자체가 존재하지 않음)
- [x] 02 Design — **N/A (user-approved)**: 이 프로젝트에서 새로 설계하는 아키텍처가 없다(설계는 상류
  SAGE 저장소 쪽에서 이미 완료·릴리스됨)
- [x] 03 Implementation — `plan_docs/03-implementation/sage_cycle_declaration_adoption.md`
- [x] 04 Analyze — `plan_docs/04-analyze/sage_cycle_declaration_adoption.md`
- [x] 05 Expert Review — `plan_docs/05-expert-review/sage_cycle_declaration_adoption.md`
- [x] 06 Report — `plan_docs/06-report/sage_cycle_declaration_adoption.md`
