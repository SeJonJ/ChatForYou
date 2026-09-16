# [Base Plan] ChatForYou 기본 브랜치 전환

Cycle-Stem: `default_branch_transition`
Risk Level: L2
Status: IN PROGRESS

## 0. Prior Knowledge

| Type | Note | Key Takeaway |
|---|---|---|
| Prior design | `sage_10k_adoption_followup.md` | 저장소 내부 변경과 원격 GitHub 전환을 분리하고 롤백 경로를 유지한다. |
| Live repository | old-branch reference inventory | workflow, 검증 예시, convention skill, ship skill에 기존 브랜치 참조가 남아 있다. |
| Runtime discovery | Claude/Codex project skills | `chatforyou-ship`은 이미 양 호스트 경로에 있으므로 SSOT 수정 후 동등 생성·검증하는 것이 핵심이다. |

## 1. Summary (Goal & Scope)

ChatForYou의 기본 개발·배포 기준을 `chatforyou_v2_sage`로 전환하도록 저장소 내부 자산을
정비한다. GitHub Actions branch filter, 비교 기준 문서/스크립트, 프로젝트 authored skill의
Claude·Codex 렌더, Desktop 태그 릴리즈 절차를 포함한다.

이번 사이클은 로컬 저장소 변경까지만 수행한다. GitHub default branch, branch protection,
required checks, remote HEAD, 실제 tag/release/push는 사용자가 커밋·push한 뒤 수행할 후속 작업이다.

## 2. Impact Analysis (Critical)

ChatForYou-Component-Doc-Gate: v1
Component-Backend: N/A: Java/Gradle 제품 구현을 변경하지 않는 기본 브랜치 운영 자산 전환이다.
Component-Frontend: N/A: Node.js/Electron 제품 구현을 변경하지 않는 기본 브랜치 운영 자산 전환이다.

- Backend: N/A — Java/Gradle 제품 구현을 변경하지 않는다. 사유는 기본 브랜치 운영 자산 전환이다.
- Frontend: N/A — Node.js 제품 구현을 변경하지 않는다. 사유는 기본 브랜치 운영 자산 전환이다.
- Desktop: N/A — Electron 제품 구현을 변경하지 않는다. 릴리즈 스킬의 절차만 갱신한다.
- CI/CD: 배포 workflow의 branch filter가 새 기본 브랜치를 수신하도록 변경한다.
- SAGE/agent runtime: authored skill SSOT와 Claude·Codex·legacy 렌더의 새 브랜치 일관성을 보장한다.
- Hooks: hook 코드 변경은 계획하지 않지만 기존 브랜치 하드코딩 여부를 전수 확인한다.

컴포넌트 로컬 03 문서는 위 N/A 사유에 따라 생성하지 않는다. 루트 PDCA 문서가 운영 자산의
cross-cutting 계약을 소유한다.

## 3. Technology & Risks

- L2 compound rule: `.github/workflows/**`, `scripts/**`, `.claude/**`, `.codex/**`, SAGE 자산이
  L2이므로 전체 사이클을 L2로 고정한다.
- 가장 큰 위험은 일부 참조만 바뀌어 배포·비교·릴리즈 경로가 서로 다른 브랜치를 가리키는 것이다.
- generated render 직접 수정은 재생성 시 유실되므로 spec/claims 정본과 생성 절차를 따른다.
- `chatforyou-ship`은 merge/tag/push를 수행하는 파괴 가능 rigid skill이므로 사용자 최종 확인과
  중단 조건을 유지하고 대상 브랜치만 안전하게 전환한다.
- tag 기반 Desktop release workflow 자체는 기본 브랜치와 독립적일 수 있으므로 실제 trigger를
  확인하고 불필요한 변경은 하지 않는다.
- L2 cross-review는 사용자 승인에 따라 Claude를 호출하지 않고 짧은 Codex 자체 리뷰로 대체한다.

## 4. Final Conclusion & UX Guide

사용자는 변경 검증 후 커밋·push하고, 원격에 새 브랜치가 존재하는 것을 확인한 뒤 GitHub 기본
브랜치와 보호 규칙을 전환한다. 이후 `$chatforyou-ship` 호출은 Claude와 Codex 모두 새 기본
브랜치를 대상으로 같은 버전 일관성 검증과 사용자 확인 절차를 수행해야 한다.

## 5. Document Mapping (Checklist)

- [x] 00 Base Plan — 범위, L2, 제품 컴포넌트 N/A, 원격 후속 분리
- [x] 01 Plan — 요구사항과 acceptance matrix
- [x] 02 Design — SSOT/생성/배포 흐름과 롤백
- [x] 03 Implementation — 소유권, 변경 목록, 검증 계획
- [ ] 04 Analyze — 설계 대비 구현 gap 및 acceptance evidence
- [ ] 05 Expert Review — 짧은 자체 리뷰와 최종 판정
- [ ] 06 Report — 승인 후 최종 보고, wiki/retro 산출물 명시
