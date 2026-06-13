# [Base Plan] WebRTC WebSocket 통화 중 자동 재연결 (1006 abnormal close 복구)

## 0. Prior Knowledge (Vault Scan)

| Type | Note | Key Takeaway |
|------|------|--------------|
| BRAINSTORM | [[BRAINSTORM - ChatForYou 무중단 배포 방 복구 설계]] | Rolling Update(서버 측 Pod 종료)로 인한 단절 복구를 Phase 0~4로 설계. **본 작업과 트리거가 다름**: 무중단 배포 설계는 *서버가 원인*(PreStop + HTTP Recovery API + Set-Cookie 재발급 필수)이고, 본 작업은 *클라이언트 로컬 망 단절*이 원인이라 sticky cookie가 가리키는 Pod가 그대로 살아있음 → **HTTP Recovery API/Set-Cookie 불필요**. 다만 그 문서가 명시한 "ws.onclose → auto-reconnect (안전망), exponential backoff, 기존 `connectWebSocket() → register() → JOIN_ROOM` 재사용, Thundering Herd jitter" 패턴을 본 작업이 그대로 차용한다. |
| BRAINSTORM | [[BRAINSTORM - ChatForYou 무중단 배포 방 복구 설계 논의]] | 재접속/새로고침 복구 논의 이력. 같은 방에서 WS 끊김/새로고침 분기 처리 선례. |
| SPEC | [[SPEC - ChatForYou 기능 개발 우선순위 로드맵]] | P1 "방 상태 복구" 항목과 인접하나, 본 작업은 그 하위의 *클라이언트 자동 재연결*만 선제 구현하는 성격. |
| TECH | (해당 없음 — 신규 클라이언트 재연결 상태머신은 본 사이클에서 신설) | 완료 후 `TECH - WebSocket 클라이언트 자동 재연결` 신규 노트 후보. |
| BUG | [[BUG - WebRTC U001 경쟁조건 + leaveRoom 루프 패턴]] | **가드레일 직접 근거.** 통화 중 토큰 갱신 실패 시 `redirectToLogin()` 호출 금지 → `isInRoomCallPage()` 확인 후 toast만. 재연결 시 `register()`가 다시 tokenAjax를 타므로 이 경로가 재트리거될 수 있음. WS 재연결 후 existingParticipants 수신 + 참가자 퇴장 race(K008/U001) 처리 패턴도 재사용. |
| BUG | [[BUG - kurento-service.js Peer 에러 Fallback 누락]] | `disposeParticipantEntry`/`handlePeerSetupError`/`clearFallbackState`/`reconnectMetaCache` 동작과 Category A(본인 fatal)/B(상대 partial) 분리. 재입장 전 기존 participants 정리 시 이 함수들을 재사용해야 RTCPeerConnection 누수·중복 타일을 막을 수 있음. |
| POSTMORTEM | [[POSTMORTEM - 2026-05-29 비밀방 세션 만료 통화 끊김]], [[POSTMORTEM - 260528 장애보고서]] | 통화 중 토큰 만료 → 강제 리다이렉트로 통화가 끊긴 장애. 재연결이 인증 실패를 재유발하지 않도록 #127 정책 준수가 필수. |

## 1. Summary (Goal & Scope)

**문제**: 통화 중 클라이언트 로컬 망이 순간 단절되면 시그널링 WebSocket(WSS)이 1006(abnormal close)으로 끊긴다. 현재 `kurento-service.js`의 `ws.onclose`(L325-330)는 경고 로그만 출력하고 자동 재연결이 없어, 사용자가 수동 새로고침/재입장하기 전까지 영구 끊김 상태가 된다.

**목표**: 의도치 않은 1006 단절 시 클라이언트가 **자동으로 시그널링 WS를 재연결하고 기존 입장 흐름(register → JOIN_ROOM → existingParticipants → receiveVideo)을 재생**하여 ~2~5초 끊겼다 통화를 재개한다. 사용자 수동 조작 불필요.

**범위**:
- 포함: `nodejs-frontend/static/js/rtc/kurento-service.js`의 `ws.onclose` 자동 재연결, `online`/`offline` 이벤트 연동, 지수 백오프 + cap + in-flight 단일화, 경량 heartbeat(half-open 감지 단축), 의도적 종료 가드 존중, 재입장 전 기존 peer 정리.
- 제외(향후 과제): Option B(ICE restart + 백엔드 세션 grace 보존 + reconnection token), 서버 측 PreStop/HTTP Recovery API(무중단 배포 설계 Phase 1~2 소관), 게임 상태 복원.

## 2. Impact Analysis (Critical)

- **[Backend]**: **무변경.** 재연결은 신규 API/메시지 타입 없이 기존 흐름을 재사용한다. FE `register()`가 보내는 `JOIN_ROOM`은 기존 `KurentoHandler` 경로를 그대로 타고, 서버는 같은 userId 재입장 시 `sessionReplaced`/`participantSessionReplaced`로 멱등 처리한다(FE 핸들러 이미 등록됨). `afterConnectionClosed → leaveRoom`이 끊긴 세션의 Kurento 엔드포인트를 즉시 해제하므로 재연결은 "재입장 + 미디어 재협상" 경로로 자연 성립. backend-expert는 **무변경 확인(no-op 검증)** 역할만 수행.
  - CodeGraph 확인: FE `connectWebSocket`(impact=2 심볼, kurento-service.js 내부 한정). FE `register`(L712)와 BE `KurentoUserRegistry.register`는 동명이인(별개 심볼) — FE 변경이 BE register를 건드리지 않음.
