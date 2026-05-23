> 관련 문서: [README](./README.md) | [WEBRTC_ROOM_LIFECYCLE](./WEBRTC_ROOM_LIFECYCLE.md) | [ROOM_ROUTING_FLOW](./ROOM_ROUTING_FLOW.md)

Last verified against code: 2026-05-21

# WebRTC 녹화 플로우 구조 및 흐름 설명

ChatForYou v2의 서버 녹화는 Kurento Media Server의 `Composite`와 `RecorderEndpoint`를 사용한다. 실시간 화상채팅 경로와 녹화 경로는 같은 참가자 미디어를 사용하지만, 현재 구현에서는 분기용 별도 엔드포인트를 두지 않고 텍스트 오버레이 필터와 Composite 연결을 각각 구성한다.

---

## 1. 현재 미디어 데이터 흐름

```mermaid
graph TD
    User[User Media Stream] -->|WebRTC| Outgoing[outgoingMedia WebRtcEndpoint]

    Outgoing --> Overlay[textOverlayFilter]
    Overlay -->|P2P 수신| Incoming[incomingMedia WebRtcEndpoint]

    Overlay --> Scaler[compositeScaler]
    Scaler --> HubPort[compositeHubPort]
    HubPort --> Composite[Composite]
    Composite --> RecorderHub[Recorder HubPort]
    RecorderHub --> Recorder[RecorderEndpoint]

    Recorder -->|file:///recordings/{roomId}/{recordingId}/{fileName}| LocalKms[KMS local path]
    LocalKms -->|async upload| MinIO[MinIO object: {roomId}/{recordingId}/{fileName}]
```

### P2P 경로
- `KurentoUserSession.getEndpointForUser()`가 상대방 수신 endpoint를 만든다.
- 일반 수신 경로는 `sender.textOverlayFilter -> incomingMedia`이다.
- `textOverlayFilter` 연결 예외 시 `sender.outgoingMedia -> incomingMedia`로 폴백한다.
- 자기 자신에 대한 loopback은 별도 `incomingMedia`를 만들지 않고 `outgoingMedia`를 재사용한다.

### 녹화 경로
- `RecordingService.startRecording()`이 먼저 `room.initUserHubPort()`를 호출해 `KurentoCompositeMap`에 `Composite`를 준비한다.
- 기존 참가자는 `participant.connectToComposite(composite)`로 `textOverlayFilter -> compositeScaler -> compositeHubPort -> Composite`에 연결된다.
- `KurentoRoom.startRoomRecording()`은 `Composite`와 같은 pipeline에 `RecorderEndpoint`를 만들고, `HubPort -> RecorderEndpoint` 연결 후 `record()`를 호출한다.

---

## 2. 주요 컴포넌트

| 컴포넌트 | 역할 |
|---------|------|
| `KurentoHandler` | WebSocket 녹화 시작/중지 요청을 받고 사전 조건 및 권한을 검사 |
| `RecordingService` | 녹화 시작/중지 orchestration, 자동 종료 예약, 기존 참가자 Composite 연결 |
| `KurentoRoom` | `Composite`, `RecorderEndpoint`, Redis 동기화 대상 녹화 상태 제어 |
| `RecordingUploadService` | 녹화 중지 후 비동기 업로드, 재시도, 업로드 이벤트 발행 |
| `RecordingHandler` | `RecordingEvent`를 받아 `KurentoMessageSender`로 클라이언트 브로드캐스트 |
| `RecordingFileService` | 녹화 파일 MinIO 업로드 및 녹화 파일 prefix 삭제 |
| `ChatRoomService` | 방 삭제 또는 cleanup 시 MinIO/녹화 파일 디렉터리 삭제 호출 |

요청 처리 흐름은 두 갈래다.

- 녹화 시작/중지 요청: `KurentoHandler -> RecordingService -> KurentoRoom`
- 업로드/자동종료 알림: `ApplicationEventPublisher -> RecordingEvent -> RecordingHandler -> KurentoMessageSender`

---

## 3. 녹화 시작 흐름

### 3.1 `KurentoHandler.processRecordingStart()`

1. 참가자의 `KurentoRoom`을 조회한다.
2. `room.isHasRecordedOnce()`이면 `RECORDING_FILE_EXISTS = K004` 에러를 보낸다.
3. `room.isRecordingInProgress()`이면 `ALREADY_RECORDING = K001` 에러를 보낸다.
4. `recordingService.startRecording(room, user)`를 호출한다.
5. 요청자에게 `recordingStarted` 메시지를 전송한다.

### 3.2 `RecordingService.startRecording()`

1. `room.initUserHubPort()`로 `Composite`를 생성하거나 기존 값을 재사용한다.
2. `recordingId`, `fileName`, KMS 로컬 경로를 생성한다.
   - KMS 내부 파일 경로: `/recordings/{roomId}/{recordingId}/{fileName}`
   - `RecorderEndpoint` URI: `file:///recordings/{roomId}/{recordingId}/{fileName}`
