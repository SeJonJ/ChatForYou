package webChat.utils;

import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuthException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import webChat.exception.ChatForYouException;
import webChat.exception.ErrorCode;
import webChat.utils.fixture.FirebaseTokenValidationFixture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenUtilsTest {

    @Test
    @DisplayName("checkGoogleOAuthToken 은 토큰이 비어있으면 TOKEN_NOT_FOUND 를 던진다")
    void checkGoogleOAuthToken_토큰이비어있으면_TOKEN_NOT_FOUND를던진다() {
        // given
        String blankToken = FirebaseTokenValidationFixture.BLANK_TOKEN;

        // when & then
        assertThatThrownBy(() -> TokenUtils.checkGoogleOAuthToken(blankToken))
                .isInstanceOf(ChatForYouException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.TOKEN_NOT_FOUND);
    }

    @Test
    @DisplayName("mapFirebaseAuthException 은 만료된 ID 토큰을 TOKEN_EXPIRED 로 매핑한다")
    void mapFirebaseAuthException_만료된ID토큰이면_TOKEN_EXPIRED로매핑한다() {
        // given
        FirebaseAuthException exception = FirebaseTokenValidationFixture.firebaseAuthException(AuthErrorCode.EXPIRED_ID_TOKEN);

        // when & then
        assertThatThrownBy(() -> { throw TokenUtils.mapFirebaseAuthException(exception); })
                .isInstanceOf(ChatForYouException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.TOKEN_EXPIRED);
    }

    @Test
    @DisplayName("mapFirebaseAuthException 은 위조된 ID 토큰을 TOKEN_INVALID 로 매핑한다")
    void mapFirebaseAuthException_위조된ID토큰이면_TOKEN_INVALID로매핑한다() {
        // given
        FirebaseAuthException exception = FirebaseTokenValidationFixture.firebaseAuthException(AuthErrorCode.INVALID_ID_TOKEN);

        // when & then
        assertThatThrownBy(() -> { throw TokenUtils.mapFirebaseAuthException(exception); })
                .isInstanceOf(ChatForYouException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.TOKEN_INVALID);
    }

    @Test
    @DisplayName("mapFirebaseAuthException 은 인증서 조회 실패를 INTERNAL_SERVER_ERROR 로 매핑한다")
    void mapFirebaseAuthException_인증서조회실패면_INTERNAL_SERVER_ERROR로매핑한다() {
        // given
        FirebaseAuthException exception = FirebaseTokenValidationFixture.firebaseAuthException(AuthErrorCode.CERTIFICATE_FETCH_FAILED);

        // when & then
        assertThatThrownBy(() -> { throw TokenUtils.mapFirebaseAuthException(exception); })
                .isInstanceOf(ChatForYouException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("mapFirebaseAuthException 은 알 수 없는 AuthErrorCode 를 INTERNAL_SERVER_ERROR 로 매핑한다")
    void mapFirebaseAuthException_알수없는에러코드이면_INTERNAL_SERVER_ERROR로매핑한다() {
        // given — 알 수 없는 AuthErrorCode: USER_NOT_FOUND 는 token 검증 경로에서 발생하지 않는 코드
        FirebaseAuthException exception = FirebaseTokenValidationFixture.firebaseAuthException(AuthErrorCode.USER_NOT_FOUND);

        // when & then
        assertThatThrownBy(() -> { throw TokenUtils.mapFirebaseAuthException(exception); })
                .isInstanceOf(ChatForYouException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR);
    }
}
