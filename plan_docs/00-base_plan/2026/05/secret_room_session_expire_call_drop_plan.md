# [Base Plan] 비밀방 세션 만료 통화 끊김 장애 (Secret Room Session Expiry + Call Drop)

**Risk Level**: L3
**Required Phases**: 00 → 01 → 02 → 03 → 04 → 05 → 06
**Issue**: 운영 중 실제 발생한 L3 크리티컬 장애 (3인 비밀방, 63분 통화 끊김, 재접속 후에만 복구)
**Branch**: feat/126
**Baseline**: 직전 #126 수정분 전부 롤백 완료 상태 (clean baseline)
**독립 사이클 근거**: AGENT_GUIDE.md §3.0 — 기술적으로 #126 인접이더라도 유저가 L3 독립 PDCA 사이클을 명시 요청하였으므로 신규 00-base_plan부터 시작한다.

---

## 0. Prior Knowledge (Vault Scan)

| Type | Note | Key Takeaway |
|------|------|--------------|
| BUG | [BUG] kurento-service.js Peer 에러 Fallback 누락 | #126 사이클에서 peer 설정 실패 시 Category A/B 분기 처리 완료. handlePeerSetupError, clearFallbackState, reconnectMetaCache 신설. 13/13 PASS. 이번 버그와 동일 파일이지만 독립 사이클. |
| TECH | [TECH] ChatForYou WebRTC 백엔드 흐름 | JOIN_ROOM/LEAVE_ROOM/RECEIVE_VIDEO_FROM 전체 흐름 이해. userId 교체 감지(isCurrentParticipantSession), 세션 교체 vs 신규 입장 구분. |
| POSTMORTEM | [POSTMORTEM] ChatForYou 서버 장애보고서 | 250921: nginx proxy timeout 미설정이 30분 끊김 원인. 이번 사건은 nginx가 아닌 JWT 토큰 만료(A003) + 방 접근 토큰 만료(R005)가 원인. 동일 증상(통화 끊김)이지만 다른 루트 원인. |
| BUG | [BUG] ChatForYou 예외 전파 미흡 패턴 수정 | 예외 전파 구조 전반 정비 이력. A003 토큰 만료 후 재시도 로직은 ajaxUtil.js에 이미 구현됨. |
| TECH | [TECH] ChatForYou WebRTC 백엔드 흐름 §5.4 LEAVE_ROOM | wkdtpwhs 퇴장 직후 새 세션 재입장 시 leaveRoom() 내 isCurrentParticipantSession() 검사로 세션 매핑 이슈 여부 확인 필요. |

**Vault 분석 결론**: 이번 버그의 신호(통화 끊김)는 과거 nginx 타임아웃 장애와 동일하나, 로그 증거상 원인은 JWT/방 접근 토큰 만료 → WebSocket 기반 통화 중 RECEIVE_VIDEO_FROM 처리 시점의 경쟁 조건(race condition)으로 별개 원인.

---

## 1. Summary (Goal & Scope)

### 버그 증상

1. 로그인된 유저 3명이 비밀방(`b1df35d1-ee60-4dc4-8124-8bbbcc759655`)을 생성하고 입장
2. 방 생성: 2026-05-29 14:30:05 (KST 23:30)
3. 첫 3명 완전 입장: 14:30:23
4. 63분 통화 지속 후 첫 토큰 만료 에러 발생: 15:33:43 (code=A003), 15:33:44 (code=R005)
5. 동시에 USER_NOT_FOUND(U001) 에러 발생: 15:33:46 — `wkdtpwhs`가 `wptqltdkrak` 세션을 찾지 못함
6. 이후 20분 이상 반복적인 퇴장-재입장 루프 발생 (프론트 handleApiError → 자동 leaveRoom 트리거 추정)
7. 재접속(방 재입장) 후에야 정상화됨
8. 새로고침만으로는 복구 안 됨

### 핵심 타임라인 (로그 기반)

```
14:30:05  방 생성 (b1df35d1...)
14:30:10  honmajji039 입장 (1명)
14:30:11  wkdtpwhs 입장 (2명)
14:30:22  wptqltdkrak 입장 (3명) → 통화 시작
14:49:50  honmajji039 퇴장 (2명)
15:02:14  honmajji039 재입장 (3명) → 정상
15:33:42  wkdtpwhs 퇴장 (2명) ← 시작 후 63분
15:33:43  A003: 토큰이 만료되었습니다. (HTTP 요청)
15:33:44  R005: 방 접근 토큰이 만료되었습니다. (HTTP 요청)
15:33:44  wkdtpwhs WebSocket 재접속 성공 (wkdtpwhs JOIN_ROOM)
15:33:44  wptqltdkrak 퇴장 감지 (2명 → wkdtpwhs, honmajji039 남음)
15:33:46  [ERROR] USER_NOT_FOUND(U001): wkdtpwhs가 wptqltdkrak 세션 조회 실패
           → wkdtpwhs가 RECEIVE_VIDEO_FROM(wptqltdkrak) 시도했으나 wptqltdkrak가 이미 퇴장
15:33:49  wkdtpwhs 재퇴장 (1명) ← handleApiError → leaveRoom 실행 추정
15:33:49  A003 재발생
... (이하 반복 루프 15:36~15:37분대까지 지속)
15:39~16:15  3명 모두 재입장, 통화 지속
16:16:32  wkdtpwhs 퇴장
16:16:37  honmajji039 퇴장 (방 해산)
```

