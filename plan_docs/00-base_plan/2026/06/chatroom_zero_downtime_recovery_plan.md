# [Base Plan] ChatRoom Zero-Downtime Deployment Recovery

## 0. Prior Knowledge (Vault Scan)

| Type | Note | Key Takeaway |
|------|------|--------------|
| SPEC | SPEC - ChatForYou 채팅방 무중단 배포 복구 설계 | 최신 코드 기준 SSOT. 현재 구현은 일반 WebSocket 재연결과 owner-scope shutdown guard까지만 존재하며, deploy recovery API와 Redis claim lock은 미구현. |
| BRAINSTORM | BRAINSTORM - ChatForYou 무중단 배포 방 복구 설계 | 2026-05 논의 아카이브. HTTP recovery, HttpOnly cookie 제약, owner-unhealthy 방 복구 방향은 유지한다. |
| BRAINSTORM | BRAINSTORM - ChatForYou 무중단 배포 방 복구 설계 논의 | 과거 검토 이력. 최신 코드와 어긋나는 전제는 SPEC을 우선한다. |
| TECH | TECH - ChatForYou 채팅방 라우팅 아키텍처 | 방 라우팅은 Redis room mapping과 nginx sticky cookie에 의존한다. |
| TECH | TECH - ChatForYou WebRTC 백엔드 흐름 | Kurento room/session lifecycle은 backend in-memory state와 Redis room state가 함께 움직인다. |
| BUG | BUG - 자동재연결 후 방 퇴장 시 userCount -1 회귀 | 재연결과 room lifecycle 변경은 userCount와 leave/cleanup 중복 처리 회귀를 재검증해야 한다. |

## 1. Summary (Goal & Scope)

Rolling Update 중 기존 backend Pod가 종료될 때 RTC 방이 즉시 삭제되어 사용자가 dashboard로 튕기는 현재 흐름을 보완한다.

목표는 Phase 1 기준으로 0초 무중단이 아니다. 첫 목표는 다음 세 가지다.

- owner instance가 unhealthy인 방을 무조건 삭제하지 않고 recoverable candidate로 판정한다.
- HTTP recovery response로 `chatforyou-server`, `room-redirect-count`, `room-id` cookie를 재설정한다.
- 기존 frontend WebSocket reconnect와 `register()` -> `JOIN_ROOM` 흐름을 재사용해 사용자가 수동 조작 없이 같은 방으로 돌아오게 한다.

명시적 제외 범위:

- CatchMind 라운드, 점수, 그림판 상태 복구
- 완전한 0초 media continuity
- recording partial file 복구의 완전 자동화
- 운영 cluster에 직접 `kubectl apply` 하는 작업

## 2. Impact Analysis (Critical)

- [Backend]: `ChatRoomController` owner-unhealthy branch, `RoutingService` cookie issuance, Redis room/routing/claim operations, `ShutdownConfig` shutdown metadata, health/readiness behavior, Kurento room lifecycle에 영향이 있다.
- [Frontend]: `kurento-service.js` reconnect state machine에 deploy recovery HTTP step과 reason별 UX 분기가 추가된다. 기존 reconnect loop는 유지하되 sticky cookie 갱신은 HTTP response에 위임한다.
- [Desktop]: `chatforyou-desktop/src` 직접 수정은 금지한다. `nodejs-frontend` 변경 후 desktop sync 검증이 필요하다.
- [Infra/Deploy]: 현재 repo에는 운영 Deployment manifest가 없고 GitHub Actions는 `kubectl set image` 중심이다. preStop/readiness 적용은 repo-managed manifest 추가 또는 workflow patch 절차 중 하나를 결정해야 한다.

## 3. Technology & Risks

Risk Level: L3

Reason:

- WebSocket reconnect, WebRTC/Kurento room lifecycle, Redis room ownership, K8s lifecycle이 함께 바뀐다.
- `AGENT_GUIDE.md` 독립 PDCA 규칙에 따라 기존 `webrtc_ws_auto_reconnect` 문서를 재사용하지 않고 신규 phase 문서를 작성한다.
- 구현 전 `docs/agent/webrtc-review-protocol.md`의 두 라운드 리뷰가 문서화되어야 한다.

Current-code findings:

