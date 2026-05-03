package webChat.service.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import webChat.model.kafka.KafkaEvent;
import webChat.model.kafka.KafkaServerEvent;
import webChat.model.kafka.KafkaSendKey;
import webChat.model.kafka.KafkaTopic;
import webChat.model.redis.DataType;
import webChat.model.redis.RedisKeyPrefix;
import webChat.service.routing.fixture.CookieCheckEventFixture;
import webChat.service.redis.RedisService;
import webChat.utils.HttpUtil;

import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CookieCheckEventLocalModeTest {

    @InjectMocks
    private CookieCheckEvent cookieCheckEvent;

    @Mock
    private RedisService redisService;

    @Mock
    private RoutingInstanceProvider instanceProvider;

    @Mock
    private KafkaTemplate<String, KafkaEvent> kafkaTemplate;

    @Mock
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("collectOwnCookieAsync 는 cookie.check.domain 이 비어 있으면 HTTP 호출 없이 local cookie 를 저장한다")
    void collectOwnCookieAsync_whenCookieCheckDomainBlank_savesLocalCookieWithoutHttpCall() throws Exception {
        // given
        String instanceId = CookieCheckEventFixture.LOCAL_INSTANCE_ID;
        ReflectionTestUtils.setField(cookieCheckEvent, "cookieCheckDomain", "");
        given(instanceProvider.getInstanceId()).willReturn(instanceId);
        given(instanceProvider.getActiveServers()).willReturn(Set.of(instanceId));
        given(redisService.getRedisDataByDataType(
                RedisKeyPrefix.INSTANCE_COOKIE_PREFIX.getPrefix() + instanceId,
                DataType.INSTANCE_COOKIE,
                String.class
        )).willReturn(null);
        doNothing().when(instanceProvider).initInstanceId();
        doNothing().when(instanceProvider).initInstanceProviderEvent();

        try (MockedStatic<HttpUtil> httpUtil = mockStatic(HttpUtil.class)) {
            // when
            cookieCheckEvent.collectOwnCookieAsync();

            // then
            httpUtil.verifyNoInteractions();
            verify(redisService).saveInstanceCookieMapping(instanceId, CookieCheckEventFixture.localCookie(instanceId));
            verify(kafkaTemplate).send(eq(KafkaTopic.SERVER_LIFECYCLE_EVENTS), eq(KafkaSendKey.EVENT_TYPE), any(KafkaServerEvent.class));
        }
    }

    @Test
    @DisplayName("handleCookieResponse 는 cookie.check.domain 이 비어 있으면 Kafka 응답을 네트워크 검증 없이 무시한다")
    void handleCookieResponse_whenCookieCheckDomainBlank_skipsNetworkValidation() {
        // given
        String instanceId = CookieCheckEventFixture.LOCAL_INSTANCE_ID;
        ReflectionTestUtils.setField(cookieCheckEvent, "cookieCheckDomain", "");
        given(instanceProvider.getInstanceId()).willReturn(instanceId);
        KafkaServerEvent event = CookieCheckEventFixture.cookieResponseForLocalRequester(instanceId);

        try (MockedStatic<HttpUtil> httpUtil = mockStatic(HttpUtil.class)) {
            // when
            cookieCheckEvent.handleCookieResponse(event);

            // then
            httpUtil.verifyNoInteractions();
            verify(redisService, never()).saveInstanceCookieMapping(any(), any());
            verify(kafkaTemplate, never()).send(any(), any(), any(KafkaEvent.class));
        }
    }

    @Test
    @DisplayName("handleCookieDiscovered 는 cookie.check.domain 이 비어 있으면 Kafka 발견 쿠키를 네트워크 검증 없이 무시한다")
    void handleCookieDiscovered_whenCookieCheckDomainBlank_skipsNetworkValidation() {
        // given
        String instanceId = CookieCheckEventFixture.LOCAL_INSTANCE_ID;
        ReflectionTestUtils.setField(cookieCheckEvent, "cookieCheckDomain", "");
        given(instanceProvider.getInstanceId()).willReturn(instanceId);
        KafkaServerEvent event = CookieCheckEventFixture.cookieDiscoveredFromPeer();

        try (MockedStatic<HttpUtil> httpUtil = mockStatic(HttpUtil.class)) {
            // when
            cookieCheckEvent.handleCookieDiscovered(event);

            // then
            httpUtil.verifyNoInteractions();
            verify(redisService, never()).saveInstanceCookieMapping(any(), any());
            verify(kafkaTemplate, never()).send(any(), any(), any(KafkaEvent.class));
        }
    }
}
