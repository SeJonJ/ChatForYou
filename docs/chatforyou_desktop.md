## 개발 시 유의점 : chatforyou-desktop(electron) ##

### plan_docs 미사용 원칙
`chatforyou-desktop/` 은 `nodejs-frontend/` 자산을 동기화한 결과물이므로 자체 `plan_docs/` 를 운영하지 않는다.
데스크탑에 영향을 주는 변경은 다음 위치에 기록한다:
- 프론트 코드 레벨 상세 → `nodejs-frontend/plan_docs/[기능명]_plan.md`
- 기능 단위 PDCA 산출물 → 루트 `plan_docs/00–06/[기능명].md`
- Electron 환경에서만 발생하는 sync / build-scripts / auto-update 변경 사유는 위 plan_docs 에 별도 섹션으로 명시
- 책임 분배 상세: `docs/agent/pdca-templates.md` 의 **Component-level Plan Docs** 참조

### Agent / Skill 활용(사용 가능 하다면)

| 작업 영역 | 사용 권장 |
|---|---|
| Electron 관련 JS 개발 | `javascript-typescript:javascript-pro` agent |
| 프론트 코드 컨벤션 검증 | `frontend-convention-checker` agent |
| 코드 단순화 | `simplify` skill |
| 디버깅 | `error-debugging:debugger` agent |

### 폴더 규칙

1. chatforyou-desktop
- electron 을 이용한 chatforyou-desktop 앱에서 src 폴더는 nodejs-frontend 폴더와 동기화되도록 했어. 절대 수정하면 안돼.
- build-scripts 는 nodejs-frontend 의 src 파일을 빌드하는 폴더인데, 이곳을 수정하는 경우가 있다면 알리고, 어느 부분을 어떻게 수정해야하고, 왜 수정해야하는지 알려줘.
- auto-update 는 자동 업데이트 관련 폴더인데, 이곳을 수정하는 경우가 있다면 알리고, 어느 부분을 어떻게 수정해야하고, 왜 수정해야하는지 알려줘.
- frontend 개발 후 npm run sync 및 scss:build 를 실행하여 동기화 해줘. 단 에러가 발생한 경우 반드시 알려줘야해.