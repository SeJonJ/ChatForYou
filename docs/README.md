# docs/

ChatForYou v2의 모든 개발 가이드 및 컨벤션 문서가 위치하는 디렉토리입니다.

모든 AI 에이전트는 구현 전 관련 문서를 반드시 읽어야 합니다.
어떤 상황에서 어떤 문서를 읽어야 하는지는 `AGENT_GUIDE.md`의 Required Agent Reference Documents와 Component Reference Documents에 정의되어 있습니다.
프로젝트 컨텍스트가 낯설거나 여러 컴포넌트에 걸친 작업이면 Startup Checklist에 따라 이 `docs/README.md`를 먼저 읽어 전체 구조를 확인합니다.

---

## 프로젝트 개요

ChatForYou v2는 WebRTC 기반 영상 회의 및 실시간 게임 플랫폼입니다.

주요 컴포넌트:

| 컴포넌트 | 위치 | 기본 역할 |
|------|------|------|
| Node.js Frontend | `nodejs-frontend/` | jQuery/Bootstrap 기반 웹 UI, WebRTC 클라이언트, SCSS 빌드 |
| Spring Boot Backend | `springboot-backend/` | API, WebSocket/STOMP signaling, Kurento room/session lifecycle, Redis/JPA 상태 관리 |
| Electron Desktop | `chatforyou-desktop/` | `nodejs-frontend` 산출물 동기화 기반 데스크톱 앱, Electron packaging |

핵심 기능:
- N:M WebRTC 영상 회의
- 실시간 채팅과 파일 공유
- CatchMind 게임
- 텍스트 오버레이, 자막, 화면 공유
- Electron 데스크톱 패키징

## 아키텍처 요약

| 영역 | 주요 기술 / 파일 |
|------|------|
| WebRTC | Kurento Media Server, `/signal` WebSocket/STOMP, ICE/SDP 교환, DataChannel |
| Backend | Controller / Service / Repository 계층, Redis room/session state, JPA user/daily persistence |
| Frontend | jQuery, Bootstrap 5, `kurento-service.js`, SCSS under `src/static/scss/` |
| Desktop | Electron, `electron-builder`, frontend asset sync, Electron runtime config conversion |

주요 설정 파일:

| 영역 | 파일 |
|------|------|
| Frontend local/prod | `nodejs-frontend/config/config.local.js`, `nodejs-frontend/config/config.prod.js` |
| Desktop runtime | `chatforyou-desktop/src/config/config.js` |
| Backend app config | `springboot-backend/src/main/resources/application.properties` |
| Runtime infra | KMS, Redis, DB environment variables |

## 주요 개발 명령

세부 실행 조건과 검증 의무는 각 컴포넌트 문서를 우선합니다.

### Frontend

```bash
cd nodejs-frontend
npm run local
npm run prod
npm run start
npm run sass
npm run dev
```

### Backend

```bash
cd springboot-backend
./gradlew clean build
./gradlew test
java -Dkms.url=ws://[KMS_IP]:[PORT]/kurento -jar build/libs/*.jar
```

### Desktop

```bash
cd chatforyou-desktop
npm run sync
npm run start
npm run dev
npm run build:win
npm run build:mac
npm run build:linux
npm run scss:build
npm run scss:watch
```

## 컴포넌트 컨벤션 가이드

각 컴포넌트의 코딩 스타일, 아키텍처 규칙, 에이전트 역할 경계를 정의합니다.

| 파일 | 대상 컴포넌트 | 주요 내용 |
|------|-------------|----------|
| [`springboot_backend.md`](springboot_backend.md) | Spring Boot | 백엔드 코딩 컨벤션, API 설계 규칙, 테스트 요건, 에이전트 역할 경계 |
| [`nodejs_frontend.md`](nodejs_frontend.md) | Node.js / jQuery | 프론트엔드 코딩 컨벤션, SCSS 규칙, WebRTC 클라이언트 패턴, 에이전트 역할 경계 |
| [`chatforyou_desktop.md`](chatforyou_desktop.md) | Electron | 데스크톱 sync 워크플로우, Electron 런타임 규칙, 패키징, Main/Preload 보안 경계 |
| [`git_commit_convention.md`](git_commit_convention.md) | Git | 커밋 메시지 형식, 브랜치 정책, push 권한 규칙 |

