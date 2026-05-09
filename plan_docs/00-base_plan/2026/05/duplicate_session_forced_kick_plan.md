# [Base Plan] Duplicate Session Forced Kick

**작성일:** 2026-05-08
**Risk Level:** L3
**Required Phases:** 00 -> 01 -> 02 -> 03 -> 04 -> 05 -> 06

## 1. Summary (Goal & Scope)

duplicate session 재입장 시 새 세션만 active 로 유지하고, old client 는 room 전용 WS 이벤트로 강제 종료한다.
다른 참가자에게는 강퇴 사유를 노출하지 않고 same-user media session 만 조용히 교체한다.

## 2. Impact Analysis (Critical)

- [Backend]: `InMemoryParticipantRepository`, `KurentoHandler`, `KurentoRoomManager`, `KurentoMessageBuilder`, `KurentoMessageType`
- [Frontend]: `nodejs-frontend/static/js/rtc/kurento-service.js`, room template modal 재사용 분기
- [Desktop]: `nodejs-frontend` 변경분 sync 필요
- [QA]: stale old session no-op, count invariant, recording/game invariant, manual 2-session smoke

## 3. Technology & Risks

- repository auto-close 를 유지하면 kick 선전송이 불가능하다.
- replacement 중 `participantLeft/newParticipantArrived` 를 재사용하면 peer DOM flicker 와 user_count 오염 위험이 있다.
- forced exit 는 `leaveRoom()` 재사용 금지, unload/LEAVE_ROOM/datachannel farewell 중복 차단이 필요하다.

## 4. Final Conclusion & UX Guide

- old client WS: `sessionReplaced`
- peer WS: `participantSessionReplaced`
- replacement 동안 `incrementUserCount()`, `decrementUserCount()`, `sendRoomUserCntEvent()` 미호출
- old client 모달:
  - 제목: `세션 종료`
  - 본문: 서버 message 그대로 사용
  - dismiss 불가, 확인 후 `roomlist.html` 강제 이동

## 5. Document Mapping (Checklist)

- [x] `plan_docs/01-plan/duplicate_session_forced_kick.md`
- [x] `plan_docs/02-design/duplicate_session_forced_kick.md`
- [x] `plan_docs/03-implementation/duplicate_session_forced_kick.md`
- [x] `springboot-backend/plan_docs/duplicate_session_forced_kick.md`
- [x] `nodejs-frontend/plan_docs/duplicate_session_forced_kick.md`
