> 관련 문서: [README](./README.md) | [WEBRTC_ROOM_LIFECYCLE](./WEBRTC_ROOM_LIFECYCLE.md) | [ROOM_ROUTING_FLOW](./ROOM_ROUTING_FLOW.md)

Last verified against code: 2026-06-17

# Zero-Downtime Deploy Flow — 무중단 배포 복구 명세

Kubernetes Rolling Update 중 RTC 방이 삭제되지 않고, 참가자가 수동 조작 없이 같은 방에 자동 재입장하는 흐름이다. preStop → readiness drain → 클라이언트 재연결 → HTTP Recovery → WebSocket 재입장 순서를 코드 기준으로 추적한다.

---

## 한눈에 보기

### K8s 배포 전략 — RollingUpdate

백엔드는 `RollingUpdate (maxUnavailable: 1, maxSurge: 1)` 전략으로 배포된다.
replicas=3 기준으로 **Pod 하나씩 순차 교체**하며, 새 Pod의 readinessProbe가 200을 반환한 뒤에만 기존 Pod에 SIGTERM을 보낸다.

```
[old-1] [old-2] [old-3]
    → new-1 ready 확인 후 → old-1 SIGTERM (preStop 실행)
    → new-2 ready 확인 후 → old-2 SIGTERM
    → new-3 ready 확인 후 → old-3 SIGTERM
```

배포 1회당 SIGTERM(preStop → recovery 처리)이 **3번** 반복된다.

---

### 사용자(프론트) 입장에서 일어나는 일

사용자는 아무것도 하지 않아도 3분 안에 자동 복구된다.

```
1. WebSocket 끊김
   → 오버레이 표시: "서버 패치가 진행 중입니다. (남은 시간 2:59)"

2. 브라우저가 자동 재연결 반복
   → 새 Pod 부팅 완료 전까지 실패, 이후 성공

3. 새 Pod에서 "복구 절차 필요" 응답 (REDIRECT_RECOVER)
   → 방은 삭제되지 않음. 단지 주인(instanceId)만 없는 상태

4. 브라우저가 복구 API 호출 → 방 주인을 새 Pod로 변경 + 새 쿠키 수신
   → Set-Cookie: chatforyou-server=새Pod값  (이 쿠키가 없으면 nginx가 엉뚱한 Pod로 라우팅)

5. 새 쿠키로 WebSocket 재연결 → JOIN_ROOM → 영상 복원
   → 오버레이 사라짐, 통화 재개
```

> **핵심**: `chatforyou-server` 쿠키는 HttpOnly라 JS에서 직접 수정 불가. 반드시 4번의 HTTP 응답 `Set-Cookie`로만 갱신된다. 이 단계를 건너뛰면 nginx가 죽은 Pod를 계속 가리키게 된다.

---

### 백엔드 입장에서 일어나는 일

**[죽는 Pod — old]**

```
SIGTERM 수신
  → preStop: POST /internal/pre-shutdown (loopback 전용, 외부 차단)
      → markOwnedRoomsRecoverable()
          → 내 소유 방에 room:recovery:{roomId} 플래그 저장 (TTL)
          → 방 데이터(roomId:{roomId})는 그대로 유지 — 삭제하지 않음
      → beginShutdown() → readiness() 503 반환 시작
  → nginx가 이 Pod를 트래픽 대상에서 제외
  → terminationGracePeriodSeconds=60 동안 기존 연결 drain
  → ShutdownConfig.cleanup(): 내 소유 방만 CREATED로 리셋, Kurento 해제
      → room:recovery 키는 건드리지 않음 (새 Pod가 읽어야 하므로)
```

> Kurento in-memory 상태(MediaPipeline, WebRtcEndpoint)는 이 시점에 소멸한다. Redis에 저장할 수 없고, 참가자 재입장 시 새로 생성된다.

**[새로 뜨는 Pod — new]**

