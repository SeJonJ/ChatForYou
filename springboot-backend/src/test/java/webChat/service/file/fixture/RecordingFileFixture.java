package webChat.service.file.fixture;

import webChat.model.record.RecordingFile;
import webChat.model.record.RecordingInfo;
import webChat.model.record.RecordingStatus;
import webChat.model.room.KurentoRoom;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * RecordingFileService 단위 테스트용 Fixture.
 * 녹화 파일 다운로드 시나리오에서 반복되는 객체 생성을 중앙 관리한다.
 */
public final class RecordingFileFixture {

    public static final String ROOM_ID = "room-test-001";
    public static final String FILE_NAME = "recording.mp4";
    public static final String FILE_DIR = "room-test-001/rec-001/recording.mp4";

    // expiresAt: 과거 (1시간 전)
    public static final long EXPIRES_AT_PAST = System.currentTimeMillis() - 3_600_000L;
    // expiresAt: 미래 (1시간 후)
    public static final long EXPIRES_AT_FUTURE = System.currentTimeMillis() + 3_600_000L;

    private RecordingFileFixture() {
    }

    public static RecordingFile recordingFileWithExpiredAt(long expiresAt) {
        return RecordingFile.ofCreate(FILE_NAME, FILE_DIR, "file://" + FILE_DIR, System.currentTimeMillis());
    }

    public static RecordingInfo recordingInfoWithFile(RecordingFile recordingFile) {
        return RecordingInfo.builder()
                .recordingId("rec-001")
                .roomId(ROOM_ID)
                .recordingUserId("user-001")
                .recordingNickName("tester")
                .startAt(System.currentTimeMillis() - 60_000L)
                .recordingFile(recordingFile)
                .status(RecordingStatus.STOPPED)
                .build();
    }

    public static KurentoRoom roomWithRecordingFile(long expiresAt) {
        RecordingFile recordingFile = recordingFileWithExpiredAt(expiresAt);
        RecordingInfo recordingInfo = recordingInfoWithFile(recordingFile);

        KurentoRoom room = mock(KurentoRoom.class);
        given(room.getRecordingInfo()).willReturn(recordingInfo);
        return room;
    }

    public static KurentoRoom roomWithNullRecordingInfo() {
        KurentoRoom room = mock(KurentoRoom.class);
        given(room.getRecordingInfo()).willReturn(null);
        return room;
    }

    public static KurentoRoom roomWithNullRecordingFile() {
        RecordingInfo recordingInfo = RecordingInfo.builder()
                .recordingId("rec-001")
                .roomId(ROOM_ID)
                .recordingUserId("user-001")
                .recordingNickName("tester")
                .startAt(System.currentTimeMillis() - 60_000L)
                .recordingFile(null)
                .status(RecordingStatus.STOPPED)
                .build();

        KurentoRoom room = mock(KurentoRoom.class);
        given(room.getRecordingInfo()).willReturn(recordingInfo);
        return room;
    }
}
