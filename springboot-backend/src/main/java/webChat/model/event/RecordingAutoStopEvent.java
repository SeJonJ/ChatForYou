package webChat.model.event;

import lombok.Getter;
import webChat.service.recording.RecordingHandler;

/**
 * 자동 녹화 중지 이벤트
 */
@Getter
public class RecordingAutoStopEvent implements RecordingEvent {
    private final String roomId;
    private final String recordingId;
    private final int minutes;
    private final String message = "Auto-stopping recording after 10 minutes";

    public RecordingAutoStopEvent(
            String roomId,
            String recordingId,
            int autoStopMinutes
    ) {
        this.roomId = roomId;
        this.recordingId = recordingId;
        this.minutes = autoStopMinutes;
    }

    /**
     * 녹화 자동 중지 이벤트 처리
     */
    @Override
    public void handle(RecordingHandler handler) {
        handler.notifyAutoStopRecording(
                roomId,
                recordingId,
                minutes,
                message
        );
    }
}
