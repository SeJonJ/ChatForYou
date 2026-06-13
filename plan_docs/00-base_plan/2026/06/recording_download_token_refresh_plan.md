# [Base Plan] 방 안 Firebase 토큰 만료 근본 대응 (reactive 확장 A + 경량 proactive D1)

> Plan stem: `recording_download_token_refresh`
> Risk Level: **L3** (Desktop sync 대상 + 유저 L3 명시)
> Independent PDCA cycle (AGENT_GUIDE §3.0) — #127(`secret_room_session_expire_call_drop`)과 동일 파일 인접 버그이나 별도 독립 사이클로 처리.

## 0. Prior Knowledge (Vault Scan)

| Type | Note | Key Takeaway |
|------|------|--------------|
| BRAINSTORM | — | 해당 없음 |
| SPEC | — | 해당 없음 |
| TECH | TECH - Axios JS | ChatForYou FE는 token refresh / error handling을 ajax 공통 유틸로 중앙화하는 구조. file 경로만 우회 상태였음을 확인. |
| BUG | BUG - WebRTC U001 경쟁조건 + leaveRoom 루프 패턴 | **패턴 B**(통화 중 토큰 갱신 실패 guard): A003 갱신 실패 시 `isInRoomCallPage()`이면 `redirectToLogin()` 금지·toast만. 본 사이클의 file 경로 확장에 동일 적용 필수. `_connected` stale-true 가능성은 heuristic 한계로 인지. |
| POSTMORTEM | POSTMORTEM - 2026-05-29 비밀방 세션 만료 통화 끊김 | #127 사이클의 원본 장애. JWT 만료(A003) + 통화 중 무조건 redirect = WebSocket 끊김 + leaveRoom 루프. 교훈: "통화 중 리다이렉트는 WebSocket을 끊는다 → graceful degradation". 본 사이클은 이 교훈을 file download/upload 경로에 확장. |

### Vault Scan 결론
- #127 hotfix(`secret_room_session_expire_call_drop`, commit `c7a16a9`)가 `executeTokenAjax()` 경로에만 A003 retry + `isInRoomCallPage()` guard를 도입했고, 레거시 `fileUploadAjax`/`fileDownloadAjax`는 마이그레이션에서 누락됨 → 본 버그의 직접 원인.
- 재발 방지 체크리스트("HTTP 토큰 갱신 실패 처리 시 `redirectToLogin()` 호출 전 `isInRoomCallPage()` 확인")가 file 경로에는 미적용 상태 → 본 사이클이 이를 닫는다.

## 1. Summary (Goal & Scope)

### 문제
운영 로그(`...v03...bqt95.log`)에서 확인: 로그인 약 1시간 경과 후 Firebase ID 토큰이 만료되고, 방 안에서 녹화 파일 다운로드를 누를 때마다 백엔드가 `A003(토큰이 만료되었습니다)`를 반환(08:46:30/33/36 연속 + 08:55~08:57 반복). 방 입장·통화(WebSocket)는 정상인데 다운로드만 실패.

### 근본 원인 (코드 확정)
1. **마이그레이션 누락 (직접 원인)**: #127 hotfix가 A003 refresh·retry를 `executeTokenAjax()`에만 적용. `fileUploadAjax`(L525)/`fileDownloadAjax`(L554)에는 refresh·retry 없음.
2. **blob 에러 구조적 미탐지**: `fileDownloadAjax`는 `dataType:'binary'` + `xhrFields.responseType:'blob'`이라 A003 에러 본문이 Blob으로 와서 `error.responseJSON`이 항상 `undefined` → 코드 식별 자체가 불가.
3. **방 안 토큰 방치 (구조적 원인)**: 방 페이지(`templates/room/kurentoroom.html`)는 Firebase SDK를 로드하지 않고 `ajaxUtil.js`의 raw REST + localStorage만 사용 → 로그인 페이지의 SDK 자동 갱신(`login/main.js onIdTokenChanged`)이 방 안엔 없음. 토큰은 만료된 채 방치. WebSocket `/signal`은 Firebase 토큰 미사용이라 통화는 유지되지만 토큰은 계속 썩음.

