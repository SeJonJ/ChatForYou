# WebRTC 녹화 플로우 구조 및 흐름 설명

## 📋 개요

ChatForYou v2 플랫폼의 WebRTC 녹화 시스템은 **Kurento Media Server**의 Composite 기능을 활용하여 여러 사용자의 화면을 하나로 병합(Mixing)하고, **MinIO**를 통해 클라우드 스토리지에 저장하는 구조를 가집니다. **이중 플로우 아키텍처**를 통해 실시간 화상채팅(P2P)과 녹화(Composite)를 동시에 지원합니다.

### 핵심 특징
- 🎥 **화상채팅 (P2P)**: 각 사용자는 개별 스트림을 통해 서로 소통 (지연 시간 최소화)
- 📹 **통합 녹화 (Composite)**: 최대 4명의 화면을 하나의 비디오 파일로 병합하여 저장
- ☁️ **클라우드 저장 (MinIO)**: 녹화 완료 후 비동기로 MinIO 서버에 업로드 및 URL 생성
- 🔄 **재시도 메커니즘**: 업로드 실패 시 자동 재시도 및 실패 복구 로직 내장
- 📡 **이벤트 기반 처리**: Kafka/Spring Event를 통한 비동기 상태 알림
- ⏱️ **자동 종료**: 설정된 시간 경과 시 녹화 자동 종료 기능 (TaskScheduler)
- 🛡️ **상태 동기화**: 녹화 중인 방 입장 시 신규 참가자에게 녹화 상태 알림 및 UI 제어

---

## 🏗️ 아키텍처 구조

### 1. 미디어 데이터 흐름
```mermaid
graph TD
    User[User Media Stream] -->|WebRTC| Kurento[Kurento WebRtcEndpoint]
    Kurento --> PassThrough[PassThrough (Tee)]
    
    PassThrough -->|Flow A: Chat| P2P[Other Users (P2P)]
    PassThrough -->|Flow B: Record| HubPort[HubPort]
    
    HubPort --> Composite[Composite Helper]
    Composite -->|Mixed Stream| Recorder[RecorderEndpoint]
    
    Recorder -->|Save .webm| LocalStorage[Local Disk]
    LocalStorage -->|Async Upload| MinIO[MinIO Storage]
```

### 2. 핵심 컴포넌트

#### A. 미디어 처리 (Kurento)
- **WebRtcEndpoint**: 사용자의 미디어 입출력 엔드포인트
- **PassThrough**: 미디어 스트림을 분기하여 P2P와 녹화용으로 나눔
- **Composite**: 다중 사용자 스트림을 하나의 화면으로 믹싱
- **HubPort**: Composite와 각 요소를 연결하는 포트
- **RecorderEndpoint**: 믹싱된 스트림을 파일로 저장

#### B. 서비스 레이어 (Spring Boot)
- **KurentoRoom**: 방 단위의 미디어 파이프라인 및 녹화 상태 관리 (Redis 연동)
- **RecordingService**: 녹화 시작/중지 요청 처리, 자동 종료 스케줄링 및 메타데이터 관리
- **RecordingUploadService**: 녹화 완료 후 비동기 업로드 및 재시도 로직 처리
- **RecordingFileService**: MinIO 연동 및 파일 처리
- **ChatRoomService**: 방 삭제 시 녹화 파일 연쇄 삭제 (`minioFileService` 호출)
- **RoomBatchJob**: 주기적으로 비활성 방을 정리하며 녹화 파일 삭제 트리거

---

## 🔄 녹화 프로세스 상세 흐름

### 1. 녹화 시작 (`startRoomRecording`)

**담당**: `RecordingHandler` -> `RecordingService` -> `KurentoRoom`

1.  **Composite 생성**: 해당 방의 `Composite`가 없으면 생성 (파이프라인 공유)
2.  **RecorderEndpoint 설정**: 
    - 로컬 저장 경로 설정 (`/recordings/{roomId}/{recordingId}/...`)
    - 미디어 프로파일 설정 (WEBM_VIDEO_ONLY 또는 WEBM)
3.  **연결 구성**: `Composite` -> `HubPort` -> `RecorderEndpoint`
4.  **참여자 연결**: 기존 방 참여자들의 스트림을 Composite에 연결
5.  **녹화 시작**: `roomRecorder.record()` 호출
6.  **자동 종료 예약**: `RecordingService`에서 `scheduleAutoStop`으로 타이머 가동
7.  **상태 동기화**: Redis에 `isRoomRecording = true`, `RecordingStatus = RECORDING` 업데이트

### 2. 사용자 미디어 연결

각 사용자가 입장하거나 미디어를 송출할 때:

1.  **PassThrough 분기**: 사용자의 `OutgoingMedia`를 `PassThrough`에 연결
2.  **녹화 경로 연결**: `PassThrough` -> `HubPort` -> `Composite`
3.  **P2P 경로 연결**: `PassThrough` -> `WebRtcEndpoint` (다른 사용자)

