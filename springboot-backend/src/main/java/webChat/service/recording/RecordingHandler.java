package webChat.service.recording;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import webChat.model.event.RecordingEvent;
import webChat.service.kurento.KurentoMessageBuilder;
import webChat.service.kurento.KurentoMessageSender;

@Service
@Slf4j
@RequiredArgsConstructor
public class RecordingHandler {
    private final KurentoMessageSender messageSender;


    /**
     * Spring 의 EventPublish 를 사용해서 발행된 이벤트를 구독
     * kafka 의 이벤트 발행 - 구독 기능을 spring 에서 쓸 수 있도록 함
     */
    @EventListener
    @Async("taskExecutor")
    public void handleRecordingEvent(RecordingEvent event){
        event.handle(this);
    }

    /**
     * 녹화 업로드 완료 알림
     * @param fileName 녹화 파일명
     * @param roomId 방 ID
     * @param recordingId 녹화 ID
     * @param minioFilePath 다운로드를 위한 minioFilePath
     * @param fileSize 파일 크기 (bytes)
     */
    public void notifyRecordingUploadCompleted(String fileName, String roomId, String recordingId,
                                               String minioFilePath, long fileSize) {
        messageSender.broadcastSuccess(
                roomId,
                KurentoMessageBuilder.uploadCompleted()
                        .fileName(fileName)
                        .recordingId(recordingId)
                        .minioFilePath(minioFilePath)
                        .fileSize(fileSize)
        );
    }

    /**
     * 녹화 업로드 실패 알림
     * @param roomId 방 ID
     * @param recordingId 녹화 ID
     * @param errorMessage 에러 메시지
     */
    public void notifyRecordingUploadFailed(String roomId, String recordingId, String errorMessage) {
        messageSender.broadcastError(
            roomId,
            KurentoMessageBuilder.uploadFailed()
                .recordingId(recordingId)
                .error(errorMessage)
        );
    }

    /**
     * 녹화 자동중지 이벤트
     * @param roomId 방 ID
     * @param recordingId 녹화 ID
     * @param minutes 녹화 중지 타이머(시간)
     * @param eventMessage 녹화 일시 중지 메시지
     */
    public void notifyAutoStopRecording(String roomId, String recordingId, int minutes, String eventMessage) {
        messageSender.broadcastSuccess(
            roomId,
            KurentoMessageBuilder.autoStopped()
                .recordingId(recordingId)
                .minutes(minutes)
                .formatMessage("녹화가 %d분 경과로 자동 종료되었습니다.", minutes)
        );
        log.info("Broadcast :: {}", eventMessage);
    }

    /**
     * 녹화 자동 중지 실패 알림
     * @param roomId 방 ID
     * @param recordingId 녹화 ID
     * @param minutes 녹화 중지 타이머(시간)
     * @param errorMessage 에러 메시지
     */
    public void notifyAutoStopRecordingFailed(String roomId, String recordingId, int minutes, String errorMessage) {
        messageSender.broadcastError(
            roomId,
            KurentoMessageBuilder.autoStopFailed()
                .recordingId(recordingId)
                .minutes(minutes)
                .error(errorMessage)
                .formatMessage("%d분 자동 녹화 중지에 실패했습니다. 자세한 사항은 관리자에게 문의부탁드립니다.", minutes)
        );
        log.error("Broadcast AutoStop recording room {} : {}", roomId, errorMessage);
    }
}