- **[Frontend]**: `nodejs-frontend/static/js/rtc/kurento-service.js` 단일 파일 중심. `ws.onclose`/`ws.onerror` 핸들러, 신규 재연결 상태머신(백오프·in-flight 가드·heartbeat·online/offline 리스너), 재입장 전 기존 participants 정리. 재연결 진입 시 기존 가드(`roomTeardownStarted`/`forcedSessionExitInProgress`/`suppressWebSocketCloseWarning`) 존중. `register()`가 재호출되며 `tokenAjax`를 타므로 통화 중 인증 실패 경로는 `ajaxUtil.js`의 `isInRoomCallPage()` 정책(#127)에 의존 — **ajaxUtil.js 자체는 무변경**(기존 정책 재사용).
- **[Desktop]**: `chatforyou-desktop/`은 `nodejs-frontend/` sync 대상. 직접 수정 금지. FE 변경 후 `npm run sync:frontend`(또는 desktop 측 `npm run sync`)로 빌드 무에러 확인 필수. SCSS 변경 없으면 `scss:build` 불필요. 새 UI(재연결 오버레이)를 추가한다면 인라인 스타일/Bootstrap 유틸 우선 — SCSS 신규 파일은 지양.

### Before / After (요약)

| 항목 | Before | After |
|---|---|---|
| 1006 단절 | `console.warn`만, 영구 끊김 | 가드 미설정 시 지수 백오프로 자동 재연결 + 기존 입장 흐름 재생 |
| online/offline | 미사용 | offline 시 백오프 일시중지, online 복귀 시 즉시 재시도 |
| half-open 감지 | onclose에만 의존(망에 따라 수십초 지연) | 경량 heartbeat로 단축 감지 |
| 재입장 시 peer | 정리 없이 중복 가능 | `disposeParticipantEntry`로 기존 participants 정리 후 재협상 |

## 3. Technology & Risks

- **기술 스택**: 브라우저 전역 스크립트 + jQuery + `kurento-utils.js`. React/TS/import 금지(`docs/nodejs_frontend.md`). 재연결 상태는 모듈 스코프 `let` 변수 + setTimeout/setInterval로 관리.
- **위험 요소**:
  - 재연결 폭주(thundering/loop): 지수 백오프 + cap + in-flight 단일 가드 + (선택) jitter로 완화.
  - RTCPeerConnection/비디오 타일 누수: 재입장 전 `disposeParticipantEntry` 일괄 정리 필수.
  - 인증 실패 루프: 통화 중 토큰 만료 시 redirect 금지(#127), `recording_download_token_refresh` 사이클과 교차 가능 — 동시 갱신 안전성 검토 필요.
  - 의도적 종료 오인: teardown/forcedExit/suppress 가드 미존중 시 정상 퇴장에도 재연결 시도 위험.
  - **WebRTC/WebSocket L3 변경** → 설계 단계 2-round review 필수(02-design에 기록).
- **자동복구 불가 경계(문서화 대상)**: 망 영영 미복구 / 방이 batch로 삭제됨 / 토큰 갱신 실패. 이 경우 백오프 cap 도달 후 사용자 액션 모달로 폴백.

## 4. Final Conclusion & UX Guide

- 확정 방향: **Option A′(onclose 자동 재연결 + online/offline + 경량 heartbeat 결합형)**. 3안 비교는 `02-design`에서 정식 수행 후 확정.
- UX: 재연결 중 비차단 안내(오버레이/토스트), 성공 시 자동 해제, cap 도달/영구 단절 시 기존 `showConnectionFailModal` 재사용한 수동 재입장 유도.
- 백엔드 무변경, FE 단독. Desktop sync 대상.

## 5. Document Mapping (Checklist)

- [x] 요구사항 및 데이터: `plan_docs/01-plan/webrtc_ws_auto_reconnect.md`
- [x] 인터페이스 및 시퀀스: `plan_docs/02-design/webrtc_ws_auto_reconnect.md`
- [ ] 프론트 구현 가이드: `nodejs-frontend/plan_docs/webrtc_ws_auto_reconnect_plan.md` (frontend-expert 작성 — STEP 2)
- [ ] 백엔드 무변경 확인: backend-expert no-op 검증 (STEP 2, 신규 문서 불필요 시 보고로 갈음)
- [ ] 구현 가이드/결과: `plan_docs/03-implementation/webrtc_ws_auto_reconnect.md` (STEP 2~3)
- [ ] 갭 분석: `plan_docs/04-analyze/webrtc_ws_auto_reconnect.md` (STEP 4)
- [ ] 전문가 리뷰: `plan_docs/05-expert-review/webrtc_ws_auto_reconnect.md` (STEP 5)
- [ ] 최종 보고서: `plan_docs/06-report/webrtc_ws_auto_reconnect.md` (STEP 6, 05 APPROVED 시에만)
- [ ] vault knowledge capture 완료 (또는 해당 없음 — 이유 명시)
