package webChat.service.routing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import webChat.model.kafka.KafkaEvent;
import webChat.service.redis.RedisService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 방 소유권 판정용 인스턴스 생존 확인 단위 테스트.
 *
 * 인스턴스별 메모리 목록은 기동 직후 자기 자신만 담고 있어, 살아있는 다른 인스턴스를 죽은 것으로
 * 오판한다. 공유 Redis heartbeat 를 기준으로 판정하는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class InstanceAliveCheckTest {

    private static final String INSTANCE_ID = "instance-a";
    private static final String PEER_INSTANCE_ID = "instance-b";

    @Mock
    private KafkaTemplate<String, KafkaEvent> kafkaTemplate;

    @Mock
    private RedisService redisService;

    @Mock
    private CookieCheckEvent cookieCheckEvent;

    private RoutingInstanceProvider instanceProvider;

    @BeforeEach
    void setUp() {
        instanceProvider = new RoutingInstanceProvider(kafkaTemplate, redisService, cookieCheckEvent);
        ReflectionTestUtils.setField(instanceProvider, "instanceId", INSTANCE_ID);
    }

    @Test
    @DisplayName("자기 자신은 종료 전까지 살아있다고 판정하고 Redis 를 조회하지 않는다")
    void isInstanceAlive_자기자신_종료전_true() {
        // when
        boolean result = instanceProvider.isInstanceAlive(INSTANCE_ID);

        // then
        assertThat(result).isTrue();
        verifyNoInteractions(redisService);
    }

    @Test
    @DisplayName("자기 자신이라도 종료가 끝났으면 살아있지 않다고 판정한다")
    void isInstanceAlive_자기자신_종료후_false() {
        // given
        ReflectionTestUtils.setField(instanceProvider, "isShutdown", true);

        // when & then
        assertThat(instanceProvider.isInstanceAlive(INSTANCE_ID)).isFalse();
    }

    @Test
    @DisplayName("다른 인스턴스는 메모리 목록에 없어도 heartbeat 가 있으면 살아있다고 판정한다")
    void isInstanceAlive_다른인스턴스_heartbeat있음_true() {
        // given
        given(redisService.hasInstanceHeartbeat(PEER_INSTANCE_ID)).willReturn(true);

        // when & then
        assertThat(instanceProvider.getActiveServers()).doesNotContain(PEER_INSTANCE_ID);
        assertThat(instanceProvider.isInstanceAlive(PEER_INSTANCE_ID)).isTrue();
    }

    @Test
    @DisplayName("다른 인스턴스의 heartbeat 가 없으면 살아있지 않다고 판정한다")
    void isInstanceAlive_다른인스턴스_heartbeat없음_false() {
        // given
        given(redisService.hasInstanceHeartbeat(PEER_INSTANCE_ID)).willReturn(false);

        // when & then
        assertThat(instanceProvider.isInstanceAlive(PEER_INSTANCE_ID)).isFalse();
    }

    @Test
    @DisplayName("instanceId 가 비어 있으면 Redis 조회 없이 살아있지 않다고 판정한다")
    void isInstanceAlive_빈값_false() {
        // when & then
        assertThat(instanceProvider.isInstanceAlive(null)).isFalse();
        assertThat(instanceProvider.isInstanceAlive("")).isFalse();
        verifyNoInteractions(redisService);
    }
}
