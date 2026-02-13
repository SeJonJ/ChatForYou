package webChat.model.event;

import lombok.Getter;
import webChat.service.recording.RecordingHandler;

/**
 * 자동 녹화 중지 실패 이벤트
 */
@Getter
public class RecordingAutoStopFailedEvent implements RecordingEvent {
    private final String roomId;
    private final String recordingId;
    private final String errorMessage;
    private final int minutes;

    public RecordingAutoStopFailedEvent(
            String roomId,
            String recordingId,
            int autoStopMinutes,
            String errorMessage
    ) {
        this.roomId = roomId;
        this.recordingId = recordingId;
        this.minutes = autoStopMinutes;
        this.errorMessage = errorMessage;
    }

    /**
     * 녹화 자동 중지 이벤트 처리
     */
    @Override
    public void handle(RecordingHandler handler) {
        handler.notifyAutoStopRecordingFailed(
                roomId,
                recordingId,
                minutes,
                errorMessage
        );
    }
}
