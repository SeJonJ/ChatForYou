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
import webChat.exception.ChatForYouException;
import webChat.exception.ErrorCode;
import webChat.model.login.OauthRedis;
import webChat.model.room.ChatRoom;
import webChat.security.jwt.JwtRoomProvider;
import webChat.service.chatroom.ChatRoomService;
import webChat.service.routing.RoutingInstanceProvider;
import webChat.service.routing.RoutingService;
import webChat.service.user.UserService;
import webChat.utils.TokenUtils;

import java.util.Map;

import static org.mockito.BDDMockito.doNothing;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ChatRoomControllerTokenRefreshTest {

    private static final String AUTHORIZATION = "Bearer dummy-token";
    private static final String ROOM_TOKEN = "expired-room-token";
    private static final String ROOM_ID = "room-1";
    private static final String EMAIL = "tester@example.com";

    @Mock
    private ChatRoomService chatRoomService;

    @Mock
    private RoutingService routingService;

    @Mock
    private RoutingInstanceProvider instanceProvider;

    @Mock
    private UserService userService;

    @Mock
    private JwtRoomProvider jwtRoomProvider;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ChatRoomController controller = new ChatRoomController(
                chatRoomService,
                routingService,
                instanceProvider,
                userService,
                jwtRoomProvider
        );

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("room token refresh 성공 시 새 room token 을 반환한다")
    void refreshRoomToken_성공시_새RoomToken을반환한다() throws Exception {
        // given
        OauthRedis oauthRedis = createOauthRedis();
        given(userService.getValidatedOauthUser(EMAIL)).willReturn(oauthRedis);
        doNothing().when(jwtRoomProvider).validateRefreshable(ROOM_TOKEN, ROOM_ID, EMAIL);
        given(chatRoomService.refreshRoomToken(EMAIL, ROOM_ID))
                .willReturn(Map.of("token", "new-room-token"));

        // when & then
        try (MockedStatic<TokenUtils> tokenUtils = mockValidToken()) {
            mockMvc.perform(post("/chatforyou/api/chat/room/token/refresh/{roomId}", ROOM_ID)
                            .header("Authorization", AUTHORIZATION)
                            .header("X-Room-Token", ROOM_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.token").value("new-room-token"));
        }
    }

    @Test
    @DisplayName("room token claims 가 현재 사용자와 맞지 않으면 INVALID_ROOM_ACCESS 를 반환한다")
    void refreshRoomToken_roomTokenClaims가현재사용자와맞지않으면_INVALID_ROOM_ACCESS를반환한다() throws Exception {
        // given
        OauthRedis oauthRedis = createOauthRedis();
        given(userService.getValidatedOauthUser(EMAIL)).willReturn(oauthRedis);
        org.mockito.BDDMockito.willThrow(new ChatForYouException(ErrorCode.INVALID_ROOM_ACCESS))
                .given(jwtRoomProvider)
                .validateRefreshable(ROOM_TOKEN, ROOM_ID, EMAIL);

        // when & then
        try (MockedStatic<TokenUtils> tokenUtils = mockValidToken()) {
            mockMvc.perform(post("/chatforyou/api/chat/room/token/refresh/{roomId}", ROOM_ID)
                            .header("Authorization", AUTHORIZATION)
                            .header("X-Room-Token", ROOM_TOKEN))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("R004"));
        }
    }

    @Test
    @DisplayName("존재하지 않는 방이면 ROOM_NOT_FOUND 를 반환한다")
    void refreshRoomToken_존재하지않는방이면_ROOM_NOT_FOUND를반환한다() throws Exception {
        // given
        OauthRedis oauthRedis = createOauthRedis();
        given(userService.getValidatedOauthUser(EMAIL)).willReturn(oauthRedis);
        doNothing().when(jwtRoomProvider).validateRefreshable(ROOM_TOKEN, ROOM_ID, EMAIL);
        given(chatRoomService.refreshRoomToken(EMAIL, ROOM_ID))
                .willThrow(new ChatForYouException(ErrorCode.ROOM_NOT_FOUND));

        // when & then
        try (MockedStatic<TokenUtils> tokenUtils = mockValidToken()) {
            mockMvc.perform(post("/chatforyou/api/chat/room/token/refresh/{roomId}", ROOM_ID)
                            .header("Authorization", AUTHORIZATION)
                            .header("X-Room-Token", ROOM_TOKEN))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("R001"));
        }
    }

    @Test
    @DisplayName("Authorization 헤더가 없으면 UNAUTHORIZED 를 반환한다")
    void refreshRoomToken_Authorization헤더가없으면_UNAUTHORIZED를반환한다() throws Exception {
        // when & then
        mockMvc.perform(post("/chatforyou/api/chat/room/token/refresh/{roomId}", ROOM_ID)
                        .header("X-Room-Token", ROOM_TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A002"));
    }

    @Test
    @DisplayName("X-Room-Token 헤더가 없으면 UNAUTHORIZED 를 반환한다")
    void refreshRoomToken_whenXRoomTokenHeaderMissing_returnsUnauthorized() throws Exception {
        // when & then
        mockMvc.perform(post("/chatforyou/api/chat/room/token/refresh/{roomId}", ROOM_ID)
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A002"));
    }

    @Test
    @DisplayName("비밀방 입장 시 room token 이 만료되면 ROOM_TOKEN_EXPIRED 를 반환한다")
    void joinRoom_whenSecretRoomTokenExpired_returnsRoomTokenExpired() throws Exception {
        // given
        ChatRoom chatRoom = mock(ChatRoom.class);
        OauthRedis oauthRedis = createOauthRedis();
        given(chatRoomService.findRoomById(ROOM_ID)).willReturn(chatRoom);
        given(chatRoom.isSecretChk()).willReturn(true);
        given(chatRoom.getRoomId()).willReturn(ROOM_ID);
        given(userService.getValidatedOauthUser(EMAIL)).willReturn(oauthRedis);
        willThrow(new ChatForYouException(ErrorCode.ROOM_TOKEN_EXPIRED))
                .given(jwtRoomProvider)
                .validate(ROOM_TOKEN, ROOM_ID, EMAIL);

        // when & then
        try (MockedStatic<TokenUtils> tokenUtils = mockValidToken()) {
            mockMvc.perform(get("/chatforyou/api/chat/room/{roomId}", ROOM_ID)
                            .header("Authorization", AUTHORIZATION)
                            .header("X-Room-Token", ROOM_TOKEN))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("R005"));
        }
    }

    private MockedStatic<TokenUtils> mockValidToken() throws Exception {
        FirebaseToken token = mock(FirebaseToken.class);
        given(token.getEmail()).willReturn(EMAIL);

        MockedStatic<TokenUtils> tokenUtils = mockStatic(TokenUtils.class);
        tokenUtils.when(() -> TokenUtils.checkGoogleOAuthToken(AUTHORIZATION)).thenReturn(token);
        return tokenUtils;
    }

    private OauthRedis createOauthRedis() {
        OauthRedis oauthRedis = new OauthRedis();
        oauthRedis.setEmail(EMAIL);
        return oauthRedis;
    }
}
