package webChat.security.jwt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import webChat.exception.ChatForYouException;
import webChat.exception.ErrorCode;
import webChat.security.jwt.fixture.JwtRoomProviderFixture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtRoomProviderTest {

    @Test
    @DisplayName("validate 는 유효한 room token 이면 예외 없이 통과한다")
    void validate_유효한RoomToken이면_예외없이통과한다() {
        // given
        JwtRoomProvider provider = new JwtRoomProvider(
                JwtRoomProviderFixture.ROOM_KEY,
                JwtRoomProviderFixture.VALID_EXPIRE_MS
        );
        String token = provider.create(JwtRoomProviderFixture.ROOM_ID, JwtRoomProviderFixture.USER_ID);

        // when & then
        assertThatCode(() -> provider.validate(token, JwtRoomProviderFixture.ROOM_ID))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validate 는 만료된 room token 이면 ROOM_TOKEN_EXPIRED 를 던진다")
    void validate_만료된RoomToken이면_ROOM_TOKEN_EXPIRED를던진다() {
        // given
        JwtRoomProvider provider = new JwtRoomProvider(
                JwtRoomProviderFixture.ROOM_KEY,
                JwtRoomProviderFixture.EXPIRED_EXPIRE_MS
        );
        String token = provider.create(JwtRoomProviderFixture.ROOM_ID, JwtRoomProviderFixture.USER_ID);

        // when & then
        assertThatThrownBy(() -> provider.validate(token, JwtRoomProviderFixture.ROOM_ID))
                .isInstanceOf(ChatForYouException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ROOM_TOKEN_EXPIRED);
    }

    @Test
    @DisplayName("validate 는 roomId 가 다르면 INVALID_ROOM_ACCESS 를 던진다")
    void validate_roomId가다르면_INVALID_ROOM_ACCESS를던진다() {
        // given
        JwtRoomProvider provider = new JwtRoomProvider(
                JwtRoomProviderFixture.ROOM_KEY,
                JwtRoomProviderFixture.VALID_EXPIRE_MS
        );
        String token = provider.create(JwtRoomProviderFixture.ROOM_ID, JwtRoomProviderFixture.USER_ID);

        // when & then
        assertThatThrownBy(() -> provider.validate(token, JwtRoomProviderFixture.OTHER_ROOM_ID))
                .isInstanceOf(ChatForYouException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_ROOM_ACCESS);
    }

    @Test
    @DisplayName("validate 는 malformed token 이면 INVALID_ROOM_ACCESS 를 던진다")
    void validate_malformedToken이면_INVALID_ROOM_ACCESS를던진다() {
        // given
        JwtRoomProvider provider = new JwtRoomProvider(
                JwtRoomProviderFixture.ROOM_KEY,
                JwtRoomProviderFixture.VALID_EXPIRE_MS
        );

        // when & then
        assertThatThrownBy(() -> provider.validate(
                JwtRoomProviderFixture.MALFORMED_TOKEN,
                JwtRoomProviderFixture.ROOM_ID
        ))
                .isInstanceOf(ChatForYouException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_ROOM_ACCESS);
    }

    @Test
    @DisplayName("validateRefreshable 은 만료된 room token 이고 roomId 와 userId 가 일치하면 통과한다")
    void validateRefreshable_만료된RoomToken이고RoomId와UserId가일치하면_통과한다() {
        // given
        JwtRoomProvider provider = new JwtRoomProvider(
                JwtRoomProviderFixture.ROOM_KEY,
                JwtRoomProviderFixture.EXPIRED_EXPIRE_MS
        );
        String token = provider.create(JwtRoomProviderFixture.ROOM_ID, JwtRoomProviderFixture.USER_ID);

        // when & then
        assertThatCode(() -> provider.validateRefreshable(
                token,
                JwtRoomProviderFixture.ROOM_ID,
                JwtRoomProviderFixture.USER_ID
        )).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateRefreshable 은 userId 가 다르면 INVALID_ROOM_ACCESS 를 던진다")
    void validateRefreshable_userId가다르면_INVALID_ROOM_ACCESS를던진다() {
        // given
        JwtRoomProvider provider = new JwtRoomProvider(
                JwtRoomProviderFixture.ROOM_KEY,
                JwtRoomProviderFixture.EXPIRED_EXPIRE_MS
        );
        String token = provider.create(JwtRoomProviderFixture.ROOM_ID, JwtRoomProviderFixture.USER_ID);

        // when & then
        assertThatThrownBy(() -> provider.validateRefreshable(
                token,
                JwtRoomProviderFixture.ROOM_ID,
                JwtRoomProviderFixture.OTHER_USER_ID
        ))
                .isInstanceOf(ChatForYouException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_ROOM_ACCESS);
    }
}
