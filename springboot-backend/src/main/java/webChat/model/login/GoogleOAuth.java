package webChat.model.login;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Builder
@Getter
@Setter
public class GoogleOAuth {
    private String accessToken;
    private String refreshToken;
    private String name;
    private String email;
    private boolean emailVerified;
    private String photo;
}
