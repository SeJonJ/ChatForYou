---
name: frontend-codeconvention
description: >
  프론트엔드 코드 컨벤션 검증 스킬. 유저가 "프론트엔드 코드 컨벤션 수행", "frontend-codeconvention",
  "프론트 코드 리뷰", "프론트 컨벤션 체크" 등의 표현을 사용할 때 반드시 이 스킬을 사용한다.
  git diff로 변경된 Node.js 프론트엔드 파일을 nodejs_frontend.md 기준으로 자동 검증한다.
---

# 프론트엔드 코드 컨벤션 검증

## 목적

`git diff`로 변경된 프론트엔드 파일만 대상으로 `nodejs_frontend.md` 코드 컨벤션을 검증한다.
전체 코드베이스가 아닌 변경 분만 검토하므로 빠르고 정확하다.

## 실행 방법

`frontend-convention-checker` agent를 사용해 아래 작업을 수행한다:

1. **변경 파일 감지**: `git diff --name-only HEAD` 또는 `git diff --name-only origin/chatforyou_v2...HEAD` 로 변경된 파일 목록 추출
2. **프론트엔드 파일 필터링**: `nodejs-frontend/` 또는 `chatforyou-desktop/src/` 경로 하위 파일만 선별 (`.js`, `.ts`, `.jsx`, `.tsx`, `.html`, `.ejs`, `.scss`, `.css`)
3. **컨벤션 기준 로드**: `docs/nodejs_frontend.md` 파일의 코드 컨벤션 규칙 참조
4. **검증 수행**: 변경된 각 파일에 대해 컨벤션 위반 여부 확인
5. **결과 리포트**: 위반 항목, 위치(file:line), 수정 가이드 출력
6. **구현 가이드 업데이트**: `nodejs-frontend/plan_docs/` 하위 구현 가이드 파일(`*.md`)이 있다면 해당 체크박스를 업데이트

## 리포트 형식

```
## 프론트엔드 코드 컨벤션 검증 결과

### 변경 파일 목록
- nodejs-frontend/src/.../someFeature.js
- nodejs-frontend/src/static/scss/...

### 위반 사항
| 파일 | 라인 | 규칙 | 내용 | 수정 방법 |
|------|------|------|------|-----------|
| someFeature.js | 35 | 함수 네이밍 | camelCase 미준수 | camelCase로 변경 |

### 통과 항목
- SCSS 구조: 정상
- ...

### 결론
[통과 / 수정 필요] — 위반 N건
```

## 주의사항

- 변경 파일이 없으면 "변경된 프론트엔드 파일 없음"으로 종료
- 백엔드 Java 파일은 대상에서 제외
- 구현 가이드 체크박스 업데이트는 `nodejs-frontend/plan_docs/*.md` 파일이 존재할 때만 수행
- 위반 발견 시 자동 수정 금지 — 반드시 유저에게 확인 후 수정
