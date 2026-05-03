package webChat.service.kurento;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kurento.client.KurentoClient;
import org.kurento.client.MediaPipeline;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import webChat.exception.ErrorCode;
import webChat.service.chatroom.participant.KurentoParticipantService;
import webChat.service.kafka.ChatKafkaProducer;
import webChat.service.recording.RecordingService;
import webChat.service.redis.RedisService;

import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KurentoHandlerTest {

    @Mock
    private KurentoRoomManager kurentoRoomManager;

    @Mock
    private KurentoClient kurentoClient;

    @Mock
    private RedisService redisService;

    @Mock
    private KurentoParticipantService participantService;

    @Mock
    private ChatKafkaProducer chatKafkaProducer;

    @Mock
    private RecordingService recordingService;

    @Mock
    private KurentoMessageSender kurentoMessageSender;

    @Mock
    private WebSocketSession session;

    @Test
    @DisplayName("존재하지 않는 방으로 JOIN_ROOM 요청 시 ROOM_NOT_FOUND 표준 에러를 세션에 전송한다")
    void handleTextMessage_whenJoinRoomTargetsMissingRoom_sendsStandardRoomNotFoundError() {
        // given
        KurentoHandler handler = new KurentoHandler(
                kurentoRoomManager,
                kurentoClient,
                redisService,
                participantService,
                chatKafkaProducer,
                recordingService,
                kurentoMessageSender
        );

        when(participantService.getBySessionId(session)).thenReturn(null);
        when(redisService.getRedisDataByDataType(eq("missing-room"), eq(webChat.model.redis.DataType.CHATROOM), eq(webChat.model.room.KurentoRoom.class)))
                .thenReturn(null);

        String payload = """
                {
                  "event":"JOIN_ROOM",
                  "roomId":"missing-room",
                  "senderId":"user-1",
                  "senderNickName":"tester"
                }
                """;

        // when
        handler.handleTextMessage(session, new TextMessage(payload));

        // then
        verify(kurentoMessageSender).sendStandardErrorToSession(session, ErrorCode.ROOM_NOT_FOUND, null);
    }
}
