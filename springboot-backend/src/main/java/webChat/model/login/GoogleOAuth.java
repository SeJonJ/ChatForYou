package webChat.model.login;

import lombok.*;
import webChat.entity.SocialUser;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(access = AccessLevel.PRIVATE)
public class GoogleOAuth {
    private String accessToken;
    private String refreshToken;
    private String email;
    private String nickName;
    @Setter
    private boolean emailVerified;
    private String photo;
    private String type;

    public static GoogleOAuth of(String accessToken, String refreshToken, boolean emailVerified, SocialUser socialUser) {
        return GoogleOAuth.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .nickName(socialUser.getNickname())
                .email(socialUser.getEmail())
                .emailVerified(emailVerified)
                .photo(socialUser.getPhotoUrl())
                .type(socialUser.getType())
                .build();
    }
}
