package webChat.service.chatroom;

import com.google.firebase.auth.FirebaseToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import webChat.entity.SocialUser;
import webChat.model.login.GoogleOAuth;
import webChat.repository.SocialUserRepository;
import webChat.service.redis.RedisService;
import webChat.utils.TokenUtils;

@Service
@RequiredArgsConstructor
@Slf4j
public class LoginService {
    @Autowired
    private SocialUserRepository socialUserRepository;

    @Autowired
    private RedisService redisService;

    public GoogleOAuth checkSocialUser(GoogleOAuth googleOAuth) {
        SocialUser socialUser = socialUserRepository.findByEmail(googleOAuth.getEmail());
        FirebaseToken decodeToken = null;
        try {
            decodeToken = new TokenUtils().checkGoogleOAuthToken(googleOAuth.getAccessToken());


            //resultAuth.setEmailVerified(decodeToken.isEmailVerified());
        } catch (Exception e) {
            log.info("google oauth 토큰 인증 실패");
        }

        // 계정이 없는 경우
        if (socialUser == null) {
            if (googleOAuth.isEmailVerified()) {
                SocialUser user = SocialUser.builder().build();
                user.setAccessToken(googleOAuth.getAccessToken());
                user.setRefreshToken(googleOAuth.getRefreshToken());
                user.setName(googleOAuth.getName());
                user.setEmail(googleOAuth.getEmail());
                user.setPhoto(googleOAuth.getPhoto());
                user.setType("google");
                socialUserRepository.save(user);
            }
        } else {
            // 레디스 insert
            if (decodeToken.isEmailVerified()) {
                redisService.insertGoogleOauthToken(googleOAuth);
            }
            googleOAuth.setEmailVerified(decodeToken.isEmailVerified());
        }

        return googleOAuth;
    }
}
