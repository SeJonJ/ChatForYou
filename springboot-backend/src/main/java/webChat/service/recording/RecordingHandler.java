package webChat.service.recording;

import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import webChat.model.event.RecordingEvent;
import webChat.model.record.RecordingStatus;
import webChat.service.kurento.KurentoHandler;

@Service
@Async
@Slf4j
@RequiredArgsConstructor
public class RecordingHandler {
    private final KurentoHandler kurentoHandler;


    /**
     * Spring 의 EventPublish 를 사용해서 발행된 이벤트를 구독
     * kafka 의 이벤트 발행 - 구독 기능을 spring 에서 쓸 수 있도록 함
     */
    @EventListener
    @Async
    public void handleRecordingEvent(RecordingEvent event){
        event.handle(this);
    }

    /**
     * 녹화 업로드 완료 알림
     * @param roomId 방 ID
     * @param recordingId 녹화 ID
     * @param downloadUrl 다운로드 URL
     * @param fileSize 파일 크기 (bytes)
     */
    public void notifyRecordingUploadCompleted(String roomId, String recordingId,
                                               String downloadUrl, long fileSize) {
        JsonObject message = new JsonObject();
        message.addProperty("id", "recordingUploadCompleted");
        message.addProperty("status", RecordingStatus.COMPLETED.name());
        message.addProperty("recordingId", recordingId);
        message.addProperty("downloadUrl", downloadUrl);
        message.addProperty("fileSize", fileSize);
        message.addProperty("fileSizeMB", fileSize / 1024 / 1024);
        message.addProperty("message", "녹화 파일 업로드가 완료되었습니다.");

        kurentoHandler.broadcastToRoom(roomId, message);
        log.info("Broadcast recording upload completed notification to room {}", roomId);
    }

    /**
     * 녹화 업로드 실패 알림
     * @param roomId 방 ID
     * @param recordingId 녹화 ID
     * @param errorMessage 에러 메시지
     */
    public void notifyRecordingUploadFailed(String roomId, String recordingId, String errorMessage) {
        JsonObject message = new JsonObject();
        message.addProperty("id", "recordingUploadFailed");
        message.addProperty("status", RecordingStatus.FAILED.name());
        message.addProperty("recordingId", recordingId);
        message.addProperty("error", errorMessage);
        message.addProperty("message", "녹화 파일 업로드에 실패했습니다.");

        kurentoHandler.broadcastToRoom(roomId, message);
        log.error("Broadcast recording upload failed notification to room {}: {}", roomId, errorMessage);
    }
}
