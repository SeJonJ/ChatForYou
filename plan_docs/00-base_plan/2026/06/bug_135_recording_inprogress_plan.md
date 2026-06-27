# [Base Plan] Bug #135 — 배포/종료 중 녹화 중단 시 in-progress 상태 정리

## 0. Prior Knowledge (Vault Scan)

| Type | Note | Key Takeaway |
|------|------|--------------|
| SPEC | SPEC - ChatForYou 채팅방 무중단 배포 복구 설계 | Phase 3(녹화/운영 안정화)에서 `recording in-progress false 처리`를 **GitHub #135로 명시 이월**. §4.5는 claim lock 획득 후 "recording in-progress 최소 정리"를 복구 책임으로 규정. |
| SPEC | SPEC - ChatForYou 채팅방 무중단 배포 복구 설계 | `partial recording file 정책`은 **별도 #136**으로 분리됨 → 본 사이클 범위 밖. |
| TECH | TECH - ChatForYou WebRTC 백엔드 흐름 | Kurento room/session lifecycle은 backend in-memory state와 Redis room state가 함께 움직인다. 녹화 RecorderEndpoint는 in-memory(KurentoRecorderMap)에만 존재. |
| TECH | TECH - ChatForYou WebRTC 녹화 플로우 | 방 전체 녹화는 Composite→RecorderEndpoint로 단일 파일 기록, 파일은 KMS 컨테이너 `/recordings/` 마운트에 생성되고 stop 시점에 MinIO 업로드. |

> 이 버그는 무중단 배포 복구 사이클(`chatroom_zero_downtime_recovery`, Phase 00~06 완료)에서 **의도적으로 이월된 잔여 항목**이다. 신규 기능이 아니라 기존 복구 설계의 누락 보완이다.

## 1. Summary (Goal & Scope)

무중단 배포(Rolling Update) 또는 Pod 종료 시 진행 중이던 방 녹화가 중단되면, Redis에 저장된 방의 녹화 상태 플래그(`isRecordingInProgress`, 부수적으로 `hasRecordedOnce`/`recordingInfo`)가 stale하게 `true`로 남는다. 복구 후 같은 방에 재입장하면 서버가 "녹화 중" 시그널(`recordingInProgress`)을 보내 UI가 녹화 중으로 오인하고, 새 녹화 시작이 영구 차단된다.

목표:

- 종료(preStop) / 방 복구 시 중단된 녹화의 `isRecordingInProgress` 플래그를 명시적으로 `false`로 정리한다.
- 복구 후 새 녹화가 정상적으로 시작될 수 있도록 녹화 상태를 정합한 stopped 상태로 되돌린다.
- ungraceful crash(preStop 미실행) 케이스도 복구 claim 시점에서 함께 정리한다.

완료 조건(버그 이슈 기준):

- 배포 중 녹화 진행 방을 종료/복구한 뒤 stale `recordingInProgress` 시그널이 더 이상 전송되지 않는다.
- 복구 후 새 녹화가 정상 시작된다.

명시적 제외 범위:

- **partial recording file(중단된 부분 녹화 파일)의 저장/업로드/삭제 정책 = GitHub #136** (별도 이슈). 본 작업은 KMS 컨테이너와 함께 사라진 부분 파일을 추적/복구하지 않는다.
- 녹화 미디어 자체의 무중단 연속성(배포를 가로질러 녹화 이어가기).

## 2. Impact Analysis (Critical)

- [Backend]: 영향 범위가 전적으로 백엔드에 한정된다.
  - `webChat/model/room/KurentoRoom.java` — 녹화 상태 필드(`isRecordingInProgress`, `hasRecordedOnce`, `recordingInfo`)와 reset 책임.
  - `webChat/config/ShutdownConfig.java` — `cleanup()`이 owner 방을 `updateChatRoom`으로 Redis에 다시 쓰기 전 녹화 상태를 정리해야 한다(graceful preStop/ContextClosed/shutdown hook 경로).
  - `webChat/service/chatroom/recovery/impl/ChatRoomRecoveryServiceImpl.java` — `recoverRoom()` claim 성공 경로에서 새 owner가 녹화 자원(RecorderEndpoint)을 갖지 않으므로 stale 녹화 상태를 정리해야 한다(ungraceful crash 포함, SPEC §4.5 "recording in-progress 최소 정리").
  - 참조(읽기): `KurentoHandler.joinRoom()` line 353 — `isRecordingInProgress()` true면 신규 참가자에게 `recordingInProgress` 시그널 전송. `processRecordingStart()` line 158/167 — `hasRecordedOnce`/`isRecordingInProgress`가 새 녹화를 차단.
