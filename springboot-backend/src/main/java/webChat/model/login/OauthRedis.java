package webChat.model.login;

import lombok.Getter;
import lombok.Setter;

/**
 * 로그인한 유저 정보를 redis 에 저장하기 위한 객체
 */
@Getter
@Setter
public class OauthRedis {
    private long idx;
    private String email;
    private String accessToken;
    private String refreshToken;
    private String nickname;
    private long lastLoginDate;

}
