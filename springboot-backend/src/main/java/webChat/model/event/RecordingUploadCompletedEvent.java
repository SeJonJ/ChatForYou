package webChat.model.event;

import lombok.Getter;
import webChat.service.recording.RecordingHandler;

/**
 * 녹화 파일 업로드 완료 이벤트
 */
@Getter
public class RecordingUploadCompletedEvent implements RecordingEvent {
    private final String roomId;
    private final String recordingId;
    private final String fileName;
    private final String minioFilePath;
    private final long fileSize;

    public RecordingUploadCompletedEvent(
            String roomId,
            String recordingId,
            String fileName,
            String minioFilePath,
            long fileSize
    ) {
        this.roomId = roomId;
        this.recordingId = recordingId;
        this.fileName = fileName;
        this.minioFilePath = minioFilePath;
        this.fileSize = fileSize;
    }

    /**
     * 업로드 완료 이벤트 처리
     */
    @Override
    public void handle(RecordingHandler handler) {
        handler.notifyRecordingUploadCompleted(
                fileName,
                roomId,
                recordingId,
                minioFilePath,
                fileSize
        );
    }
}
