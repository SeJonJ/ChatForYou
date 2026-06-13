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
| 외부 전문가 | `chatforyou-external-expert` | 주황색 | code-reviewer, code-review:code-review, **`$claude consult` (STEP 5 cross-model 필수)** | `bkit:audit`, `bkit:code-review`, `gstack:review`, `gstack:cso` |

> **선택적 스킬 원칙**: 각 팀원은 기능 종류에 맞는 스킬을 자율적으로 판단하여 호출한다. 각 agent 파일의 "선택적 스킬 호출" 섹션 참고.

---

## 팀 활성화 조건

**사용 O**: 신규 기능 추가, 주요 리팩토링, 다수 파일에 걸친 변경 (PLAN 파일이 필요한 수준)

**사용 X**: 단순 버그 수정 1~2줄, 텍스트 수정, 개별 agent로 충분한 작업

---

## 독립 PDCA 사이클 규칙 (MANDATORY — 재발 방지)

유저가 L3 또는 이에 준하는 PDCA 개발 플로우를 명시적으로 요청한 경우, 기술적으로 인접한 버그·기능이더라도 **반드시 신규 00-base_plan부터 시작하는 독립 PDCA 사이클**로 처리한다.

- 같은 파일(kurento-service.js, KurentoHandler.java 등)에 있다는 이유로 기존 사이클 문서를 재사용하거나 덮어쓰는 것은 금지
- "기술적 인접" ≠ "사이클 재사용 허가"
- 근거: 2026-05 세션 만료 + 통화 끊김 장애에서 기존 #126 문서를 재사용하려 한 오판이 프로세스 누락으로 이어진 실증 사례

**위반 징표**: 00-base_plan을 새로 만들지 않고 기존 plan_docs 파일을 수정하거나 기존 00-base_plan을 이번 사이클의 base로 쓰는 모든 행위.

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
6. 결정론 검증 대상이 있는 경우 `docs/agent/verification-protocol.md` 기준으로 `scripts/verify-changes.sh` 실행 결과를 구현 가이드 또는 03-implementation에 기록
7. 검증 결과에 따라 구현 가이드 체크박스 갱신
   - `Development feature list`: 실제 구현 완료 후만 `[x]`
   - `Test scenarios and validation method`: 테스트 코드 또는 검증 절차가 준비되고 필요한 결정론 검증이 `PASS`, `PASS/N/A`, 또는 허용된 `DEGRADE` 로 기록된 경우만 `[x]`
   - `Code conventions`: backend-convention-checker 통과 후만 `[x]`
8. 잔존 항목이 있으면 구현 가이드 하단 `Open Issues` 또는 `Remaining Risks`에 기록

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
5. 결정론 검증 대상이 있는 경우 `docs/agent/verification-protocol.md` 기준으로 `scripts/verify-changes.sh` 실행 결과를 구현 가이드 또는 03-implementation에 기록
6. 검증 결과에 따라 구현 가이드 체크박스 갱신
   - `Development feature list`: 실제 구현 완료 후만 `[x]`
   - `Test scenarios and validation method`: 테스트 시나리오와 검증 절차가 정리되고 필요한 결정론 검증이 `PASS`, `PASS/N/A`, 또는 허용된 `DEGRADE` 로 기록된 경우만 `[x]`
   - `Code conventions`: frontend-convention-checker 통과 후만 `[x]`
7. 예외 처리/로그 표시 관련 미해결 항목이 있으면 구현 가이드 하단 `Open Issues` 또는 `Remaining Risks`에 기록
8. `chatforyou-desktop/src` 직접 수정 금지
9. 데스크톱 반영이 필요하면 `nodejs-frontend` 기준으로 수정 후 `docs/chatforyou_desktop.md`의 sync 절차로 처리

### STEP 2 Exit Gate

STEP 3 QA로 넘어가기 전에 아래 항목이 모두 충족되어야 한다:

1. **결정론 검증 게이트 통과** — `scripts/verify-changes.sh` 를 실행하고, L2 이상에서 필수 검증(build/test/syntax)이 PASS 여야 한다. FAIL(exit 2) 이면 QA로 넘어가지 않고 STEP 2 에서 수정한다. 출력된 `## Verification Evidence` 블록을 `plan_docs/03-implementation/[기능명].md` 에 기록한다. (`docs/agent/verification-protocol.md`)
2. 백엔드/프론트 변경 범위에 대한 컨벤션 검증이 완료되었다.
3. 각 구현 가이드의 TODO 체크박스가 실제 상태에 맞게 갱신되었다 (검증이 실제로 돌아간 뒤에만 `[x]`).
4. 남아 있는 예외 처리 TODO, 로그 상세화 누락, 레거시 예외 흔적이 있으면 문서의 `Open Issues` 또는 `Remaining Risks`에 기록되었다.

