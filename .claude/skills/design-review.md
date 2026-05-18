---
name: design-review
description: >
  외부 전문가가 plan_docs/N월_[기능]_plan.md 로 작성한 설계를 Claude가 검증하는 3단계 워크플로우.
  1) 외부 설계 문서 + 관련 MD 분석 → 2) 타당성 검증 및 수정(이유 명시) → 3) 백엔드/프론트 구현 가이드 작성.
  유저가 설계·분석·아키텍처·검토 등을 요청할 때 반드시 사용.
  CLAUDE.md 규칙 4번(설계 검증 형식)과 6·7번(PLAN 파일 운영)을 통합한 워크플로우.
triggers:
  - "설계"
  - "아키텍처"
  - "분석"
  - "검토"
  - "design review"
  - "설계 검토"
  - "구조 분석"
  - "plan 검토"
  - "설계 방향"
---

# 외부설계 → Claude 검증 → 구현가이드 워크플로우

## 개요

시작 전 `plan_docs/N월_[기능]_plan.md` 존재 여부를 확인하고 경로를 분기한다.

```
[Path A] 외부 설계 파일 있음
  → [1단계] Claude: 설계 문서 + 관련 MD 파일 분석
  → [2단계] Claude: 타당성 검증 및 수정 (이유 명시)
  → [3단계] Claude: 구현 가이드 작성

[Path B] 외부 설계 파일 없음
  → plan_docs/ARCHITECT_GUIDE.md 읽기
  → 유저와 기능 논의 (No-Code Mandate: 소스 코드 수정 금지)
  → plan_docs/N월_[기능]_plan.md + 01/02/03 문서 작성
  → 유저 승인 후 구현 가이드 작성
```

---

## Path B: 외부 설계 파일이 없는 경우

### B-1. plan_docs/ARCHITECT_GUIDE.md 읽기

ARCHITECT_GUIDE.md의 01-05 표준과 핵심 원칙(Design-First, No-Code Mandate)을 확인한다.

### B-2. 유저와 기능 논의

다음 항목을 유저와 순서대로 확인한다:

| 항목 | 확인 내용 |
|---|---|
| **기능 목적** | 무엇을 해결하는가, 누가 사용하는가 |
| **범위** | 백엔드 / 프론트 / Desktop 중 어디에 영향을 주는가 |
| **연관 기능** | 기존 기능과 어떻게 연결되는가 |
| **제약 조건** | 성능, 보안, 호환성 요구사항 |

### B-3. 설계 문서 작성 (ARCHITECT_GUIDE.md 표준)

```
plan_docs/
  N월_[기능]_plan.md          ← 논의 결론, 유의점, 문서 맵핑
  01-plan/                    ← L1 System Context + L2 Data Model
  02-design/                  ← L3 API Interface + Sequence Diagram
  03-implementation/          ← L4 수정 파일 목록, 알고리즘, Config 변경
```

> 유저 승인 후 Path A의 구현 가이드 작성 단계로 진행

---

## Path A: 외부 설계 파일이 있는 경우

### 1단계: 설계 문서 + 관련 파일 분석

다음 파일을 **모두** 읽는다:

1. `plan_docs/N월_[기능]_plan.md` — 외부 전문가 설계 원본
2. `CLAUDE.md` — 프로젝트 개발 원칙 및 컨벤션
3. `docs/springboot_backend.md` — 백엔드 컨벤션
4. `docs/nodejs_frontend.md` — 프론트 컨벤션
5. 관련 기존 plan 파일 (유사 기능이 있는 경우)
6. 설계에서 언급된 기존 소스 파일 (필요 시)

분석 시 아래 항목을 확인한다:

| 확인 항목 | 세부 내용 |
|---|---|
| **목표 부합** | 설계가 프로젝트 목표(WebRTC, Spring Boot, jQuery 기반)와 맞는가 |
| **아키텍처 일관성** | 기존 컨트롤러·서비스·레이어 구조를 따르는가 |
| **컨벤션 준수** | springboot_backend.md / nodejs_frontend.md 기준 위반 없는가 |
| **엣지 케이스** | 누락된 예외 처리, 동시성, 경계값은 없는가 |
| **실현 가능성** | 현재 기술 스택으로 구현 가능한가 |

---

## 2단계: 타당성 검증 및 수정

### 응답 형식 (필수)

```markdown
## 설계 검증 결과

### 분석한 파일 목록
- plan_docs/N월_[기능]_plan.md
- (분석한 파일들 열거)

### 타당성 평가
<설계 방향이 프로젝트 목표·구조와 부합하는지 종합 판단>

### 수정 사항
| # | 항목 | 수정 이유 | 수정 내용 |
|---|------|---------|---------|
| 1 | ... | ... | ... |

> 수정이 없을 경우: "수정 없음 — 설계가 프로젝트 구조와 일치함"

### 실무 레퍼런스 비교
<업계 기준 대비 장점 / 주의점>

### 개선 제안 (선택)
<필수가 아닌 품질 향상 제안>

### 리스크 / 주의사항
<확인된 리스크 목록>
```

---

## 3단계: 구현 가이드 작성

검증 완료 후 백엔드/프론트 구현 가이드를 **별도 파일**로 작성한다.

### 백엔드 구현 가이드: `springboot-backend/plan_docs/[기능명].md`

```markdown
# [기능명] 백엔드 구현 가이드

> 원본 설계: plan_docs/N월_[기능]_plan.md
> 검증 완료: YYYY-MM-DD

## 구현 목록
- [ ] ...

## 파일 소유권
- **백엔드 전문가**: springboot-backend/src/main/
- **QA 전문가**: springboot-backend/src/test/

## API 설계
| Method | URL | 설명 |
|--------|-----|------|
| ... | ... | ... |

## 테스트 시나리오
- [ ] ...

## 코드 컨벤션
- [ ] backend-convention-checker 검증
- [ ] backend-test-convention-checker 검증 (테스트 코드)
```

### 프론트 구현 가이드: `nodejs-frontend/plan_docs/[기능명].md`

```markdown
# [기능명] 프론트 구현 가이드

> 원본 설계: plan_docs/N월_[기능]_plan.md
> 검증 완료: YYYY-MM-DD

## 구현 목록
- [ ] ...

## 파일 소유권
- **프론트 전문가**: nodejs-frontend/

## 코드 컨벤션
- [ ] frontend-convention-checker 검증
```

---

## 행동 규칙

- **외부 설계 문서를 먼저 읽지 않고 검증을 시작하지 않는다**
- 수정 시 반드시 이유를 명시한다. "수정 없음"도 명시한다
- 구현 가이드는 백엔드/프론트를 반드시 분리하여 작성한다
- commit / push 금지
