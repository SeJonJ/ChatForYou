# [기본 계획] 프론트엔드 Vite + ES 모듈 전환 1차 (#151)

Document-Language: ko
Cycle-Stem: `feat_151_frontend_vite_esm_phase1`
Risk Level: L3
Done-Criteria-Revision: 2
ChatForYou-Component-Doc-Gate: v1
Component-Backend: N/A: 백엔드 소스 변경 없음 — 프론트엔드 빌드 체계 전환 전용 사이클
Component-Frontend: REQUIRED

- 이슈: https://github.com/SeJonJ/ChatForYou/issues/151 (feat) — 1차
- 브랜치: `SeJonJ/feat-151`
- 작성 역할: leader
- 인터뷰 기록: `.sage/plan_interview.md` (확정 결정 D-Q1 ~ D-Q8)

## 0. 사전 지식

| 유형 | 메모 | 핵심 시사점 |
|------|------|--------------|
| TECH | `TECH - ChatForYou Kubernetes 배포 노트` | 프론트 Deployment `chatforyou-frontend-v03` 은 replicas 1, probe 없음, `terminationGracePeriodSeconds` 30, RollingUpdate 25%/25%. 백엔드와 달리 readiness 가 없어 기동 직후 트래픽이 바로 들어온다 → 베이스 이미지 교체 실패를 클러스터가 자동으로 잡아주지 못한다 |
| TECH | `TECH - ChatForYou K8s Service Ingress 매니페스트` | `chatforyou-ingress` 가 `/chatforyou` Prefix 를 프론트 서비스로 보낸다. `chatforyou-static-ingress` 는 `/images /css /js /fonts /static` prefix 를 `https://hjproject.kro.kr/chatforyou$request_uri` 로 permanent-redirect 한다. **`/assets` 도 `/build` 도 그 목록에 없다** → 빌드 산출물 URL 은 반드시 `/chatforyou/` 아래로 들어가야 한다 (D-Q3 의 직접 근거) |
| TECH | `TECH - ChatForYou K8s 배포 개선 백로그` | B8 = 프론트엔드 단일 replica + probe 없음, 리소스 사정상 유지(유저 판단 보류). 이번 사이클에서 probe 도입 여부는 TBD |
| TECH | `TECH - ChatForYou v2 retro bug_143_deploy_reconnect_kick 2026-09-17` | #143 회고. `reconnectPolicy.js` 를 브라우저·Node 겸용 순수 함수로 분리하고 `node:test` 로 보호망을 만든 배경. 그 보호망은 이번 전환에서 러너만 바뀌고 계약은 그대로 유지돼야 한다 |
| 리포 조사 | 모든 템플릿 4행(또는 7행)에 `<base href="/chatforyou/">` 존재 | 템플릿의 상대 URL 은 문서 URL 이 아니라 이 base 기준으로 해석된다. Vite 의 상대 base 전략이 성립하지 않는 이유 |
| 리포 조사 | `server.js` 가 `/js /css /images /fonts /vendor` 를 `/static` 아래로 가상 매핑하고, 루트 `/chatforyou` 를 `templates/roomlist.html` 로 매핑 | 미전환 페이지와 런타임 fetch 가 이 가상 매핑에 의존한다 → dist 로 루트를 옮기되 매핑 자체는 유지해야 한다 |
| 리포 조사 | `Dockerfile` 이 `npm ci --omit=dev` 후 `npm run prod` 를 수행. `.dockerignore` 없음 | devDependency 인 vite 로 빌드할 수 없다 → multi-stage 필수 (D4) |
| 리포 조사 | Electron `build-scripts/lib/convert_path.js` 가 `<base href="/chatforyou/">` 를 `../` 로 치환하고, `convert_path.json` 의 `pathMappings.html` 이 `js/` 를 `static/js/` 로 재작성. generic 규칙이 `src="assets/"` 를 `src="static/assets/"` 로 재작성 | Vite 기본 출력 디렉터리 `assets` 와 충돌 → 출력 디렉터리명을 `build` 로 바꾼다 (D-Q3) |
| 리포 조사 | Vite 는 엔트리 HTML 의 `src` 를 그 HTML 의 파일시스템 위치 기준으로 해석한다. 현재 `templates/roomlist.html` 의 `src="js/roomlist/roomlist.js"` 는 디스크상 `static/js/roomlist/roomlist.js` 다 | 엔트리 HTML 의 script 경로 재지정이 구현 범위에 포함된다 |
| 리포 조사 | 데스크톱 릴리즈 CI 가 node 18 로 `sync-frontend.js` 를 돌린다 | dist 소비로 바뀌면 그 워크플로에서도 `vite build` 가 필요 → node 상향이 1차 필수 범위 |
| 외부 확인 | `npm view` engines: `electron-builder@24`/`app-builder-lib@24` = `>=14.0.0`(상한 없음), `vite@7` = `^20.19.0 || >=22.12.0`, `vitest@3` = `^18 || ^20 || >=22`. 데스크톱 `package-lock.json` 에 `node-gyp` 참조 0건·`.node` 바이너리 0건 | Node 22 상향에 네이티브 모듈 ABI 리스크 없음. Vite 7 때문에 하한은 22.12 |

