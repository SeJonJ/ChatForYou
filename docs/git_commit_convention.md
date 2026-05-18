# Git Commit & Push Convention

## 핵심 원칙

1. **자동 커밋/푸시 절대 금지** — 어떠한 경우에도 에이전트/도구가 자동으로 커밋·푸시해서는 안 된다
2. **반드시 유저 승인 후 실행** — 커밋 메시지 추천 후 유저가 직접 실행
3. **영역별 분리 커밋** — 수정 파일 전체를 한 번에 커밋하지 않고 기능/영역 단위로 분리

---

## 커밋 단위 구분

| 영역 | 대상 파일 | 메시지 형식 |
|------|-----------|-------------|
| 프론트엔드 | js, html, css, ejs 등 nodejs-frontend 소속 파일 | `#NNN 기능명 :: 설명` |
| 백엔드 | java, gradle, application.yml 등 springboot-backend 소속 파일 | `#NNN 기능명 :: 설명` |
| 인프라 | Dockerfile, docker-compose, nginx.conf, 배포 yml 등 | `infra :: 설명` |
| 에이전트/가이드 | AGENT_GUIDE.md, CLAUDE.md, CODEX.md, .claude/* 등 | `agent :: 설명` |
| 문서 | docs/*, README, plan_docs/* | `docs :: 설명` |
| 보안 패치 | 의존성 업데이트, 보안 취약점 수정 | `security :: 설명` |

---

## 커밋 메시지 형식

### 이슈 기반 작업 (기능 개발 / 버그 수정)

```
#<이슈번호> <기능명> :: <변경 내용 요약>
```

### 비이슈 작업 (인프라 / 에이전트 / 문서)

```
<카테고리> :: <변경 내용 요약>
```

---

## 예시

```bash
# 프론트엔드 변경 (이슈 #104)
git add nodejs-frontend/...
git commit -m "#104 예외처리개선 :: 백엔드 수정에 맞춰 프론트 예외 처리 반영"

# 백엔드 변경 (이슈 #104)
git add springboot-backend/...
git commit -m "#104 예외처리개선 :: GlobalExceptionHandler 추가 및 ErrorResponse 통일"

# 인프라 변경
git add Dockerfile docker-compose.yml ...
git commit -m "infra :: 도커 파일 멀티스테이지 빌드 개선"

# 에이전트/가이드 변경
git add AGENT_GUIDE.md CLAUDE.md .claude/...
git commit -m "agent :: 커밋 규칙 문서 추가 및 AGENT_GUIDE 참조 연동"

# 문서 변경
git add docs/...
git commit -m "docs :: git commit convention 문서 신규 작성"

# 보안 패치
git add package.json package-lock.json ...
git commit -m "security :: Bump lodash from 4.17.21 to 4.18.1"
```

---

## 에이전트 행동 규칙

- 커밋 메시지 **추천**만 허용
- 커밋·푸시 **실행 절대 금지** — `git commit`, `git push` 명령 실행 불가
- 작업 완료 후 변경된 파일을 영역별로 그룹화하여 커밋 메시지 목록을 유저에게 제안
- 단일 작업에서 여러 영역이 변경된 경우 반드시 영역별로 분리 추천