3. `RecordingInfo`와 `RecordingFile`을 `RecordingStatus.RECORDING`으로 만든다.
4. 현재 방 참가자 목록을 순회하며 Composite에 연결한다.
5. `room.startRoomRecording(recordingId, mediaProfileSpecType, recordingInfo)`를 호출한다.
6. `room.setRecordingInfo(recordingInfo)`로 녹화 메타데이터를 반영한다.
7. `scheduleAutoStop()`으로 자동 종료 작업을 예약하고 `RecordingInfo.autoStopTask`에 저장한다.

### 3.3 `KurentoRoom.startRoomRecording()`

1. `hasRecordedOnce`가 이미 `true`이면 시작하지 않고 반환한다.
2. `KurentoCompositeMap.getComposite(roomId)`로 `Composite`를 조회한다.
3. 같은 pipeline에 `RecorderEndpoint`를 생성한다.
4. `HubPort`를 만들고 `HubPort -> RecorderEndpoint`로 연결한다.
5. `roomRecorder.record()`를 호출한다.
6. `KurentoRecorderMap`에 `RecorderEndpoint`와 recorder용 `HubPort`를 저장한다.
7. `hasRecordedOnce = true`, `isRecordingInProgress = true`로 상태를 변경한다.

---

## 4. 녹화 중 참가자 연결

녹화 중 신규 참가자가 입장하면 `KurentoRoomManager.connectParticipantToCompositeIfNeeded()` 경로에서 `participant.connectToComposite(composite)`가 호출된다. 이 경로는 녹화가 이미 진행 중이고 `Composite`가 존재할 때만 동작한다.

참가자별 Composite 연결은 `KurentoUserSession` 내부의 `compositeScaler`와 `compositeHubPort`로 관리된다. 참가자가 퇴장하거나 녹화가 중지되면 `disconnectFromComposite()`로 해당 연결을 해제한다.

---

## 5. 녹화 중지 및 업로드 흐름

### 5.1 `KurentoHandler.processRecordingStop()`

1. 방이 녹화 중인지 확인한다. 녹화 중이 아니면 `NOT_RECORDING = K002` 에러를 보낸다.
2. 녹화 정보가 없으면 `RECORDING_ENDPOINT_NOT_FOUND = K003` 에러를 보낸다.
3. 요청자 userId가 `RecordingInfo.recordingUserId`와 다르면 `ACCESS_DENIED = A001` 에러를 보낸다.
4. `recordingService.stopRecording(room, user)`를 호출한다.
5. 요청자에게 `recordingStopped` 메시지를 전송한다.

### 5.2 `RecordingService.stopRecording()`

1. `RecordingInfo.autoStopTask`가 있으면 취소한다.
2. `room.stopRoomRecording(recordingId)`로 `RecorderEndpoint.stopAndWait()`를 수행한다.
3. 모든 현재 참가자를 순회하며 Composite 연결을 해제한다.
4. `recordingUploadService.uploadRecordingAsync(room, recordingInfo)`로 업로드를 비동기 시작한다.

### 5.3 `RecordingUploadService.uploadRecordingAsync()`

1. `RecordingStatus.UPLOADING`으로 변경하고 방 정보를 업데이트한다.
2. `RecordingFile.fileFullPath`에서 `file://`를 제거해 로컬 파일을 확인한다.
3. 로컬 파일이 없으면 `false`를 반환한다.
4. `RecordingFileService.uploadRecording()`으로 MinIO에 업로드한다.
5. MinIO object path를 `RecordingFile.minioFilePath`에 저장한다.
   - MinIO 경로: `{roomId}/{recordingId}/{fileName}`
6. 성공 시 `RecordingStatus.COMPLETED`로 업데이트하고 `RecordingUploadCompletedEvent`를 발행한다.
7. 최종 예외 경로에서는 `RecordingStatus.FAILED`로 업데이트하고 `RecordingUploadFailedEvent`를 발행한다.

업로드 완료 이벤트는 `RecordingHandler.notifyRecordingUploadCompleted()`를 통해 `uploadCompleted` 메시지로 방에 브로드캐스트된다. 자동 종료 성공 이벤트는 `recordingAutoStopped` 메시지로 브로드캐스트된다.

---

## 6. 저장 및 삭제 정책

| 계층 | 현재 구현 |
|------|-----------|
| KMS 로컬 파일 | `file:///recordings/{roomId}/{recordingId}/{fileName}`에 기록 |
| MinIO object | `{roomId}/{recordingId}/{fileName}` 경로로 업로드 |
| Redis 상태 | `KurentoRoom.recordingInfo`, `isRecordingInProgress`, `hasRecordedOnce` |
| 삭제 | `ChatRoomService.deleteRoom()`에서 `minioFileService.deleteFileDir(roomId)`와 `recordingFileService.deleteFileDir(roomId)` 호출 |

`hasRecordedOnce`는 한 번 `true`가 되면 같은 방에서 재녹화를 막는 파일 잠금 역할을 한다.

---

## 7. 주요 코드 위치

```text
springboot-backend/src/main/java/webChat/
├── service/kurento/KurentoHandler.java
├── service/kurento/KurentoRoomManager.java
├── service/kurento/KurentoUserSession.java
├── model/room/KurentoRoom.java
├── service/recording/RecordingService.java
├── service/recording/RecordingUploadService.java
├── service/recording/RecordingHandler.java
├── service/file/impl/RecordingFileService.java
└── service/chatroom/ChatRoomService.java
```