## 1. 요약 (목표와 범위)

**목표:** 전역 `<script>` 로 파일 간 전역을 공유하던 프론트엔드 구조를 Vite 빌드 + ES 모듈로 바꾸고, Vitest 단위 테스트 환경을 만든다. **사용자가 체감하는 기능과 UI 는 100% 그대로 유지한다.** jQuery 와 기존 HTML 구조도 유지한다.

이번 사이클은 이슈 #151 의 **1차**다. WebRTC 메인 JS 는 2차 사이클로 이월한다.

### 1.1 포함 (1차)

| 구분 | 내용 |
|---|---|
| 빌드 인프라 | `vite.config.js` 신규, `package.json`(vite/vitest/스크립트), `Dockerfile` multi-stage + `node:22-alpine`, `.dockerignore` 신규, `.nvmrc` 신규, `server.js` dist 서빙, `eslint.config.js` 모듈 소스타입 |
| ESM 전환 | `static/js/common/config.js`(신규 접근 모듈), `static/js/roomlist/roomlist.js`, `static/js/login/**`, `static/js/error/script_500.js` |
| 엔트리 HTML | `templates/roomlist.html`, `templates/login/**` |
| 데스크톱 연동 | `build-scripts/sync-frontend.js`(dist 기준), `convert_path.json` / `lib/convert_path.js` 에 `/chatforyou/build/` 규칙 |
| 검증 배선 | `scripts/verify-frontend-test.sh` 신규, `scripts/verify-frontend-syntax.sh` / `verify-frontend-lint.sh` / `verify-desktop-sync.sh` 조정, `sage/project-profile.yaml` 의 `verification.commands.test` 연결 |
| CI | `.github/workflows/chatforyou_release_by_tag.yml` node 18 → 22 |
| 테스트 | `test/reconnectPolicy.test.js` 를 Vitest 러너로 이관(원본 미변경), 신규 L2 단위 테스트 |

### 1.2 확정 결정 (인터뷰)

| ID | 결정 |
|---|---|
| D-Q1 | 로그인 화면(`static/js/login/**`, `templates/login/**`)을 1차에 포함한다 → 이 사이클은 security 도메인에 걸린다 |
| D-Q2 | Vite MPA. 전환 대상 HTML 만 엔트리로 등록하고, 미전환 `static/`·`templates/` 자산은 dist 로 그대로 복사한다. `server.js` 는 dist 단일 루트만 서빙한다 |
| D-Q3 | `base: '/chatforyou/'` 절대경로. 출력 디렉터리명은 `assets` 가 아니라 `build` |
| D-Q5 | `config/config.js` 는 전역 주입을 유지하고 `window.__CONFIG__` 계약을 동결한다. 접근 모듈 `static/js/common/config.js` 를 신설해 모듈은 전역을 직접 참조하지 않는다. 나머지 L2 JS 는 1차에서 전부 ESM 전환 |
| D-Q6 | `test/reconnectPolicy.test.js` 는 러너만 Vitest 로 이관하고 원본 `static/js/rtc/reconnectPolicy.js` 는 건드리지 않는다 |
| D-Q7 | Node 22 + Vite 7. `.nvmrc`/`engines` 로 22.12+ 하한. 데스크톱 릴리즈 빌드 성공을 1차 인수기준에 포함 |
| D-Q8 | `scripts/verify-frontend-test.sh` 를 신규로 만들고 프로필 `verification.commands.test` 에 연결 |
| D-Q4 | 문서 언어 ko |

### 1.3 이번 사이클이 건드리지 않는 것 (2차 이월)