### 언제 읽어야 하는가

| 상황 | 읽어야 할 문서 |
|------|--------------|
| `springboot-backend/` 하위 파일 수정 | `springboot_backend.md` |
| `nodejs-frontend/` 하위 파일 수정 | `nodejs_frontend.md` |
| Desktop sync, 런타임, 패키징 수정 | `chatforyou_desktop.md` |
| Git 커밋 메시지 추천 작성 | `git_commit_convention.md` |
| 복수 컴포넌트에 걸친 변경 | 해당하는 모든 문서 |

---

## 에이전트 운영 가이드 (`docs/agent/`)

이 파일들은 단순 참고 링크가 아니라 **조건부 필수 문서**입니다.
해당 조건이 충족되면 에이전트는 반드시 해당 파일을 읽고 절차를 따라야 합니다.

---

### [`agent/risk-classification.md`](agent/risk-classification.md)

**트리거:** 위험도(L0–L3) 판단이 불명확할 때

**내용 요약:**

`AGENT_GUIDE.md`의 L0–L3 위험도 분류 기준을 구체적인 예시와 함께 상세히 설명합니다.

- **L0–L3 상세 기준표** — 각 레벨에 포함되는 변경 유형과 제외되는 변경 유형을 명확히 구분합니다.
  - L0: README, CHANGELOG, 주석 전용 수정
  - L1: CSS/SCSS, HTML 마크업, 비기능적 UI 변경
  - L2: Redis key/TTL, JPA 엔티티, API 응답 구조, JWT/인증 로직
  - L3: WebRTC 시그널링, Kurento, ICE/SDP, room lifecycle, Desktop sync
- **복합 변경 시나리오 판정 예시 14건** — "버튼 UI + WebSocket 이벤트명 변경 → L3(복합 → 최고 레벨)" 등 실제 사례를 테이블로 제공합니다.
- **불확실한 경우 판정 절차** — 수정 파일 목록 → 기준 대입 → 가장 높은 레벨 선택 → 이유 명시 순서를 안내합니다.

> 핵심 원칙: 판단이 애매하면 항상 높은 레벨을 선택하고 이유를 명시한다.

---

### [`agent/coding-principles.md`](agent/coding-principles.md)

**트리거:** 구현 작업 또는 다단계 변경 전

**내용 요약:**

ChatForYou v2 구현 작업의 기본 코딩 행동 원칙을 정의합니다.

- **Think Before Coding** — 목표, 영향 범위, 관련 컴포넌트와 기존 설계를 먼저 확인합니다.
- **Simplicity First** — 기존 패턴과 helper API를 우선하고 불필요한 추상화를 피합니다.
- **Surgical Changes** — 요청 범위와 직접 관련된 파일만 최소 변경합니다.
- **Goal-Driven Execution** — 검증 가능한 완료 상태까지 진행하고 결과를 보고합니다.

> 프로젝트 Mandatory Rules와 충돌하면 `AGENT_GUIDE.md`가 우선합니다.

---

### [`agent/webrtc-review-protocol.md`](agent/webrtc-review-protocol.md)

**트리거:** WebRTC / WebSocket / Signaling / Kurento 관련 코드 변경 시 (**필수**)

**내용 요약:**

L3 WebRTC/WebSocket 변경 시 반드시 수행해야 하는 **2라운드 설계 리뷰 프로토콜**을 정의합니다.
두 라운드가 모두 문서화될 때까지 구현 코드 작성이 차단됩니다.

- **Round 1 — 흐름 정합성 리뷰** 체크리스트 (6개 항목)
  - 메시지 순서 정합성 (offer → answer → ICE candidate)
  - Kurento와 클라이언트 간 SDP/ICE 교환 일관성
  - room 소유권 및 세션 귀속 명확성
  - 클라이언트/서버 상태 전이 경우의 수 완전성
  - 메시지 스키마 하위 호환성
  - 누락/중복 메시지 처리 안전성
- **Round 2 — 장애 및 생명주기 리뷰** 체크리스트 (7개 항목)
  - race condition (중복 offer, 동시 candidate 처리 등)
  - reconnect/disconnect 처리 완전성
  - Kurento `MediaPipeline` / `WebRtcEndpoint` 리소스 해제 보장
  - 중복 이벤트 멱등 처리
  - 타임아웃/재시도 무한루프 방지
  - WebSocket 핸들러 레벨 인증/인가
  - 비정상 종료 후 클라이언트 상태 복원
