package webChat.service.kurento;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kurento.client.KurentoClient;
import org.kurento.client.MediaPipeline;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import webChat.exception.ErrorCode;
import webChat.model.redis.DataType;
import webChat.model.room.KurentoRoom;
import webChat.service.chatroom.participant.KurentoParticipantService;
import webChat.service.kafka.ChatKafkaProducer;
import webChat.service.recording.RecordingService;
import webChat.service.redis.RedisService;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class KurentoHandlerTest {

    @Mock
    private KurentoRoomManager kurentoRoomManager;

    @Mock
    private KurentoClient kurentoClient;

    @Mock
    private MediaPipeline mediaPipeline;

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

    @Mock
    private WebSocketSession activeSession;

    @Mock
    private KurentoUserSession staleUser;

    @Mock
    private KurentoUserSession activeUser;

    @Mock
    private KurentoRoom kurentoRoom;

    @Test
    @DisplayName("존재하지 않는 방으로 JOIN_ROOM 요청 시 ROOM_NOT_FOUND 표준 에러를 세션에 전송한다")
    void handleTextMessage_존재하지않는방참가요청시_ROOM_NOT_FOUND에러를전송한다() {
        // given
        KurentoHandler handler = createHandler();
        String payload = """
                {
                  "event":"JOIN_ROOM",
                  "roomId":"missing-room",
                  "senderId":"user-1",
                  "senderNickName":"tester"
                }
                """;

        given(participantService.getBySessionId(session)).willReturn(null);
        given(redisService.getRedisDataByDataType(eq("missing-room"), eq(DataType.CHATROOM), eq(KurentoRoom.class)))
                .willReturn(null);

        // when
        handler.handleTextMessage(session, new TextMessage(payload));

        // then
        verify(kurentoMessageSender).sendStandardErrorToSession(session, ErrorCode.ROOM_NOT_FOUND, null);
    }

    @Test
    @DisplayName("이전 세션의 종료 이벤트는 현재 활성 참가자를 제거하지 않는다")
    void afterConnectionClosed_이전세션종료이벤트면_현재활성참가자제거와인원수감소를건너뛴다() throws Exception {
        // given
        KurentoHandler handler = createHandler();

        lenient().when(staleUser.getRoomId()).thenReturn("room-1");
        lenient().when(staleUser.getUserId()).thenReturn("user-1");
        lenient().when(staleUser.getSession()).thenReturn(session);
        lenient().when(activeUser.getSession()).thenReturn(activeSession);
        given(participantService.getBySessionId(session)).willReturn(staleUser);
        given(participantService.isCurrentParticipantSession("room-1", "user-1", session)).willReturn(false);
        lenient().when(redisService.getRedisDataByDataType(eq("room-1"), eq(DataType.CHATROOM), eq(KurentoRoom.class)))
                .thenReturn(kurentoRoom);
        lenient().when(participantService.getParticipantMap("room-1")).thenReturn(Map.of("user-1", activeUser));

        // when
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        // then
        verify(kurentoRoomManager, never()).leave(any(), any());
        verify(redisService, never()).decrementUserCount(any());
        verify(chatKafkaProducer, never()).sendRoomUserCntEvent(any());
    }

    @Test
    @DisplayName("동일 사용자의 세션 교체 JOIN_ROOM 은 userCount 를 증가시키지 않는다")
    void handleTextMessage_동일사용자세션교체join이면_userCount를증가시키지않는다() throws Exception {
        // given
        KurentoHandler handler = createHandler();
        String payload = """
                {
                  "event":"JOIN_ROOM",
                  "roomId":"room-1",
                  "senderId":"user-1",
                  "senderNickName":"tester"
                }
                """;

        given(redisService.getRedisDataByDataType(eq("room-1"), eq(DataType.CHATROOM), eq(KurentoRoom.class)))
                .willReturn(kurentoRoom);
        lenient().when(kurentoRoom.getKurento()).thenReturn(kurentoClient);
        lenient().when(kurentoClient.createMediaPipeline()).thenReturn(mediaPipeline);
        given(kurentoRoomManager.join(kurentoRoom, "user-1", "tester", session))
                .willReturn(new KurentoJoinResult(activeUser, true));
        given(participantService.getBySessionId(session)).willReturn(null, activeUser);

        // when
        handler.handleTextMessage(session, new TextMessage(payload));

        // then
        verify(kurentoRoomManager).join(kurentoRoom, "user-1", "tester", session);
        verify(redisService, never()).incrementUserCount(kurentoRoom);
        verify(chatKafkaProducer, never()).sendRoomUserCntEvent(kurentoRoom);
    }

    @Test
    @DisplayName("일반 JOIN_ROOM 은 userCount 를 증가시키고 방 인원 이벤트를 발행한다")
    void handleTextMessage_일반join이면_userCount를증가시킨다() throws Exception {
        // given
        KurentoHandler handler = createHandler();
        String payload = """
                {
                  "event":"JOIN_ROOM",
                  "roomId":"room-1",
                  "senderId":"user-1",
                  "senderNickName":"tester"
                }
                """;

        given(redisService.getRedisDataByDataType(eq("room-1"), eq(DataType.CHATROOM), eq(KurentoRoom.class)))
                .willReturn(kurentoRoom);
        lenient().when(kurentoRoom.getKurento()).thenReturn(kurentoClient);
        lenient().when(kurentoClient.createMediaPipeline()).thenReturn(mediaPipeline);
        given(kurentoRoomManager.join(kurentoRoom, "user-1", "tester", session))
                .willReturn(new KurentoJoinResult(activeUser, false));
        given(participantService.getBySessionId(session)).willReturn(activeUser);

        // when
        handler.handleTextMessage(session, new TextMessage(payload));

        // then
        verify(redisService).incrementUserCount(kurentoRoom);
        verify(chatKafkaProducer).sendRoomUserCntEvent(kurentoRoom);
    }

    private KurentoHandler createHandler() {
        return new KurentoHandler(
                kurentoRoomManager,
                kurentoClient,
                redisService,
                participantService,
                chatKafkaProducer,
                recordingService,
                kurentoMessageSender
        );
    }
}