위 항목 중 하나라도 빠지면 QA 단계로 넘어가지 않는다.

---

### STEP 3: QA — 시나리오/통합/경계값 테스트

`chatforyou-qa-expert` agent를 호출하여:
1. 백엔드 전문가 결과물 확인 (src/main/ + 단위 테스트)
2. 백엔드 전문가가 놓친 케이스 중심으로 시나리오 설계
3. @WebMvcTest (HTTP/인증/경계값) + @SpringBootTest (통합/동시성) 테스트 작성
4. backend-test-convention-checker로 검증

> 프론트 테스트는 담당하지 않음. 프론트는 frontend-convention-checker로 컨벤션만 검증.

---

### STEP 4: 팀 리더 + QA(보조) — 04-analyze 작성 (설계-구현 gap 분석)

`chatforyou-lead` agent가 (qa 보조):
1. 02-design 과 03-implementation, 실제 구현 코드를 대조하여 설계-구현 gap 분석
2. `chatforyou-qa-expert` 가 STEP 3 에서 작성한 통합/경계/HTTP 테스트의 커버리지 검증 의견을 **보조로** 수신
3. `plan_docs/04-analyze/[기능명].md` 작성
   - Design vs Implementation Gap (Match Rate)
   - Missing Items & Deviations
   - QA Coverage Verification (qa 보조 의견)
   - Review Context for External Model (**필수**)
4. 04 의 `Review Context for External Model` 섹션에는 아래 항목을 반드시 포함한다:
   - Original User Intent
   - Key Decisions During Implementation
   - Scope Changes / Deferred Items
   - Design vs Implementation Notes
   - QA Coverage Summary
   - Known Risks / Open Questions
   - Files External Reviewer Must Inspect
5. 04 자체로 단독 판정(APPROVED/FAIL)을 내리지 않는다 — 판정은 STEP 5 external-expert + Claude 에서 종합

---

### STEP 5: 외부 전문가 + Claude 교차검증 — 05-expert-review 작성

`chatforyou-external-expert` agent를 호출하여:
1. 백엔드/프론트/QA 전원 결과물 + STEP 4 의 04 gap findings 수신
2. 팀 의견 상충 지점, 누락 위험 요소 분석
3. **결정론 검증 Gate**
   - Review loop 시작 전에 `docs/agent/verification-protocol.md` 기준으로 `scripts/verify-changes.sh`를 재실행한다.
   - 결과가 `PASS` 또는 `PASS/N/A`이면 Phase 05 진입 가능하다.
   - `DEGRADE`는 검증 인프라 부재 사유와 사람이 수용한 근거를 05에 기록한 경우에만 계속 진행한다.
   - `BLOCK` 또는 필수 검증 FAIL이면 Phase 05 review-rework loop를 시작하지 않고 STEP 2/3 rework로 되돌린다.
4. **Core WebRTC Architecture Gate**
   - Review loop 시작 전에 WebRTC 코어 기능의 아키텍처 변경 여부를 확인한다.
   - 대상: WebRTC, WebSocket, Signaling, Kurento, ICE/SDP, DataChannel, room lifecycle, media pipeline, signaling event contract, recovery/reconnect state machine.
   - 새 아키텍처 변경이 감지되면 자동 review-rework를 시작하지 않고 유저 사전 확인을 받는다.
   - 단순 버그 수정이나 기존 승인 설계 안의 국소 수정은 이 gate의 사전 확인 대상이 아니다.
5. **Claude 교차검증 (cross-model independent review) — MANDATORY**
   - 기본: `$claude consult` 호출
   - 입력은 `plan_docs/00-base_plan/[feature].md`, `01-plan`, `02-design`, `03-implementation`, `04-analyze`, `springboot-backend/plan_docs/[feature]_plan.md`, `nodejs-frontend/plan_docs/[feature]_plan.md`, 구현 파일 목록으로 고정한다.
   - 요청: "설계 기준 구현 정합 + 설계-구현 gap + 누락/엣지/보안/lifecycle 리스크" review
   - 결과를 05 에 `Reviewer: Claude via $claude consult` 로 명시
   - cross-model 동의는 권고이지 결정이 아님 (최종 판단 = external-expert + 유저)
