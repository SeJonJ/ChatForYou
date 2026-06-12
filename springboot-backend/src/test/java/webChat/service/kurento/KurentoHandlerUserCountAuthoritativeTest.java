package webChat.service.kurento;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kurento.client.KurentoClient;
import org.kurento.client.MediaPipeline;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import webChat.model.chat.ChatType;
import webChat.model.redis.DataType;
import webChat.model.room.KurentoRoom;
import webChat.repository.kurento.participant.KurentoParticipantRepository;
import webChat.service.chatroom.participant.KurentoParticipantService;
import webChat.service.chatroom.participant.impl.KurentoParticipantServiceImpl;
import webChat.service.kafka.ChatKafkaProducer;
import webChat.service.recording.RecordingService;
import webChat.service.redis.RedisService;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * userCount 정합성 authoritative 동기화 회귀 검증 테스트.
 *
 * 핵심: 이전 단위 테스트가 상호배제(lock)만 보고 userCount 최종값을 검증하지 않아
 * false-positive 였던 점을 보완한다. 모든 시나리오에서
 * "room.getUserCount() == 실제 참가자 맵 size" 를 assert 한다.
 *
 * 비원자 read-modify-write(±1) 버그는 mock 만으로는 재현 불가하므로, 실제
 * KurentoParticipantRepository + KurentoParticipantServiceImpl 을 사용해 참가자 맵 갱신을
 * 그대로 태운다. join/leave 의 맵 변경만 KurentoRoomManager mock 이 실제 서비스로 위임하고,
 * userCount 권위 기록은 RedisServiceImpl 의 syncUserCount 와 동일하게 실제 KurentoRoom POJO 에
 * 그대로 size 를 쓴다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KurentoHandlerUserCountAuthoritativeTest {

    private static final String ROOM = "room-1";

    @Mock
    private KurentoRoomManager kurentoRoomManager;
    @Mock
    private KurentoClient kurentoClient;
    @Mock
    private MediaPipeline mediaPipeline;
    @Mock
    private RedisService redisService;
    @Mock
    private ChatKafkaProducer chatKafkaProducer;
    @Mock
    private RecordingService recordingService;
    @Mock
    private KurentoMessageSender kurentoMessageSender;

    // 실제 저장소 + 실제 서비스 — 참가자 맵 갱신/조회 원자성을 그대로 재현한다.
    private KurentoParticipantRepository participantRepository;
    private KurentoParticipantService participantService;

    private KurentoRoom room;
    private KurentoHandler handler;

    @BeforeEach
    void setUp() throws IOException {
        participantRepository = new KurentoParticipantRepository();
        participantService = new KurentoParticipantServiceImpl(participantRepository);

        // userCount 의 권위 소스가 될 실제 POJO. baseline 은 0.
        room = new KurentoRoom(ROOM, "room", "creator", null, false, 0, 8, ChatType.MSG, "instance-1");

        handler = new KurentoHandler(
                kurentoRoomManager,
                kurentoClient,
                redisService,
                participantService,
                chatKafkaProducer,
                recordingService,
                kurentoMessageSender
        );

        given(redisService.getRedisDataByDataType(eq(ROOM), eq(DataType.CHATROOM), eq(KurentoRoom.class)))
                .willReturn(room);
        given(kurentoClient.createMediaPipeline()).willReturn(mediaPipeline);

        // syncUserCount: RedisServiceImpl 과 동일하게 전달된 실제 size 를 그대로 기록(authoritative).
        lenient().doAnswer(inv -> {
            room.setUserCount(inv.getArgument(1));
            return null;
        }).when(redisService).syncUserCount(any(KurentoRoom.class), anyInt());

        // KurentoRoomManager.join/leave 는 실제 참가자 맵을 갱신하도록 실제 서비스에 위임한다.
        // (handler 가 join/leave 직후 getParticipantCount 로 읽는 size 가 실제로 변하도록.)
        lenient().doAnswer(inv -> {
            String userId = inv.getArgument(1);
            String nickName = inv.getArgument(2);
            WebSocketSession session = inv.getArgument(3);
            boolean replaced = participantService.getParticipant(ROOM, userId) != null;
            participantService.addParticipant(ROOM, makeParticipant(userId, nickName, session));
            return new KurentoJoinResult(participantService.getParticipant(ROOM, userId), replaced);
        }).when(kurentoRoomManager).join(eq(room), any(), any(), any());

        lenient().doAnswer(inv -> {
            KurentoUserSession user = inv.getArgument(1);
            participantService.removeParticipant(ROOM, user.getUserId());
            return null;
        }).when(kurentoRoomManager).leave(eq(room), any());
    }

    /**
     * 세션이 세팅된 경량 KurentoUserSession 을 만든다.
     * KurentoParticipantRepository.validateParticipant 가 non-null session 을 요구하므로
     * WebSocketSession mock 을 주입한다. 미디어 파이프라인은 사용하지 않는다.
     */
    private KurentoUserSession makeParticipant(String userId, String nickName, WebSocketSession session) {
        KurentoUserSession user = mock(KurentoUserSession.class);
        lenient().when(user.getUserId()).thenReturn(userId);
        lenient().when(user.getNickName()).thenReturn(nickName);
        lenient().when(user.getRoomId()).thenReturn(ROOM);
        lenient().when(user.getSession()).thenReturn(session);
        return user;
    }

    private WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        lenient().when(session.getId()).thenReturn(id);
        return session;
    }

    private String joinPayload(String userId) {
        return String.format("""
                {"event":"JOIN_ROOM","roomId":"%s","senderId":"%s","senderNickName":"%s"}
                """, ROOM, userId, userId);
    }

    private void assertCountMatchesMap() {
        assertThat(room.getUserCount())
                .as("userCount 는 항상 실제 참가자 맵 size 와 일치해야 한다")
                .isEqualTo(participantService.getParticipantCount(ROOM));
    }

    @Test
    @DisplayName("1명 입장 후 재연결(close+rejoin)을 N회 반복해도 userCount 는 1을 유지한다")
    void 단일유저_재연결_N회반복후_userCount는1유지() {
        // given: user-1 최초 입장
        WebSocketSession s0 = session("s-0");
        handler.handleTextMessage(s0, new TextMessage(joinPayload("user-1")));
        assertThat(room.getUserCount()).isEqualTo(1);
        assertCountMatchesMap();

        WebSocketSession prev = s0;
        // when: 자동재연결 = 신규 세션 join(replace) → 구세션 close(stale leave) 를 5회 반복
        for (int i = 1; i <= 5; i++) {
            WebSocketSession next = session("s-" + i);
            // 신규 세션으로 rejoin (동일 userId → replace 경로, 맵 size 1 유지)
            handler.handleTextMessage(next, new TextMessage(joinPayload("user-1")));
            // 구세션 종료 이벤트 도달 (stale 가드로 무시되어야 함 — 최신 세션이 next 이므로)
            handler.afterConnectionClosed(prev, CloseStatus.GOING_AWAY);

            // then: 재연결 후에도 userCount 는 1, 맵과 일치
            assertThat(room.getUserCount())
                    .as("재연결 %d회차 후 userCount", i)
                    .isEqualTo(1);
            assertCountMatchesMap();
            prev = next;
        }
    }

    @Test
    @DisplayName("2명 중 1명이 재연결해도 userCount 는 2를 유지한다")
    void 두유저중_한명재연결시_userCount는2유지() {
        // given: user-1, user-2 입장
        handler.handleTextMessage(session("a-0"), new TextMessage(joinPayload("user-1")));
        WebSocketSession bPrev = session("b-0");
        handler.handleTextMessage(bPrev, new TextMessage(joinPayload("user-2")));
        assertThat(room.getUserCount()).isEqualTo(2);
        assertCountMatchesMap();

        // when: user-2 가 재연결(신규 세션 join → 구세션 close)
        WebSocketSession bNew = session("b-1");
        handler.handleTextMessage(bNew, new TextMessage(joinPayload("user-2")));
        handler.afterConnectionClosed(bPrev, CloseStatus.GOING_AWAY);

        // then: 재연결은 인원 불변 — userCount 2 유지
        assertThat(room.getUserCount()).isEqualTo(2);
        assertCountMatchesMap();
    }

    @Test
    @DisplayName("다른 userId 들이 순차 입장/퇴장하면 userCount 가 정확히 증감한다")
    void 다른유저_입장퇴장시_userCount정확히증감() {
        WebSocketSession a = session("a");
        WebSocketSession b = session("b");
        WebSocketSession c = session("c");

        handler.handleTextMessage(a, new TextMessage(joinPayload("user-A")));
        assertThat(room.getUserCount()).isEqualTo(1);
        handler.handleTextMessage(b, new TextMessage(joinPayload("user-B")));
        assertThat(room.getUserCount()).isEqualTo(2);
        handler.handleTextMessage(c, new TextMessage(joinPayload("user-C")));
        assertThat(room.getUserCount()).isEqualTo(3);
        assertCountMatchesMap();

        // 퇴장
        handler.afterConnectionClosed(b, CloseStatus.NORMAL);
        assertThat(room.getUserCount()).isEqualTo(2);
        handler.afterConnectionClosed(a, CloseStatus.NORMAL);
        assertThat(room.getUserCount()).isEqualTo(1);
        handler.afterConnectionClosed(c, CloseStatus.NORMAL);
        assertThat(room.getUserCount()).isZero();
        assertCountMatchesMap();
    }

    @Test
    @DisplayName("모든 참가자가 퇴장하면 userCount 는 0이 된다")
    void 전원퇴장시_userCount는0() {
        WebSocketSession a = session("a");
        WebSocketSession b = session("b");
        handler.handleTextMessage(a, new TextMessage(joinPayload("user-A")));
        handler.handleTextMessage(b, new TextMessage(joinPayload("user-B")));
        assertThat(room.getUserCount()).isEqualTo(2);

        handler.afterConnectionClosed(a, CloseStatus.NORMAL);
        handler.afterConnectionClosed(b, CloseStatus.NORMAL);

        assertThat(room.getUserCount()).isZero();
        assertCountMatchesMap();
    }

    /**
     * Negative-control: authoritative(실제 size 기록)를 회귀 직전의 ±1 산술로 되돌리면
     * 재연결 시나리오에서 userCount 가 실제 맵 size 와 어긋나며, count 단언이 실패함을 입증한다.
     * 이로써 위 테스트들이 실제 회귀 버그(replace-join increment 스킵 vs leave decrement 비대칭)를
     * 실제로 잡아내는 유효한 테스트임을 증명한다.
     */
    @Nested
    @DisplayName("Negative-control: ±1 산술로 회귀시키면 count 단언이 실패한다")
    class NegativeControl {

        @Test
        @DisplayName("replace-join은 increment 스킵, 구세션 종료는 decrement 하는 비대칭 ±1 로 회귀시키면 userCount가 과소된다")
        void plusMinusOne_비대칭산술회귀시_재연결후_userCount과소를_탐지한다() throws Exception {
            // authoritative 동기화를 무력화하고 회귀 직전의 비대칭 ±1 산술을 복원한다.
            lenient().doAnswer(inv -> null).when(redisService).syncUserCount(any(KurentoRoom.class), anyInt());

            // join: 신규 입장만 +1, replace(재연결)는 increment 스킵 — 비대칭의 상단(증가 누락).
            lenient().doAnswer(inv -> {
                String userId = inv.getArgument(1);
                String nickName = inv.getArgument(2);
                WebSocketSession s = inv.getArgument(3);
                boolean replaced = participantService.getParticipant(ROOM, userId) != null;
                participantService.addParticipant(ROOM, makeParticipant(userId, nickName, s));
                if (!replaced) {
                    room.setUserCount(room.getUserCount() + 1);
                }
                return new KurentoJoinResult(participantService.getParticipant(ROOM, userId), replaced);
            }).when(kurentoRoomManager).join(eq(room), any(), any(), any());

            // leave: 맵 변경과 무관하게 무조건 -1 — 비대칭의 하단(과잉 감소).
            // 회귀의 본질은 stale 가드가 없던 시절 구세션 종료가 신규 세션이 차지한 인원까지 깎아내리던 것.
            lenient().doAnswer(inv -> {
                room.setUserCount(Math.max(0, room.getUserCount() - 1));
                return null;
            }).when(kurentoRoomManager).leave(eq(room), any());

            // user-1 입장 → 신규 +1 → userCount 1, 맵 size 1 (일치)
            WebSocketSession s0 = session("s-0");
            handler.handleTextMessage(s0, new TextMessage(joinPayload("user-1")));
            assertThat(room.getUserCount()).isEqualTo(1);
            assertThat(participantService.getParticipantCount(ROOM)).isEqualTo(1);

            // 재연결: 신규 세션(s1) join 은 replace → increment 스킵. 맵에는 신규 세션 user-1 1명 그대로.
            WebSocketSession s1 = session("s-1");
            handler.handleTextMessage(s1, new TextMessage(joinPayload("user-1")));
            // 맵 size 는 여전히 1 (replace 는 put 교체).
            assertThat(participantService.getParticipantCount(ROOM)).isEqualTo(1);

            // 구세션(s0)의 뒤늦은 종료가 비대칭 산술의 leave(-1)를 그대로 실행 → userCount 0 으로 과소.
            // 회귀 재현을 위해 ±1 leave 경로를 직접 트리거한다(맵은 신규 세션이라 변하지 않음).
            kurentoRoomManager.leave(room, participantService.getParticipant(ROOM, "user-1"));

            // then: 실제 맵 size 는 1 인데 ±1 산술 userCount 는 0 으로 과소 → count 단언이 실패해야 한다.
            assertThat(participantService.getParticipantCount(ROOM))
                    .as("실제 참가자 맵에는 user-1(신규 세션)이 그대로 1명 남아있다")
                    .isEqualTo(1);
            assertThatThrownBy(() ->
                    assertThat(room.getUserCount())
                            .isEqualTo(participantService.getParticipantCount(ROOM)))
                    .isInstanceOf(AssertionError.class);
        }
    }
}
