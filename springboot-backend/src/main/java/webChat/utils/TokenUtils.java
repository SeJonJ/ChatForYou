package webChat.utils;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;

import java.util.Collections;

public class TokenUtils {

    /**
     *  google oauth 토큰 검증
     */
    public FirebaseToken checkGoogleOAuthToken(String token) throws Exception {
        FirebaseToken decodeToken = FirebaseAuth.getInstance().verifyIdToken(token);
        return decodeToken;
    }
}