### 확정 범위 (유저 승인 완료 — 변경 금지)
- **A (reactive 확장)**: `ajaxUtil.js`의 `fileUploadAjax()`/`fileDownloadAjax()`에 A003 refresh·retry를 기존 인프라 재사용으로 확장. `fileDownloadAjax`는 blob 에러 → JSON 정규화 추가.
- **D1 (경량 proactive)**: JWT `exp` 디코드 헬퍼 + 만료 임박(`exp-now<300s`) 시 보호 요청 직전/스케줄 훅에서 선제 refresh. SDK 미도입, dedup은 기존 모듈 레벨 promise 재사용. 헬퍼 1 + 스케줄 훅 1로 한정.
- **Backend**: `RecordingFileService.getObject()` L110-112의 `expiresAt` 차단 제거("방 생존 중 다운로드 허용"). config·Redis 필드는 삭제 금지(호환성). `/file/download` 계약 유지.
- **거절된 방안 C**: 백엔드 인증 chokepoint refresh — 만료토큰 신원검증=계정탈취 위험으로 비채택.

### Out of Scope (의도적 제외 — 사유 명시)
| 제외 항목 | 사유 |
|---|---|
| 방 페이지에 Firebase SDK 도입 | 오버 리팩토링. proactive는 기존 REST 프리미티브 재사용(D1)로 충분. |
| `buildTokenHeaders` 전면 async 전환 | 대규모 변경. file 2함수 외과적 확장으로 한정. |
| 백엔드 인증 로직 변경 | 방안 C 보안 위험으로 거절. expiresAt 1건만. |
| `recording.url-expire.minutes` config / `RecordingFile.expiresAt` Redis 필드 삭제 | 호환성. 차단 정책만 완화, 필드는 유지. |
| WebSocket `/signal` 토큰화 | 토큰 미사용 경로 — 만료 무관, 변경 불필요. |

## 2. Impact Analysis (Critical)

### [Backend]
- 영향 1건: `springboot-backend/src/main/java/webChat/service/file/impl/RecordingFileService.java` `getObject()` L110-112 expiresAt 차단 제거.
- CodeGraph impact: `RecordingFileService.getObject:98`은 `FileController.download:68` 단일 호출처. `super.getObject()`(AbstractFileService, Minio/Recording 공용)는 무변경 → 일반 파일 경로 무영향.
- `/chatforyou/api/file/download` 경로·파라미터(`roomId/bucket/fileName/filePath`)·binary 응답·A003 ErrorResponse 형식 모두 유지.
- 인증·다운로드 로그 흐름(`TokenUtils.checkGoogleOAuthToken` → `getValidatedOauthUser` → `saveDownloadLog`)은 무변경.

### [Frontend]
- 핵심: `nodejs-frontend/static/js/common/ajaxUtil.js` — `fileUploadAjax`/`fileDownloadAjax` A003 retry 통합 + blob→JSON 정규화 헬퍼 + (D1) JWT exp 디코드 헬퍼 + 스케줄 훅 1.
- 재사용(신규 작성 금지): `getAccessTokenRefreshPromise()` L89, `refreshAccessTokenWithFirebaseRest()` L52, `isInRoomCallPage()` L118, `shouldRetryAccessTokenRequest()` L186, `buildTokenHeaders()` L35, `handleApiError()` L323.
- 호출처(전수 확인): `fileUtil.js`(일반 파일 upload L70/download L94), `dataChannelFileUtil.js`(녹화·채팅 파일 upload L82/download L154). 콜백 계약(성공=Blob, 실패=정규화 error) 유지 필요.
- 토큰 관여 HTTP 표면 4곳(`buildTokenHeaders`/`fileUploadAjax`/`fileDownloadAjax`/`refreshRoomTokenWithApi`)만 존재 — file 2함수 수정으로 "엔드포인트가 refresh 누락" 버그 클래스가 닫힘.

