package webChat.service.chatroom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import webChat.exception.ChatForYouException;
import webChat.exception.ErrorCode;
import webChat.model.redis.DataType;
import webChat.model.room.KurentoRoom;
import webChat.security.jwt.JwtRoomProvider;
import webChat.service.analysis.AnalysisService;
import webChat.service.file.impl.MinioFileService;
import webChat.service.file.impl.RecordingFileService;
import webChat.service.kafka.ChatKafkaProducer;
import webChat.service.kurento.KurentoRoomManager;
import webChat.service.redis.RedisService;
import webChat.service.routing.RoutingInstanceProvider;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class ChatRoomServiceTest {

    private static final String ROOM_ID = "room-1";
    private static final String EMAIL = "tester@example.com";

    @Mock private RedisService redisService;
    @Mock private JwtRoomProvider jwtRoomProvider;
    @Mock private KurentoRoomManager kurentoRoomManager;
    @Mock private RoutingInstanceProvider instanceProvider;
    @Mock private ChatKafkaProducer chatKafkaProducer;
    @Mock private SseService sseService;
    @Mock private MinioFileService minioFileService;
    @Mock private RecordingFileService recordingFileService;
    @Mock private AnalysisService analysisService;

    @InjectMocks
    private ChatRoomService chatRoomService;

    @Test
    @DisplayName("refreshRoomToken 은 비밀방의 유효한 요청에 새 token 을 반환한다")
    void refreshRoomToken_비밀방유효한요청시_새토큰을반환한다() {
        // given
        KurentoRoom room = secretRoom(ROOM_ID);
        given(redisService.getRedisDataByDataType(ROOM_ID, DataType.CHATROOM, KurentoRoom.class)).willReturn(room);
        given(jwtRoomProvider.create(ROOM_ID, EMAIL)).willReturn("new-room-token");

        // when
        Map<String, Object> result = chatRoomService.refreshRoomToken(EMAIL, ROOM_ID);

        // then
        assertThat(result).containsEntry("token", "new-room-token");
    }

    @Test
    @DisplayName("refreshRoomToken 은 존재하지 않는 방이면 ROOM_NOT_FOUND 를 던진다")
    void refreshRoomToken_방이없으면_ROOM_NOT_FOUND를던진다() {
        // given
        given(redisService.getRedisDataByDataType(ROOM_ID, DataType.CHATROOM, KurentoRoom.class)).willReturn(null);

        // when & then
        assertThatThrownBy(() -> chatRoomService.refreshRoomToken(EMAIL, ROOM_ID))
                .isInstanceOf(ChatForYouException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ROOM_NOT_FOUND);
    }

    @Test
    @DisplayName("refreshRoomToken 은 비밀방이 아니면 INVALID_ROOM_ACCESS 를 던진다")
    void refreshRoomToken_비밀방이아니면_INVALID_ROOM_ACCESS를던진다() {
        // given
        KurentoRoom room = publicRoom(ROOM_ID);
        given(redisService.getRedisDataByDataType(ROOM_ID, DataType.CHATROOM, KurentoRoom.class)).willReturn(room);

        // when & then
        assertThatThrownBy(() -> chatRoomService.refreshRoomToken(EMAIL, ROOM_ID))
                .isInstanceOf(ChatForYouException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_ROOM_ACCESS);
    }

    private KurentoRoom secretRoom(String roomId) {
        KurentoRoom room = mock(KurentoRoom.class);
        given(room.getRoomId()).willReturn(roomId);
        given(room.isSecretChk()).willReturn(true);
        return room;
    }

    private KurentoRoom publicRoom(String roomId) {
        KurentoRoom room = mock(KurentoRoom.class);
        given(room.isSecretChk()).willReturn(false);
        return room;
    }
}
