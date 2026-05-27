---
name: chatforyou-dev-team
description: ChatForYou v2 주요 기능 개발을 위한 5인 에이전트 팀 조율 워크플로우. 팀 리더, 백엔드 전문가(30년), 프론트 전문가(30년), QA 전문가, 외부 전문가로 구성. PLAN 파일이 필요한 수준의 기능 개발에서만 사용. 단순 버그 수정은 개별 agent 사용.
---

# chatforyou-dev-team 워크플로우

이 skill이 호출되면 아래 순서로 5인 팀을 조율하여 기능 개발을 진행한다.

## Invocation

- 권장 호출 형태: `/chatforyou-dev-team [기능 요구사항]`
- 스킬 직접 호출 형태: `Use $chatforyou-dev-team to coordinate the feature work.`
- 이 스킬이 트리거되면 `chatforyou-agent-registry` 기준으로 등록된 custom agent만 사용해 팀을 소집한다.

---

## 팀 해산 명령어 처리

인자가 다음 중 하나인 경우 팀 해산으로 처리한다:
- `exit`
- `팀해산`
- `팀 종료`

**팀 해산 절차**:
1. 현재 활성 중인 chatforyou-dev-team 에이전트(lead, backend, frontend, qa, external)가 있으면 종료 메시지 전달
2. 진행 중이던 PLAN 파일이 있으면 미완료 항목을 유저에게 요약 보고
3. "chatforyou-dev-team이 해산되었습니다." 메시지 출력

위 명령어가 아닌 인자는 **기능 개발 요청**으로 처리한다.

---

## 팀 구성

| 역할 | Agent | 색상 | 핵심 도구 | 선택적 스킬 (기능 맥락에 따라) |
|------|-------|------|---------|------|
| 팀 리더 | `chatforyou-lead` | 파란색 | write-plan, feature-dev | `gstack:office-hours`, `gstack:plan-eng-review` |
| 백엔드 전문가 | `chatforyou-backend-expert` | 초록색 | backend-architect, backend-convention-checker | `bkit:bkend-auth/data/storage/cookbook`, `gstack:cso`, `gstack:investigate` |
| 프론트 전문가 | `chatforyou-frontend-expert` | 노란색 | javascript-pro, frontend-convention-checker | `bkit:phase-5/6`, `gstack:browse` |
| QA 전문가 | `chatforyou-qa-expert` | 빨간색 | backend-test-layer skill, backend-test-convention-checker | `bkit:qa-phase`, `bkit:zero-script-qa`, `gstack:qa`, `gstack:investigate` |
| 외부 전문가 | `chatforyou-external-expert` | 주황색 | code-reviewer, code-review:code-review | `bkit:audit`, `bkit:code-review`, `gstack:review`, `gstack:codex`, `gstack:cso` |

> **선택적 스킬 원칙**: 각 팀원은 기능 종류에 맞는 스킬을 자율적으로 판단하여 호출한다. 각 agent 파일의 "선택적 스킬 호출" 섹션 참고.

---

## 팀 활성화 조건

**사용 O**: 신규 기능 추가, 주요 리팩토링, 다수 파일에 걸친 변경 (PLAN 파일이 필요한 수준)

**사용 X**: 단순 버그 수정 1~2줄, 텍스트 수정, 개별 agent로 충분한 작업

---

## 워크플로우 실행 순서

### STEP 1: 팀 리더 — 설계 검증 및 분석 요약 작성

`chatforyou-lead` agent를 호출하여 먼저 `plan_docs/N월_[기능]_plan.md` 존재 여부를 확인한다.

> ⚠️ **MANDATORY FIRST**: 팀 리더는 설계 파일을 읽기 전에 먼저 `AGENT_GUIDE.md`의 Risk & Workflow Gate를 확인하고,
> 위험도에 따라 필요한 `docs/agent/*` 문서를 읽은 뒤 아래 세 파일을 읽는다.
> - `docs/springboot_backend.md` — 백엔드 컨벤션 기준
> - `docs/nodejs_frontend.md` — 프론트 컨벤션 기준
> - `docs/chatforyou_desktop.md` — 데스크톱 동기화/직접 수정 금지 기준
>
> 이 세 파일을 기반으로 설계 분석 전체를 진행한다.

**[Path A] 외부 설계 파일이 있는 경우**:
1. (docs 읽기 완료 후) `plan_docs/N월_[기능]_plan.md` 읽기
2. 컨벤션 기준으로 설계 타당성 검증 — 위반 시 이유 명시
3. 기존 구현 가이드 존재 여부 확인 (`springboot-backend/plan_docs/`, `nodejs-frontend/plan_docs/`)
4. **분석 요약 작성 후 각 전문가에게 전달** (구현 가이드는 각 전문가가 직접 작성)
5. 파일 소유권 배분

**[Path B] 외부 설계 파일이 없는 경우**:
1. `plan_docs/ARCHITECT_GUIDE.md` 읽기
2. 유저와 기능 범위·목적 논의 (소스 코드 수정 금지)
3. ARCHITECT_GUIDE.md 표준으로 plan_docs/ 문서 작성
4. 유저 승인 후 분석 요약 작성 및 파일 소유권 배분

