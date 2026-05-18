package webChat.utils;

import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import webChat.exception.ChatForYouException;
import webChat.exception.ErrorCode;

public class TokenUtils {

    public static FirebaseToken checkGoogleOAuthToken(String token) {
        if(StringUtil.isNullOrEmpty(token)) throw new ChatForYouException(ErrorCode.TOKEN_NOT_FOUND);
        try{
            return FirebaseAuth.getInstance().verifyIdToken(token);
        }catch (FirebaseAuthException firebaseAuthException){
            throw mapFirebaseAuthException(firebaseAuthException);
        } catch (Exception e) {
            throw new ChatForYouException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    static ChatForYouException mapFirebaseAuthException(FirebaseAuthException firebaseAuthException) {
        AuthErrorCode authErrorCode = firebaseAuthException.getAuthErrorCode();
        if (authErrorCode == null) {
            return new ChatForYouException(ErrorCode.INTERNAL_SERVER_ERROR);
        }

        return switch (authErrorCode) {
            case EXPIRED_ID_TOKEN, REVOKED_ID_TOKEN -> new ChatForYouException(ErrorCode.TOKEN_EXPIRED);
            case INVALID_ID_TOKEN -> new ChatForYouException(ErrorCode.TOKEN_INVALID);
            case CERTIFICATE_FETCH_FAILED, CONFIGURATION_NOT_FOUND, TENANT_ID_MISMATCH, TENANT_NOT_FOUND ->
                    new ChatForYouException(ErrorCode.INTERNAL_SERVER_ERROR);
            default -> new ChatForYouException(ErrorCode.INTERNAL_SERVER_ERROR);
        };
    }
}
