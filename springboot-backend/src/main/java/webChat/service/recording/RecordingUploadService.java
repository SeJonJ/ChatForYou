package webChat.service.recording;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import webChat.config.ThreadPoolConfig;
import webChat.model.event.RecordingUploadCompletedEvent;
import webChat.model.event.RecordingUploadFailedEvent;
import webChat.model.record.RecordingFile;
import webChat.model.record.RecordingInfo;
import webChat.model.record.RecordingStatus;
import webChat.model.room.KurentoRoom;
import webChat.service.chatroom.ChatRoomService;
import webChat.service.file.impl.RecordingFileService;
import webChat.utils.ThreadUtils;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 녹화 파일 업로드 후처리를 담당하는 서비스
 * 책임:
 * 1. 녹화 파일 업로드 (RecordingFileService 사용)
 * 2. 업로드 상태 관리 (UPLOADING → COMPLETED/FAILED)
 * 3. DB 업데이트 (ChatRoomService 사용)
 * 4. 업로드 완료 이벤트 발행 (ApplicationEventPublisher 사용)
 * 5. 재시도 로직 (ThreadUtils 사용)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecordingUploadService {
    private final RecordingFileService recordingFileService;
    private final ChatRoomService chatRoomService;
    private final ThreadPoolConfig threadPoolConfig;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${kms.recording.expire:1}")
    private int recordingExpire;

    /**
     * 녹화 파일을 MinIO에 비동기로 업로드 (재시도 로직 포함)
     *
     * @param room 방 정보
     * @param recordingInfo 녹화 정보
     * @return CompletableFuture<Boolean> 업로드 성공 여부
     */
    public CompletableFuture<Boolean> uploadRecordingAsync(KurentoRoom room, RecordingInfo recordingInfo) {
        String roomId = room.getRoomId();
        String recordingId = recordingInfo.getRecordingId();

        return ThreadUtils.executeAsyncTask(
            // Task: 업로드 작업
            () -> {
                try {
                    log.info("Starting upload for recording: {} in room: {}", recordingId, roomId);

                    // 1. 상태를 UPLOADING으로 변경
                    recordingInfo.setStatus(RecordingStatus.UPLOADING);
                    room.setRecordingInfo(recordingInfo);
                    chatRoomService.updateRoom(room);

                    // 2. 로컬 파일 크기 계산
                    String localFilePath = recordingInfo.getRecordingFile().getFileFullPath();
                    String cleanPath = localFilePath.replace("file://", "");
                    File localFile = new File(cleanPath);

                    if (!localFile.exists()) {
                        log.error("Recording file not found: {}", localFilePath);
                        return false;
                    }

                    long fileSize = localFile.length();
                    log.info("Recording file size: {} bytes ({} MB)", fileSize, fileSize / 1024 / 1024);

                    // 3. MinIO 업로드
                    String downloadUrl = recordingFileService.uploadRecording(
                            recordingInfo,
                            localFilePath,
                            recordingExpire
                    );

                    // 4. RecordingFile 업데이트
                    RecordingFile updatedFile = RecordingFile.of(
                            recordingInfo.getRecordingFile().getFilePath(),
                            downloadUrl,
                            fileSize,
                            recordingInfo.getStartAt(),
                            System.currentTimeMillis() + TimeUnit.HOURS.toMillis(recordingExpire)
                    );

                    recordingInfo.setRecordingFile(updatedFile);
                    recordingInfo.setStatus(RecordingStatus.COMPLETED);
                    room.setRecordingInfo(recordingInfo);
                    chatRoomService.updateRoom(room);

                    log.info("Recording upload completed: {} (size: {} MB, url: {})",
                            recordingId, fileSize / 1024 / 1024, downloadUrl);
                    return true; // 성공

                } catch (Exception e) {
                    log.error("Upload attempt failed for recording {}: {}", recordingId, e.getMessage());
                    return false; // 실패 (재시도됨)
                }
            },
            threadPoolConfig.getMaxRetries(),      // 최대 재시도 횟수
            threadPoolConfig.getRetryDelayMs(),    // 재시도 간격
            "Recording-Upload-Thread-" + recordingId,
            // 성공 콜백: 업로드 완료 이벤트 발행
            (result) -> {
                if (Boolean.TRUE.equals(result)) {
                    try {
                        RecordingFile file = recordingInfo.getRecordingFile();

                        // 업로드 완료 이벤트 발행
                        eventPublisher.publishEvent(new RecordingUploadCompletedEvent(
                                this,
                                roomId,
                                recordingId,
                                file.getFileFullPath(),
                                file.getFileSize()
                        ));

                        log.info("Recording upload completed event published for: {}", recordingId);
                    } catch (Exception e) {
                        log.error("Failed to publish upload completed event: {}", e.getMessage());
                    }
                }
            }
        ).exceptionally(ex -> {
            // 최종 실패 처리
            log.error("Recording upload finally failed after {} retries for recording: {}",
                    threadPoolConfig.getMaxRetries(), recordingId, ex);

            recordingInfo.setStatus(RecordingStatus.FAILED);
            room.setRecordingInfo(recordingInfo);
            chatRoomService.updateRoom(room);

            // 업로드 실패 이벤트 발행
            eventPublisher.publishEvent(new RecordingUploadFailedEvent(
                    this,
                    roomId,
                    recordingId,
                    ex.getMessage()
            ));

            return false;
        });
    }
}