이 구조 덕분에 녹화 프로세스가 P2P 화상채팅 품질에 영향을 주지 않습니다.

### 3. 녹화 중지 및 업로드 (`stopRoomRecording`)

**담당**: `RecordingHandler` -> `RecordingService` -> `RecordingUploadService`

#### 3.1 녹화 중지
1.  **자동 종료 타이머 취소**: 예약된 `RecordingAutoStop` 작업 취소
2.  **RecorderEndpoint 정지**: `stopAndWait()` 호출로 파일 쓰기 완료 대기
3.  **참여자 연결 해제**: 모든 참여자의 스트림을 Composite에서 연결 해제
4.  **리소스 해제**: RecorderEndpoint 및 HubPort 메모리 해제
5.  **상태 업데이트**: Redis에 `RecordingStatus = UPLOADING` 설정

#### 3.2 비동기 업로드 프로세스 (`RecordingUploadService`)
녹화가 중지되면 `uploadRecordingAsync` 메서드가 호출됩니다.

1.  **비동기 Task 실행**: 별도 스레드 풀(`ThreadUtils.executeAsyncTask`)에서 실행
2.  **로컬 파일 검증**: 파일 존재 및 크기 확인
3.  **MinIO 업로드**: `recordingFileService.uploadRecording()` 호출
4.  **메타데이터 갱신**: 
    - MinIO 파일 URL 획득
    - `RecordingStatus = COMPLETED` 업데이트
5.  **이벤트 발행**: 
    - 성공 시: `RecordingUploadCompletedEvent` 발행 (-> 클라이언트에 알림)
    - 실패 시: `RecordingUploadFailedEvent` 발행

---

## 💾 데이터 관리 전략

### 1. 저장소 계층
| 계층 | 기술 | 용도 |
|------|------|------|
| **Local Cache** | Memory | Kurento 미디어 객체 (Composite, Recorder) 관리 |
| **Status Store** | Redis | 서버 간 녹화 상태, 방 정보 동기화 |
| **Object Storage** | MinIO | 영구적 녹화 파일 저장, URL 생성 |

### 2. 라이프사이클 관리
- **Kurento 객체**: 방이 활성화된 동안 메모리에 유지 (`KurentoRecorderMap`)
- **로컬 파일**: 녹화 중 임시 저장 -> 업로드 후 자동 삭제 (설정에 따름)
- **MinIO 객체 (영구 저장)**: 
    - **삭제 정책**: `RoomBatchJob` 실행 시 비활성 방이 삭제될 때, `ChatRoomService.delChatRoom()`을 통해 연쇄적으로 삭제됨.
    - 별도의 만료 스케줄러 없이 방의 수명주기를 따름.

---

## 📁 주요 코드 구조

```
springboot-backend/src/main/java/webChat/
├── model/room/KurentoRoom.java          # 녹화 시작/중지 및 미디어 파이프라인 제어
├── service/recording/
│   ├── RecordingService.java            # 통합 녹화 서비스 (시작/종료/자동종료)
│   ├── RecordingUploadService.java      # 비동기 업로드 및 재시도 로직
│   └── RecordingHandler.java            # WebSocket 요청 핸들러
├── service/file/impl/
│   ├── MinioFileService.java            # MinIO 연동 구현
│   └── RecordingFileService.java        # 녹화 파일 전용 서비스
├── service/chatroom/ChatRoomService.java # 방 삭제 시 녹화 파일 정리 로직 포함
└── batch/RoomBatchJob.java              # 주기적 방 정리 배치 (녹화 파일 자동 삭제 트리거)
```

---

## 🚀 향후 개선 목표 및 미구현 기능 (TODO)

### 1. 안정성 및 유지보수
- [ ] **에러 처리 표준화**
- [ ] **녹화 통계 및 모니터링**

### 2. 사용자 기능
- [ ] **녹화 일시정지/재개**: `RecorderEndpoint`의 pause 기능을 활용하거나 파일 분할/병합을 통한 일시정지 기능 구현 필요.
- [ ] **다중 화질 녹화**: 720p, 1080p 등 해상도 및 비트레이트 선택 옵션 추가 필요.
- [ ] **녹화 파일 썸네일**: FFmpeg 등을 활용하여 녹화 종료 후 썸네일 이미지를 자동 생성하고 저장하는 기능.
- [ ] **녹화 진행률 표시**: 클라이언트(Frontend)에서 녹화 경과 시간을 실시간으로 보여주는 UI/UX 개선.

### 3. 보안 및 권한
- [ ] **권한 관리 강화**: 현재 녹화 시작자만 종료할 수 있으나, 방장 권한(Creator)과 연동하여 권한 관리를 더 정교하게 다듬을 필요가 있음.