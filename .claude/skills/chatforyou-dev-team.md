---
name: chatforyou-dev-team
description: ChatForYou v2 주요 기능 개발을 위한 5인 에이전트 팀 조율 워크플로우. 팀 리더, 백엔드 전문가(30년), 프론트 전문가(30년), QA 전문가, 외부 전문가로 구성. PLAN 파일이 필요한 수준의 기능 개발에서만 사용. 단순 버그 수정은 개별 agent 사용.
---

# chatforyou-dev-team 워크플로우

이 skill이 호출되면 아래 순서로 5인 팀을 조율하여 기능 개발을 진행한다.

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
| 외부 전문가 | `chatforyou-external-expert` | 주황색 | code-reviewer, code-review:code-review, **`gstack:codex` (STEP 5 cross-model 필수)** | `bkit:audit`, `bkit:code-review`, `gstack:review`, `gstack:cso` |

> **선택적 스킬 원칙**: 각 팀원은 기능 종류에 맞는 스킬을 자율적으로 판단하여 호출한다. 각 agent 파일의 "선택적 스킬 호출" 섹션 참고.

---

## 팀 활성화 조건

**사용 O**: 신규 기능 추가, 주요 리팩토링, 다수 파일에 걸친 변경 (PLAN 파일이 필요한 수준)
복잡 버그도 L2 이상이거나 PLAN 파일 생성이 필요한 수준이면 chatforyou-dev-team을 활성화한다.

**사용 X**: 단순 버그 수정 1~2줄, 텍스트 수정, 개별 agent로 충분한 작업

---

## 워크플로우 실행 순서

### STEP 1: 팀 리더 — 설계 검증 및 분석 요약 작성 (= Phase 00 Base Plan + Phase 01 Plan + Phase 02 Design)

`chatforyou-lead` agent를 호출하여 먼저 `plan_docs/N월_[기능]_plan.md` 존재 여부를 확인한다.

> ⚠️ **MANDATORY FIRST**: 팀 리더는 설계 파일을 읽기 전에 반드시 아래 순서로 읽는다.
> 1. `AGENT_GUIDE.md` — 단일 진실 공급원 (공통 규칙, 워크플로우, 위험도 분류)
> 2. `.local/local_agent_guide.md` — 로컬 설정 (존재하는 경우)
> 3. `docs/springboot_backend.md` — 백엔드 컨벤션 기준
> 4. `docs/nodejs_frontend.md` — 프론트 컨벤션 기준
>
> `AGENT_GUIDE.md`의 Pre-Implementation Compliance Gate 선언(Risk Level + Phase range) 후 설계 분석을 진행한다.

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

### STEP 2: 병렬 개발 — 백엔드 + 프론트 (= Phase 03 Do)

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
7. 잔존 항목이 있으면 구현 가이드 하단 `Open Issues` 또는 `Remaining Risks`에 기록

**프론트 전문가**:
1. 리더 분석 요약 수신
2. 구현 전 `AGENT_GUIDE.md`, `docs/nodejs_frontend.md`, `docs/chatforyou_desktop.md` 재확인
3. **`nodejs-frontend/plan_docs/[기능명].md` 작성** (기존 파일 있으면 병합)
   - 신규/수정 파일 목록 (신규 생성 / 수정 / 삭제 구분)
   - 신규 함수의 완전한 JavaScript 코드 (JSDoc 포함)
   - 수정 함수별 Before/After 전체 함수 코드
   - HTML/View 변경 사항
   - 테스트 시나리오 개요
   - 아래 TODO 체크박스 항목 필수 포함
     - [ ] Development feature list
     - [ ] Test scenarios and validation method
     - [ ] Code conventions
4. nodejs-frontend/ 개발
5. frontend-convention-checker로 자체 검증
6. 검증 결과에 따라 구현 가이드 체크박스 갱신
7. `chatforyou-desktop/src` 직접 수정 금지

### STEP 2 Exit Gate

STEP 3 QA로 넘어가기 전에 아래 항목이 모두 충족되어야 한다:

1. 백엔드/프론트 변경 범위에 대한 컨벤션 검증이 완료되었다.
2. 각 구현 가이드의 TODO 체크박스가 실제 상태에 맞게 갱신되었다.
3. 남아 있는 예외 처리 TODO, 로그 상세화 누락이 있으면 `Open Issues` 또는 `Remaining Risks`에 기록되었다.

위 3개 중 하나라도 빠지면 QA 단계로 넘어가지 않는다.

---

### STEP 3: QA — 시나리오/통합/경계값 테스트 (= Phase 03 Do 계속)

`chatforyou-qa-expert` agent를 호출하여:
1. 백엔드 전문가 결과물 확인 (src/main/ + 단위 테스트)
2. 백엔드 전문가가 놓친 케이스 중심으로 시나리오 설계
3. @WebMvcTest (HTTP/인증/경계값) + @SpringBootTest (통합/동시성) 테스트 작성
4. backend-test-convention-checker로 검증
5. STEP 4 에서 lead 가 04-analyze 작성 시 **QA Coverage Verification 의견을 보조로 제공**:
   - 커버한 케이스 / 미커버한 케이스 / 의도적으로 제외한 케이스
   - 설계 요구사항 대비 테스트 커버리지의 충분성 판단
   - 추가 권장 시나리오 (있다면)

