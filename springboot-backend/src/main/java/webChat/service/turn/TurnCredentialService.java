package webChat.service.turn;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import webChat.exception.ChatForYouException;
import webChat.exception.ErrorCode;
import webChat.model.turn.out.TurnCredentialOutVo;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * coturn use-auth-secret 방식의 단기 TURN 자격증명을 HMAC-SHA1 로 발급한다.
 * 정적 평문 자격증명 대신 만료(TTL)가 있는 파생 자격증명을 발급해 유출 시 폭발 반경을 TTL 이내로 한정한다.
 */
@Slf4j
@Service
public class TurnCredentialService {

    private static final String HMAC_ALGO = "HmacSHA1";

    // coturn 4.6.2 는 만료 EpochSec 가 2^32 이상이면 allocate 를 401 로 거부한다(실측). 모든 만료값은 이 미만이어야 한다.
    static final long COTURN_EXPIRY_CEILING = 4_294_967_295L;

    @Value("${turn.static-auth-secret}")
    private String staticAuthSecret;

    @Value("${turn.server.host}")
    private String turnHost;

    @Value("${turn.server.port}")
    private int turnPort;

    @Value("${turn.credential.ttl-seconds:3600}")
    private long ttlSeconds;

    @Value("${turn.credential.kurento-expiry-seconds:1576800000}")
    private long kurentoExpirySec;

    @Value("${server.rtc.peer.reconnect-fallback-timeout-ms:300000}")
    private long peerReconnectTimeoutMs;

    /**
     * 입장 인증을 통과한 브라우저 사용자에게 단기 TTL TURN 자격증명을 발급한다.
     */
    public TurnCredentialOutVo issueForBrowser(String userId) {
        long exp = currentEpochSecond() + ttlSeconds;
        guardExpiryCeiling(exp);

        // coturn username 포맷은 "만료ts:userId" 이므로 userId 내부 ':' 는 파싱을 깨뜨린다. 사전 제거한다.
        String username = exp + ":" + userId.replace(":", "");
        String credential = hmacBase64(username);

        List<String> urls = List.of(
                "turn:" + turnHost + ":" + turnPort + "?transport=udp",
                "turn:" + turnHost + ":" + turnPort + "?transport=tcp"
        );

        return TurnCredentialOutVo.builder()
                .urls(urls)
                .username(username)
                .credential(credential)
                .ttl(ttlSeconds)
                .peerReconnectTimeoutMs(peerReconnectTimeoutMs)
                .build();
    }

    /**
     * Kurento ConfigMap 의 turnURL 에 임베드할 장기(50년) 자격증명 "ts:cred" 를 생성한다(런타임 API 아님, secret 로테이션 보조).
     */
    public String issueForKurento() {
        long exp = currentEpochSecond() + kurentoExpirySec;
        guardExpiryCeiling(exp);

        // turnURL 의 "user:pass@host:port" 파서가 username 내부 ':' 를 잘못 쪼개므로 userId 없이 timestamp-only 로 둔다.
        String username = String.valueOf(exp);
        String credential = hmacBase64(username);
        return username + ":" + credential;
    }

    // 만료값이 coturn 상한(2^32) 이상이면 발급 자체를 차단한다. 통과시키면 coturn 이 401 을 반환해 relay 가 조용히 실패한다.
    private void guardExpiryCeiling(long exp) {
        if (exp >= COTURN_EXPIRY_CEILING) {
            log.error("TURN credential expiry exceeds coturn ceiling :: exp={}", exp);
            throw new ChatForYouException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    // coturn use-auth-secret 검증과 동형: base64(HMAC_SHA1(secret, username)). URL-safe 아닌 표준 base64 여야 한다.
    private String hmacBase64(String username) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(staticAuthSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] raw = mac.doFinal(username.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(raw);
        } catch (GeneralSecurityException e) {
            // secret 노출 방지를 위해 예외 메시지를 전파하지 않는다.
            log.error("Failed to compute TURN HMAC credential");
            throw new ChatForYouException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    long currentEpochSecond() {
        return Instant.now().getEpochSecond();
    }
}
