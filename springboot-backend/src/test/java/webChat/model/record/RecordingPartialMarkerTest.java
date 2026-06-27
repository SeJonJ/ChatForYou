package webChat.model.record;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import webChat.model.chat.ChatType;
import webChat.model.room.KurentoRoom;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class RecordingPartialMarkerTest {

    private KurentoRoom newRoom() {
        return new KurentoRoom("room-1", "테스트방", "creator", "", false, 0, 4, ChatType.RTC, "instance-1");
    }

    private RecordingFile newFile() {
        RecordingFile created = RecordingFile.ofCreate("rec.webm", "/path", "/path/rec.webm", 1000L);
        return RecordingFile.of(created, "minio/rec.webm", 100L, 2000L);
    }

    @Test
    @DisplayName("fromRoom_정상녹화정보와파일_파일식별필드와recordingId가매핑된다")
    void fromRoom_withRecordingInfoAndFile_mapsFileIdentityFields() {
        // given
        KurentoRoom room = newRoom();
        room.setRecordingInfo(RecordingInfo.builder()
                .recordingId("rec-1")
                .roomId("room-1")
                .recordingUserId("user-1")
                .recordingNickName("nick-1")
                .startAt(1000L)
                .recordingFile(newFile())
                .build());

        // when
        RecordingPartialMarker marker = RecordingPartialMarker.fromRoom(room);

        // then
        assertThat(marker.getRoomId()).isEqualTo("room-1");
        assertThat(marker.getRecordingId()).isEqualTo("rec-1");
        assertThat(marker.getRecordingUserId()).isEqualTo("user-1");
        assertThat(marker.getRecordingNickName()).isEqualTo("nick-1");
        assertThat(marker.getStartAt()).isEqualTo(1000L);
        assertThat(marker.getFileName()).isEqualTo("rec.webm");
        assertThat(marker.getFilePath()).isEqualTo("/path");
        assertThat(marker.getFileFullPath()).isEqualTo("/path/rec.webm");
        assertThat(marker.getMinioFilePath()).isEqualTo("minio/rec.webm");
        assertThat(marker.isNotified()).isFalse();
        assertThat(marker.getMarkedAt()).isGreaterThan(0L);
    }

    @Test
    @DisplayName("fromRoom_recordingInfo가null_roomId와markedAt만채워지고파일필드는null")
    void fromRoom_withNullRecordingInfo_fillsOnlyRoomIdAndMarkedAt() {
        // given
        KurentoRoom room = newRoom();
        room.setRecordingInfo(null);

        // when
        RecordingPartialMarker marker = RecordingPartialMarker.fromRoom(room);

        // then
        assertThat(marker.getRoomId()).isEqualTo("room-1");
        assertThat(marker.getMarkedAt()).isGreaterThan(0L);
        assertThat(marker.getRecordingId()).isNull();
        assertThat(marker.getFileName()).isNull();
        assertThat(marker.getFileFullPath()).isNull();
        assertThat(marker.isNotified()).isFalse();
    }

    @Test
    @DisplayName("fromRoom_recordingFile이null_recordingId는채워지고파일경로는null")
    void fromRoom_withNullRecordingFile_fillsRecordingIdButNullFilePaths() {
        // given
        KurentoRoom room = newRoom();
        room.setRecordingInfo(RecordingInfo.builder()
                .recordingId("rec-1")
                .roomId("room-1")
                .recordingUserId("user-1")
                .recordingNickName("nick-1")
                .startAt(1000L)
                .recordingFile(null)
                .build());

        // when
        RecordingPartialMarker marker = RecordingPartialMarker.fromRoom(room);

        // then
        assertThat(marker.getRecordingId()).isEqualTo("rec-1");
        assertThat(marker.getStartAt()).isEqualTo(1000L);
        assertThat(marker.getFileName()).isNull();
        assertThat(marker.getFilePath()).isNull();
        assertThat(marker.getFileFullPath()).isNull();
        assertThat(marker.getMinioFilePath()).isNull();
    }

    @Test
    @DisplayName("markNotified_호출시_동일인스턴스를notified=true로반환한다")
    void markNotified_setsNotifiedTrueAndReturnsSameInstance() {
        // given
        RecordingPartialMarker marker = RecordingPartialMarker.builder()
                .roomId("room-1")
                .notified(false)
                .build();

        // when
        RecordingPartialMarker result = marker.markNotified();

        // then
        assertThat(result).isSameAs(marker);
        assertThat(result.isNotified()).isTrue();
    }
}