> 프론트 테스트는 담당하지 않음. 프론트는 frontend-convention-checker로 컨벤션만 검증.

---

### STEP 4: 팀 리더 + QA(보조) — 04-analyze 작성 (= Phase 04 Analyze)

`chatforyou-lead` agent가 (qa 보조):
1. 02-design 과 03-implementation, 실제 구현 코드를 대조하여 설계-구현 gap 분석
2. `chatforyou-qa-expert` 가 STEP 3 에서 작성한 통합/경계/HTTP 테스트의 커버리지 검증 의견을 **보조로** 수신
3. `plan_docs/04-analyze/[기능명].md` 작성:
   - Design vs Implementation Gap (Match Rate)
   - Missing Items & Deviations
   - QA Coverage Verification (qa 보조 의견)
   - **Review Context for External Model** (필수):
     - Original User Intent
     - Key Decisions During Implementation
     - Scope Changes / Deferred Items
     - Design vs Implementation Notes
     - QA Coverage Summary (qa 보조 의견 반영)
     - Known Risks / Open Questions
     - Files External Reviewer Must Inspect
4. 04 자체로 단독 판정(APPROVED/FAIL)을 내리지 않는다 — 판정은 STEP 5 external-expert + codex 에서 종합

---

### STEP 5: 외부 전문가 + Codex 반복 검토 루프 — 05-expert-review 작성 (= Phase 05 Expert Review)

`chatforyou-external-expert` 오케스트레이션 + `chatforyou-lead` fix 라우팅.
단발성 1회 검토가 아니라 **검토 → 분류 → 수정 → 재검토** 를 자동 반복하는 루프다.
세부 정책의 단일 출처는 `docs/agent/pdca-templates.md` 의 External Consultant Protocol + Phase 05 Template.

**루프 진입 게이트 (반드시 먼저 확인)**:
- **Risk Level 게이팅**: **L3** = 3-iteration 루프 mandatory + 자동 진행 / **L2** = recommended only → **루프 시작 전 유저 확인** / **L1·L0** = 루프 미사용
- **Core WebRTC 아키텍처 게이트**: findings/조치가 WebRTC 코어(WebSocket/Signaling/Kurento/ICE/SDP/DataChannel/room lifecycle/media pipeline/signaling contract/reconnect state machine)를 변경하면 **자동 rework 중단 → 유저 사전 승인 먼저**. 이미 승인된 설계 내부 로컬 버그 수정은 게이트 대상 아님.

**한 iteration = ① Codex consult → ② findings 원문 기록 → ③ triage(채택/기각/보류) → ④ 채택 항목 Required Fixes → lead 라우팅 → backend/frontend fix → ⑤ 03·04 갱신**

```
[Iteration 1]
1. external-expert: /codex consult (00·01·02·03·04 + component plan docs + 구현 파일)
   - 요청: "단순 코드 리뷰가 아닌 개발 맥락 기반 외부자 검토. 04 Review Context 포함, 설계-구현 일치/기술스택 적합성/lifecycle·edge·security·test·UX 리스크/06 진입 가능 여부. 최종 권고 APPROVED 또는 FAIL."
2. Codex APPROVED → 루프 종료 → STEP 6
3. Codex FAIL → triage → 채택 항목 "Iteration 1 Required Fixes" → lead → fix → 03·04 갱신

[Iteration 2] (Iteration 1 FAIL 시)
4. external-expert: /codex consult 재실행 (갱신된 구현 파일)
5. APPROVED → 루프 종료 → STEP 6
6. FAIL → triage → "Iteration 2 Required Fixes" → lead → fix → 03·04 갱신

[Iteration 3] (Iteration 2 FAIL 시)
7. external-expert: /codex consult 재실행 → APPROVED → 루프 종료 → STEP 6
8. 여전히 FAIL → Final Status: BLOCKED → 06 금지 → 유저 에스컬레이션
```

종료 조건:
- APPROVED (어느 iteration 이든) → 즉시 종료 → STEP 6
- 남은 이슈 전부 기각 → external-expert APPROVED 선언 가능
- 3 iteration 후에도 APPROVED 미달성 → **BLOCKED** → 06 금지 → 유저 보고

- fallback: 서브에이전트 안에서 gstack preamble 충돌 시, 라운드별 복붙용 `/codex consult` 명령을 유저에게 출력 → 유저 실행 결과를 해당 iteration 의 05 에 ingest 후 자동 재개

`plan_docs/05-expert-review/[기능명].md` 작성:
- 팀 결과물 요약 / 팀 의견 상충 분석
- **External / Cross-model Review** (External Findings 원문 + **Review Loop Iterations 표** + external-expert Interpretation + Needs User Approval + Final Status)
- Critical Findings / Suggestions / 통합 리스크 / Required Actions
- 최종 판정 (APPROVED / FAIL / BLOCKED)

