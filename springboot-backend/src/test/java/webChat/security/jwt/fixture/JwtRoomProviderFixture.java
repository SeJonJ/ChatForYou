package webChat.security.jwt.fixture;

import io.jsonwebtoken.security.Keys;

import java.security.Key;

public final class JwtRoomProviderFixture {

    public static final Key ROOM_KEY = Keys.hmacShaKeyFor("12345678901234567890123456789012".getBytes());
    public static final String ROOM_ID = "room-1";
    public static final String OTHER_ROOM_ID = "room-2";
    public static final String USER_ID = "user-1";
    public static final String OTHER_USER_ID = "user-2";
    public static final String MALFORMED_TOKEN = "not-a-jwt-token";
    public static final long VALID_EXPIRE_MS = 60_000L;
    public static final long EXPIRED_EXPIRE_MS = -1L;

    private JwtRoomProviderFixture() {
    }
}
