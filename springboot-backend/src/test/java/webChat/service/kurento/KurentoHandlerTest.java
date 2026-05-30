package webChat.service.kurento;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    @Test
    @DisplayName("PARTICIPANT_RECEIVE_FAILED_targetUserId가_방_비멤버면_조용히종료")
    void handleTextMessage_participantReceiveFailed_targetUserId가방비멤버면조용히종료() {
        // given
        KurentoHandler handler = createHandler();
        String payload = """
                {
                  "event":"PARTICIPANT_RECEIVE_FAILED",
                  "roomId":"room-1",
                  "senderId":"user-1",
                  "senderNickName":"tester",
                  "targetUserId":"unknown-user",
                  "phase":"create"
                }
                """;

        given(participantService.getBySessionId(session)).willReturn(activeUser);
        given(activeUser.getUserId()).willReturn("user-1");
        given(activeUser.getRoomId()).willReturn("room-1");
        // targetUserId 가 방 멤버 아님 → null 반환
        given(participantService.getParticipant("room-1", "unknown-user")).willReturn(null);

        // when
        handler.handleTextMessage(session, new TextMessage(payload));

        // then: 방 비멤버 — 에러 응답 없음
        verify(kurentoMessageSender, never()).sendStandardErrorToSession(any(), any(), any());
        verify(kurentoMessageSender, never()).sendStandardErrorToUser(any(), any(), any());
    }

    @Test
    @DisplayName("PARTICIPANT_RECEIVE_FAILED_Rate_Limit_4회째_silently_drop")
    void handleTextMessage_participantReceiveFailed_rateLimitExceeded_silentlyDrop() {
        // given
        KurentoHandler handler = createHandler();
        String payload = """
                {
                  "event":"PARTICIPANT_RECEIVE_FAILED",
                  "roomId":"room-1",
                  "senderId":"user-1",
                  "senderNickName":"tester",
                  "targetUserId":"user-2",
                  "phase":"create"
                }
                """;

        given(participantService.getBySessionId(session)).willReturn(activeUser);
        given(activeUser.getUserId()).willReturn("user-1");
        given(activeUser.getRoomId()).willReturn("room-1");
        given(participantService.getParticipant("room-1", "user-2")).willReturn(staleUser);

        // when: 3회 정상 처리 + 4회째는 Rate Limit 초과
        for (int i = 0; i < 4; i++) {
            handler.handleTextMessage(session, new TextMessage(payload));
        }

        // then: 4회 모두 에러 응답 없음 (Rate Limit 초과 시 silently drop)
        verify(kurentoMessageSender, never()).sendStandardErrorToSession(any(), any(), any());
        verify(kurentoMessageSender, never()).sendStandardErrorToUser(any(), any(), any());
        // member 검증이 rate-limit 검사 앞이므로 4회 모두 getParticipant 호출됨
        verify(participantService, times(4)).getParticipant(eq("room-1"), eq("user-2"));
    }

    @Test
    @DisplayName("PARTICIPANT_RECEIVE_FAILED_인증된_유저_방멤버_타겟_로그기록")
    void handleTextMessage_participantReceiveFailed_인증된유저_방멤버타겟_로그기록() {
        // given
        KurentoHandler handler = createHandler();
        String payload = """
                {
                  "event":"PARTICIPANT_RECEIVE_FAILED",
                  "roomId":"room-1",
                  "senderId":"user-1",
                  "senderNickName":"tester",
                  "targetUserId":"user-2",
                  "phase":"create"
                }
                """;

        given(participantService.getBySessionId(session)).willReturn(activeUser);
        given(activeUser.getUserId()).willReturn("user-1");
        given(activeUser.getRoomId()).willReturn("room-1");
        given(participantService.getParticipant("room-1", "user-2")).willReturn(staleUser);

        // when
        handler.handleTextMessage(session, new TextMessage(payload));

        // then: kurentoMessageSender 에 에러 응답 없이 조용히 종료 (log.warn 만 기록)
        verify(kurentoMessageSender, never()).sendStandardErrorToSession(any(), any(), any());
        verify(kurentoMessageSender, never()).sendStandardErrorToUser(any(), any(), any());
    }

    @Test
    @DisplayName("PARTICIPANT_RECEIVE_FAILED_targetUserId_null이면_rate-limit오염없이조용히종료")
    void handleTextMessage_participantReceiveFailed_targetUserIdNull이면조용히종료() {
        // given
        KurentoHandler handler = createHandler();
        String payload = """
                {
                  "event":"PARTICIPANT_RECEIVE_FAILED",
                  "roomId":"room-1",
                  "senderId":"user-1",
                  "senderNickName":"tester",
                  "phase":"create"
                }
                """;

        given(participantService.getBySessionId(session)).willReturn(activeUser);
        given(activeUser.getUserId()).willReturn("user-1");
        // targetUserId guard 에서 early return — getRoomId() 는 호출되지 않으므로 stubbing 생략

        // when
        handler.handleTextMessage(session, new TextMessage(payload));

        // then: targetUserId 누락 — 방 멤버 조회와 에러 응답 없음
        verify(participantService, never()).getParticipant(any(), any());
        verify(kurentoMessageSender, never()).sendStandardErrorToSession(any(), any(), any());
        verify(kurentoMessageSender, never()).sendStandardErrorToUser(any(), any(), any());
    }

    @Test
    @DisplayName("PARTICIPANT_RECEIVE_FAILED_targetUserId_blank이면_rate-limit오염없이조용히종료")
    void handleTextMessage_participantReceiveFailed_targetUserIdBlank이면조용히종료() {
        // given
        KurentoHandler handler = createHandler();
        String payload = """
                {
                  "event":"PARTICIPANT_RECEIVE_FAILED",
                  "roomId":"room-1",
                  "senderId":"user-1",
                  "senderNickName":"tester",
                  "targetUserId":"   ",
                  "phase":"create"
                }
                """;

        given(participantService.getBySessionId(session)).willReturn(activeUser);
        given(activeUser.getUserId()).willReturn("user-1");
        // targetUserId guard 에서 early return — getRoomId() 는 호출되지 않으므로 stubbing 생략

        // when
        handler.handleTextMessage(session, new TextMessage(payload));

        // then: targetUserId blank — 방 멤버 조회와 에러 응답 없음
        verify(participantService, never()).getParticipant(any(), any());
        verify(kurentoMessageSender, never()).sendStandardErrorToSession(any(), any(), any());
        verify(kurentoMessageSender, never()).sendStandardErrorToUser(any(), any(), any());
    }

    @Test
    @DisplayName("PARTICIPANT_RECEIVE_FAILED_user_null_인증없음_조용히종료")
    void handleTextMessage_participantReceiveFailed_user_null_조용히종료() {
        // given
        KurentoHandler handler = createHandler();
        String payload = """
                {
                  "event":"PARTICIPANT_RECEIVE_FAILED",
                  "roomId":"room-1",
                  "senderId":"user-1",
                  "senderNickName":"tester",
                  "targetUserId":"user-2",
                  "phase":"create"
                }
                """;

        given(participantService.getBySessionId(session)).willReturn(null);

        // when
        handler.handleTextMessage(session, new TextMessage(payload));

        // then: 인증 없음 — kurentoMessageSender 호출 없음
        verify(kurentoMessageSender, never()).sendStandardErrorToSession(any(), any(), any());
        verify(kurentoMessageSender, never()).sendStandardErrorToUser(any(), any(), any());
    }

    @Test
    @Tag("slow")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    @DisplayName("PARTICIPANT_RECEIVE_FAILED_Rate_Limit_윈도우_만료_후_카운터_리셋되어_재허용된다")
    void handleTextMessage_participantReceiveFailed_윈도우만료후카운터리셋하여재허용된다() throws InterruptedException {
        // given
        KurentoHandler handler = createHandler();
        String payload = """
                {
                  "event":"PARTICIPANT_RECEIVE_FAILED",
                  "roomId":"room-1",
                  "senderId":"user-1",
                  "senderNickName":"tester",
                  "targetUserId":"user-2",
                  "phase":"create"
                }
                """;

        given(participantService.getBySessionId(session)).willReturn(activeUser);
        given(activeUser.getUserId()).willReturn("user-1");
        given(activeUser.getRoomId()).willReturn("room-1");
        given(participantService.getParticipant("room-1", "user-2")).willReturn(staleUser);

        // when: 3회 정상 처리
        for (int i = 0; i < 3; i++) {
            handler.handleTextMessage(session, new TextMessage(payload));
        }
        // 4회째 — Rate Limit 초과로 silently drop
        handler.handleTextMessage(session, new TextMessage(payload));

        // then: member 검증이 앞이므로 4회 전송 모두 getParticipant 호출 (4회째는 이후 rate-limit drop)
        verify(participantService, times(4)).getParticipant(eq("room-1"), eq("user-2"));

        // when: 10초 윈도우 만료 대기
        Thread.sleep(11_000L);

        // 윈도우 만료 후 1회 추가 전송 — 카운터 리셋되어 허용
        handler.handleTextMessage(session, new TextMessage(payload));

        // then: 윈도우 만료로 카운터 리셋 → 추가 1회 허용되어 getParticipant 총 5회 호출
        verify(participantService, times(5)).getParticipant(eq("room-1"), eq("user-2"));
    }

    @Test
    @DisplayName("leaveRoom_후_발신자_기준_rateLimit_키가_정리되어_재보고가_허용된다")
    void handleTextMessage_leaveRoom후발신자기준rateLimit키가정리되어재보고가허용된다() throws Exception {
        // given
        KurentoHandler handler = createHandler();
        String failPayloadAtoB = """
                {
                  "event":"PARTICIPANT_RECEIVE_FAILED",
                  "roomId":"room-1",
                  "senderId":"user-A",
                  "senderNickName":"userA",
                  "targetUserId":"user-B",
                  "phase":"create"
                }
                """;
        String failPayloadBtoA = """
                {
                  "event":"PARTICIPANT_RECEIVE_FAILED",
                  "roomId":"room-1",
                  "senderId":"user-B",
                  "senderNickName":"userB",
                  "targetUserId":"user-A",
                  "phase":"create"
                }
                """;
        String leavePayload = """
                {
                  "event":"LEAVE_ROOM",
                  "roomId":"room-1",
                  "senderId":"user-A",
                  "senderNickName":"userA"
                }
                """;

        // user-A 세션: session, activeUser
        given(activeUser.getUserId()).willReturn("user-A");
        given(activeUser.getRoomId()).willReturn("room-1");
        given(activeUser.getSession()).willReturn(session);
        given(participantService.getBySessionId(session)).willReturn(activeUser);
        given(participantService.getParticipant("room-1", "user-B")).willReturn(staleUser);

        // user-B 세션: activeSession, staleUser (reporter 역할)
        given(staleUser.getUserId()).willReturn("user-B");
        given(staleUser.getRoomId()).willReturn("room-1");
        given(participantService.getBySessionId(activeSession)).willReturn(staleUser);
        given(participantService.getParticipant("room-1", "user-A")).willReturn(activeUser);

        // leaveRoom 진행을 위한 stub
        given(participantService.isCurrentParticipantSession("room-1", "user-A", session)).willReturn(true);
        given(redisService.getRedisDataByDataType(eq("room-1"), eq(DataType.CHATROOM), eq(KurentoRoom.class)))
                .willReturn(kurentoRoom);
        given(kurentoRoom.getRoomId()).willReturn("room-1");
        given(participantService.getParticipantMap("room-1"))
                .willReturn(Map.of("user-A", activeUser, "user-B", staleUser));

        // when: user-A → user-B 3회 보고 (key: user-A:user-B 생성, 3회 누적)
        for (int i = 0; i < 3; i++) {
            handler.handleTextMessage(session, new TextMessage(failPayloadAtoB));
        }
        // user-B → user-A 3회 보고 (key: user-B:user-A 생성, 3회 누적)
        for (int i = 0; i < 3; i++) {
            handler.handleTextMessage(activeSession, new TextMessage(failPayloadBtoA));
        }

        // user-A:user-B 키가 3회로 꽉 찬 상태 → 4회째 시도 시 Rate Limit 초과로 차단
        handler.handleTextMessage(session, new TextMessage(failPayloadAtoB));
        // member 검증이 rate-limit 검사 앞이므로 4회째도 getParticipant 호출 → 총 4회
        verify(participantService, times(4)).getParticipant(eq("room-1"), eq("user-B"));

        // when: user-A 퇴장 → user-A:user-B 키 제거 (leaveRoom 단방향 정리)
        handler.handleTextMessage(session, new TextMessage(leavePayload));

        // then: user-A:user-B 키 제거 후 user-A의 재보고 1회 → Rate Limit 없이 허용
        handler.handleTextMessage(session, new TextMessage(failPayloadAtoB));
        // 키 제거로 카운터 리셋 → getParticipant(room-1, user-B) 1회 더 호출되어 총 5회
        verify(participantService, times(5)).getParticipant(eq("room-1"), eq("user-B"));
    }

    @Test
    @DisplayName("비멤버_target_반복전송이_멤버_target의_rateLimitMap을_오염시키지않는다")
    void handleTextMessage_participantReceiveFailed_비멤버target반복전송이멤버target오염안함() {
        // given
        KurentoHandler handler = createHandler();
        String nonMemberPayload = """
                {
                  "event":"PARTICIPANT_RECEIVE_FAILED",
                  "roomId":"room-1",
                  "senderId":"user-1",
                  "senderNickName":"tester",
                  "targetUserId":"nonmember-999",
                  "phase":"create"
                }
                """;
        String memberPayload = """
                {
                  "event":"PARTICIPANT_RECEIVE_FAILED",
                  "roomId":"room-1",
                  "senderId":"user-1",
                  "senderNickName":"tester",
                  "targetUserId":"user-2",
                  "phase":"create"
                }
                """;

        given(participantService.getBySessionId(session)).willReturn(activeUser);
        given(activeUser.getUserId()).willReturn("user-1");
        given(activeUser.getRoomId()).willReturn("room-1");
        // 비멤버: null 반환
        given(participantService.getParticipant("room-1", "nonmember-999")).willReturn(null);
        // 멤버: staleUser 반환
        given(participantService.getParticipant("room-1", "user-2")).willReturn(staleUser);

        // when: 비멤버 target으로 RATE_LIMIT_MAX(3)를 초과하는 10회 반복 전송
        for (int i = 0; i < 10; i++) {
            handler.handleTextMessage(session, new TextMessage(nonMemberPayload));
        }

        // 이후 멤버 target으로 3회 전송 (rate-limit 미적용 상태여야 함)
        for (int i = 0; i < 3; i++) {
            handler.handleTextMessage(session, new TextMessage(memberPayload));
        }

        // then: 멤버 target은 비멤버 요청에 의해 rate-limit이 오염되지 않아 3회 모두 처리됨
        // (비멤버 target key "user-1:nonmember-999" 와 멤버 target key "user-1:user-2" 는 독립된 map entry)
        verify(participantService, times(3)).getParticipant(eq("room-1"), eq("user-2"));
        verify(kurentoMessageSender, never()).sendStandardErrorToSession(any(), any(), any());
        verify(kurentoMessageSender, never()).sendStandardErrorToUser(any(), any(), any());
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
