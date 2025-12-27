package webChat.model.event;

import webChat.service.recording.RecordingHandler;

/**
 * 녹화 관련 이벤트의 공통 인터페이스
 * Strategy 패턴으로 각 이벤트가 자신의 처리 방법을 구현
 */
public interface RecordingEvent {
    /**
     * 이벤트가 발생한 방 ID
     */
    String getRoomId();

    /**
     * 녹화 ID
     */
    String getRecordingId();

    /**
     * 이벤트를 처리하는 Strategy 메서드
     * 각 이벤트가 자신을 처리하는 방법을 구현
     *
     * @param handler RecordingHandler 인스턴스
     */
    void handle(RecordingHandler handler);
}
