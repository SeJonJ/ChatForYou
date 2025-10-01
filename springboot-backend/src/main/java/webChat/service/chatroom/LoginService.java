package webChat.service.chatroom;

import com.google.firebase.auth.FirebaseToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.coyote.BadRequestException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import webChat.entity.SocialUser;
import webChat.model.login.GoogleOAuth;
import webChat.model.login.OauthRedis;
import webChat.repository.SocialUserRepository;
import webChat.service.redis.RedisService;
import webChat.utils.TokenUtils;

import java.util.Calendar;

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
            log.error("google oauth 토큰 인증 실패 !!!");
        }

        // 계정이 없는 경우
        long time = Calendar.getInstance().getTimeInMillis();
        if (socialUser == null) {
            if (googleOAuth.isEmailVerified()) {
                SocialUser user = SocialUser.builder().build();
                user.setEmail(googleOAuth.getEmail());
                user.setNickname(googleOAuth.getName().split("@")[0]);
                user.setPhotoUrl(googleOAuth.getPhoto());
                user.setType("google");
                user.setCreateDate(time);
                user.setUpdateDate(time);
                user.setLastLoginDate(time);

                socialUserRepository.save(user);
            }
        } else {
            googleOAuth.setEmailVerified(decodeToken.isEmailVerified());
        }

        // 레디스 insert
        if (decodeToken.isEmailVerified()) {
            OauthRedis oauthRedis = new OauthRedis();
            oauthRedis.setEmail(googleOAuth.getEmail());
            oauthRedis.setAccessToken(googleOAuth.getAccessToken());
            oauthRedis.setRefreshToken(googleOAuth.getRefreshToken());
            oauthRedis.setNickname(googleOAuth.getName().split("@")[0]);
            oauthRedis.setLastLoginDate(time);
            redisService.insertGoogleOauthToken(oauthRedis, time);
        }

        return googleOAuth;
    }

    public void logout(String authorization, String email) throws Exception{
        FirebaseToken decodeToken = null;

        // 토큰 검증
        try {
            decodeToken = new TokenUtils().checkGoogleOAuthToken(authorization);
        } catch (Exception e) {
            log.error("google oauth 토큰 인증 실패 !!!");
        }

        // 레디스 삭제
        if (!decodeToken.isEmailVerified() || !decodeToken.getEmail().equalsIgnoreCase(email)) {
            throw new BadRequestException("Invalid Logout Info !!! ");
        }
        redisService.deleteLoginInfo(email);

    }
}
