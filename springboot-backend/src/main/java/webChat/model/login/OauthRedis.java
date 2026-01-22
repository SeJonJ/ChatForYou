package webChat.model.login;

import lombok.Getter;
import lombok.Setter;

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
