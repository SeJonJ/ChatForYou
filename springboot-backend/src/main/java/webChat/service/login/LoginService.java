package webChat.service.login;

import lombok.NonNull;
import webChat.model.login.GoogleOAuth;
import webChat.model.response.common.QRLoginResponse;

public interface LoginService {
    void logout(String authorization, String email);
    GoogleOAuth checkSocialUser(String accessToken, String refreshToken, String name, String email, boolean emailVerified, String photo);
    QRLoginResponse createQRSession();
    GoogleOAuth authenticateQRSession(String sessionId, @NonNull String accessToken, @NonNull String refreshToken, @NonNull String name, @NonNull String email, boolean emailVerified, String photo);
    QRLoginResponse getSessionStatus(String sessionId);
}
