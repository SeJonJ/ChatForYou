package webChat.controller.turn.fixture;

import com.google.firebase.auth.FirebaseToken;
import webChat.model.login.OauthRedis;
import webChat.model.room.ChatRoom;
import webChat.model.turn.out.TurnCredentialOutVo;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * TurnControllerTest 공용 Fixture.
 * 테스트 데이터 생성 책임을 중앙화한다.
 */
public final class TurnControllerFixture {

    public static final String VALID_AUTHORIZATION = "Bearer valid-oauth-token";
    public static final String INVALID_AUTHORIZATION = "Bearer invalid-token";
    public static final String EMAIL = "tester@example.com";
    public static final String EMAIL_WITH_COLON = "tester:sub@example.com";
    public static final String PUBLIC_ROOM_ID = "public-room-1";
    public static final String SECRET_ROOM_ID = "secret-room-1";
    public static final String NOT_EXIST_ROOM_ID = "not-exist-room";
    public static final String VALID_ROOM_TOKEN = "valid-room-jwt-token";
    public static final String INVALID_ROOM_TOKEN = "invalid-room-jwt-token";

    // 발급된 자격증명의 username 포맷 검증용 상수 — 실제 secret 값은 노출하지 않는다
    public static final long EXPECTED_TTL = 3600L;
    public static final long EXPECTED_PEER_RECONNECT_MS = 300000L;
    public static final String EXPECTED_TURN_HOST = "turn.test.local";
    public static final int EXPECTED_TURN_PORT = 33488;
    // 테스트용 더미 credential — 실제 static-auth-secret 으로 계산된 값이 아님, 비노출 보장
    public static final String DUMMY_CREDENTIAL = "dummyBase64CredentialNotRealSecret=";

    private TurnControllerFixture() {
    }

    public static FirebaseToken mockFirebaseToken() {
        FirebaseToken token = mock(FirebaseToken.class);
        given(token.getEmail()).willReturn(EMAIL);
        return token;
    }

    public static OauthRedis oauthRedis() {
        OauthRedis oauthRedis = new OauthRedis();
        oauthRedis.setEmail(EMAIL);
        oauthRedis.setIdx(1L);
        return oauthRedis;
    }

    public static ChatRoom publicRoom() {
        ChatRoom room = mock(ChatRoom.class);
        given(room.getRoomId()).willReturn(PUBLIC_ROOM_ID);
        given(room.isSecretChk()).willReturn(false);
        return room;
    }

    public static ChatRoom secretRoom() {
        ChatRoom room = mock(ChatRoom.class);
        given(room.getRoomId()).willReturn(SECRET_ROOM_ID);
        given(room.isSecretChk()).willReturn(true);
        return room;
    }

    public static TurnCredentialOutVo turnCredentialOutVo() {
        return TurnCredentialOutVo.builder()
                .urls(List.of(
                        "turn:" + EXPECTED_TURN_HOST + ":" + EXPECTED_TURN_PORT + "?transport=udp",
                        "turn:" + EXPECTED_TURN_HOST + ":" + EXPECTED_TURN_PORT + "?transport=tcp"
                ))
                .username("1718812800:" + EMAIL)
                .credential(DUMMY_CREDENTIAL)
                .ttl(EXPECTED_TTL)
                .peerReconnectTimeoutMs(EXPECTED_PEER_RECONNECT_MS)
                .build();
    }
}