```
부팅
  → RoutingBootstrapCoordinator: Kafka로 nginx cookie 수집
  → CookieCheckEvent.isCookieCollected() = true
  → readiness() 200 반환 → nginx 트래픽 수신 시작

joinRoom() 호출 수신 (재연결 브라우저로부터)
  → instance:heartbeat:{oldId} 만료 확인 → owner unhealthy
  → room:recovery:{roomId} 존재 + TTL 유효
  → master Redis 1회 재확인 (slave 복제지연 오판 방지)
  → REDIRECT_RECOVER 반환 (방 삭제 없음)

recoverRoom() 호출 수신
  → 인증 + 비밀방 토큰 검증
  → room:claim-lock:{roomId} SET NX PX 30000 (동시 경합 방지)
  → master Redis에서 room + routing 재확인
  → room.instanceId = 현재 Pod로 갱신
  → room:mapping:{roomId} 갱신
  → Set-Cookie: chatforyou-server / room-redirect-count=0 / room-id
  → claim-lock Lua compare-and-delete 해제

JOIN_ROOM 수신 (새 쿠키로 재입장)
  → 신규 Kurento MediaPipeline 생성
  → 참가자 등록 → existingParticipants 전송
  → SDP/ICE → 영상 복원
```

---

## 1. 구성 요소

| 컴포넌트 | 역할 |
|---------|------|
| `InternalLifecycleController` | `preStop` hook이 호출하는 `/internal/pre-shutdown` loopback 엔드포인트 |
| `ChatRoomRecoveryService` | 복구 후보 판정, recovery metadata 저장, Redis claim lock 획득/해제 |
| `ChatRoomController` | `joinRoom()` owner-unhealthy 분기 + `recoverRoom()` HTTP Recovery 엔드포인트 |
| `RoutingService` | recovery cookie(`chatforyou-server`, `room-redirect-count`, `room-id`) 발급 |
| `ShutdownConfig` | Pod 종료 시 owner-scope cleanup (recovery metadata 보존) |
| `HealthController` | `readiness()` — 종료 중 503 반환으로 nginx 트래픽 차단 |
| `kurento-service.js` | `REDIRECT_RECOVER` 수신 시 `recoverRoomAndReconnect()` 호출 + 카운트다운 오버레이 |

---

## 2. Redis 키 구조

| Key 패턴 | 값 타입 | 역할 |
|---------|---------|------|
| `room:recovery:{roomId}` | `RoomRecoveryMetadata` | 배포 종료 전 owner 인스턴스가 작성하는 복구 후보 마커 (TTL: `recovery.room.ttl-seconds`) |
| `room:claim-lock:{roomId}` | instanceId | 동시 복구 경합 방지 claim lock (TTL: 30 s, Lua compare-and-delete) |
| `room:mapping:{roomId}` | `RoomRoutingInfo` | 방별 owner instanceId와 nginx cookie (recovery 후 갱신) |
| `instance:heartbeat:{instanceId}` | timestamp | 인스턴스 생존 신호 — unhealthy 판정의 근거 |

---

## 3. Recovery Cookie

| Cookie | HttpOnly | 역할 | recovery 시 동작 |
|--------|----------|------|-----------------|
| `chatforyou-server` | ✅ | nginx sticky 라우팅 | 신규 Pod의 cookie 값으로 갱신 |
| `room-redirect-count` | ✅ | redirect loop 방지 | `0` 으로 리셋 |
| `room-id` | ❌ | 방 ID 힌트 | 현재 roomId 유지 |

JS에서 `chatforyou-server`와 `room-redirect-count`를 직접 조작할 수 없다. 반드시 HTTP 응답의 `Set-Cookie`로만 갱신된다.

---

## 4. 전체 흐름 개요

