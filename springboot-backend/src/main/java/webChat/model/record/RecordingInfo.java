package webChat.model.record;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;
import webChat.model.kurento.KurentoRecordingMessage;

import java.util.concurrent.ScheduledFuture;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecordingInfo {
    @NonNull
    private String recordingId; // recording uuid
    @NonNull
    private String roomId; // 방정보
    @NonNull
    private String recordingUserId; // 녹화 유저 아이디
    @NonNull
    private String recordingNickName; // 녹화 유저 닉네임
    private long startAt; // 시작 시간
    private RecordingFile recordingFile; // 녹화 파일
    @JsonIgnore
    private ScheduledFuture<?> autoStopTask;  // 10분 타이머
    private RecordingStatus status; // RECORDING, PAUSED, STOPPED

    public RecordingInfo of(@NonNull KurentoRecordingMessage recordingMessage){
        return RecordingInfo.builder()
                .recordingId(recordingMessage.getRecordingId())
                .startAt(recordingMessage.getStartAt())
                .roomId(recordingMessage.getRoomId())
                .recordingUserId(recordingMessage.getSenderId())
                .recordingNickName(recordingMessage.getSenderNickName())
                .status(RecordingStatus.READY)
                .build();
    }
}
