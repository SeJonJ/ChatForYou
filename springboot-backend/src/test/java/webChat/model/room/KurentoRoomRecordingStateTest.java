package webChat.model.room;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import webChat.model.chat.ChatType;
import webChat.model.record.RecordingInfo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@ExtendWith(MockitoExtension.class)
class KurentoRoomRecordingStateTest {

    private KurentoRoom newRoom() {
        return new KurentoRoom("room-1", "테스트방", "creator", "", false, 0, 4, ChatType.RTC, "instance-1");
    }

    @Test
    @DisplayName("resetRecordingState_녹화정보가있는방_3필드모두초기화된다")
    void resetRecordingState_withRecordingInfo_clearsAllThreeFields() {
        // given
        KurentoRoom room = newRoom();
        room.setHasRecordedOnce(true);
        room.setRecordingInProgress(true);
        room.setRecordingInfo(RecordingInfo.builder()
                .recordingId("rec-1")
                .roomId("room-1")
                .recordingUserId("user-1")
                .recordingNickName("nick-1")
                .startAt(1000L)
                .build());

        // when
        room.resetRecordingState();

        // then
        assertThat(room.isRecordingInProgress()).isFalse();
        assertThat(room.isHasRecordedOnce()).isFalse();
        assertThat(room.getRecordingInfo()).isNull();
    }

    @Test
    @DisplayName("resetRecordingState_recordingInfo가null이어도_NPE없이초기화된다")
    void resetRecordingState_withNullRecordingInfo_doesNotThrow() {
        // given
        KurentoRoom room = newRoom();
        room.setRecordingInProgress(true);
        room.setRecordingInfo(null);

        // when & then
        assertThatCode(room::resetRecordingState).doesNotThrowAnyException();
        assertThat(room.isRecordingInProgress()).isFalse();
        assertThat(room.isHasRecordedOnce()).isFalse();
        assertThat(room.getRecordingInfo()).isNull();
    }
}
