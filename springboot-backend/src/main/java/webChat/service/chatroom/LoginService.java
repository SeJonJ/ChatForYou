package webChat.service.chatroom;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import webChat.entity.SocialUser;
import webChat.model.login.GoogleOAuth;
import webChat.model.login.GoogleOAuthResponse;
import webChat.repository.SocialUserRepository;

@Service
@RequiredArgsConstructor
@Slf4j
public class LoginService {
    @Autowired
    private SocialUserRepository socialUserRepository;

    public GoogleOAuthResponse checkSocialUser(GoogleOAuth googleOAuth) {
        SocialUser socialUser = socialUserRepository.findByEmail(googleOAuth.getEmail());

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
            // db 값 변경
        }

        return GoogleOAuthResponse.builder()
                .accessToken(googleOAuth.getAccessToken())
                .refreshToken(googleOAuth.getRefreshToken())
                .email(googleOAuth.getEmail())
                .type("google")
                .build();
    }
}
