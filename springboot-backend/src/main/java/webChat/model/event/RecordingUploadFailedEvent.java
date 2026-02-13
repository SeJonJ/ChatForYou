package webChat.model.event;

import lombok.Getter;
import webChat.service.recording.RecordingHandler;

/**
 * 녹화 파일 업로드 실패 이벤트
 */
@Getter
public class RecordingUploadFailedEvent implements RecordingEvent{
    private final String roomId;
    private final String recordingId;
    private final String errorMessage;

    public RecordingUploadFailedEvent(
            String roomId,
            String recordingId,
            String errorMessage
    ) {
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