```
K8s Rolling Update
  └─ SIGTERM → preStop curl /internal/pre-shutdown
        ├─ markOwnedRoomsRecoverable() → room:recovery:{roomId} 저장
        └─ beginShutdown() → readiness 503 → nginx 트래픽 차단

WebSocket close (1006)
  └─ afterConnectionClosed() → leaveRoom()
        └─ scheduleReconnect() → deploy overlay 카운트다운(3분)

doReconnect() → register() → joinRoom()
  ├─ [owner unhealthy + recovery 메타 있음] → REDIRECT_RECOVER
  └─ [복구 불가] → delChatRoom() → REDIRECT_DASHBOARD (기존 fallback 유지)

REDIRECT_RECOVER 수신
  └─ POST /chat/room/{roomId}/recover
        ├─ 인증 + 비밀방 검증
        ├─ claim-lock 획득 (SET NX PX 30000)
        ├─ master Redis에서 방/routing 재확인
        ├─ room owner + room:mapping 갱신
        └─ Set-Cookie: chatforyou-server / room-redirect-count=0 / room-id

새 cookie로 connectWebSocket() → register() → JOIN_ROOM
  └─ 신규 Kurento pipeline 생성 → existingParticipants → SDP → ICE → 영상 복원
```

---

## 5. 단계별 상세

### 5.1 Pre-Shutdown (SIGTERM)

처리 위치: `InternalLifecycleController.preShutdown()`, `ChatRoomRecoveryService.markOwnedRoomsRecoverable()`

K8s가 Pod에 SIGTERM을 보내기 직전 `preStop` hook이 실행된다.

```yaml
lifecycle:
  preStop:
    exec:
      command: ["sh", "-c", "curl -s -X POST http://localhost:8443/chatforyou/api/internal/pre-shutdown"]
```

1. `validateLoopbackRequest()` — loopback(`127.0.0.1` / `::1`) 이외 요청은 `ACCESS_DENIED`.
2. `instanceProvider.beginShutdown()` — 인스턴스를 shutdown 상태로 전환. 이후 `readiness()` 가 503 반환.
3. `markOwnedRoomsRecoverable()` — Redis scan으로 현재 인스턴스 소유 방을 조회해 `room:recovery:{roomId}` 작성.
   - 쓰기 실패 시 cleanup 중단 (recovery metadata 없이 방을 초기화하지 않음).
4. `terminationGracePeriodSeconds=60` 동안 기존 연결 drain.

### 5.2 WebSocket 종료 정리

처리 위치: `KurentoHandler.afterConnectionClosed()`

Pod 종료로 WebSocket이 끊기면 `afterConnectionClosed()` → `leaveRoom()` 경로로 in-memory 상태가 정리된다 (`WEBRTC_ROOM_LIFECYCLE.md §5.8` 참조).

프론트엔드 동작:
- `ws.onclose` (비정상 종료) → `scheduleReconnect()` 진입
- `setReconnectNoticeMessage(DEPLOY_RECONNECT_NOTICE)` → 3분 카운트다운 오버레이 표시
- 지수 backoff + jitter 재연결 루프 (최대 8회)

### 5.3 joinRoom() owner-unhealthy 분기

처리 위치: `ChatRoomController.joinRoom()`, `ChatRoomRecoveryService.decide()`

재연결 후 `register()` → `joinRoom()` 순서로 요청이 도달한다.

| 조건 | 기존 동작 | 변경 후 동작 |
|------|----------|-------------|
| owner unhealthy + recovery 메타 있음 + TTL 유효 | `delChatRoom()` → `REDIRECT_DASHBOARD` | `REDIRECT_RECOVER` 반환 |
| owner unhealthy + recovery 메타 없음 / 만료 | `delChatRoom()` → `REDIRECT_DASHBOARD` | 동일 (fallback 유지) |
| owner healthy + 현재 instance와 다름 | `REDIRECT_ROOM` (routing cookie 재설정) | 동일 |
| owner == 현재 instance | `JOIN_ROOM` 진행 | 동일 |

slave 복제지연 방지: recovery 결정 후 master Redis에서 room owner를 1회 재확인한다. master 확인 결과 이미 healthy이면 `REDIRECT_RECOVER` 없이 `JOIN_ROOM` 진행.

### 5.4 HTTP Recovery

처리 위치: `ChatRoomController.recoverRoom()`, `ChatRoomRecoveryService.recover()`

```http
POST /chatforyou/api/chat/room/{roomId}/recover
Authorization: Bearer <firebase-token>
X-Room-Token: <secret-room-token, 비밀방만>
```

