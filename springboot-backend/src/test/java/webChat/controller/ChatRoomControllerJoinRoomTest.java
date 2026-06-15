package webChat.controller;

import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import webChat.model.chat.ChatType;
import webChat.model.login.OauthRedis;
import webChat.model.room.ChatRoom;
import webChat.model.room.recovery.RecoveryDecision;
import webChat.model.room.recovery.RecoveryReason;
import webChat.model.routing.RoomRoutingInfo;
import webChat.model.user.UserDto;
import webChat.security.jwt.JwtRoomProvider;
import webChat.service.chatroom.ChatRoomService;
import webChat.service.chatroom.recovery.ChatRoomRecoveryService;
import webChat.service.redis.RedisService;
import webChat.service.routing.RoutingInstanceProvider;
import webChat.service.routing.RoutingService;
import webChat.service.user.UserService;
import webChat.utils.TokenUtils;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ChatRoomController.joinRoom() 참가자 멤버십 기록 검증.
 *
 * FR-1: RTC 성공 분기에서만 addRoomMember 1회 호출.
 * redirect/MSG 분기에서는 addRoomMember 를 호출하지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class ChatRoomControllerJoinRoomTest {

    private static final String AUTHORIZATION = "Bearer dummy-token";
    private static final String ROOM_ID = "room-1";
    private static final String EMAIL = "tester@example.com";
    private static final String INSTANCE_ID = "instance-1";

    @Mock private ChatRoomService chatRoomService;
    @Mock private RoutingService routingService;
    @Mock private RoutingInstanceProvider instanceProvider;
    @Mock private UserService userService;
    @Mock private JwtRoomProvider jwtRoomProvider;
    @Mock private RedisService redisService;
    @Mock private ChatRoomRecoveryService chatRoomRecoveryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ChatRoomController controller = new ChatRoomController(
                chatRoomService,
                routingService,
                instanceProvider,
                userService,
                jwtRoomProvider,
                redisService,
                chatRoomRecoveryService
        );

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ── RTC 성공 분기 — addRoomMember 1회 호출 ────────────────────────────

    @Test
    @DisplayName("joinRoom RTC 성공 분기에서 addRoomMember 가 1회 호출된다 (FR-1)")
    void joinRoom_RTC성공_멤버십기록() throws Exception {
        // given — 공개방, 현재 인스턴스 정상, RTC 타입
        OauthRedis oauthRedis = createOauthRedis();
        ChatRoom rtcRoom = rtcRoom(ROOM_ID, INSTANCE_ID);
        // ofJoinRoom → ChatRoomOutVo.ofJoin 에서 userDto.getUserId() 를 호출하므로 null 방지
        UserDto userDto = UserDto.builder().userId(EMAIL).nickName("tester").build();

        given(userService.getValidatedOauthUser(EMAIL)).willReturn(oauthRedis);
        given(chatRoomService.findRoomById(ROOM_ID)).willReturn(rtcRoom);
        given(instanceProvider.isHealthy(INSTANCE_ID)).willReturn(true);
        given(instanceProvider.getInstanceId()).willReturn(INSTANCE_ID);
        given(userService.getUserInfo(oauthRedis)).willReturn(userDto);

        try (MockedStatic<TokenUtils> tokenUtils = mockValidToken()) {
            // when
            mockMvc.perform(get("/chatforyou/api/chat/room/{roomId}", ROOM_ID)
                            .header("Authorization", AUTHORIZATION))
                    .andExpect(status().isOk());
        }

        // then — SADD 는 RTC 성공 분기에서만 1회
        verify(redisService, times(1)).addRoomMember(ROOM_ID, EMAIL);
    }

    // ── 비정상 인스턴스 분기 — addRoomMember 미호출 ───────────────────────

    @Test
    @DisplayName("비정상 인스턴스 → REDIRECT_DASHBOARD 분기에서 addRoomMember 가 호출되지 않는다 (FR-1)")
    void joinRoom_instanceUnhealthy_redirect_멤버십미기록() throws Exception {
        // given — 인스턴스 비정상 → delChatRoom + REDIRECT_DASHBOARD 반환
        OauthRedis oauthRedis = createOauthRedis();
        ChatRoom rtcRoom = rtcRoom(ROOM_ID, INSTANCE_ID);

        given(userService.getValidatedOauthUser(EMAIL)).willReturn(oauthRedis);
        given(chatRoomService.findRoomById(ROOM_ID)).willReturn(rtcRoom);
        // 비정상 인스턴스 조건
        given(instanceProvider.isHealthy(INSTANCE_ID)).willReturn(false);
        given(chatRoomRecoveryService.evaluateJoinRecovery(rtcRoom))
                .willReturn(RecoveryDecision.notRecoverable(RecoveryReason.NOT_RECOVERABLE));

        try (MockedStatic<TokenUtils> tokenUtils = mockValidToken()) {
            // when
            mockMvc.perform(get("/chatforyou/api/chat/room/{roomId}", ROOM_ID)
                            .header("Authorization", AUTHORIZATION))
                    .andExpect(status().isOk());
        }

        // then — 리다이렉트 분기에서는 SADD 미호출
        verify(redisService, never()).addRoomMember(anyString(), anyString());
    }

    // ── 인스턴스 불일치 분기 — addRoomMember 미호출 ───────────────────────

    @Test
    @DisplayName("인스턴스 불일치 → REDIRECT_ROOM 분기에서 addRoomMember 가 호출되지 않는다 (FR-1)")
    void joinRoom_instanceMismatch_redirect_멤버십미기록() throws Exception {
        // given — 인스턴스 정상이지만 현재 인스턴스와 불일치 → REDIRECT_ROOM
        OauthRedis oauthRedis = createOauthRedis();
        ChatRoom rtcRoom = rtcRoom(ROOM_ID, "instance-other");
        // routingService.setRoutingInfo 에서 roomRoutingInfo.getRoomId() 호출 → null 방지
        RoomRoutingInfo roomRoutingInfo = mock(RoomRoutingInfo.class);
        given(roomRoutingInfo.getRoomId()).willReturn(ROOM_ID);
        given(roomRoutingInfo.getNginxCookie()).willReturn("nginx-cookie");

        given(userService.getValidatedOauthUser(EMAIL)).willReturn(oauthRedis);
        given(chatRoomService.findRoomById(ROOM_ID)).willReturn(rtcRoom);
        given(instanceProvider.isHealthy("instance-other")).willReturn(true);
        // 현재 인스턴스 != 방 인스턴스 → 불일치 분기
        given(instanceProvider.getInstanceId()).willReturn(INSTANCE_ID);
        given(routingService.getRedirectCount(any())).willReturn(0);
        given(routingService.getRoomRoutingInfoByRoomId(ROOM_ID)).willReturn(roomRoutingInfo);

        try (MockedStatic<TokenUtils> tokenUtils = mockValidToken()) {
            // when
            mockMvc.perform(get("/chatforyou/api/chat/room/{roomId}", ROOM_ID)
                            .header("Authorization", AUTHORIZATION))
                    .andExpect(status().isOk());
        }

        // then — 인스턴스 불일치 리다이렉트에서는 SADD 미호출
        // 대상 인스턴스의 joinRoom 재호출 시 거기서 기록됨 (02-design §1.2)
        verify(redisService, never()).addRoomMember(anyString(), anyString());
    }

    // ── slave 복제 지연 오판 방어 — master 재확인 후 정상 입장 ────────────────

    @Test
    @DisplayName("stale slave 가 owner-unhealthy 로 오판해도 master 정상 owner 면 복구 없이 RTC 입장한다 (recover-reconnect 루프 방지)")
    void joinRoom_staleSlaveUnhealthy_masterHealthy_정상입장() throws Exception {
        // given — slave 는 죽은 owner 를, master 는 복구된 현재 인스턴스를 가리킨다
        OauthRedis oauthRedis = createOauthRedis();
        ChatRoom slaveRoom = mock(ChatRoom.class);
        given(slaveRoom.isSecretChk()).willReturn(false);
        given(slaveRoom.getInstanceId()).willReturn("instance-dead");

        ChatRoom masterRoom = mock(ChatRoom.class);
        given(masterRoom.getInstanceId()).willReturn(INSTANCE_ID);
        given(masterRoom.getChatType()).willReturn(ChatType.RTC);

        UserDto userDto = UserDto.builder().userId(EMAIL).nickName("tester").build();

        given(userService.getValidatedOauthUser(EMAIL)).willReturn(oauthRedis);
        given(chatRoomService.findRoomById(ROOM_ID)).willReturn(slaveRoom);
        given(instanceProvider.isHealthy("instance-dead")).willReturn(false);
        given(redisService.getChatRoomFromMaster(ROOM_ID)).willReturn(masterRoom);
        given(instanceProvider.isHealthy(INSTANCE_ID)).willReturn(true);
        given(instanceProvider.getInstanceId()).willReturn(INSTANCE_ID);
        given(userService.getUserInfo(oauthRedis)).willReturn(userDto);

        try (MockedStatic<TokenUtils> tokenUtils = mockValidToken()) {
            // when
            mockMvc.perform(get("/chatforyou/api/chat/room/{roomId}", ROOM_ID)
                            .header("Authorization", AUTHORIZATION))
                    .andExpect(status().isOk());
        }

        // then — master 가 정상 owner 를 보이므로 복구 판정/방 삭제 없이 RTC 멤버십을 기록한다
        verify(chatRoomRecoveryService, never()).evaluateJoinRecovery(any());
        verify(chatRoomService, never()).delChatRoom(anyString(), anyBoolean());
        verify(redisService, times(1)).addRoomMember(ROOM_ID, EMAIL);
    }

    // ── MSG 타입 분기 — addRoomMember 미호출 ──────────────────────────────

    @Test
    @DisplayName("ChatType.MSG 분기에서 addRoomMember 가 호출되지 않는다 (FR-1, D-A 결정)")
    void joinRoom_MSG타입_멤버십미기록() throws Exception {
        // given — MSG 타입 방: 녹화 무관이므로 ledger 불필요
        OauthRedis oauthRedis = createOauthRedis();
        ChatRoom msgRoom = msgRoom(ROOM_ID, INSTANCE_ID);

        given(userService.getValidatedOauthUser(EMAIL)).willReturn(oauthRedis);
        given(chatRoomService.findRoomById(ROOM_ID)).willReturn(msgRoom);
        given(instanceProvider.isHealthy(INSTANCE_ID)).willReturn(true);
        given(instanceProvider.getInstanceId()).willReturn(INSTANCE_ID);

        try (MockedStatic<TokenUtils> tokenUtils = mockValidToken()) {
            // when
            mockMvc.perform(get("/chatforyou/api/chat/room/{roomId}", ROOM_ID)
                            .header("Authorization", AUTHORIZATION))
                    .andExpect(status().isOk());
        }

        // then — MSG 타입은 녹화 ledger 와 무관하므로 SADD 미호출
        verify(redisService, never()).addRoomMember(anyString(), anyString());
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    private MockedStatic<TokenUtils> mockValidToken() {
        FirebaseToken token = mock(FirebaseToken.class);
        given(token.getEmail()).willReturn(EMAIL);

        MockedStatic<TokenUtils> tokenUtils = mockStatic(TokenUtils.class);
        tokenUtils.when(() -> TokenUtils.checkGoogleOAuthToken(AUTHORIZATION)).thenReturn(token);
        return tokenUtils;
    }

    private OauthRedis createOauthRedis() {
        OauthRedis oauthRedis = new OauthRedis();
        oauthRedis.setEmail(EMAIL);
        oauthRedis.setIdx(1L);
        return oauthRedis;
    }

    private ChatRoom rtcRoom(String roomId, String instanceId) {
        ChatRoom room = mock(ChatRoom.class);
        given(room.getRoomId()).willReturn(roomId);
        given(room.getInstanceId()).willReturn(instanceId);
        given(room.isSecretChk()).willReturn(false);
        given(room.getChatType()).willReturn(ChatType.RTC);
        return room;
    }

    private ChatRoom msgRoom(String roomId, String instanceId) {
        ChatRoom room = mock(ChatRoom.class);
        // getRoomId() stub 제외: MSG 분기에서 ofSuccess(null) 반환 시 roomId 미참조
        given(room.getInstanceId()).willReturn(instanceId);
        given(room.isSecretChk()).willReturn(false);
        given(room.getChatType()).willReturn(ChatType.MSG);
        return room;
    }
}