아래는 **어떤 이유로도 이번 사이클에서 수정하지 않는다.** 파일을 열어 읽는 것은 허용하지만 편집은 금지한다.

| 대상 | 사유 |
|---|---|
| `nodejs-frontend/static/js/rtc/**` (19개 전부) | webrtc 도메인. `recording.js` 는 recording 도메인, `catchMind.js` 는 game 도메인 |
| `nodejs-frontend/static/js/chatroom/socket.js` | webrtc 도메인 |
| `nodejs-frontend/templates/room/kurentoroom.html` | webrtc 도메인. 1차 이후에도 전역 `<script>` 로 남는다 |
| `nodejs-frontend/config/config.js`, `config.local.js`, `config.prod.js` | 계약 동결 대상 (D-Q5) |
| `nodejs-frontend/static/js/common/{common,spinnerOpt,ajaxUtil,fileUtil}.js` | **공유 모듈** — `kurentoroom.html` 이 비모듈 `<script>` 로 로드한다. 1차에서 전환하면 산출물 이중화가 강제된다 |
| `nodejs-frontend/static/js/popup/**` (5종 전부) | 같은 이유. `popup-loader.js` 와 `audio_error_popup.js` 를 `kurentoroom.html` 이 로드한다 |
| `nodejs-frontend/templates/popup/**` | 위 팝업 JS 가 2차로 이월되므로 조각 HTML 도 그대로 둔다 |

**공유 모듈을 1차에서 빼는 근거 (유저 판단, 2026-09-20):**

`kurentoroom.html` 은 2차까지 수정하지 않는 파일인데 173~180행에서 `js/common/{common,spinnerOpt,ajaxUtil}.js` 와 `js/popup/{popup-loader,audio_error_popup}.js` 를 **비모듈 classic `<script>`** 로 로드한다. 이 파일들을 1차에서 ESM 으로 전환하면 그 URL 이 dist 에서 사라지거나(404) 비모듈 파싱에 실패해(SyntaxError) 방 페이지 전체가 죽는다. 이를 피하려면 같은 소스에서 레거시 사본을 따로 산출해야 하는데, 그것은 **dist 에 같은 로직을 두 벌 두는 구조적 비용**이다.

2차에서 rtc 와 함께 전환하면 `kurentoroom.html` 도 엔트리가 되므로 이중화 자체가 불필요해진다. 그래서 공유 모듈군을 2차로 함께 미룬다. **빌드·배포 인프라는 축소되지 않으며, 이번 사이클의 본래 목적은 그대로 달성된다.**

이 결정의 대가로 1차에는 **과도기 상태**가 생긴다 — 전환된 `roomlist.js`·`login/**` 이 classic 으로 남은 `ajaxUtil.js`·`common.js` 의 전역(`tokenAjax`, `getApiErrorMessage`, `showApiErrorToast`, `handleApiError`, `isElectron`)을 계속 참조한다. 모듈 스크립트는 classic 스크립트보다 나중에 실행되므로 런타임 순서는 성립한다. 설계·검증 처리는 02 §1.13.

**2차 필수 이월 항목 — 유저 명시 지시:**

> `config/config.js` 의 ESM 모듈화 및 `window.__CONFIG__` 전역 해제는 2차 사이클 항목이다. 위 공유 모듈군(`common/**`, `popup/**`, `templates/popup/**`)의 ESM 전환도 2차 항목이다.
> 유저가 "2차에서 잊지 않도록 위키 작성 시 기록해줘"라고 명시 지시했으므로, **이 사이클 완료 시 지식 write-back 노트에 반드시 이 항목을 실어야 한다.** 노트에 실리지 않으면 인수 항목 A15 가 미충족이다.

전역을 1차에서 풀 수 없는 이유는 같은 `window.__CONFIG__` 를 세 곳이 동시에 쓰기 때문이다.
1. 2차까지 미전환인 `kurentoroom.html` 의 rtc 코드
2. Electron `ConfigProcessor` (`build-scripts/sync-frontend.js` 의 설정 변환 단계)
3. 빌드타임 파일 교체 (`npm run prod` 가 `config.prod.js` → `config.js` 복사)

## 2. 영향도 분석 (중요)

