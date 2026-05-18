package webChat.utils.fixture;

import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuthException;

public final class FirebaseTokenValidationFixture {

    public static final String BLANK_TOKEN = "";

    private FirebaseTokenValidationFixture() {
    }

    public static FirebaseAuthException firebaseAuthException(AuthErrorCode authErrorCode) {
        return new FirebaseAuthException(
                com.google.firebase.ErrorCode.INVALID_ARGUMENT,
                authErrorCode.name(),
                null,
                null,
                authErrorCode
        );
    }
}
