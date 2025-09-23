package webChat.model.login;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GoogleOAuth {
    private String accessToken;
    private String refreshToken;
    private String name;
    private String email;
    private boolean emailVerified;
    private String photo;
}