---

### STEP 6: 팀 리더 — 06-report + 최종 취합 (= Phase 06 Report)

**진입 조건**: STEP 5 의 최종 판정이 **APPROVED** 인 경우에만 진입.

- 05 = **APPROVED** → STEP 6 진행 → commit 메시지 추천 → 유저 commit
- 05 = **FAIL** → STEP 6 보류. Required Actions 를 유저에게 전달 → rework → 04 갱신(Review Context 포함) → STEP 5 재실행 → APPROVED 달성 시 STEP 6 진입
- 05 = **BLOCKED** (L3 3-iteration 소진, APPROVED 미달성) → **STEP 6 금지**. external-expert 의 BLOCKED 보고(Iteration별 요약 + 미해결 이슈 + 권고 A/B/C)를 유저에게 전달 → 유저 결정 후 STEP 5 재진입 또는 유저 명시 승인하 종료

`chatforyou-lead` agent가:
1. 전원 결과물 취합
2. STEP 2 Exit Gate 충족 여부 확인 (구현 가이드 체크박스 + 컨벤션 검증 결과)
3. 외부 전문가 Critical 항목 + Codex 교차검증 결과를 유저에게 전달
4. PLAN 파일 체크리스트 완료 표시
5. `plan_docs/06-report/[기능명].md` 작성 (Completion Summary / Lessons Learned / Future Tasks / Vault Knowledge Capture)
6. commit 메시지 추천 (실제 commit은 유저가 직접)

---

## 팀 워크플로우 다이어그램

```
[plan_docs/N월_[기능]_plan.md] ← 외부 전문가 작성
    ↓
[chatforyou-lead]  (STEP 1)
  외부 설계 검증 + 구현 가이드 작성 + 파일 소유권 배분
    ↓ (병렬)
[chatforyou-backend-expert]   [chatforyou-frontend-expert]  (STEP 2)
  src/main/ 개발                nodejs-frontend/ 개발
  backend-convention-checker    frontend-convention-checker
    ↓ Exit Gate ↓
[chatforyou-qa-expert]  (STEP 3)
  src/test/ 테스트 코드 작성
  backend-test-layer skill 기준
  backend-test-convention-checker 검증
    ↓
[chatforyou-lead] + qa(보조)  (STEP 4)
  04-analyze: 설계-구현 gap 분석 + QA 커버리지 검증
  + Review Context for External Model (필수)
    ↓
[chatforyou-external-expert] + /codex  (STEP 5)  ← 반복 검토 루프 (L3 자동, 최대 3 iteration)
  05-expert-review: 독립 종합 + Codex 교차검증
  ┌───────────────────────────────────────────────┐
  │ Iteration n: /codex consult → triage(채택/기각/보류) │
  │   APPROVED → 루프 종료                          │
  │   FAIL → Required Fixes → lead → fix → 03·04 갱신 │
  │   (WebRTC 코어 변경 감지 시 자동 rework 중단→유저 승인)│
  └───────────────────────────────────────────────┘
  Final Status: APPROVED / FAIL / BLOCKED
    ↓ (APPROVED만)
[chatforyou-lead]  (STEP 6)
  06-report + 최종 취합 + commit 메시지 추천
  (commit/push는 유저 직접)
```

FAIL / BLOCKED 시:
```
05 FAIL    → Required Actions → rework → 04 갱신 → STEP 5 재실행 → APPROVED → STEP 6
05 BLOCKED → (L3 3-iteration 소진) 06 금지 → 유저 보고(권고 A/B/C) → 유저 결정 후 재진입/종료
```

---

## 팀 공통 규칙

- **commit / push 절대 금지** — 모든 팀원 공통
- **파일 소유권 엄수** — 배분되지 않은 파일 수정 금지
- **PLAN 파일 없이 시작 금지**
- **구현 시작 전 `AGENT_GUIDE.md`와 관련 `docs/*.md` 재확인 필수**
- **`chatforyou-desktop/src` 직접 수정 금지** — 웹 공통 변경은 `nodejs-frontend`에서 처리 후 sync
- 외부 전문가의 Critical 항목은 유저에게 반드시 보고

---

## AGENT_GUIDE Compliance Gate

이 skill을 통해 시작하는 모든 작업에도 `AGENT_GUIDE.md`의 Pre-Implementation Compliance Gate가 동일하게 적용된다.

### STEP 1 시작 전 (팀 리더 필수 선언)
- **Risk Level**: L0 / L1 / L2 / L3 (`AGENT_GUIDE.md` Risk & Workflow Gate 기준)
- **Applicable phase range**: Phase XX–XX (`docs/agent/pdca-templates.md` 기준)
- L2 이상: `plan_docs/N월_[기능]_plan.md` 존재 확인 필수
- L3 + WebRTC/WebSocket 변경: `docs/agent/webrtc-review-protocol.md` 필수 리뷰 완료 후 구현

### 작업 완료 전 (팀 리더 필수 확인)
`AGENT_GUIDE.md` Definition of Done 전체 항목을 확인한다.
건너뛴 항목은 이유를 명시한다.
