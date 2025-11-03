package webChat.model.response.common;

import lombok.Builder;
import lombok.Getter;
import webChat.model.login.GoogleOAuth;
import webChat.model.login.QRSession;
import webChat.model.login.QRSessionStatus;

import java.util.Base64;

@Getter
@Builder
public class QRLoginResponse {
    private String sessionId;
    private QRSessionStatus status;
    private String qrUrl;
    private String qrImage;
    private GoogleOAuth userData;

    public static QRLoginResponse of(QRSession qrSession, byte[] qrImage) {
        return QRLoginResponse.builder()
                .sessionId(qrSession.getSessionId())
                .status(qrSession.getStatus())
                .userData(qrSession.getUserData())
                .qrUrl(qrSession.getQrUrl())
                .qrImage(Base64.getEncoder().encodeToString(qrImage))
                .build();
    }

    public static QRLoginResponse of(QRSession qrSession) {
        return QRLoginResponse.builder()
                .sessionId(qrSession.getSessionId())
                .status(qrSession.getStatus())
                .userData(qrSession.getUserData())
                .build();
    }
}
