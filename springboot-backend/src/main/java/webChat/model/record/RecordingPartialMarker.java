package webChat.model.record;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import webChat.model.room.KurentoRoom;

/**
 * 배포/종료로 중단된 방 녹화의 부분 파일 식별 정보를 보존하는 마커.
 * KurentoRoom.recordingInfo 가 null 로 정리되면 부분 파일 경로를 잃으므로,
 * reset 직전에 파일 식별 필드만 평면화해 별도 Redis 키에 남긴다.
 * 실제 부분 파일 삭제/업로드는 별도 부분 파일 정리 작업이 이 마커를 소비해 수행한다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecordingPartialMarker {

    private String roomId;
    private String recordingId;
    private String recordingUserId;
    private String recordingNickName;

    // 부분 파일 식별자 — KMS 컨테이너와 함께 사라진 파일을 별도 정리 작업이 찾기 위한 경로 후보
    private String fileName;
    private String filePath;
    private String fileFullPath;
    private String minioFilePath;

    private long startAt;   // 녹화 시작 시각 (RecordingInfo.startAt)
    private long markedAt;  // 마커 기록 시각 — 마커 생성 시점 추적

    // 재입장 안내 메시지 1회 표시 여부. 마커 수명(파일 정리)과 메시지 수명을 분리한다.
    private boolean notified;

    /**
     * 중단 시점의 KurentoRoom 에서 부분 파일 식별 정보를 추출해 마커를 만든다.
     * recordingInfo 또는 recordingFile 이 null 일 수 있어(시작 실패 직후 등) 각 단계에서 방어한다.
     * notified 는 false 로 시작해 첫 재입장에서 안내 메시지를 1회 보낸다.
     */
    public static RecordingPartialMarker fromRoom(KurentoRoom room) {
        RecordingInfo info = room.getRecordingInfo();
        RecordingPartialMarkerBuilder builder = RecordingPartialMarker.builder()
                .roomId(room.getRoomId())
                .markedAt(System.currentTimeMillis())
                .notified(false);

        if (info != null) {
            builder.recordingId(info.getRecordingId())
                    .recordingUserId(info.getRecordingUserId())
                    .recordingNickName(info.getRecordingNickName())
                    .startAt(info.getStartAt());

            RecordingFile file = info.getRecordingFile();
            if (file != null) {
                builder.fileName(file.getFileName())
                        .filePath(file.getFilePath())
                        .fileFullPath(file.getFileFullPath())
                        .minioFilePath(file.getMinioFilePath());
            }
        }
        return builder.build();
    }

    /** 안내 메시지 전송 후 동일 객체를 notified=true 로 다시 저장하기 위한 헬퍼. */
    public RecordingPartialMarker markNotified() {
        this.notified = true;
        return this;
    }
}