- **backend (`springboot-backend`)**: 변경 없음. 프론트는 기존 API 를 그대로 호출하며 요청 URL·응답 계약은 바뀌지 않는다.
- **frontend (`nodejs-frontend`)**
  - 정적 서버가 소스 트리가 아니라 빌드 산출물(dist) 단일 루트를 서빙하는 구조로 바뀐다.
  - 전환 대상 화면(방 목록, 로그인 3종)의 **진입 스크립트**가 전역 `<script>` → `<script type="module">` 로 바뀐다. 같은 페이지의 공유 모듈(`common/**`, `popup/**`)은 1차에서 classic 으로 남는다.
  - 팝업은 로드 방식이 바뀌지 않는다. `popup-loader` 의 런타임 경로 조립도 그대로 유지된다 — 다만 그 파일들이 dist 로 복사돼야 기존 URL 이 유지된다.
  - 미전환 화상통화 화면은 **소스가 아니라 서빙 경로만** 바뀐다. 동작은 그대로여야 한다.
- **desktop (`chatforyou-desktop`)**
  - `src/` 는 직접 수정하지 않는다. 동기화 소스가 `nodejs-frontend` 소스 트리 → `nodejs-frontend/dist` 로 바뀌므로 `build-scripts` 의 동기화 대상과 경로 재작성 규칙이 바뀐다.
  - 릴리즈 CI 에서 동기화 전에 `vite build` 가 선행돼야 한다.
- **배포/운영 (리포 밖)**
  - 프론트 컨테이너 베이스 이미지가 `node:18-alpine` → `node:22-alpine` 으로 바뀐다.
  - k8s 매니페스트는 **리포에서 관리하지 않는다**(live `kubectl edit`/apply, 리포에 `k8s/` 디렉토리 자체가 없음). 따라서 이번 사이클의 k8s 영향은 코드 변경이 아니라 **위키 노트 기록 + 유저 수동 적용**으로 처리한다.
  - `chatforyou-static-ingress` 의 redirect prefix 목록에 `/build` 를 추가할 필요는 없다. 빌드 산출물 URL 이 `/chatforyou/build/...` 로 `chatforyou-ingress` 관할에 들어가기 때문이다. 이것이 D-Q3 을 절대 base 로 정한 이유다.

**변경 전 → 후 (개발자 체감)**

| 항목 | 변경 전 | 변경 후 |
|---|---|---|
| 프론트 실행 | `node server.js` 로 소스 트리 직접 서빙 | `vite build` 후 `node server.js` 가 dist 서빙 |
| 파일 간 의존 | 전역 변수 (로드 순서 의존) | `import`/`export` (전환 범위 한정) |
| 단위 테스트 | `node --test test/*.test.js` | `vitest run` (게이트 명령으로 배선) |
| 컨테이너 | 단일 스테이지, node 18 | multi-stage, node 22, 런타임 스테이지에 dist + server.js 만 |
| 데스크톱 동기화 | 소스 트리 동기화 | dist 동기화 |

**사용자 체감 변화: 없음(0건)이 요구사항이다.**

## 3. 기술과 리스크

**L3 판정 근거:** 이번 범위에 `nodejs-frontend/static/js/login/**` 과 `nodejs-frontend/templates/login/**` 이 포함된다. 두 글롭 모두 프로필 `risk.domains` 의 `security` 도메인(L3)에 속한다. 나머지 변경(`nodejs-frontend/*.js`, `static/js/**`, `chatforyou-desktop/build-scripts/**`, `.github/workflows/**`, `scripts/**`, `sage/**`)은 L2 다. 복합 변경은 최고 등급을 따르므로 이 사이클은 L3 이며, 독립 리뷰(Phase 05)와 인수 증거 게이트(`report_gate_by_risk.L3 = enforce`)가 강제된다. 유저가 Q1 에서 "1차에 포함"을 명시적으로 선택한 결과다.

**리스크**

