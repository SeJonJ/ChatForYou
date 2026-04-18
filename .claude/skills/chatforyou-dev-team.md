---
name: chatforyou-dev-team
description: ChatForYou v2 주요 기능 개발을 위한 5인 에이전트 팀 조율 워크플로우. 팀 리더, 백엔드 전문가(30년), 프론트 전문가(30년), QA 전문가, 외부 전문가로 구성. PLAN 파일이 필요한 수준의 기능 개발에서만 사용. 단순 버그 수정은 개별 agent 사용.
---

# chatforyou-dev-team 워크플로우

이 skill이 호출되면 아래 순서로 5인 팀을 조율하여 기능 개발을 진행한다.

---

## 팀 구성

| 역할 | Agent | 색상 | 핵심 도구 |
|------|-------|------|---------|
| 팀 리더 | `chatforyou-lead` | 파란색 | write-plan, feature-dev |
| 백엔드 전문가 | `chatforyou-backend-expert` | 초록색 | backend-architect, backend-convention-checker |
| 프론트 전문가 | `chatforyou-frontend-expert` | 노란색 | javascript-pro, frontend-convention-checker |
| QA 전문가 | `chatforyou-qa-expert` | 빨간색 | backend-test-layer skill, backend-test-convention-checker |
| 외부 전문가 | `chatforyou-external-expert` | 주황색 | code-reviewer, code-review:code-review |

---

## 팀 활성화 조건

**사용 O**: 신규 기능 추가, 주요 리팩토링, 다수 파일에 걸친 변경 (PLAN 파일이 필요한 수준)

**사용 X**: 단순 버그 수정 1~2줄, 텍스트 수정, 개별 agent로 충분한 작업

---

## 워크플로우 실행 순서

### STEP 1: 팀 리더 — 요구사항 분석 및 PLAN 작성

`chatforyou-lead` agent를 호출하여:
1. 유저 요구사항 분석 및 범위 확정
2. PLAN 파일 작성 (파일 소유권 배분 포함)
3. 팀원별 작업 지시 작성

```
파일 소유권 분배 규칙:
- 백엔드 전문가: springboot-backend/src/main/
- QA 전문가: springboot-backend/src/test/
- 프론트 전문가: nodejs-frontend/ (chatforyou-desktop/src 제외)
```

---

### STEP 2: 병렬 개발 — 백엔드 + 프론트

`chatforyou-backend-expert`와 `chatforyou-frontend-expert`를 **병렬**로 호출:

**백엔드 전문가**:
- springboot-backend/src/main/ 개발
- src/test/service/ 에 Service 단위 테스트 작성 (정상 케이스 + 단순 예외)
- backend-convention-checker로 자체 검증

**프론트 전문가**:
- nodejs-frontend/ 개발
- frontend-convention-checker로 자체 검증
- chatforyou-desktop/src 직접 수정 금지

---

### STEP 3: QA — 시나리오/통합/경계값 테스트

`chatforyou-qa-expert` agent를 호출하여:
1. 백엔드 전문가 결과물 확인 (src/main/ + 단위 테스트)
2. 백엔드 전문가가 놓친 케이스 중심으로 시나리오 설계
3. @WebMvcTest (HTTP/인증/경계값) + @SpringBootTest (통합/동시성) 테스트 작성
4. backend-test-convention-checker로 검증

> 프론트 테스트는 담당하지 않음. 프론트는 frontend-convention-checker로 컨벤션만 검증.

---

### STEP 4: 외부 전문가 — 종합 검증

`chatforyou-external-expert` agent를 호출하여:
1. 백엔드/프론트/QA 전원 결과물 수신
2. 팀 의견 상충 지점, 누락 위험 요소 분석
3. 종합 검증 리포트 작성 (Critical / Suggestions / 통합 리스크)

---

### STEP 5: 팀 리더 — 최종 취합

`chatforyou-lead` agent가:
1. 전원 결과물 취합
2. 외부 전문가 Critical 항목 유저에게 전달
3. PLAN 파일 체크리스트 완료 표시
4. commit 메시지 추천 (실제 commit은 유저가 직접)

---

## 팀 워크플로우 다이어그램

```
[기능 요청]
    ↓
[chatforyou-lead]
  PLAN 파일 작성 + 파일 소유권 배분
    ↓ (병렬)
[chatforyou-backend-expert]   [chatforyou-frontend-expert]
  src/main/ 개발                nodejs-frontend/ 개발
  backend-convention-checker    frontend-convention-checker
    ↓                                   ↓
[chatforyou-qa-expert]
  src/test/ 테스트 코드 작성
  backend-test-layer skill 기준
  backend-test-convention-checker 검증
    ↓
[chatforyou-external-expert]
  전원 결과물 수신 → 종합 분석 → 검증 리포트
    ↓
[chatforyou-lead]
  최종 취합 + commit 메시지 추천
  (commit/push는 유저 직접)
```

---

## 팀 공통 규칙

- **commit / push 절대 금지** — 모든 팀원 공통
- **파일 소유권 엄수** — 배분되지 않은 파일 수정 금지
- **PLAN 파일 없이 시작 금지**
- 외부 전문가의 Critical 항목은 유저에게 반드시 보고
