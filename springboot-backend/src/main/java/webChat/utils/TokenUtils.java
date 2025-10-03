package webChat.utils;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import webChat.controller.ExceptionController;

import java.util.Collections;

public class TokenUtils {

    /**
     *  google oauth 토큰 검증
     */
    public FirebaseToken checkGoogleOAuthToken(String token) throws Exception {
        if(StringUtil.isNullOrEmpty(token)) throw new ExceptionController.NotExistTokenException("token is null or empty");
        try{
            return FirebaseAuth.getInstance().verifyIdToken(token);
        }catch (FirebaseAuthException firebaseAuthException){
            throw new ExceptionController.TokenExpiredException("");
        }
    }

    // TODO 토큰 체크 필요 :: 혼자서 expired 체크가 된다??
    private boolean isTokenExpired(FirebaseToken decodedToken) {
        return false;
    }
}