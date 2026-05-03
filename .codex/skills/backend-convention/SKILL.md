---
name: backend-codeconvention
description: >
  백엔드 코드 컨벤션 검증 스킬. 유저가 "백엔드 코드 컨벤션 수행", "backend-codeconvention",
  "백엔드 코드 리뷰", "백엔드 컨벤션 체크" 등의 표현을 사용할 때 반드시 이 스킬을 사용한다.
  git diff로 변경된 Spring Boot 백엔드 파일을 springboot_backend.md 기준으로 자동 검증한다.
---

# 백엔드 코드 컨벤션 검증

## 목적

`git diff`로 변경된 백엔드 파일만 대상으로 `springboot_backend.md` 코드 컨벤션을 검증한다.
전체 코드베이스가 아닌 변경 분만 검토하므로 빠르고 정확하다.

## 실행 방법

`backend-convention-checker` agent를 사용해 아래 작업을 수행한다:

1. **변경 파일 감지**: `git diff --name-only HEAD` 또는 `git diff --name-only origin/chatforyou_v2...HEAD` 로 변경된 파일 목록 추출
2. **백엔드 파일 필터링**: `springboot-backend/` 경로 하위 파일만 선별
3. **컨벤션 기준 로드**: `docs/springboot_backend.md` 파일의 코드 컨벤션 규칙 참조
4. **검증 수행**: 변경된 각 파일에 대해 컨벤션 위반 여부 확인
5. **결과 리포트**: 위반 항목, 위치(file:line), 수정 가이드 출력
6. **구현 가이드 업데이트**: `springboot-backend/plan_docs/` 하위 구현 가이드 파일(`*.md`)이 있다면 실제 검증 결과에 맞춰 체크박스를 업데이트
   - 검증 통과 시에만 `Code conventions`를 `[x]`
   - 검증 미실행 또는 위반 잔존 시 `Code conventions`는 `[ ]` 유지
   - 필요하면 구현 가이드의 `Convention validation` 또는 `Open Issues` 구역에 사유를 짧게 남김

## 리포트 형식

```
## 백엔드 코드 컨벤션 검증 결과

### 변경 파일 목록
- springboot-backend/src/.../SomeController.java
- springboot-backend/src/.../SomeService.java

### 위반 사항
| 파일 | 라인 | 규칙 | 내용 | 수정 방법 |
|------|------|------|------|-----------|
| SomeController.java | 42 | 응답 형식 | Map 반환 사용 | ResponseDto<T> 로 교체 |

### 통과 항목
- 패키지 구조: 정상
- ...

### 결론
[통과 / 수정 필요] — 위반 N건

### 체크박스 업데이트 결과
- [x] Code conventions
또는
- [ ] Code conventions (reason: validation not executed / violations remaining)
```

## 주의사항

- 변경 파일이 없으면 "변경된 백엔드 파일 없음"으로 종료
- 프론트엔드 파일은 대상에서 제외
- 구현 가이드 체크박스 업데이트는 `springboot-backend/plan_docs/*.md` 파일이 존재할 때만 수행
- 검증을 실행하지 않았다면 체크박스 갱신 금지
- 위반이 남아 있으면 `[x]` 선반영 금지