- **판정 기준**

  | 결정 | 조건 |
  |------|------|
  | APPROVED | P0/P1 이슈 없음 |
  | APPROVED_WITH_RISK | P1 이슈가 있으나 사용자가 명시적으로 수용 |
  | BLOCKED | P0 이슈 존재 — 수정 전까지 구현 금지 |

---

### [`agent/command-safety.md`](agent/command-safety.md)

**트리거:** git / kubectl / npm / docker 등 쉘 명령 실행 전

**내용 요약:**

사용자의 명시적 요청 없이 실행해서는 안 되는 명령어의 전체 목록을 제공합니다.

- **절대 금지 명령** (12개) — 사용자 명시 요청 없이 절대 실행 불가
  - `git commit`, `git push`, `git reset --hard`, `git rebase -i`
  - `rm -rf [프로젝트 디렉토리]`
  - `kubectl delete`, `kubectl apply` (production 환경)
  - `docker volume rm`, `docker compose down -v`
  - `npm audit fix --force`
  - `DROP TABLE`, `TRUNCATE` 등 파괴적 SQL
  - secrets / certificates / production config 변경
- **주의 명령** (5개) — 실행 전 범위 확인 필수
  - `./gradlew clean build`, `npm install`, `docker compose up`, `kubectl apply` (dev/staging), `npm run sync`
- **장시간 서버 명령** — 사용자가 명시적으로 런타임 검증을 요청한 경우에만 실행
  - `npm run start/dev`, `java -jar *.jar`, Electron 앱 실행 명령

---

### [`agent/wrapper-contract.md`](agent/wrapper-contract.md)

**트리거:** CLAUDE.md, GEMINI.md, CODEX.md 등 wrapper 문서 작성 또는 수정 시

**내용 요약:**

agent-specific wrapper 문서(CLAUDE.md 등)가 정의해도 되는 항목과 절대 정의해서는 안 되는 항목을 규정합니다.

- **허용 항목** (6가지)
  - 시작 시 파일 읽기 순서
  - 사용 가능한 도구 목록
  - 런타임 경로 차이
  - agent-specific tool routing
  - 명령 실행 방식의 차이
  - 에이전트 역할 설명
- **금지 항목** (8가지)
  - 위험도(L0–L3) 재정의
  - PDCA Phase 재정의
  - Definition of Done 재정의
  - WebRTC/WebSocket 리뷰 요건 약화
  - Git policy 변경 / Desktop sync 정책 변경 / 테스트 요건 재정의 / 명령 안전 규칙 약화
- **Guide Drift 방지** — wrapper 문서에 공통 규칙이 조금씩 축적되어 `AGENT_GUIDE.md`와 충돌하는 현상(guide drift)의 경고 신호와 해결 절차를 안내합니다.
  - 경고 신호: wrapper에 `## Workflow` 섹션, `webrtc`/`phase`/`dod` 키워드 등장
  - 해결: 공통 규칙 → `AGENT_GUIDE.md`로 이동, 상세 체크리스트 → `docs/agent/`로 이동

---

### [`agent/pdca-templates.md`](agent/pdca-templates.md)

**트리거:** Phase 00–06 문서 작성이 필요할 때

**내용 요약:**

PDCA 사이클의 각 Phase 문서를 작성할 때 사용하는 **표준 템플릿 7종**을 제공합니다.
루트 가이드에 있던 Phase 00–06 상세 템플릿을 별도 파일로 분리한 것입니다.

| Phase | 문서명 | 주요 포함 섹션 |
|-------|--------|--------------|
| 00 | Base Plan | Prior Knowledge (Vault Scan), Summary, Impact Analysis, Tech Risks |
| 01 | Plan | Requirements, Data Model (Entity/DTO), API Spec |
| 02 | Design | Class/Interface Design, Sequence Diagram, Error Code |
| 03 | Implementation | Scope, Modified Files, Build/Test Results, Convention Check |
| 04 | Gap Analysis | Design vs. Implementation comparison, External Expert Review |
| 05 | Expert Review | Code Quality, Security, Performance, Convention Compliance |
| 06 | Final Report | Completion Summary, Lessons Learned, Future Tasks |

