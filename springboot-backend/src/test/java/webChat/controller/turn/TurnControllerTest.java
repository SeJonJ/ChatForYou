package webChat.controller.turn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import webChat.controller.GlobalExceptionHandler;
import webChat.controller.TurnController;
import webChat.controller.turn.fixture.TurnControllerFixture;
import webChat.exception.ChatForYouException;
import webChat.exception.ErrorCode;
import webChat.model.login.OauthRedis;
import webChat.model.room.ChatRoom;
import webChat.model.turn.in.TurnCredentialInVo;
import webChat.model.turn.out.TurnCredentialOutVo;
import webChat.security.jwt.JwtRoomProvider;
import webChat.service.chatroom.ChatRoomService;
import webChat.service.turn.TurnCredentialService;
import webChat.service.user.UserService;
import webChat.utils.TokenUtils;

import java.util.Map;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TurnController HTTP 계층 테스트 (S1-S5, S8 커버).
 *
 * 담당 시나리오:
 * - S1: 공개방 정상 발급 (200 + username 포맷 + credential 비어있지 않음 + ttl 양수)
 * - S2: Authorization 없음/만료/무효 → 401 계열
 * - S3: 비밀방 X-Room-Token 없음/무효 → INVALID_ROOM_ACCESS / ROOM_TOKEN_EXPIRED
 * - S4 (HTTP 관점): 응답 credential 필드가 비어있지 않음 (HMAC 정합은 단위테스트 담당)
 * - S5 (HTTP 관점): username이 만료ts:userId 포맷이며 ttl 양수 응답
 * - 방 없음 → ROOM_NOT_FOUND (404)
 * - roomId @NotBlank 위반 → 400
 * - S8: 비밀값(credential 원문) 에러 응답에 미노출
 *
 * 제외 (단위테스트 중복):
 * - HMAC 재계산값 일치 (TurnCredentialServiceTest 담당)
 * - 2^32 경계 (TurnCredentialServiceTest 담당)
 * - Kurento 50년 만료 (TurnCredentialServiceTest 담당)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TurnControllerTest {

    private static final String URL = "/chatforyou/api/turn/credential";

    @Mock private UserService userService;
    @Mock private ChatRoomService chatRoomService;
    @Mock private JwtRoomProvider jwtRoomProvider;
    @Mock private TurnCredentialService turnCredentialService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        objectMapper = new ObjectMapper();

        TurnController controller = new TurnController(
                userService, chatRoomService, jwtRoomProvider, turnCredentialService
        );

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    // ── S1: 공개방 정상 발급 ──────────────────────────────────────────────────

    @Test
    @DisplayName("S1_issueCredential_공개방_유효OAuth_200_username포맷_credential비어있지않음_ttl양수")
    void issueCredential_publicRoom_validOAuth_returns200WithValidPayload() throws Exception {
        // given
        OauthRedis oauthRedis = TurnControllerFixture.oauthRedis();
        ChatRoom publicRoom = TurnControllerFixture.publicRoom();
        TurnCredentialOutVo outVo = TurnControllerFixture.turnCredentialOutVo();

        given(userService.getValidatedOauthUser(TurnControllerFixture.EMAIL)).willReturn(oauthRedis);
        given(chatRoomService.findRoomById(TurnControllerFixture.PUBLIC_ROOM_ID)).willReturn(publicRoom);
        given(turnCredentialService.issueForBrowser(TurnControllerFixture.EMAIL)).willReturn(outVo);

        String body = objectMapper.writeValueAsString(Map.of("roomId", TurnControllerFixture.PUBLIC_ROOM_ID));

        try (MockedStatic<TokenUtils> tokenUtils = mockValidToken()) {
            // when & then
            mockMvc.perform(post(URL)
                            .header("Authorization", TurnControllerFixture.VALID_AUTHORIZATION)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result").value("SUCCESS"))
                    // username이 "숫자:userId" 포맷
                    .andExpect(jsonPath("$.data.username").value(matchesPattern("^\\d+:.+$")))
                    // credential 비어있지 않음
                    .andExpect(jsonPath("$.data.credential").value(not(emptyString())))
                    // ttl 양수
                    .andExpect(jsonPath("$.data.ttl").value(greaterThan(0)))
                    // urls 배열 2개 (udp/tcp)
                    .andExpect(jsonPath("$.data.urls").isArray())
                    .andExpect(jsonPath("$.data.urls", hasSize(2)));
        }
    }

    @Test
    @DisplayName("S1_issueCredential_공개방_비밀방토큰없어도_공개방이면_200_정상발급")
    void issueCredential_publicRoom_noRoomToken_returns200() throws Exception {
        // given — 공개방은 X-Room-Token 없어도 정상 발급
        OauthRedis oauthRedis = TurnControllerFixture.oauthRedis();
        ChatRoom publicRoom = TurnControllerFixture.publicRoom();
        TurnCredentialOutVo outVo = TurnControllerFixture.turnCredentialOutVo();

        given(userService.getValidatedOauthUser(TurnControllerFixture.EMAIL)).willReturn(oauthRedis);
        given(chatRoomService.findRoomById(TurnControllerFixture.PUBLIC_ROOM_ID)).willReturn(publicRoom);
        given(turnCredentialService.issueForBrowser(TurnControllerFixture.EMAIL)).willReturn(outVo);

        String body = objectMapper.writeValueAsString(Map.of("roomId", TurnControllerFixture.PUBLIC_ROOM_ID));

        try (MockedStatic<TokenUtils> tokenUtils = mockValidToken()) {
            mockMvc.perform(post(URL)
                            .header("Authorization", TurnControllerFixture.VALID_AUTHORIZATION)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result").value("SUCCESS"));
        }

        // 공개방이므로 jwtRoomProvider.validate 가 호출되지 않아야 한다
        verify(jwtRoomProvider, never()).validate(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    // ── S2: Authorization 없음/만료/무효 → 401 계열 ─────────────────────────

    @Test
    @DisplayName("S2_issueCredential_Authorization헤더없음_토큰미발급_401반환")
    void issueCredential_missingAuthorization_returns401() throws Exception {
        // given — Authorization 헤더 자체가 없으면 Spring MVC가 MissingRequestHeaderException 으로 처리
        String body = objectMapper.writeValueAsString(Map.of("roomId", TurnControllerFixture.PUBLIC_ROOM_ID));

        // when & then
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A002"));
    }

    @Test
    @DisplayName("S2_issueCredential_토큰만료_TOKEN_EXPIRED_401반환")
    void issueCredential_expiredToken_returns401WithTokenExpired() throws Exception {
        // given — checkGoogleOAuthToken이 TOKEN_EXPIRED 예외 발생
        String body = objectMapper.writeValueAsString(Map.of("roomId", TurnControllerFixture.PUBLIC_ROOM_ID));

        try (MockedStatic<TokenUtils> tokenUtils = mockStatic(TokenUtils.class)) {
            tokenUtils.when(() -> TokenUtils.checkGoogleOAuthToken(TurnControllerFixture.INVALID_AUTHORIZATION))
                    .thenThrow(new ChatForYouException(ErrorCode.TOKEN_EXPIRED));

            mockMvc.perform(post(URL)
                            .header("Authorization", TurnControllerFixture.INVALID_AUTHORIZATION)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("A003"));
        }
    }

    @Test
    @DisplayName("S2_issueCredential_무효토큰_TOKEN_INVALID_401반환")
    void issueCredential_invalidToken_returns401WithTokenInvalid() throws Exception {
        // given
        String body = objectMapper.writeValueAsString(Map.of("roomId", TurnControllerFixture.PUBLIC_ROOM_ID));

        try (MockedStatic<TokenUtils> tokenUtils = mockStatic(TokenUtils.class)) {
            tokenUtils.when(() -> TokenUtils.checkGoogleOAuthToken(TurnControllerFixture.INVALID_AUTHORIZATION))
                    .thenThrow(new ChatForYouException(ErrorCode.TOKEN_INVALID));

            mockMvc.perform(post(URL)
                            .header("Authorization", TurnControllerFixture.INVALID_AUTHORIZATION)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("A006"))
                    // 에러 응답에 credential/secret 미노출 (S8)
                    .andExpect(jsonPath("$.data.credential").doesNotExist());
        }
    }

    // ── S3: 비밀방 X-Room-Token 검증 ────────────────────────────────────────

    @Test
    @DisplayName("S3_issueCredential_비밀방_RoomToken없음_INVALID_ROOM_ACCESS")
    void issueCredential_secretRoom_missingRoomToken_returnsInvalidRoomAccess() throws Exception {
        // given
        OauthRedis oauthRedis = TurnControllerFixture.oauthRedis();
        ChatRoom secretRoom = TurnControllerFixture.secretRoom();

        given(userService.getValidatedOauthUser(TurnControllerFixture.EMAIL)).willReturn(oauthRedis);
        given(chatRoomService.findRoomById(TurnControllerFixture.SECRET_ROOM_ID)).willReturn(secretRoom);
        // roomToken null → jwtRoomProvider.validate가 INVALID_ROOM_ACCESS 던짐
        willThrow(new ChatForYouException(ErrorCode.INVALID_ROOM_ACCESS))
                .given(jwtRoomProvider).validate(null, TurnControllerFixture.SECRET_ROOM_ID, TurnControllerFixture.EMAIL);

        String body = objectMapper.writeValueAsString(Map.of("roomId", TurnControllerFixture.SECRET_ROOM_ID));

        try (MockedStatic<TokenUtils> tokenUtils = mockValidToken()) {
            mockMvc.perform(post(URL)
                            .header("Authorization", TurnControllerFixture.VALID_AUTHORIZATION)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("R004"));
        }

        // 인증 실패 시 자격증명 발급 미호출 확인
        verify(turnCredentialService, never()).issueForBrowser(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("S3_issueCredential_비밀방_RoomToken무효_INVALID_ROOM_ACCESS")
    void issueCredential_secretRoom_invalidRoomToken_returnsInvalidRoomAccess() throws Exception {
        // given
        OauthRedis oauthRedis = TurnControllerFixture.oauthRedis();
        ChatRoom secretRoom = TurnControllerFixture.secretRoom();

        given(userService.getValidatedOauthUser(TurnControllerFixture.EMAIL)).willReturn(oauthRedis);
        given(chatRoomService.findRoomById(TurnControllerFixture.SECRET_ROOM_ID)).willReturn(secretRoom);
        willThrow(new ChatForYouException(ErrorCode.INVALID_ROOM_ACCESS))
                .given(jwtRoomProvider).validate(
                        TurnControllerFixture.INVALID_ROOM_TOKEN,
                        TurnControllerFixture.SECRET_ROOM_ID,
                        TurnControllerFixture.EMAIL);

        String body = objectMapper.writeValueAsString(Map.of("roomId", TurnControllerFixture.SECRET_ROOM_ID));

        try (MockedStatic<TokenUtils> tokenUtils = mockValidToken()) {
            mockMvc.perform(post(URL)
                            .header("Authorization", TurnControllerFixture.VALID_AUTHORIZATION)
                            .header("X-Room-Token", TurnControllerFixture.INVALID_ROOM_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("R004"))
                    .andExpect(jsonPath("$.data.credential").doesNotExist());
        }

        verify(turnCredentialService, never()).issueForBrowser(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("S3_issueCredential_비밀방_RoomToken만료_ROOM_TOKEN_EXPIRED")
    void issueCredential_secretRoom_expiredRoomToken_returnsRoomTokenExpired() throws Exception {
        // given
        OauthRedis oauthRedis = TurnControllerFixture.oauthRedis();
        ChatRoom secretRoom = TurnControllerFixture.secretRoom();

        given(userService.getValidatedOauthUser(TurnControllerFixture.EMAIL)).willReturn(oauthRedis);
        given(chatRoomService.findRoomById(TurnControllerFixture.SECRET_ROOM_ID)).willReturn(secretRoom);
        willThrow(new ChatForYouException(ErrorCode.ROOM_TOKEN_EXPIRED))
                .given(jwtRoomProvider).validate(
                        TurnControllerFixture.INVALID_ROOM_TOKEN,
                        TurnControllerFixture.SECRET_ROOM_ID,
                        TurnControllerFixture.EMAIL);

        String body = objectMapper.writeValueAsString(Map.of("roomId", TurnControllerFixture.SECRET_ROOM_ID));

        try (MockedStatic<TokenUtils> tokenUtils = mockValidToken()) {
            mockMvc.perform(post(URL)
                            .header("Authorization", TurnControllerFixture.VALID_AUTHORIZATION)
                            .header("X-Room-Token", TurnControllerFixture.INVALID_ROOM_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("R005"));
        }

        verify(turnCredentialService, never()).issueForBrowser(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("S3_issueCredential_비밀방_유효RoomToken_200_정상발급")
    void issueCredential_secretRoom_validRoomToken_returns200() throws Exception {
        // given
        OauthRedis oauthRedis = TurnControllerFixture.oauthRedis();
        ChatRoom secretRoom = TurnControllerFixture.secretRoom();
        TurnCredentialOutVo outVo = TurnControllerFixture.turnCredentialOutVo();

        given(userService.getValidatedOauthUser(TurnControllerFixture.EMAIL)).willReturn(oauthRedis);
        given(chatRoomService.findRoomById(TurnControllerFixture.SECRET_ROOM_ID)).willReturn(secretRoom);
        // 유효 토큰: validate 정상 통과 (예외 없음)
        given(turnCredentialService.issueForBrowser(TurnControllerFixture.EMAIL)).willReturn(outVo);

        String body = objectMapper.writeValueAsString(Map.of("roomId", TurnControllerFixture.SECRET_ROOM_ID));

        try (MockedStatic<TokenUtils> tokenUtils = mockValidToken()) {
            mockMvc.perform(post(URL)
                            .header("Authorization", TurnControllerFixture.VALID_AUTHORIZATION)
                            .header("X-Room-Token", TurnControllerFixture.VALID_ROOM_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.credential").value(not(emptyString())));
        }
    }

    // ── 방 없음 → ROOM_NOT_FOUND ──────────────────────────────────────────────

    @Test
    @DisplayName("issueCredential_존재하지않는방_ROOM_NOT_FOUND_404반환")
    void issueCredential_roomNotFound_returns404() throws Exception {
        // given
        OauthRedis oauthRedis = TurnControllerFixture.oauthRedis();

        given(userService.getValidatedOauthUser(TurnControllerFixture.EMAIL)).willReturn(oauthRedis);
        given(chatRoomService.findRoomById(TurnControllerFixture.NOT_EXIST_ROOM_ID)).willReturn(null);

        String body = objectMapper.writeValueAsString(Map.of("roomId", TurnControllerFixture.NOT_EXIST_ROOM_ID));

        try (MockedStatic<TokenUtils> tokenUtils = mockValidToken()) {
            mockMvc.perform(post(URL)
                            .header("Authorization", TurnControllerFixture.VALID_AUTHORIZATION)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("R001"));
        }

        verify(turnCredentialService, never()).issueForBrowser(org.mockito.ArgumentMatchers.any());
    }

    // ── roomId @NotBlank 위반 → 400 ──────────────────────────────────────────

    @Test
    @DisplayName("issueCredential_roomId누락_NotBlank위반_400반환")
    void issueCredential_missingRoomId_returns400() throws Exception {
        // given — roomId 필드 없는 바디
        String body = "{}";

        try (MockedStatic<TokenUtils> tokenUtils = mockValidToken()) {
            mockMvc.perform(post(URL)
                            .header("Authorization", TurnControllerFixture.VALID_AUTHORIZATION)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("C001"));
        }
    }

    @Test
    @DisplayName("issueCredential_roomId빈문자열_NotBlank위반_400반환")
    void issueCredential_blankRoomId_returns400() throws Exception {
        // given — roomId가 공백 문자열
        String body = objectMapper.writeValueAsString(Map.of("roomId", "   "));

        try (MockedStatic<TokenUtils> tokenUtils = mockValidToken()) {
            mockMvc.perform(post(URL)
                            .header("Authorization", TurnControllerFixture.VALID_AUTHORIZATION)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("C001"));
        }
    }

    // ── S5: username이 만료ts:userId 포맷 + ttl 양수 (HTTP 관점) ──────────────

    @Test
    @DisplayName("S5_issueCredential_username포맷이_만료ts_콜론_userId_이며_ttl이_양수다")
    void issueCredential_usernameFormatAndPositiveTtl() throws Exception {
        // given
        OauthRedis oauthRedis = TurnControllerFixture.oauthRedis();
        ChatRoom publicRoom = TurnControllerFixture.publicRoom();
        TurnCredentialOutVo outVo = TurnControllerFixture.turnCredentialOutVo();

        given(userService.getValidatedOauthUser(TurnControllerFixture.EMAIL)).willReturn(oauthRedis);
        given(chatRoomService.findRoomById(TurnControllerFixture.PUBLIC_ROOM_ID)).willReturn(publicRoom);
        given(turnCredentialService.issueForBrowser(TurnControllerFixture.EMAIL)).willReturn(outVo);

        String body = objectMapper.writeValueAsString(Map.of("roomId", TurnControllerFixture.PUBLIC_ROOM_ID));

        try (MockedStatic<TokenUtils> tokenUtils = mockValidToken()) {
            mockMvc.perform(post(URL)
                            .header("Authorization", TurnControllerFixture.VALID_AUTHORIZATION)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.username").value(matchesPattern("^\\d+:.+$")))
                    .andExpect(jsonPath("$.data.ttl").value(greaterThan(0)))
                    .andExpect(jsonPath("$.data.peerReconnectTimeoutMs").value(greaterThan(0)));
        }
    }

    // ── S8: 에러 응답에 자격증명/비밀값 미노출 ────────────────────────────────

    @Test
    @DisplayName("S8_issueCredential_에러응답에_credential_secret_평문_미포함")
    void issueCredential_errorResponse_doesNotExposeCredentialOrSecret() throws Exception {
        // given — 방 없음 에러 상황에서 에러 응답 body에 credential/secret 관련 필드가 없어야 한다
        OauthRedis oauthRedis = TurnControllerFixture.oauthRedis();
        given(userService.getValidatedOauthUser(TurnControllerFixture.EMAIL)).willReturn(oauthRedis);
        given(chatRoomService.findRoomById(TurnControllerFixture.NOT_EXIST_ROOM_ID)).willReturn(null);

        String body = objectMapper.writeValueAsString(Map.of("roomId", TurnControllerFixture.NOT_EXIST_ROOM_ID));

        try (MockedStatic<TokenUtils> tokenUtils = mockValidToken()) {
            mockMvc.perform(post(URL)
                            .header("Authorization", TurnControllerFixture.VALID_AUTHORIZATION)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.data.credential").doesNotExist())
                    .andExpect(jsonPath("$.data.username").doesNotExist())
                    .andExpect(jsonPath("$.data.urls").doesNotExist());
        }
    }

    // ── URL 배열 포맷 검증 (udp/tcp 2개) ─────────────────────────────────────

    @Test
    @DisplayName("issueCredential_응답urls_udp_tcp_2개포함")
    void issueCredential_responseUrls_containsUdpAndTcp() throws Exception {
        // given
        OauthRedis oauthRedis = TurnControllerFixture.oauthRedis();
        ChatRoom publicRoom = TurnControllerFixture.publicRoom();
        TurnCredentialOutVo outVo = TurnControllerFixture.turnCredentialOutVo();

        given(userService.getValidatedOauthUser(TurnControllerFixture.EMAIL)).willReturn(oauthRedis);
        given(chatRoomService.findRoomById(TurnControllerFixture.PUBLIC_ROOM_ID)).willReturn(publicRoom);
        given(turnCredentialService.issueForBrowser(TurnControllerFixture.EMAIL)).willReturn(outVo);

        String body = objectMapper.writeValueAsString(Map.of("roomId", TurnControllerFixture.PUBLIC_ROOM_ID));

        try (MockedStatic<TokenUtils> tokenUtils = mockValidToken()) {
            mockMvc.perform(post(URL)
                            .header("Authorization", TurnControllerFixture.VALID_AUTHORIZATION)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.urls").isArray())
                    .andExpect(jsonPath("$.data.urls", hasSize(2)))
                    .andExpect(jsonPath("$.data.urls[0]").value(
                            matchesPattern("^turn:.+\\?transport=udp$")))
                    .andExpect(jsonPath("$.data.urls[1]").value(
                            matchesPattern("^turn:.+\\?transport=tcp$")));
        }
    }

    // ── 인증 게이트 순서 — OAuth 통과 후 방 조회 ─────────────────────────────

    @Test
    @DisplayName("issueCredential_OAuth인증실패시_방조회_자격증명발급_미호출")
    void issueCredential_oauthFails_roomAndCredentialNotCalled() throws Exception {
        // given — OAuth 토큰 검증 단계에서 실패
        String body = objectMapper.writeValueAsString(Map.of("roomId", TurnControllerFixture.PUBLIC_ROOM_ID));

        try (MockedStatic<TokenUtils> tokenUtils = mockStatic(TokenUtils.class)) {
            tokenUtils.when(() -> TokenUtils.checkGoogleOAuthToken(TurnControllerFixture.INVALID_AUTHORIZATION))
                    .thenThrow(new ChatForYouException(ErrorCode.TOKEN_INVALID));

            mockMvc.perform(post(URL)
                            .header("Authorization", TurnControllerFixture.INVALID_AUTHORIZATION)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnauthorized());
        }

        // OAuth 게이트에서 막히면 이후 방 조회 및 자격증명 발급이 호출되지 않아야 한다
        verify(chatRoomService, never()).findRoomById(org.mockito.ArgumentMatchers.any());
        verify(turnCredentialService, never()).issueForBrowser(org.mockito.ArgumentMatchers.any());
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private MockedStatic<TokenUtils> mockValidToken() {
        FirebaseToken token = TurnControllerFixture.mockFirebaseToken();
        MockedStatic<TokenUtils> tokenUtils = mockStatic(TokenUtils.class);
        tokenUtils.when(() -> TokenUtils.checkGoogleOAuthToken(TurnControllerFixture.VALID_AUTHORIZATION))
                .thenReturn(token);
        return tokenUtils;
    }
}