6. **Risk별 review-rework loop 적용 기준**
   - L3: Phase 05에서 3-iteration review-rework loop를 mandatory로 자동 실행한다.
   - L2: review-rework loop는 recommended이며, loop 실행 전 유저 확인을 받는다.
   - L1/L0: review-rework loop를 사용하지 않는다.
   - L3 rework 범위는 원래 승인된 설계와 scope 안의 수정으로 제한한다. 새 요구사항, 새 아키텍처, 위험한 migration은 자동 rework하지 않고 `Needs User Approval`로 분리한다.
7. **1 iteration의 정의**
   - `$claude consult` 실행
   - Claude Findings 원문 기록
   - `chatforyou-external-expert` + `chatforyou-lead` + 담당 dev-team triage
   - 타당한 피드백 rework
   - `03-implementation` 및 `04-analyze` 갱신
8. **3-iteration stop rule**
   - L3는 최대 3회까지 자동 반복한다.
   - 3번째 Claude review 결과와 external-expert 종합 판정이 `APPROVED`가 아니면 `plan_docs/05-expert-review/[기능명].md`에 `Final Status: BLOCKED`를 기록한다.
   - 이 경우 STEP 6 진입 금지, `06-report 작성 금지`, 유저 보고 후 종료한다.
9. `$claude` 실패 시 자동 fallback을 순서대로 시도한다:
   - `$claude consult` 재시도 또는 fresh session
   - context 축약 후 04 Review Context + 01/02/03 + 핵심 구현 파일 중심으로 재시도
   - 유저가 직접 실행할 수 있는 `$claude consult` 또는 raw `claude -p` 프롬프트 출력
10. L3에서 fallback까지 실패하면 `plan_docs/05-expert-review/[기능명].md` 에 `Final Status: BLOCKED` 를 기록하고, STEP 6 / `06-report 작성 금지`를 명시한다.
11. L2에서 외부 리뷰가 recommended인 경우에만, 사용자 명시 수용하에 `APPROVED_WITH_RISK` 또는 `DONE_WITH_CONCERNS` 형태를 허용한다.
12. `plan_docs/05-expert-review/[기능명].md` 작성
   - Critical Findings / Suggestions / 통합 리스크
   - Claude Findings 원문 섹션 (요약 금지)
   - external-expert Interpretation
   - Review Loop Iterations 표
   - `Needs User Approval` 항목
   - 최종 판정 (APPROVED / FAIL / BLOCKED)

---

### STEP 6: 팀 리더 — 06-report + 최종 취합

**진입 조건**: STEP 5 의 최종 판정이 **APPROVED** 인 경우에만 STEP 6 진입.
- 05 = **FAIL** → STEP 6 보류. Required Actions 를 유저에게 전달하고 rework 후 STEP 2 또는 3 으로 복귀하여 STEP 5 재실행. APPROVED 달성 시 STEP 6 진입.
- 05 = **BLOCKED** → STEP 6 금지. 외부 리뷰 복구 또는 사용자 지시 후 STEP 5 재실행.
- L3 3-iteration stop rule로 05 = **BLOCKED**가 된 경우 → `06-report 작성 금지`, 유저 보고 후 종료.

`chatforyou-lead` agent가:
1. 전원 결과물 취합
2. STEP 2 Exit Gate 충족 여부 확인
   - 구현 가이드 체크박스 상태 확인
   - 백엔드/프론트 컨벤션 검증 결과 확인
3. 외부 전문가 Critical 항목 + Claude 교차검증 결과를 유저에게 전달
4. PLAN 파일 체크리스트 완료 표시
5. `plan_docs/06-report/[기능명].md` 작성 (Completion Summary / Lessons Learned / Future Tasks / Vault Knowledge Capture)
6. commit 메시지 추천 (실제 commit은 유저가 직접)

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
[chatforyou-lead] + qa(보조)
  04-analyze: 설계-구현 gap 분석 + QA 커버리지 검증
    ↓
[chatforyou-external-expert] + $claude consult
  05-expert-review: 독립 종합 + Claude 교차검증
    ↓
[chatforyou-lead]
  06-report + 최종 취합 + commit 메시지 추천
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
- TODO 체크박스는 대응 작업과 결정론 검증 evidence 가 실제로 완료된 뒤에만 `[x]` 로 표시
- Phase 03 종료와 Phase 05 진입 전 `scripts/verify-changes.sh` 결과를 기록한다 (`docs/agent/verification-protocol.md`)
- custom agent 위임 시 `.codex/config.toml` 등록 정보와 `.codex/agents/*.toml` 설정을 source of truth로 사용
