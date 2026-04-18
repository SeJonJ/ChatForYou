---
name: design-review
description: >
  설계·분석 요청 시 3단계 검증 스킬. Claude 1차 분석 → external-consultant agent 독립 검토
  → Claude 3차 최종 검증 및 결론 순서로 진행하고 PLAN 파일을 작성한다.
  유저가 설계·분석·아키텍처·검토 등을 요청할 때 반드시 사용.
  CLAUDE.md 규칙 4번(설계 응답 형식)과 5번(2차 자기 검증)을 external-consultant agent 연동으로 강화한 버전.
triggers:
  - "설계"
  - "아키텍처"
  - "분석"
  - "검토"
  - "design review"
  - "설계 검토"
  - "구조 분석"
  - "어떻게 설계"
  - "설계 방향"
---

# 3단계 설계 검증 워크플로우

## 개요

설계·분석 요청 시 Claude 단독 판단에 그치지 않고, `external-consultant` agent를 독립 검증자로 활용하여
맹점을 제거하고 최종 결론의 신뢰도를 높인다.

```
유저 요청
  → [1차] Claude 분석 (CLAUDE.md 규칙 4 형식)
  → [2차] external-consultant agent 독립 검토
  → [3차] Claude 피드백 검증 → 최종 결론 → PLAN 파일 작성
```

---

## 1단계: Claude 1차 분석

CLAUDE.md 규칙 4 형식을 엄격히 따른다:

```
1. 설계 방향 및 이유 — 왜 이렇게 설계했는지
2. 실무 레퍼런스 비교 — 업계 기준 대비 장점 / 주의점
3. 유저 설계와의 비교 (검증 요청 시) — 차이점, 각각의 trade-off
```

1차 분석 결과를 유저에게 제시한 후, 2단계를 진행한다고 안내한다.

---

## 2단계: external-consultant agent 독립 검토

`external-consultant` agent를 호출한다. agent에게 전달할 컨텍스트:

- 유저의 원래 요청 내용
- Claude의 1차 분석 결과 전문
- 관련 파일 경로 (agent가 직접 읽을 수 있도록)

agent는 Claude의 결론을 선입견 없이 독립 검토하고, 아래 구조로 피드백을 반환한다:

```
## 외부 고문 검토 결과
### 동의하는 부분
### 비판적 의견 (Critical)
### 개선 제안 (Suggestions)
### 놓친 케이스 / 리스크
### 최종 의견 + 신뢰도
```

---

## 3단계: Claude 피드백 검증 및 최종 결론

external-consultant의 피드백을 받아 Claude가 직접 검증한다.

### 검증 기준

| 피드백 항목 | 처리 방식 |
|---|---|
| 타당한 Critical | 설계 수정 반영, 이유 명시 |
| 타당한 Suggestion | 선택적 반영, 유저에게 판단 위임 |
| 동의할 수 없는 항목 | 반박 근거 제시, 원안 유지 이유 설명 |
| 불확실한 항목 | 추가 분석 후 판단 |

### 최종 보고 형식

```markdown
## 설계 검토 최종 결론

### 최종 권장 설계
<최종 결론>

### 근거
- [1차 Claude]: <핵심 포인트>
- [2차 외부 고문]: <핵심 피드백 요약>
- [3차 검증]: <피드백 수용/반박 판단>

### 수정된 항목
<외부 고문 피드백 반영으로 변경된 내용>

### 유지한 항목 및 이유
<반박하고 원안을 유지한 항목과 근거>

### 최종 리스크 / 주의사항
<확인된 리스크 목록>

### Trade-off 요약
| 옵션 | 장점 | 단점 |
|------|------|------|
| ... | ... | ... |
```

---

## PLAN 파일 작성

설계 검토 완료 후 CLAUDE.md 규칙 6·7에 따라 PLAN 파일을 작성한다.

```markdown
# [기능명] 설계 PLAN

## 설계 검토 완료
- [x] 1차 Claude 분석
- [x] 2차 external-consultant 독립 검토
- [x] 3차 피드백 검증 및 최종 결론

## 개발 기능 목록
- [ ] ...

## 테스트 시나리오
- [ ] ...

## 코드 컨벤션
- [ ] backend-convention-checker 사용
- [ ] backend-test-convention-checker 사용 (테스트 코드)
```

저장 경로는 CLAUDE.md 규칙 7 기준:
- 전체 기능: `ChatForYou_v2/[기능명]_plan.md`
- 백엔드: `springboot-backend/[기능명]_plan.md`
- 프론트: `nodejs-frontend/[기능명]_plan.md`
