package webChat.service.file.fixture;

import webChat.model.chat.ChatType;
import webChat.model.record.RecordingFile;
import webChat.model.record.RecordingInfo;
import webChat.model.record.RecordingPartialMarker;
import webChat.model.record.RecordingStatus;
import webChat.model.room.KurentoRoom;

/**
 * Bug #135 QA 테스트 공통 Fixture.
 * 녹화 상태 게이트·마커 시나리오에서 반복되는 객체 생성을 중앙 관리한다.
 */
public final class RecordingPartialMarkerFixture {

    public static final String ROOM_ID = "room-qa-001";
    public static final String RECORDING_ID = "rec-qa-001";
    public static final String USER_ID = "user-qa-001";
    public static final String NICK_NAME = "QA테스터";
    public static final String INSTANCE_ID = "instance-qa";
    public static final long START_AT = 1_000_000L;
    public static final long TTL_SECONDS = 86_400L;

    private RecordingPartialMarkerFixture() {
    }

    /** 실제 KurentoRoom 인스턴스 — isRecordingInProgress=true, recordingInfo+recordingFile 완비. */
    public static KurentoRoom inProgressRoomWithFile() {
        KurentoRoom room = newRoom();
        room.setRecordingInProgress(true);
        room.setHasRecordedOnce(false);
        room.setRecordingInfo(fullRecordingInfo());
        return room;
    }

    /** isRecordingInProgress=true, recordingInfo null (시작 직후 실패 케이스). */
    public static KurentoRoom inProgressRoomWithNullInfo() {
        KurentoRoom room = newRoom();
        room.setRecordingInProgress(true);
        room.setHasRecordedOnce(false);
        room.setRecordingInfo(null);
        return room;
    }

    /** isRecordingInProgress=true, recordingFile null (파일 생성 전 케이스). */
    public static KurentoRoom inProgressRoomWithNullFile() {
        KurentoRoom room = newRoom();
        room.setRecordingInProgress(true);
        room.setHasRecordedOnce(false);
        room.setRecordingInfo(infoWithNullFile());
        return room;
    }

    /**
     * 정상 완료된 녹화 방.
     * isRecordingInProgress=false, hasRecordedOnce=true — 재녹화 차단 상태.
     */
    public static KurentoRoom completedRecordingRoom() {
        KurentoRoom room = newRoom();
        room.setRecordingInProgress(false);
        room.setHasRecordedOnce(true);
        room.setRecordingInfo(fullRecordingInfo());
        return room;
    }

    /** 녹화 이력 없는 방 — isRecordingInProgress=false, hasRecordedOnce=false. */
    public static KurentoRoom freshRoom() {
        KurentoRoom room = newRoom();
        room.setRecordingInProgress(false);
        room.setHasRecordedOnce(false);
        room.setRecordingInfo(null);
        return room;
    }

    /** notified=false 마커 (아직 안내 미전송). */
    public static RecordingPartialMarker markerNotNotified() {
        return RecordingPartialMarker.builder()
                .roomId(ROOM_ID)
                .recordingId(RECORDING_ID)
                .recordingUserId(USER_ID)
                .recordingNickName(NICK_NAME)
                .startAt(START_AT)
                .markedAt(System.currentTimeMillis())
                .notified(false)
                .build();
    }

    /** notified=true 마커 (안내 전송 완료). */
    public static RecordingPartialMarker markerAlreadyNotified() {
        return RecordingPartialMarker.builder()
                .roomId(ROOM_ID)
                .recordingId(RECORDING_ID)
                .recordingUserId(USER_ID)
                .recordingNickName(NICK_NAME)
                .startAt(START_AT)
                .markedAt(System.currentTimeMillis())
                .notified(true)
                .build();
    }

    private static KurentoRoom newRoom() {
        return new KurentoRoom(ROOM_ID, "QA테스트방", "creator", "", false, 0, 4, ChatType.RTC, INSTANCE_ID);
    }

    private static RecordingInfo fullRecordingInfo() {
        RecordingFile file = RecordingFile.ofCreate("rec.webm", "/path", "/path/rec.webm", 1000L);
        RecordingFile withMinio = RecordingFile.of(file, "minio/rec.webm", 100L, 2000L);
        return RecordingInfo.builder()
                .recordingId(RECORDING_ID)
                .roomId(ROOM_ID)
                .recordingUserId(USER_ID)
                .recordingNickName(NICK_NAME)
                .startAt(START_AT)
                .recordingFile(withMinio)
                .status(RecordingStatus.RECORDING)
                .build();
    }

    private static RecordingInfo infoWithNullFile() {
        return RecordingInfo.builder()
                .recordingId(RECORDING_ID)
                .roomId(ROOM_ID)
                .recordingUserId(USER_ID)
                .recordingNickName(NICK_NAME)
                .startAt(START_AT)
                .recordingFile(null)
                .status(RecordingStatus.RECORDING)
                .build();
    }
}