- [Frontend]: **변경 없음.** `nodejs-frontend/static/js/rtc/recording.js`의 `recordingInProgress()`는 서버 시그널을 그대로 반영할 뿐이다. 백엔드가 stale 시그널을 보내지 않으면 UI 오인도 사라진다. (프론트는 신뢰 가능한 SoT가 서버임)
- [Desktop]: 영향 없음. 프론트 변경이 없으므로 Electron sync 불필요.
- [Infra/Deploy]: preStop(`/pre-shutdown`) + `terminationGracePeriodSeconds` 배선은 이미 live. 본 작업은 기존 종료/복구 경로 내부 로직만 보완하며 manifest 변경 없음.

## 3. Technology & Risks

Risk Level: **L3**

Reason:

- 변경 지점이 **room lifecycle(종료/복구)** 과 **Kurento 녹화 상태**, 그리고 신규 참가자에게 가는 **`recordingInProgress` WS 시그널**의 정확성에 직접 관여한다.
- `risk-classification.md`: "room lifecycle", "Modify room termination logic" = L3. `AGENT_GUIDE.md` §4.2: room lifecycle 변경은 L3 → 구현 전 `docs/agent/webrtc-review-protocol.md` 2라운드 리뷰 필수.
- Compound rule applied: yes (room lifecycle + Kurento recording state) → 최고 레벨 L3.
- 독립 PDCA: 기존 `chatroom_zero_downtime_recovery` 사이클을 재사용하지 않고 본 신규 `bug_135` 사이클로 진행한다(인접하다는 이유로 재사용 금지).

Current-code findings (근거 파일:라인):

- `KurentoRoom.java:73` — `isRecordingInProgress`는 `@JsonIgnore`가 아니어서 Redis(`updateChatRoom`)에 영속된다. `hasRecordedOnce`(72), `recordingInfo`(77)도 영속.
- `ShutdownConfig.java:103-108` — owner 방을 `setUserCount(0)`, `setRoomState(CREATED)` 후 `updateChatRoom()`으로 Redis에 다시 쓴다. 이때 **`isRecordingInProgress=true`가 그대로 영속**된다. 직후 `deleteKurentoRoom()`은 참가자 close와 pipeline release만 하고 `KurentoRoom.close()`(녹화 중지+플래그 false)를 호출하지 않으며, 호출하더라도 in-memory 객체만 바뀌고 Redis에 재반영되지 않는다.
- `KurentoRoom.close():127-136` / `@PreDestroy shutdown():122` — 녹화 중이면 `stopRoomRecording`을 호출하지만 이는 in-memory 변경일 뿐 Redis 영속 경로가 없다. 또한 `deleteKurentoRoom`이 이 close를 호출하지 않는다.
- `ChatRoomRecoveryServiceImpl.recoverRoom():142-156` — claim 성공 시 `masterRoom`의 owner/routing/metadata만 갱신한다. 녹화 상태는 손대지 않아 stale `isRecordingInProgress=true`가 새 owner로 그대로 이전된다. 새 owner에는 RecorderEndpoint(`KurentoRecorderMap`)가 없다.
- `KurentoHandler.joinRoom():353` — 복구된 방 재입장 시 `isRecordingInProgress()` true → `recordingInProgress` 시그널 전송 → 프론트가 녹화 UI 비활성 + "녹화 중" 토스트.
- `KurentoHandler.processRecordingStart():158,167` — `hasRecordedOnce` true → `RECORDING_FILE_EXISTS`, `isRecordingInProgress` true → `ALREADY_RECORDING`. 즉 **플래그만 false로 해도 `hasRecordedOnce`가 남으면 새 녹화는 여전히 차단**된다.

Primary risks:

- P0: `isRecordingInProgress`만 정리하고 `hasRecordedOnce`/`recordingInfo`를 남기면 "복구 후 새 녹화 정상 시작" 완료 조건이 충족되지 않는다(`RECORDING_FILE_EXISTS`로 영구 차단). → 중단된 녹화는 isRecordingInProgress=false + hasRecordedOnce=false + recordingInfo=null의 정합 stopped 상태로 일괄 reset 필요. (단, partial file 보존이 요구되면 #136과 충돌 — 아래 결정 필요 항목)
- P1: graceful 경로만 고치고 recovery claim 경로를 빼면 ungraceful crash(preStop 미실행) 시 stale 상태가 남는다. → 두 경로 모두 정리하거나, **복구 claim 시점(모든 경로의 수렴점)을 1차 정리 지점**으로 삼는 것이 견고하다.
- P1: autoStopTask(`recordingInfo.getAutoStopTask()`)는 transient(`@JsonIgnore`)라 Redis 복구 객체에는 없다 — 정리 시 NPE 주의. 죽은 Pod의 ScheduledFuture는 Pod와 함께 사라지므로 새 owner에서 cancel 대상 아님.

## 4. Design Direction (유저 승인 완료 — 2026-06-26 확정)

**핵심 수정 방향(확정):**

1. `KurentoRoom`에 중단된 녹화를 정합 stopped 상태로 되돌리는 reset 메서드를 둔다 — 녹화 상태 3필드(`isRecordingInProgress=false`, `hasRecordedOnce=false`, `recordingInfo=null`) 일괄 초기화. in-memory 객체 한정, Kurento 자원은 해당 Pod에서 이미 소멸.
2. **복구 claim 성공 경로(`recoverRoom`)에서** 새 owner가 RecorderEndpoint를 갖지 않는 stale 녹화 방을 정리한다 — graceful/ungraceful 모두 수렴하는 1차 지점.
3. **종료 cleanup(`ShutdownConfig.cleanup`)에서** owner 방을 Redis에 다시 쓰기 전 녹화 상태를 정리한다 — 영속 상태를 즉시 정합화하는 belt-and-suspenders.

**확정 결정 (A)(B)(C)(D):**

- (A) `hasRecordedOnce`/`recordingInfo` 함께 reset → **Yes.** 3필드 일괄 reset. 근거 — 완료 조건이 "복구 후 새 녹화 정상 시작"이고, 중단 파일은 죽은 Pod의 KMS 컨테이너와 함께 사라져 재사용 불가. 남기면 `RECORDING_FILE_EXISTS`로 영구 차단.
- (B) 정리 지점 → **이중(claim + shutdown).** ungraceful 대비.
- (C) partial file 처리 → **#135는 별도 Redis 키 `room:recording:partial:{roomId}`에 삭제 마커만 심는다. 실제 삭제/업로드는 #136.** recordingInfo=null reset과 충돌하지 않도록 마커는 recordingInfo가 아닌 독립 Redis 키에 저장. reset 전에 마커를 먼저 기록한다(recordingInfo의 파일 경로/식별자를 마커에 보존).
- (D) UI 메시지 → **재입장 시점 per-user 시그널** 방식. 브로드캐스트(`broadcastToRoom`)는 실행 pod의 in-memory WS 세션으로만 전송되어, 죽는 pod(cleanup)·재입장 전 새 pod(claim) 양쪽 모두 영향 참가자에게 도달하지 못한다(KurentoMessageSender.java:142). 따라서 참가자가 복구된 방에 재입장(`KurentoHandler.joinRoom`)할 때 partial 마커가 있으면 그 사용자에게 `sendToUser`로 "서버와의 연결 종료로 녹화가 중지됩니다. 서버 재연결 후 녹화를 재시작해주세요." 전송. 위치는 현재 `recordingInProgress`를 보내던 joinRoom:353 자리 — 검증된 경로.

**영향 범위 변경**: (D)로 인해 원 plan의 "Frontend 변경 없음"이 **BE+FE**로 확장된다. BE는 새 WS 메시지 타입 + joinRoom per-user 전송 추가, FE는 wsMessageHandlers 신규 핸들러 + 메시지 표시 추가.

## 5. File Ownership (2026-06-26 확정)

| 파일 | 담당 |
|------|------|
| `webChat/model/room/KurentoRoom.java` (3필드 reset 메서드) | 백엔드 전문가 |
| `webChat/config/ShutdownConfig.java` (cleanup 정리 경로) | 백엔드 전문가 |
| `webChat/service/chatroom/recovery/impl/ChatRoomRecoveryServiceImpl.java` (recoverRoom claim 정리 + partial 마커 기록) | 백엔드 전문가 |
| `webChat/service/kurento/KurentoHandler.java` (joinRoom 재입장 per-user 시그널) | 백엔드 전문가 |
| `webChat/service/kurento/KurentoMessageBuilder.java` + `KurentoMessageType.java` (신규 메시지 타입) | 백엔드 전문가 |
| partial 마커 Redis read/write (`RedisService` 등 — `room:recording:partial:{roomId}`) | 백엔드 전문가 |
| `nodejs-frontend/static/js/rtc/kurento-service.js` (wsMessageHandlers 신규 핸들러) | 프론트 전문가 |
| `nodejs-frontend/static/js/rtc/recording.js` (메시지 표시 + UI 상태) | 프론트 전문가 |
| `springboot-backend/src/test/**` (reset / claim 정리 / cleanup / joinRoom 시그널 테스트) | QA 전문가 |

## 6. Workflow Gate (L3)

- [x] Pre-Implementation Compliance Gate 선언 (Risk L3 / compound yes / phase 00-06 / 본 plan 경로 / WebRTC review status / BE+FE impact)
- [x] Phase 01 요구사항 + 녹화 상태 reset 계약 작성
- [x] Phase 02 design + `docs/agent/webrtc-review-protocol.md` 2라운드 리뷰(Round1 흐름 정확성 / Round2 실패·lifecycle) — APPROVED
- [x] P0 해소 (Round1 F1 게이트 대칭) / P1(F2 메시지 ID) fix
- [x] Phase 03 구현 (BE+FE) — 257 tests 0 failures, 컨벤션 통과
- [x] Phase 04 gap 분석 (Match 11/12)
- [x] Phase 05 external + cross-model review → APPROVED
- [x] Phase 06 report
- [x] vault knowledge capture (BUG 노트 — stale recording flag on deploy recovery) 완료