| # | 리스크 | 완화 |
|---|---|---|
| R1 | 프론트 Pod 가 replica 1 + probe 없음(백로그 B8)이라, 베이스 이미지를 node 22 로 바꾼 뒤 기동에 실패해도 클러스터가 자동으로 감지·롤백하지 못한다. 실패하면 곧바로 전면 장애다 | 배포 전에 **로컬에서 컨테이너를 실제로 기동해 `/chatforyou` 응답까지 확인**한다(인수 A9). 배포 직후 유저 수동 확인 절차를 위키 노트에 남긴다. probe 도입 여부는 이번 사이클에서 결정하지 않는다(TBD) |
| R2 | k8s 매니페스트가 리포에 없어 변경이 코드 리뷰를 거치지 않는다 | 이번 사이클은 매니페스트를 바꾸지 않는 설계(절대 base `/chatforyou/`)를 택했다. 그래도 필요한 운영 확인 사항은 위키 노트에 기록하고 유저가 수동 적용한다 |
| R3 | "기능 100% 유지" 요구에 대한 회귀 위험. 전역 공유를 끊는 전환이라 로드 순서에 암묵적으로 의존하던 코드가 조용히 깨질 수 있다 | 전환 대상 화면 전부에 수동 스모크를 인수 항목으로 건다(A1~A3). 전역 → 모듈 전환 시 export 되지 않은 암묵 전역 참조는 eslint `no-undef` 로 잡히도록 `sourceType: module` 로 바꾼다 |
| R4 | 엔트리 HTML 의 script 경로 재지정 때문에, 전환 페이지와 미전환 페이지가 같은 dist 안에서 서로 다른 경로 규약으로 공존한다. 한쪽 규약을 고치면 다른 쪽이 깨질 수 있다 | `server.js` 의 기존 가상 매핑(`/js` → `/static/js` 등)을 **제거하지 않고 유지**한다. 미전환 페이지와 런타임 fetch 가 그 매핑에 의존한다 |
| R5 | dist 로 복사하는 미전환 자산 목록과 전환 완료 파일 집합이 어긋나면 dist 에 중복 파일 또는 누락이 생긴다. 1차에서는 공유 모듈군이 복사 대상이라 목록이 커져 누락 위험도 커진다 | 02 설계에서 복사 대상 목록을 파일 소유자 경계와 1:1 로 고정하고, 통합 지점으로 관리한다 |
| R6 | `popup-loader` 가 런타임에 JS 경로를 문자열로 조립한다. 그 경로의 파일이 dist 에 없으면 팝업이 통째로 열리지 않는다 | 팝업 JS 를 전환하지 않고 원본 그대로 복사해 기존 경로를 유지한다 (02 §1.3). 전환은 2차 |
| R7 | Electron 경로 재작성 규칙이 템플릿 깊이에 따라 달라진다. 새 `/chatforyou/build/` 규칙이 깊은 템플릿(`templates/login/qr/*.html`)에서 어긋날 수 있다 | `scripts/verify-desktop-sync.sh` 로 동기화 결과를 검증하고, Electron 실행 스모크를 인수 항목(A11)으로 건다 |
| R8 | `server.js` 는 요청 경로를 그대로 `path.join` 한다(traversal 가드 없음). 이번에 이 파일을 건드리므로 리뷰에서 지적될 수 있다 | 기존 동작이며 이번 범위 밖으로 둔다. 2차 후보로 기록한다 |

**가정 / 미해결**

- `templates/login/chatlogin.html` 의 `href="/"` 는 `<base>` 가 적용되지 않는 절대경로라 `/chatforyou` 밖으로 나간다. 기존 동작이며 이번 범위 밖으로 둔다(TBD).
- `static/js/error/script_500.js` 를 참조하는 엔트리 HTML 이 리포에 없다. 엔트리로 등록하지 않고 복사 대상으로 둘지, 전환 후 미사용으로 정리할지는 02 에서 결정한다.
- `static/js/roomlist/roomlist.js` 의 `#roomConfigModal` 오류 경로가 `showWarningToast(...)` 를 가드 없이 호출한다. 이 전역은 `static/js/rtc/kurento-service.js` 에만 정의돼 있고 `roomlist.html` 은 그 파일을 로드하지 않는다 — **전환 전부터 그 경로는 ReferenceError** 다. 체감 변화 0건이 요구사항이므로 동작을 그대로 보존하고, 2차 후보로만 기록한다. 다만 `sourceType: module` 전환 후 eslint `no-undef` 가 이 식별자를 새로 잡으므로, 없는 전역을 있다고 선언하지 말고 `eslint-suppressions.json` 의 동결 목록으로 처리한다.
- D-Q2 는 메커니즘을 "publicDir" 로 표현했으나, 미전환 자산이 엔트리 HTML 과 같은 트리에 섞여 있어 단일 publicDir 로 분리할 수 없다. 결과(미전환 자산을 dist 에 그대로 복사)는 동일하게 유지하되 구현 메커니즘은 02 에서 확정한다.

## 4. 최종 결론 및 UX 가이드