- `joinRoom()`은 owner instance가 없거나 unhealthy이면 `delChatRoom(roomId, true)` 후 `REDIRECT_DASHBOARD`를 반환한다.
- `RoutingServiceImpl`은 `chatforyou-server`와 `room-redirect-count`를 HttpOnly cookie로 설정하므로 JavaScript가 직접 갱신할 수 없다.
- `ShutdownConfig`는 현재 instance가 owner인 방만 cleanup하는 guard가 있지만, owner 방을 `CREATED`와 `userCount=0`으로 되돌려 recoverable state를 구분하지 못한다.
- frontend `kurento-service.js`에는 일반 WebSocket reconnect loop가 이미 있으나 deploy recovery API 호출은 없다.
- Redis prefix에는 `room:members:` ledger는 있으나 `room:claim-lock:` 또는 recovery metadata prefix는 없다.

Primary risks:

- P0: owner-unhealthy 방을 복구 후보로 보지 못하면 기존처럼 방 삭제가 발생한다.
- P0: HttpOnly sticky cookie를 WebSocket frame 또는 JavaScript cookie 조작으로 해결하려 하면 실제 routing이 갱신되지 않는다.
- P0: Redis claim을 slave read 기반 read-modify-write로 구현하면 multi-Pod race와 replication lag로 잘못된 owner가 생길 수 있다.
- P1: `ShutdownConfig`가 recovery metadata 없이 방을 `CREATED`로 되돌리면 정상 빈 방과 deploy recovery 방을 구분할 수 없다.
- P1: reconnect retry와 recover retry가 중첩되면 reconnect 폭주, stale WebSocket handler, userCount 회귀가 생길 수 있다.
- P1: repo-managed K8s manifest가 없으면 preStop/readiness 변경이 코드 리뷰와 재현 가능한 검증 범위 밖에 남는다.

## 4. Final Conclusion & UX Guide

Phase 1 UX:

- Rolling Update 또는 owner Pod 종료로 연결이 끊기면 기존 reconnect overlay를 유지한다.
- 복구 가능한 방이면 recovery API 성공 후 같은 화면에서 WebSocket을 다시 열고 `JOIN_ROOM`을 재수행한다.
- 복구 불가능한 방, 삭제된 방, TTL이 지난 방, 권한이 없는 방은 기존 dashboard fallback을 유지하되 안내 문구를 deploy recovery 실패 사유에 맞게 분리한다.
- 사용자가 직접 새로고침하거나 방 목록에서 재입장하는 수동 복구도 기존 동작과 충돌하지 않아야 한다.

Engineering conclusion:

- 이 기능은 기존 generic WebSocket reconnect의 후속 보완이지, reconnect loop 전체 재작성 작업이 아니다.
- sticky cookie 재발급은 HTTP API 책임으로 고정한다.
- owner claim은 Redis master-side atomic operation 또는 lock abstraction으로 설계한다.
- L3 implementation은 Phase 01/02와 two-round WebRTC review가 끝난 뒤에만 시작한다.

## 5. Document Mapping (Checklist)

- [x] Phase 00 Base Plan: `plan_docs/00-base_plan/2026/06/chatroom_zero_downtime_recovery_plan.md`
- [x] Phase 01 Plan: `plan_docs/01-plan/chatroom_zero_downtime_recovery.md`
- [x] Phase 02 Design: `plan_docs/02-design/chatroom_zero_downtime_recovery.md`
- [x] WebRTC Round 1 Flow Correctness Review: recorded in Phase 02
- [x] WebRTC Round 2 Failure & Lifecycle Review: recorded in Phase 02
- [x] Backend component plan: `springboot-backend/plan_docs/chatroom_zero_downtime_recovery_plan.md`
- [x] Frontend component plan: `nodejs-frontend/plan_docs/chatroom_zero_downtime_recovery_plan.md`
- [x] Phase 03 Implementation: `plan_docs/03-implementation/chatroom_zero_downtime_recovery.md`
- [x] Phase 04 Analyze / QA: `plan_docs/04-analyze/chatroom_zero_downtime_recovery.md`
- [x] Phase 05 Expert Review: `plan_docs/05-expert-review/chatroom_zero_downtime_recovery.md` — Claude external expert 검증·보완(복제지연 reconnect 루프 차단) 후 APPROVED WITH CONDITIONS (2026-06-14)
- [x] Phase 06 Report: `plan_docs/06-report/chatroom_zero_downtime_recovery.md`
