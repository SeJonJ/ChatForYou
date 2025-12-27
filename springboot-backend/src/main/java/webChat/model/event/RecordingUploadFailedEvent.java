package webChat.model.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;
import webChat.service.kurento.KurentoHandler;
import webChat.service.recording.RecordingHandler;

/**
 * 녹화 파일 업로드 실패 이벤트
 * RecordingUploadService에서 발행하고, KurentoHandler에서 구독
 */
@Getter
public class RecordingUploadFailedEvent extends ApplicationEvent implements RecordingEvent{
    private final String roomId;
    private final String recordingId;
    private final String errorMessage;

    public RecordingUploadFailedEvent(
            Object source,
            String roomId,
            String recordingId,
            String errorMessage
    ) {
        super(source);
        this.roomId = roomId;
        this.recordingId = recordingId;
        this.errorMessage = errorMessage;
    }

    @Override
    public void handle(RecordingHandler handler) {
        handler.notifyRecordingUploadFailed(
                roomId,
                recordingId,
                errorMessage);
    }
}
