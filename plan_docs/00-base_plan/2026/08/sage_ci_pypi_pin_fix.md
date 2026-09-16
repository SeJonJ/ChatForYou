<!-- SAGE Phase 00 skeleton: fill the plan before governed source edits. -->
# [Base Plan] sage_ci_pypi_pin_fix

Cycle-Stem: `sage_ci_pypi_pin_fix`
Risk Level: L2
ChatForYou-Component-Doc-Gate: v1
Component-Backend: N/A: CI 워크플로 설치 방식만 변경하며 backend 제품 코드와 계약은 변경하지 않는다.
Component-Frontend: N/A: CI 워크플로 설치 방식만 변경하며 frontend 및 desktop 제품 코드와 계약은 변경하지 않는다.

## 0. Prior Knowledge

| Type | Note | Key Takeaway |
|------|------|--------------|
| session | 2026-08-10 CI 실패(run 31400455462) 근본원인 분석 | `sage-asset-integrity.yml`이 SAGE를 git 태그 체크아웃+editable install로 설치해 `sage validate`의 `source-build-identity` 체크가 STALE(exit 3)로 차단. 로컬 pipx 설치와 CI 설치가 서로 다른 방식이라 해시가 갈림(`.gitkeep` 2개가 PyPI 번들엔 없고 git 저장소엔 있음 — 직접 diff로 확인) |

## 1. Summary (Goal & Scope)

`.github/workflows/sage-asset-integrity.yml`의 SAGE 설치 단계를 "SeJonJ/SAGE 저장소를 git
태그로 체크아웃 후 editable install"에서 **SAGE 공식 README가 안내하는 표준 방식**
(`pip install "sage-harness[schema]==<required_version>"`, PyPI)으로 바꾼다. 버전은
`sage/project-profile.yaml`의 `sage.required_version`에서 동적으로 읽어, 프로젝트가
SAGE를 업그레이드해도 워크플로 파일을 별도로 안 고쳐도 되게 한다.

이 CI 방식(git 체크아웃)은 2026-07-17 최초 SAGE 적용 당시 SAGE가 아직 PyPI 정식 릴리스가
없어 개발 브랜치(`feat/overlay-composition`)를 직접 끌어와야 했던 시절의 임시방편이었고,
SAGE가 정식 버전을 릴리스하기 시작한 뒤에도 갱신되지 않고 남아있었다.

## 2. Impact Analysis (Critical)

- **CI 워크플로만** 변경. 제품 코드(backend/frontend/desktop) 0건 변경.
- 로컬 개발 방식(pipx)과 CI 설치 방식이 이제 동일해져, 향후 "로컬은 되는데 CI만 깨짐"류의
  설치 경로 불일치 문제가 구조적으로 사라진다.

## 3. Technology & Risks

- 위험 낮음 — 설치 소스만 바꾸고(git 체크아웃 → PyPI), 실행하는 SAGE 버전(`0.9.80`)은 동일.
  로컬에서 동일한 pip 설치+`sage validate --check --kind all --schema --strict`를 재현해
  `source-build-identity` PASS·exit 0을 사전 확인함(§03 참조).
- 버전을 하드코딩하지 않고 profile에서 읽으므로, 다음 SAGE 업그레이드 시 이 워크플로가 다시
  낡은 버전에 박제될 위험도 없다.

## 4. Final Conclusion & UX Guide

사용자에게 보이는 변화 없음. 다음 push부터 `SAGE Asset Integrity` 워크플로가 정상적으로
그린이 된다.

## 5. Document Mapping (Checklist)

- [x] 00 Base Plan (this file)
- [x] 01 Plan — **N/A**: 신규 요구사항/데이터모델/API 계약 없음(CI 설치 방식 정정)
- [x] 02 Design — **N/A**: 새 아키텍처 없음
- [x] 03 Implementation
- [x] 04 Analyze
- [x] 05 Expert Review
- [x] 06 Report
