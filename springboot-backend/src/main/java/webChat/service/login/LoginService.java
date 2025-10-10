package webChat.service.login;

import lombok.NonNull;
import org.apache.coyote.BadRequestException;
import webChat.model.login.GoogleOAuth;
import webChat.model.response.common.QRLoginResponse;

public interface LoginService {
    void logout(String authorization, String email) throws Exception;
    GoogleOAuth checkSocialUser(String accessToken, String refreshToken, String name, String email, boolean emailVerified, String photo);
    QRLoginResponse createQRSession();
    GoogleOAuth authenticateQRSession(String sessionId, @NonNull String accessToken, @NonNull String refreshToken, @NonNull String name, @NonNull String email, boolean emailVerified, String photo) throws BadRequestException;
    QRLoginResponse getSessionStatus(String sessionId) throws BadRequestException;
}