- 사용자 관점 결론: **아무것도 바뀌지 않아야 한다.** 화면, 동작, 응답 속도, 로그인 흐름 모두 전과 같아야 하며, 달라진 점이 하나라도 관측되면 그것이 결함이다.
- 개발자 관점 결론: 전역 이름 충돌로 디버깅하던 구조가 끝나고, 단위 테스트가 게이트 명령으로 배선된다. 다만 WebRTC 메인 JS 는 여전히 전역이므로, 2차가 끝나기 전까지는 **두 규약이 공존한다**는 사실을 항상 전제해야 한다.
- 배포 관점 결론: 이미지 교체 실패를 클러스터가 잡아주지 못하므로(R1), 로컬 컨테이너 기동 확인 없이 배포하지 않는다.

## 5. Done Criteria

- [x] `vite build` 가 성공하고, dist 단일 루트에서 방 목록·로그인 3종·팝업 4종·화상통화 방이 모두 로드된다
- [x] 전환 대상 화면의 기능이 전환 전과 동일하게 동작한다 (방 목록 조회/생성/입장, 일반·소셜·QR 로그인)
- [x] 1차에서 classic 으로 남는 공유 모듈(`common/**`, `popup/**`)이 dist 에서 기존 URL 그대로 로드되어, 팝업 4종과 화상통화 방이 회귀 없이 동작한다
- [x] 미전환 화상통화 화면이 전환 전과 동일하게 동작한다 (통화, 화면공유, 녹화, 캐치마인드)
- [x] `window.__CONFIG__` 계약이 동결되어, 미전환 rtc 코드와 Electron `ConfigProcessor` 가 기존대로 동작한다
- [x] `vitest run` 으로 `reconnectPolicy` 테스트와 신규 L2 단위 테스트가 통과한다
- [x] `scripts/verify-frontend-test.sh` 가 프로필 `verification.commands.test` 에서 실행되어 통과한다
- [x] 프론트 문법·lint·데스크톱 동기화 검증 스크립트가 모두 통과한다
- [x] multi-stage `Dockerfile`(node:22-alpine)로 이미지가 빌드되고, 컨테이너 기동 후 `/chatforyou` 가 응답한다
- [x] 데스크톱 릴리즈 빌드가 Node 22 에서 성공한다
- [x] Electron 앱에서 전환 화면의 자산 경로가 정상 해석된다
- [x] 지식 write-back 노트에 2차 이월 항목(`config/config.js` ESM 모듈화 및 `window.__CONFIG__` 전역 해제, 공유 모듈군 `common/**`·`popup/**`·`templates/popup/**` 의 ESM 전환)과 k8s 운영 확인 사항이 기록된다

## 6. Done Criteria Revision Log

Initial revision 1.

### Revision 2

- Changed-At: Phase 03
- Reason: 2차 이월 대상이라 수정할 수 없는 `templates/room/kurentoroom.html` 이 `static/js/common/**` 과 `static/js/popup/**` 을 비모듈 classic `<script>` 로 로드한다. 이 공유 모듈군을 1차에서 ESM 으로 전환하면 그 URL 이 dist 에서 404 가 되거나 비모듈 파싱에 실패해 방 페이지 전체가 죽으며, 이를 피하려면 같은 로직을 dist 에 두 벌 두는 레거시 사본 산출이 강제된다. 유저 판단으로 해당 파일군을 2차로 이월했다(P1). 계획 수립 시점에는 이 결합을 식별하지 못했다.
- Affected-Phases: 01, 02, 03
- Summary: 1차 ESM 전환 범위를 `common/config.js`(신규) · `roomlist.js` · `login/**` · `script_500.js` · `test/reconnectPolicy.test.js` 로 축소했다. `common/{common,spinnerOpt,ajaxUtil,fileUtil}.js` · `popup/**` · `templates/popup/**` 는 2차로 이월하고 1차에서는 dist 복사 대상이 된다. 빌드·배포 인프라(vite/server/Docker/Electron/CI/검증 배선)는 축소하지 않는다. 01 에서 레거시 사본 인수 A18 을 삭제하고 A3 를 전환 인수에서 회귀 인수로 바꿨다. 02 에서 레거시 IIFE 동시 산출 설계(§1.12)를 삭제하고 과도기 전역 의존 설계(§1.13)를 새로 넣었다. 03 의 구현 체크리스트와 인수 추적 표를 축소 범위에 맞춰 재작성해야 한다.
