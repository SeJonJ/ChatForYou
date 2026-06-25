package webChat.service.turn;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import webChat.exception.ChatForYouException;
import webChat.exception.ErrorCode;
import webChat.model.turn.out.TurnCredentialOutVo;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.spy;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TurnCredentialServiceTest {

    private static final String SECRET = "test-static-auth-secret";
    private static final String HOST = "turn.test.local";
    private static final int PORT = 33488;
    private static final long TTL = 3600L;
    private static final long PEER_RECONNECT_MS = 300000L;
    private static final long KURENTO_EXPIRY_SEC = 1576800000L; // 50년(프로덕션 기본값과 동일)

    private TurnCredentialService turnCredentialService;

    @BeforeEach
    void setUp() {
        turnCredentialService = new TurnCredentialService();
        ReflectionTestUtils.setField(turnCredentialService, "staticAuthSecret", SECRET);
        ReflectionTestUtils.setField(turnCredentialService, "turnHost", HOST);
        ReflectionTestUtils.setField(turnCredentialService, "turnPort", PORT);
        ReflectionTestUtils.setField(turnCredentialService, "ttlSeconds", TTL);
        ReflectionTestUtils.setField(turnCredentialService, "peerReconnectTimeoutMs", PEER_RECONNECT_MS);
        ReflectionTestUtils.setField(turnCredentialService, "kurentoExpirySec", KURENTO_EXPIRY_SEC);
    }

    @Test
    @DisplayName("issueForBrowser_정상발급_credential이 coturn 재계산값과 일치한다")
    void issueForBrowser_normal_credentialMatchesCoturnRecomputation() {
        // when
        TurnCredentialOutVo result = turnCredentialService.issueForBrowser("user@example.com");

        // then
        assertThat(result.getCredential()).isEqualTo(expectedHmac(result.getUsername()));
    }

    @Test
    @DisplayName("issueForBrowser_정상발급_username이 '만료ts:userId' 포맷이다")
    void issueForBrowser_normal_usernameHasExpiryColonUserIdFormat() {
        // when
        TurnCredentialOutVo result = turnCredentialService.issueForBrowser("user@example.com");

        // then
        assertThat(result.getUsername()).matches("^\\d+:.+$");
        assertThat(result.getUsername()).endsWith(":user@example.com");
    }

    @Test
    @DisplayName("issueForBrowser_정상발급_만료ts가 now+ttl 근방의 미래값이다")
    void issueForBrowser_normal_expiryIsNowPlusTtl() {
        // given
        long before = Instant.now().getEpochSecond();

        // when
        TurnCredentialOutVo result = turnCredentialService.issueForBrowser("user@example.com");

        // then
        long after = Instant.now().getEpochSecond();
        long exp = Long.parseLong(result.getUsername().split(":", 2)[0]);
        assertThat(exp).isBetween(before + TTL, after + TTL);
    }

    @Test
    @DisplayName("issueForBrowser_userId에 콜론 포함_username의 userId 부분에 콜론이 없다")
    void issueForBrowser_userIdWithColon_userIdSegmentHasNoColon() {
        // when
        TurnCredentialOutVo result = turnCredentialService.issueForBrowser("ab:cd:ef");

        // then
        String userIdSegment = result.getUsername().split(":", 2)[1];
        assertThat(userIdSegment).doesNotContain(":");
        assertThat(userIdSegment).isEqualTo("abcdef");
    }

    @Test
    @DisplayName("issueForBrowser_정상발급_urls는 udp/tcp 2개이고 ttl·peerReconnect 주입값과 일치한다")
    void issueForBrowser_normal_outVoComposition() {
        // when
        TurnCredentialOutVo result = turnCredentialService.issueForBrowser("user@example.com");

        // then
        assertThat(result.getUrls()).containsExactly(
                "turn:" + HOST + ":" + PORT + "?transport=udp",
                "turn:" + HOST + ":" + PORT + "?transport=tcp"
        );
        assertThat(result.getTtl()).isEqualTo(TTL);
        assertThat(result.getPeerReconnectTimeoutMs()).isEqualTo(PEER_RECONNECT_MS);
    }

    @Test
    @DisplayName("issueForKurento_정상발급_만료ts는 now+50년이며 2^32 미만이다")
    void issueForKurento_normal_expiryIsFiftyYearsBelowCeiling() {
        // given
        long before = Instant.now().getEpochSecond();

        // when
        String result = turnCredentialService.issueForKurento();

        // then
        long after = Instant.now().getEpochSecond();
        long exp = Long.parseLong(result.split(":", 2)[0]);
        assertThat(exp).isBetween(before + KURENTO_EXPIRY_SEC, after + KURENTO_EXPIRY_SEC);
        assertThat(exp).isLessThan(TurnCredentialService.COTURN_EXPIRY_CEILING);
    }

    @Test
    @DisplayName("issueForKurento_정상발급_username은 콜론 없는 순수 숫자(timestamp-only)다")
    void issueForKurento_normal_usernameIsTimestampOnly() {
        // when
        String result = turnCredentialService.issueForKurento();

        // then
        String username = result.split(":", 2)[0];
        assertThat(username).matches("^\\d+$");
    }

    @Test
    @DisplayName("issueForKurento_정상발급_credential이 coturn 재계산값과 일치한다")
    void issueForKurento_normal_credentialMatchesCoturnRecomputation() {
        // when
        String result = turnCredentialService.issueForKurento();

        // then
        String[] parts = result.split(":", 2);
        assertThat(parts[1]).isEqualTo(expectedHmac(parts[0]));
    }

    @Test
    @DisplayName("issueForBrowser_만료ts가 coturn 상한(2^32) 이상_INTERNAL_SERVER_ERROR 예외")
    void issueForBrowser_expiryAtOrAboveCeiling_throwsException() {
        // given
        TurnCredentialService spyService = spyWithFixedEpoch(TurnCredentialService.COTURN_EXPIRY_CEILING);

        // when & then
        assertThatThrownBy(() -> spyService.issueForBrowser("user@example.com"))
                .isInstanceOf(ChatForYouException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("issueForKurento_만료ts가 coturn 상한(2^32) 이상_INTERNAL_SERVER_ERROR 예외")
    void issueForKurento_expiryAtOrAboveCeiling_throwsException() {
        // given
        TurnCredentialService spyService = spyWithFixedEpoch(TurnCredentialService.COTURN_EXPIRY_CEILING - KURENTO_EXPIRY_SEC);

        // when & then
        assertThatThrownBy(spyService::issueForKurento)
                .isInstanceOf(ChatForYouException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    private TurnCredentialService spyWithFixedEpoch(long epochSecond) {
        TurnCredentialService spyService = spy(new TurnCredentialService());
        ReflectionTestUtils.setField(spyService, "staticAuthSecret", SECRET);
        ReflectionTestUtils.setField(spyService, "turnHost", HOST);
        ReflectionTestUtils.setField(spyService, "turnPort", PORT);
        ReflectionTestUtils.setField(spyService, "ttlSeconds", TTL);
        ReflectionTestUtils.setField(spyService, "peerReconnectTimeoutMs", PEER_RECONNECT_MS);
        ReflectionTestUtils.setField(spyService, "kurentoExpirySec", KURENTO_EXPIRY_SEC);
        given(spyService.currentEpochSecond()).willReturn(epochSecond);
        return spyService;
    }

    private String expectedHmac(String username) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] raw = mac.doFinal(username.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
