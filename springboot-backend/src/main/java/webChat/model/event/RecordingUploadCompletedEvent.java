package webChat.model.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;
import webChat.service.kurento.KurentoHandler;
import webChat.service.recording.RecordingHandler;

/**
 * 녹화 파일 업로드 완료 이벤트
 */
@Getter
public class RecordingUploadCompletedEvent implements RecordingEvent {
    private final String roomId;
    private final String recordingId;
    private final String downloadUrl;
    private final long fileSize;

    public RecordingUploadCompletedEvent(
            String roomId,
            String recordingId,
            String downloadUrl,
            long fileSize
    ) {
        this.roomId = roomId;
        this.recordingId = recordingId;
        this.downloadUrl = downloadUrl;
        this.fileSize = fileSize;
    }

    /**
     * 업로드 완료 이벤트 처리
     */
    @Override
    public void handle(RecordingHandler handler) {
        handler.notifyRecordingUploadCompleted(
                roomId,
                recordingId,
                downloadUrl,
                fileSize
        );
    }
}