### 근본 원인 가설 (Phase 01에서 검증 필요)

**가설 1 (주원인 유력)**: JWT 토큰 만료(A003) + 방 접근 토큰 만료(R005)가 HTTP 재시도 flow를 통해 발생하는 시점에, 프론트 kurento-service.js의 `error` 메시지 핸들러(`handleApiError`)가 WebSocket 에러 메시지 `USER_NOT_FOUND(U001)`에 대해 `leaveRoom('error')`를 호출하거나, `connectionFailed` 처리 경로로 빠져 강제 퇴장을 유발한다.

**가설 2**: wkdtpwhs WebSocket 재접속(JOIN_ROOM) 성공 직후 existingParticipants 목록에 여전히 wptqltdkrak가 포함되어 있어서, RECEIVE_VIDEO_FROM 메시지가 전송되지만 이미 퇴장한 wptqltdkrak의 세션이 조회되지 않아 USER_NOT_FOUND 발생.

**가설 3**: USER_NOT_FOUND(U001) 에러를 수신한 클라이언트(wkdtpwhs)가 `handleApiError`를 통해 `A003/U001 → AUTH_REQUIRED_ERROR_CODES` 처리로 `redirectToLogin()` 또는 강제 퇴장을 유발한다.

**현재 코드 증거 (`ajaxUtil.js`)**:
```javascript
const AUTH_REQUIRED_ERROR_CODES = ['A002', 'A003', 'A004', 'A006', 'U001'];
```
U001(USER_NOT_FOUND)이 AUTH_REQUIRED_ERROR_CODES에 포함되어 있다. WebSocket error 메시지(id=error, code=U001)를 수신하면 `wsMessageHandlers.error` → `handleApiError({ responseJSON: msg })` → `showApiErrorToast(message)` 만 호출하게 되어 있으나, 이 코드가 `leaveRoom` 을 직접 호출하지는 않는다. 따라서 leaveRoom 유발 경로를 추가 추적해야 한다.

---

## 2. Impact Analysis (Critical)

- **[Backend]**: KurentoHandler.processReceiveVideo() — USER_NOT_FOUND 에러가 클라이언트에 전송되는 것 자체는 정상. 그러나 에러를 받은 클라이언트의 후속 행동(leaveRoom 루프)이 비정상. 백엔드 leaveRoom 처리 자체는 기능적으로 정상 동작.
- **[Frontend]**: kurento-service.js의 `wsMessageHandlers.error` 처리 + handleApiError 호출 경로 — U001 수신 시 적절한 처리(toast + 해당 참가자 placeholder)가 이뤄지는지 검증 필요. #126에서 구현한 `handlePeerSetupError` + `clearFallbackState`가 이 경로에 적절히 연결되어 있는지 확인.
- **[Desktop]**: nodejs-frontend 변경 시 sync 필요. Electron 환경에서도 동일 증상 재현 가능성 있음.
- **[인증/토큰 flow]**: A003 발생 시 ajaxUtil.js가 refresh 시도 → 성공하면 재시도. 그러나 WebSocket 채널은 이 refresh flow 밖에 있음 — WebSocket 내 USER_NOT_FOUND 수신 후 클라이언트가 올바르게 복구되는지 전체 경로 검토 필요.

### #126 변경분과의 관계

#126(kurento_peer_error_fallback)에서 수정된 코드:
- `KurentoHandler.java` — processParticipantReceiveFailed, connectException, rate-limit 추가
- `kurento-service.js` — handlePeerSetupError, clearFallbackState, reconnectMetaCache, Category A/B 분기

#126 변경분이 이번 버그에 미친 영향:
- `processReceiveVideo`에서 `USER_NOT_FOUND(U001)` 발생 시 `connectException` → `kurentoMessageSender.sendStandardErrorToUser` 경로로 에러 전송 → 클라이언트 `wsMessageHandlers.error` → `handleApiError` 호출
- **handleApiError는 leaveRoom을 호출하지 않는다** (showApiErrorToast만 호출). 따라서 leaveRoom이 반복된 이유는 다른 경로(connectionFailed, afterConnectionClosed 등)에 있을 가능성이 높음.
- #126에서 Category A 처리로 `leaveRoom('error')`가 호출되는 조건: `handlePeerSetupError` Category A에서 `showConnectionFailModal` → 사용자가 확인 클릭 → `leaveRoom('error')`. 이 경로가 이번 버그에 연루될 수 있음.

---

## 3. Technology & Risks