---

### [`agent/phase04-bug-patterns.md`](agent/phase04-bug-patterns.md)

**트리거:** Phase 04 (Analyze) 갭 분석 수행 시

**내용 요약:**

Phase 04 갭 분석 시 능동적으로 탐색해야 할 **도메인별 고위험 버그 패턴**과 심각도 분류 기준을 정의합니다.
루트 가이드에 있던 Phase 04 버그 패턴과 severity 기준을 별도 파일로 분리한 것입니다.

- **심각도 매트릭스 (P0/P1/P2)**

  | 레벨 | 심각도 | 처리 기준 |
  |------|--------|----------|
  | P0 | Critical | 시스템 크래시, 데이터 손실, 보안 취약점, WebRTC/시그널링 핵심 장애 — Phase 05 진행 전 즉시 수정 |
  | P1 | Major | 성능 병목, 심각한 UX 저하, Redis/DB 상태 불일치 — 수정 또는 잔존 위험으로 명시 보고 |
  | P2 | Minor | 로깅 불일치, 경미한 스타일 문제, 비핵심 문서 누락 — 향후 개선 일정 수립 가능 |

- **도메인별 고위험 집중 패턴 4가지** (완전한 분류 체계가 아닌, 이 프로젝트에서 반복적으로 누락되는 고위험 항목만 선별)
  1. **WebRTC & 시그널링 (고위험)** — SDP/ICE 불일치, WebRtcEndpoint 리소스 누수, ICE candidate race condition
  2. **동시성 & 스레딩** — MDC TraceID 비전파(`@Async`), `HashMap` vs `ConcurrentHashMap` 혼용
  3. **보안 & 무결성** — 미인가 room 접근 시 예외 누락, 500 응답에 스택 트레이스 노출, XSS/인젝션 유효성 검사 부재
  4. **성능 & 관찰 가능성** — 고빈도 루프 내 과도한 로깅, Prometheus/Grafana 핵심 이벤트 미기록

  > **주의:** 4개 도메인 외에서 발견된 버그도 동일한 P0/P1/P2 기준으로 분석 문서에 기록해야 합니다. 이 패턴들은 일반 갭 분석(설계 vs 구현 비교)을 대체하지 않습니다.

---

### [`agent/output-contract.md`](agent/output-contract.md)

**트리거:** 작업 결과 보고 시 (모든 작업)

**내용 요약:**

ChatForYou v2의 모든 AI 에이전트가 작업 완료 후 사용해야 하는 **표준 보고 형식 3종**을 정의합니다.
`AGENT_GUIDE.md`의 Output Contract 요약을 보완하는 상세 템플릿입니다.

- **구현 작업 보고서 (L1–L3)** — 9개 섹션
  1. Task Summary — 한 문장 요약
  2. Risk Level + 이유
  3. Scope & Assumptions
  4. Backend / Frontend / Desktop 영향 분석
  5. 완료/건너뛴 Phase (건너뛴 이유 포함)
  6. 수정 파일 목록
  7. 검증 결과 표 (Backend build / Frontend syntax / Convention check)
  8. Remaining Risks (P0/P1/P2 분류)
  9. Next Action

- **L0 최소 보고서 (문서만 수정)** — 5개 섹션
  Summary / Modified Files / Reason for Change / Rules Affected / Validation Skipped

- **설계/분석 결과 보고서** — 6개 섹션
  Files Analyzed / Design Validity Assessment / Changes Applied / Industry Reference Comparison / Improvement Proposals / Risks & Caveats

---

## 관련 문서

| 문서 | 위치 | 설명 |
|------|------|------|
| `AGENT_GUIDE.md` | 프로젝트 루트 | 모든 에이전트의 단일 진실 공급원 (SSOT) — 워크플로우, 위험도, DoD |
| `CLAUDE.md` | 프로젝트 루트 | Claude 전용 시작 순서 및 스킬 라우팅 (thin wrapper) |
| `.local/local_agent_guide.md` | 프로젝트 루트 | 로컬 전용 설정 — Obsidian vault 연동, vault 지식 캡처 |
| `plan_docs/` | 프로젝트 루트 | PDCA Phase별 기능 설계 문서 |