```
파일 소유권 분배 규칙:
- 백엔드 전문가: springboot-backend/src/main/ + springboot-backend/plan_docs/ (가이드 작성)
- QA 전문가: springboot-backend/src/test/
- 프론트 전문가: nodejs-frontend/ + nodejs-frontend/plan_docs/ (가이드 작성, chatforyou-desktop/src 제외)

구현 가이드 저장 위치 및 작성 주체:
- 백엔드: springboot-backend/plan_docs/[기능명].md  ← 백엔드 전문가 작성
- 프론트: nodejs-frontend/plan_docs/[기능명].md    ← 프론트 전문가 작성
```

---

### STEP 2: 병렬 개발 — 백엔드 + 프론트

`chatforyou-backend-expert`와 `chatforyou-frontend-expert`를 **병렬**로 호출:

**백엔드 전문가**:
1. 리더 분석 요약 수신
2. **`springboot-backend/plan_docs/[기능명].md` 작성** (기존 파일 있으면 병합)
   - 완전한 Java 코드 스켈레톤 (신규/수정 클래스 전체)
   - 각 마이그레이션 지점별 Before/After 코드
   - JUnit 단위 테스트 템플릿 (상세 구현은 QA 전문가 담당)
   - 아래 TODO 체크박스 항목 필수 포함
     - [ ] Development feature list
     - [ ] Test scenarios and validation method
     - [ ] Code conventions
3. springboot-backend/src/main/ 개발
4. src/test/service/ 에 Service 단위 테스트 작성 (정상 케이스 + 단순 예외)
5. backend-convention-checker로 자체 검증
6. 검증 결과에 따라 구현 가이드 체크박스 갱신
   - `Development feature list`: 실제 구현 완료 후만 `[x]`
   - `Test scenarios and validation method`: 테스트 코드 또는 검증 절차가 준비된 경우만 `[x]`
   - `Code conventions`: backend-convention-checker 통과 후만 `[x]`
7. 잔존 항목이 있으면 구현 가이드 하단 `Open Issues` 또는 `Remaining Risks`에 기록

**프론트 전문가**:
1. 리더 분석 요약 수신
2. 구현 전 `AGENT_GUIDE.md`, `docs/nodejs_frontend.md`, `docs/chatforyou_desktop.md` 재확인
2. **`nodejs-frontend/plan_docs/[기능명].md` 작성** (기존 파일 있으면 병합)
   - 신규/수정 파일의 full skeleton guide
   - 주요 변경 지점별 Before/After 코드
   - 파일별 변경 단계 및 핵심 코드 스니펫
   - 테스트 코드를 제외한 테스트 시나리오 및 검증 방법
   - 아래 TODO 체크박스 항목 필수 포함
     - [ ] Development feature list
     - [ ] Test scenarios and validation method
     - [ ] Code conventions
3. nodejs-frontend/ 개발
4. frontend-convention-checker로 자체 검증
5. 검증 결과에 따라 구현 가이드 체크박스 갱신
   - `Development feature list`: 실제 구현 완료 후만 `[x]`
   - `Test scenarios and validation method`: 테스트 시나리오와 검증 절차가 정리된 경우만 `[x]`
   - `Code conventions`: frontend-convention-checker 통과 후만 `[x]`
6. 예외 처리/로그 표시 관련 미해결 항목이 있으면 구현 가이드 하단 `Open Issues` 또는 `Remaining Risks`에 기록
7. `chatforyou-desktop/src` 직접 수정 금지
8. 데스크톱 반영이 필요하면 `nodejs-frontend` 기준으로 수정 후 `docs/chatforyou_desktop.md`의 sync 절차로 처리

### STEP 2 Exit Gate

STEP 3 QA로 넘어가기 전에 아래 항목이 모두 충족되어야 한다:

1. 백엔드/프론트 변경 범위에 대한 컨벤션 검증이 완료되었다.
2. 각 구현 가이드의 TODO 체크박스가 실제 상태에 맞게 갱신되었다.
3. 남아 있는 예외 처리 TODO, 로그 상세화 누락, 레거시 예외 흔적이 있으면 문서의 `Open Issues` 또는 `Remaining Risks`에 기록되었다.

위 3개 중 하나라도 빠지면 QA 단계로 넘어가지 않는다.

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
2. STEP 2 Exit Gate 충족 여부 확인
   - 구현 가이드 체크박스 상태 확인
   - 백엔드/프론트 컨벤션 검증 결과 확인
3. 외부 전문가 Critical 항목 유저에게 전달
4. PLAN 파일 체크리스트 완료 표시
5. commit 메시지 추천 (실제 commit은 유저가 직접)

---

## 팀 워크플로우 다이어그램

```
[plan_docs/N월_[기능]_plan.md] ← 외부 전문가 작성
    ↓
[chatforyou-lead]
  외부 설계 검증 + 구현 가이드 작성 + 파일 소유권 배분
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
- **구현 시작 전 `AGENT_GUIDE.md`와 관련 `docs/*.md` 재확인 필수**
- **`chatforyou-desktop/src` 직접 수정 금지** — 웹 공통 변경은 `nodejs-frontend`에서 처리 후 sync
- 외부 전문가의 Critical 항목은 유저에게 반드시 보고
- custom agent 위임 시 `.codex/config.toml` 등록 정보와 `.codex/agents/*.toml` 설정을 source of truth로 사용
