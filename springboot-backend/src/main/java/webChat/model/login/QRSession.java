package webChat.model.login;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
public class QRSession {
    private String sessionId;
    private String qrUrl;
    private QRSessionStatus status;
    private long createdAt;
    private Long authenticatedAt;
    private GoogleOAuth userData;

    public static QRSession of(String sessionId, String qrUrl, QRSessionStatus status, long createdAt, Long qrSessionTTL) {
        return QRSession.builder()
                .sessionId(sessionId)
                .qrUrl(qrUrl)
                .status(status)
                .createdAt(createdAt)
                .authenticatedAt(createdAt + (qrSessionTTL * 1000))
                .build();
    }

    public static QRSession ofUpdateStatus(QRSession qrSession, QRSessionStatus status) {
        return QRSession.builder()
                .sessionId(qrSession.getSessionId())
                .qrUrl(qrSession.getQrUrl())
                .status(status)
                .createdAt(qrSession.getCreatedAt())
                .authenticatedAt(qrSession.getAuthenticatedAt())
                .build();
    }

    public static QRSession ofUpdateStatusAndAuth(QRSession qrSession, QRSessionStatus status, GoogleOAuth auth) {
        return QRSession.builder()
                .sessionId(qrSession.getSessionId())
                .qrUrl(qrSession.getQrUrl())
                .status(status)
                .createdAt(qrSession.getCreatedAt())
                .authenticatedAt(qrSession.getAuthenticatedAt())
                .userData(auth)
                .build();
    }
}
