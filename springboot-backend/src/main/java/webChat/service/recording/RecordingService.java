package webChat.service.recording;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.Strings;
import org.kurento.client.Composite;
import org.kurento.client.MediaProfileSpecType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import webChat.model.event.RecordingAutoStopEvent;
import webChat.model.event.RecordingAutoStopFailedEvent;
import webChat.model.record.RecordingFile;
import webChat.model.record.RecordingInfo;
import webChat.model.record.RecordingStatus;
import webChat.model.room.KurentoRoom;
import webChat.repository.KurentoCompositeMap;
import webChat.service.chatroom.participant.KurentoParticipantService;
import webChat.service.kurento.KurentoMessageBuilder;
import webChat.service.kurento.KurentoMessageSender;
import webChat.service.kurento.KurentoUserSession;

import java.io.IOException;
import java.util.Collection;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 추후 다른 스토리지로 확장 시 interface 로 공통화 필요
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecordingService {
    private final KurentoParticipantService kurentoParticipantService;
    private final RecordingUploadService recordingUploadService;
    @Qualifier("recording-autostop-executor")
    private final TaskScheduler autoStopExecutor;
    private final ApplicationEventPublisher eventPublisher;
    private final KurentoMessageSender kurentoMessageSender;

    @Value("${recording.ext:}")
    private String RECORDING_EXT;

    @Value("${recording.auto-stop-minutes:10}")
    private int autoStopMinutes;

    public String startRecording(KurentoRoom room, KurentoUserSession requestUser) throws IOException {
        String roomId = room.getRoomId();
        log.info("Starting room recording for room {} requested by user {}", roomId, requestUser.getUserId());
        try {
            // userHub 초기화
            room.initUserHubPort();

            // 녹화 파일 확장자 결정
            MediaProfileSpecType mediaProfileSpecType = MediaProfileSpecType.WEBM;
            if (RECORDING_EXT.equalsIgnoreCase("mp4")) {
                mediaProfileSpecType = MediaProfileSpecType.MP4;
            }

            // 3. 녹화 ID 및 파일 경로 생성
            String recordingId = UUID.randomUUID().toString().split("-")[0];
            String fileName = "room_recording_" + roomId + "_" + requestUser.getUserId() + "."
                    + Strings.toLowerCase(mediaProfileSpecType.name());

            // KMS 컨테이너 내부 마운트 경로 사용
            String filePath = "/recordings/" + roomId + "/" + recordingId + "/" + fileName;
            String fullPath = "file://" + filePath;

            long currentTime = System.currentTimeMillis();

            // 4. 방 전체 녹화 시작
            RecordingInfo recordingInfo = RecordingInfo.builder()
                    .recordingId(recordingId)
                    .roomId(roomId)
                    .recordingUserId(requestUser.getUserId())
                    .recordingNickName(requestUser.getNickName())
                    .startAt(currentTime)
                    .recordingFile(
                            RecordingFile.ofCreate(fileName, filePath, fullPath, currentTime))
                    .status(RecordingStatus.RECORDING)
                    .build();

            // 기존 참여자들을 Composite에 연결
            try {
                Composite composite = KurentoCompositeMap.getComposite(roomId);
                if (composite != null) {
                    Collection<KurentoUserSession> participants = kurentoParticipantService.getParticipantList(roomId);
                    log.info("Connecting {} existing participants to Composite for room {}", participants.size(),
                            roomId);

                    for (KurentoUserSession participant : participants) {
                        try {
                            if (!participant.isConnectedToComposite()) {
                                participant.connectToComposite(composite);
                                log.info("Participant {} connected to Composite for recording",
                                        participant.getUserId());
                            } else {
                                log.debug("Participant {} already connected to Composite", participant.getUserId());
                            }
                        } catch (Exception e) {
                            // TODO 예외처리 세분화
                            log.error("Failed to connect participant {} to Composite: {}",
                                    participant.getUserId(), e.getMessage());
                            // 개별 참여자 연결 실패는 전체 녹화를 중단하지 않음
                            kurentoMessageSender.broadcastError(roomId,
                                    KurentoMessageBuilder.participantRecordingError()
                                            .name(participant.getNickName())
                                            .message(participant.getNickName() + " 님의 녹화 연결에 실패했습니다."));

                        }
                    }
                    log.info("All existing participants connected to Composite for room {}", roomId);
                } else {
                    log.error("Composite not found after starting recording for room: {}", roomId);
                }
            } catch (Exception e) {
                log.error("Error connecting participants to Composite for room {}: {}", roomId, e.getMessage());
                // 참여자 연결 실패 시에도 녹화는 시작된 상태
            }

            log.info("Room recording started successfully for room {} with recordId {}", roomId, recordingId);

            room.startRoomRecording(recordingId, mediaProfileSpecType, recordingInfo);
            room.setRecordingInfo(recordingInfo);

            // 자동 중지 타이머 시작
            ScheduledFuture<?> autoStopTask = scheduleAutoStop(room, recordingInfo);
            recordingInfo.setAutoStopTask(autoStopTask);

            return recordingId;

        } catch (Exception e) {
            log.error("Failed to start room recording for room {}: {}", roomId, e.getMessage());
            throw new IOException("Failed to start room recording: " + e.getMessage(), e);
        }
    }

    public void stopRecording(KurentoRoom room, KurentoUserSession requestUser) throws IOException {
        String roomId = room.getRoomId();
        log.info("Stopping room recording for room {} requested by user {}", roomId, requestUser.getUserId());

        try {
            // 1. 타이머 취소
            RecordingInfo recordingInfo = room.getRecordingInfo();
            if (recordingInfo != null && recordingInfo.getAutoStopTask() != null) {
                recordingInfo.getAutoStopTask().cancel(false);
                log.info("Auto-stop task cancelled for recording: {}", recordingInfo.getRecordingId());
            }

            // 2. 녹화 중지
            room.stopRoomRecording(room.getRecordingInfo().getRecordingId());

            // 2. 모든 참여자의 Composite 연결 해제
            try {
                Collection<KurentoUserSession> participants = kurentoParticipantService.getParticipantList(roomId);
                log.info("Disconnecting {} participants from Composite for room {}", participants.size(), roomId);

                for (KurentoUserSession participant : participants) {
                    try {
                        if (participant.isConnectedToComposite()) {
                            participant.disconnectFromComposite();
                            log.info("Participant {} disconnected from Composite", participant.getUserId());
                        }
                    } catch (Exception e) {
                        log.error("Failed to disconnect participant {} from Composite: {}",
                                participant.getUserId(), e.getMessage());
                    }
                }
                log.info("All participants disconnected from Composite for room {}", roomId);
            } catch (Exception e) {
                log.error("Error disconnecting participants from Composite for room {}: {}", roomId, e.getMessage());
            }

            log.info("Room recording stopped successfully for room {}", roomId);

            // 비동기로 녹화 파일 업로드 시작
            if (recordingInfo != null) {
                recordingUploadService.uploadRecordingAsync(room, recordingInfo)
                        .thenAccept(success -> {
                            if (success) {
                                log.info("Recording upload completed successfully for room: {}", roomId);
                            } else {
                                log.warn("Recording upload failed after retries for room: {}", roomId);
                            }
                        });
            }

        } catch (Exception e) {
            log.error("Failed to stop room recording for room {}: {}", roomId, e.getMessage());
            throw new IOException("Failed to stop room recording: " + e.getMessage(), e);
        }
    }

    /**
     * 자동 녹화 종료 스케줄링
     */
    private ScheduledFuture<?> scheduleAutoStop(KurentoRoom room, RecordingInfo recordingInfo) {
        long delayMillis = TimeUnit.MINUTES.toMillis(autoStopMinutes);

        return autoStopExecutor.schedule(() -> {
            try {
                log.info("Auto-stopping recording after {} minutes: recordingId={}",
                        autoStopMinutes, recordingInfo.getRecordingId());

                // 녹화 중지 (시스템 사용자로)
                KurentoUserSession systemUser = new KurentoUserSession(
                        "SYSTEM",
                        "AUTO-STOP-SYSTEM",
                        room.getRoomId());

                stopRecording(room, systemUser);

                // 녹화 자동 중지 이벤트처리
                eventPublisher.publishEvent(new RecordingAutoStopEvent(
                        room.getRoomId(),
                        room.getRecordingInfo().getRecordingId(),
                        autoStopMinutes));

            } catch (Exception e) {
                log.error("Failed to auto-stop recording: {}", e.getMessage());

                // 실패 이벤트 발행
                eventPublisher.publishEvent(new RecordingAutoStopFailedEvent(
                        room.getRoomId(),
                        recordingInfo.getRecordingId(),
                        autoStopMinutes,
                        e.getMessage()));
            }
        }, new Date(System.currentTimeMillis() + delayMillis));
    }
}
