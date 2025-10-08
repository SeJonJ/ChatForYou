package webChat.model.login;

import lombok.*;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GoogleOAuth {
    private String accessToken;
    private String refreshToken;
    private String name;
    private String email;
    @Setter
    private boolean emailVerified;
    private String photo;

    public static GoogleOAuth of(String accessToken, String refreshToken, String name, String email, boolean emailVerified, String photo) {
        return GoogleOAuth.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .name(name)
                .email(email)
                .emailVerified(emailVerified)
                .photo(photo)
                .build();
    }
}
