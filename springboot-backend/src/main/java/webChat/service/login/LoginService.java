package webChat.service.login;

import org.apache.coyote.BadRequestException;
import webChat.model.login.GoogleOAuth;
import webChat.model.response.common.QRLoginResponse;

public interface LoginService {
    void logout(String authorization, String email) throws Exception;
    GoogleOAuth checkSocialUser(GoogleOAuth googleOAuth);
    QRLoginResponse createQRSession();
    void authenticateSession(String sessionId, GoogleOAuth auth) throws BadRequestException;
    QRLoginResponse getSessionStatus(String sessionId) throws BadRequestException;
}