1. Firebase token 검증 + Redis 로그인 정보 확인.
2. 비밀방이면 `X-Room-Token` 검증.
3. `room:recovery:{roomId}` 조회 — 없거나 만료 → `REDIRECT_DASHBOARD`.
4. `tryAcquireRoomClaimLock(roomId, instanceId, 30000)` — SET NX PX:
   - 성공: 복구 진행.
   - 실패: `CLAIM_IN_PROGRESS` — 프론트는 최대 3회 bounded retry 후 generic 재연결 경로로 복귀.
5. master Redis에서 방 + routing 데이터 재확인.
6. room owner + `room:mapping:{roomId}` 를 현재 instance로 갱신.
7. `RoutingService.setRecoveryRoutingInfo()` → HTTP 응답에 Set-Cookie 발급.
8. `releaseRoomClaimLock()` — Lua compare-and-delete (타 instance lock은 건드리지 않음).

### 5.5 신규 WebSocket 입장

새 cookie가 브라우저에 심어진 후 `connectWebSocket()` → `register()` → `JOIN_ROOM`.

`KurentoHandler.joinRoom()`:
1. 방이 `CREATED` 상태이면 신규 `MediaPipeline` 생성 (`ACTIVE`로 전환).
2. 참가자를 `roomParticipants`와 `usersBySessionId`에 등록.
3. 기존 참가자에게 `newParticipantArrived` 알림.
4. 재입장 참가자에게 `existingParticipants` 전송.
5. `RECEIVE_VIDEO_FROM` → SDP offer/answer → ICE candidate → 영상 복원.

### 5.6 ShutdownConfig Cleanup

처리 위치: `ShutdownConfig.cleanup()`

`terminationGracePeriodSeconds` 만료 직전 Spring context close hook에서 실행된다.

- owner-scope guard: `room.instanceId != currentInstanceId`이면 skip.
- 현재 인스턴스 소유 방: `userCount=0`, `RoomState.CREATED` 리셋. Kurento room 삭제.
- `room:recovery:{roomId}` 는 삭제하지 않는다 (recovery 윈도우 유지).
- Kurento client + instance provider 종료.

---

## 6. 경합 및 예외 처리

| 케이스 | 처리 |
|--------|------|
| 두 클라이언트 동시 recover | claim lock: 먼저 획득한 쪽만 진행. 나머지는 `CLAIM_IN_PROGRESS` bounded retry |
| recovery metadata 만료 | `decide()` → NOT_RECOVERABLE → `delChatRoom()` + `REDIRECT_DASHBOARD` |
| master Redis에서 방 없음 | recover 중단 → `REDIRECT_DASHBOARD` |
| Pod crash (preStop 미실행) | recovery metadata 없음 → 기존 fallback. Phase 2 bootstrap 보완에서 추가 처리 예정 |
| claim lock 획득 중 exception | `finally` 블록에서 lock release 보장 |
| slave 복제지연 false positive | master 재확인으로 healthy owner를 `REDIRECT_RECOVER`로 오판하지 않음 |

---

## 7. 주요 코드 위치

```text
springboot-backend/src/main/java/webChat/
├── controller/ChatRoomController.java          — joinRoom(), recoverRoom()
├── controller/InternalLifecycleController.java — preShutdown()
├── controller/HealthController.java            — readiness()
├── service/chatroom/recovery/
│   ├── ChatRoomRecoveryService.java
│   └── impl/ChatRoomRecoveryServiceImpl.java   — decide(), recover(), markOwnedRoomsRecoverable()
├── service/redis/impl/RedisServiceImpl.java    — tryAcquireRoomClaimLock(), releaseRoomClaimLock(),
│                                                  saveRoomRecoveryMetadata(), getRoomRecoveryMetadata()
├── service/routing/impl/RoutingServiceImpl.java— setRecoveryRoutingInfo()
├── config/ShutdownConfig.java                 — cleanup()
└── model/room/recovery/
    ├── RoomRecoveryMetadata.java
    ├── RecoveryDecision.java
    ├── RecoveryStatus.java
    └── RecoveryResult.java

nodejs-frontend/static/js/rtc/kurento-service.js
    — recoverRoomAndReconnect(), scheduleReconnect(), startReconnectCountdown()
```
