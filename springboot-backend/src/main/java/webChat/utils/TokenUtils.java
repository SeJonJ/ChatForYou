package webChat.utils;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import webChat.exception.ChatForYouException;
import webChat.exception.ErrorCode;

public class TokenUtils {

    /**
     *  google oauth 토큰 검증
     */
    public static FirebaseToken checkGoogleOAuthToken(String token) {
        if(StringUtil.isNullOrEmpty(token)) throw new ChatForYouException(ErrorCode.TOKEN_NOT_FOUND);
        try{
            return FirebaseAuth.getInstance().verifyIdToken(token);
        }catch (FirebaseAuthException firebaseAuthException){
            throw new ChatForYouException(ErrorCode.TOKEN_EXPIRED);
        }
    }
}