### [Desktop]
- `chatforyou-desktop/src` 직접 수정 금지. `nodejs-frontend` 수정 후 sync 검증(`npm run sync`/`sync:frontend`).
- CSS/SCSS 변경 없음 예상 → SCSS 빌드 생략 가능(사유 보고). Electron IPC 다운로드(`fileUtil.js` isElectron 분기)는 콜백 계약 유지 시 무후퇴.

## 3. Technology & Risks

| 리스크 | 영향 | 완화 |
|---|---|---|
| blob 에러 정규화 비동기화 | `fileDownloadAjax` 재시도 분기가 `Blob.text()` Promise 의존 → 동기 콜백 계약과 충돌 가능 | 재시도 분기를 Promise 체인으로 처리, 최종 콜백 시그니처(성공=Blob/실패=정규화 error)는 유지. component plan에서 확정. |
| `_connected` stale-true (vault 인지된 heuristic 한계) | 비정상 종료 후 토큰 갱신 실패 시 toast만 뜨고 redirect 안 됨 | 기존 #127 동작과 동일 — 본 사이클이 새 리스크를 도입하지 않음. |
| proactive 중복 refresh 스톰 | 스케줄 훅 + 요청 직전 동시 트리거 | 기존 모듈 레벨 `accessTokenRefreshPromise` dedup이 보장. 신규 dedup 작성 금지. |
| `exp` 파싱 실패/부재 | proactive skip | 안전하게 skip 후 reactive(A003 retry)로 폴백. |
| 백엔드 expiresAt 완화의 보안 영향 | 만료된 presigned 정책 무력화 우려 | 다운로드는 방(`KurentoRoom`) Redis 생존 + 인증 토큰 검증 + 녹화 메타 존재가 전제 → 방 종료 시 자동 차단 유지. config·필드는 보존. |
| WebRTC 코어 영향 | 없음 | WebSocket/Signaling/Kurento/DataChannel/미디어 파이프라인 무변경 → `webrtc-review-protocol.md` 2-round review 미트리거. L3 expert-review 루프(STEP 5)는 적용. |

## 4. Final Conclusion & UX Guide

- reactive(안전망) + 경량 proactive(방 안 선제 갱신) 병행으로 "방 안 토큰 방치" 근본 대응. 백엔드 expiresAt 완화로 "방 생존 중 다운로드 허용" 정책 정합.
- UX: 만료 임박 토큰이어도 (D1) 선제 갱신으로 첫 다운로드가 A003 없이 성공. 갱신 실패 + 통화 중이면 redirect 없이 toast 안내만(통화 유지). reactive A003 retry는 안전망으로 유지.

## 5. Document Mapping (Checklist)

- [x] `plan_docs/00-base_plan/2026/06/recording_download_token_refresh_plan.md` — lead (본 문서)
- [x] `plan_docs/01-plan/recording_download_token_refresh.md` — lead
- [x] `plan_docs/02-design/recording_download_token_refresh.md` — lead
- [ ] `springboot-backend/plan_docs/recording_download_token_refresh_plan.md` — backend-expert (STEP 2)
- [ ] `nodejs-frontend/plan_docs/recording_download_token_refresh_plan.md` — frontend-expert (STEP 2)
- [ ] `plan_docs/03-implementation/recording_download_token_refresh.md` — lead (STEP 3)
- [ ] `plan_docs/04-analyze/recording_download_token_refresh.md` — lead + qa(보조) (STEP 4)
- [ ] `plan_docs/05-expert-review/recording_download_token_refresh.md` — external-expert(+codex, L3 최대 3 iteration) (STEP 5)
- [ ] `plan_docs/06-report/recording_download_token_refresh.md` — lead (STEP 6, 05 APPROVED 시에만)
- [ ] vault knowledge capture 완료 (또는 해당 없음 — 이유 명시)
