package webChat.service.routing;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import webChat.model.kafka.KafkaEvent;
import webChat.model.kafka.KafkaSendKey;
import webChat.model.kafka.KafkaServerEvent;
import webChat.model.kafka.KafkaTopic;
import webChat.model.kafka.ServerEvent;
import webChat.service.redis.RedisService;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class RoutingInstanceProviderLifecycleTest {

    private static final String INSTANCE_ID = "instance-a";
    private static final String PEER_INSTANCE_ID = "instance-b";

    @Mock
    private KafkaTemplate<String, KafkaEvent> kafkaTemplate;

    @Mock
    private RedisService redisService;

    @Mock
    private CookieCheckEvent cookieCheckEvent;

    @Mock
    private ScheduledExecutorService heartbeatScheduler;

    private RoutingInstanceProvider instanceProvider;

    @BeforeEach
    void setUp() {
        instanceProvider = new RoutingInstanceProvider(kafkaTemplate, redisService, cookieCheckEvent);
        ReflectionTestUtils.setField(instanceProvider, "instanceId", INSTANCE_ID);
        ReflectionTestUtils.setField(instanceProvider, "heartbeatScheduler", heartbeatScheduler);
    }

    @Test
    @DisplayName("handleServerEvent 는 instanceId 초기화 전 조기 수신 이벤트를 안전하게 무시한다")
    void handleServerEvent_instanceId초기화전이벤트수신시_안전하게무시한다() {
        // given
        ReflectionTestUtils.setField(instanceProvider, "instanceId", null);
        ConsumerRecord<String, KafkaEvent> record = new ConsumerRecord<>(
                KafkaTopic.SERVER_LIFECYCLE_EVENTS,
                0,
                0L,
                KafkaSendKey.EVENT_TYPE,
                KafkaServerEvent.of(ServerEvent.SERVER_STARTED, PEER_INSTANCE_ID, System.currentTimeMillis())
        );

        // when
        instanceProvider.handleServerEvent(record);

        // then
        assertThat(instanceProvider.getActiveServers()).isEmpty();
        verifyNoInteractions(cookieCheckEvent, redisService, kafkaTemplate);
    }

    @Test
    @DisplayName("initializeLocalRoutingState 는 자기 자신을 즉시 라우팅 후보에 추가하고 heartbeat 를 한 번만 시작한다")
    void initializeLocalRoutingState_두번호출해도_자기자신추가와하트비트시작은한번만수행한다() {
        // given
        given(heartbeatScheduler.scheduleAtFixedRate(any(Runnable.class), eq(30L), eq(30L), eq(TimeUnit.SECONDS)))
                .willReturn(null);

        // when
        instanceProvider.initializeLocalRoutingState();
        instanceProvider.initializeLocalRoutingState();

        // then
        assertThat(instanceProvider.getActiveServers()).containsExactly(INSTANCE_ID);
        assertThat(instanceProvider.getTotalVirtualNodes()).isEqualTo(150);
        verify(redisService).setObject(eq("instance:heartbeat:" + INSTANCE_ID), anyLong(), eq(90L), eq(TimeUnit.SECONDS));
        verify(heartbeatScheduler).scheduleAtFixedRate(any(Runnable.class), eq(30L), eq(30L), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("announceClusterPresence 는 discovery 와 started 이벤트를 한 번씩만 발행한다")
    void announceClusterPresence_두번호출해도_discovery와started이벤트를한번씩만발행한다() {
        // given
        ArgumentCaptor<KafkaServerEvent> eventCaptor = ArgumentCaptor.forClass(KafkaServerEvent.class);

        // when
        instanceProvider.announceClusterPresence();
        instanceProvider.announceClusterPresence();

        // then
        verify(kafkaTemplate, timeout(3000).times(2))
                .send(eq(KafkaTopic.SERVER_LIFECYCLE_EVENTS), eq(KafkaSendKey.EVENT_TYPE), eventCaptor.capture());
        assertThat(eventCaptor.getAllValues())
                .extracting(KafkaServerEvent::getEventType)
                .containsExactly(ServerEvent.SERVER_DISCOVERY_REQUEST, ServerEvent.SERVER_STARTED);
    }

    @Test
    @DisplayName("shutdown 은 instanceId 가 null 이면 클러스터 정리 발행을 건너뛰고 heartbeat 만 종료한다")
    void shutdown_instanceId가Null이면_KafkaRedis정리를건너뛰고heartbeat만종료한다() {
        // given
        ReflectionTestUtils.setField(instanceProvider, "instanceId", null);
        given(heartbeatScheduler.isShutdown()).willReturn(false);

        // when
        instanceProvider.shutdown();

        // then
        verifyNoInteractions(kafkaTemplate);
        verify(redisService, never()).delInstanceInfo(any());
        verify(heartbeatScheduler).shutdown();
        assertThat(instanceProvider.isShutdown()).isTrue();
    }

    @Test
    @DisplayName("shutdown 이후에는 예약된 SERVER_STARTED 가 뒤늦게 발행되지 않는다")
    void shutdown_이후에는_예약된ServerStarted가발행되지않는다() throws Exception {
        // given
        ArgumentCaptor<KafkaServerEvent> eventCaptor = ArgumentCaptor.forClass(KafkaServerEvent.class);
        given(heartbeatScheduler.isShutdown()).willReturn(false);

        // when
        instanceProvider.announceClusterPresence();
        instanceProvider.shutdown();
        Thread.sleep(2300L);

        // then
        verify(kafkaTemplate, timeout(3000).times(2))
                .send(eq(KafkaTopic.SERVER_LIFECYCLE_EVENTS), eq(KafkaSendKey.EVENT_TYPE), eventCaptor.capture());
        assertThat(eventCaptor.getAllValues())
                .extracting(KafkaServerEvent::getEventType)
                .containsExactly(ServerEvent.SERVER_DISCOVERY_REQUEST, ServerEvent.SERVER_STOPPED);
    }
}