### 핵심 기술 리스크

| 위험 | 설명 | 영향도 |
|------|------|--------|
| WebSocket + JWT 갱신 비동기 타이밍 | HTTP 토큰 갱신(ajax)과 WebSocket 세션이 별개 채널로 동작 — 토큰 갱신 완료 전 WebSocket 메시지 처리 중 USER_NOT_FOUND 발생 가능 | 높음 |
| USER_NOT_FOUND → leaveRoom 루프 | U001 수신 후 클라이언트가 연속적으로 leaveRoom + rejoin을 반복하는 루프 진입 가능성 | 높음 |
| 세션 교체 타이밍(replace vs. leave) | wkdtpwhs 퇴장+재접속 중 wptqltdkrak도 동시에 퇴장하면 existingParticipants 목록과 실제 참가자 상태 불일치 발생 | 중간 |
| 비밀방 특이성 | 비밀방은 방 접근 토큰(R005) 도 별도 만료 관리 — 일반방 대비 토큰 복구 경로가 다름 | 중간 |
| #126 사이드이펙트 | handlePeerSetupError Category A에서 `showConnectionFailModal(dismissible: false)` 이후 leaveRoom이 강제 호출되는 경로가 USER_NOT_FOUND 에러를 잘못 처리하는지 확인 필요 | 중간 |

### 재현 방법 (검증용)

1. 3인 이상 비밀방 생성
2. 63분 이상 통화 유지 (JWT 기본 만료 시간 전후)
3. 토큰 만료 직후 한 명이 페이지 이동 또는 새로고침 → WebSocket 재접속
4. 나머지 참가자의 화면에서 `USER_NOT_FOUND` 에러 토스트 발생 + leaveRoom 루프 확인

단축 재현 방법:
- JWT access_token을 localStorage에서 강제 삭제 후 RECEIVE_VIDEO_FROM 메시지 전송 시도
- 또는 서버측 JWT 만료 시간을 5분으로 줄여 빠른 재현

---

## 4. Final Conclusion & UX Guide

### UX 요구사항

1. 통화 중 JWT 토큰이 만료되더라도 WebSocket 채널은 끊기지 않아야 한다
2. RECEIVE_VIDEO_FROM 중 상대방이 이미 퇴장해 USER_NOT_FOUND가 발생하더라도 강제 퇴장(leaveRoom) 이 일어나서는 안 된다 — toast + placeholder 처리로 충분
3. 반복 퇴장-재입장 루프는 절대 발생하면 안 된다
4. 방 접근 토큰(R005) 만료 시에도 비밀방에서 계속 통화가 유지되어야 한다

### 수정 방향 (Phase 01/02에서 상세화)

- `wsMessageHandlers.error`에서 U001 수신 시 Category B 처리(toast + placeholder)로 연결되어야 함 — leaveRoom 미호출
- WebSocket과 HTTP 채널의 토큰 만료 처리가 분리되어야 함
- 비밀방 환경에서 방 접근 토큰 갱신 성공 여부와 무관하게 WebSocket 연결이 유지되어야 함

---

## 5. Document Mapping (Checklist)

- [x] `plan_docs/00-base_plan/2026/05/secret_room_session_expire_call_drop_plan.md` (이 파일) — chatforyou-lead
- [x] `plan_docs/01-plan/secret_room_session_expire_call_drop.md` — chatforyou-lead
- [x] `plan_docs/02-design/secret_room_session_expire_call_drop.md` — chatforyou-lead
- [x] `springboot-backend/plan_docs/secret_room_session_expire_call_drop_plan.md` — chatforyou-backend-expert
- [x] `nodejs-frontend/plan_docs/secret_room_session_expire_call_drop_plan.md` — chatforyou-frontend-expert
- [x] `plan_docs/03-implementation/secret_room_session_expire_call_drop.md` — chatforyou-lead
- [x] `plan_docs/04-analyze/secret_room_session_expire_call_drop.md` — chatforyou-lead + qa-expert 보조
- [x] `plan_docs/05-expert-review/secret_room_session_expire_call_drop.md` — chatforyou-external-expert (APPROVED)
- [x] `plan_docs/06-report/secret_room_session_expire_call_drop.md` — chatforyou-lead

### 프로세스 누락 재발 방지 기록

이번 PDCA 사이클은 유저 요청에 따라 신규 독립 사이클로 시작한다.
직전 #126(kurento_peer_error_fallback) 사이클과 기술적으로 인접하지만,
AGENT_GUIDE.md §3.0 독립 PDCA 사이클 규칙에 따라 기존 문서를 재사용하지 않는다.

변경된 파일:
- `AGENT_GUIDE.md` — §3.0 Independent PDCA Cycle Rule 신설
- `.codex/skills/chatforyou-dev-team/SKILL.md` — "독립 PDCA 사이클 규칙" 섹션 신설

- [x] vault knowledge capture 완료 (POSTMORTEM 1건 + BUG 1건 신규 생성, index.md + log.md 갱신)
