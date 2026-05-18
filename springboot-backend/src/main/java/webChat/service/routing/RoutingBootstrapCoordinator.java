package webChat.service.routing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.event.ConsumerStartedEvent;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 라우팅/Kafka 부트스트랩 순서를 한 곳에서 드러내기 위한 coordinator.
 * 순서는 instanceId 준비, 로컬 routing 상태 초기화, listener readiness 확인,
 * cluster announce, cookie 수집 시작이다.
 */
@Component
@Slf4j
public class RoutingBootstrapCoordinator {

    private static final long DEFAULT_SERVER_LIFECYCLE_READY_TIMEOUT_SECONDS = 10L;

    private final RoutingInstanceProvider instanceProvider;
    private final CookieCheckEvent cookieCheckEvent;
    private final CountDownLatch serverLifecycleListenerReadyLatch = new CountDownLatch(1);
    private final AtomicBoolean bootstrapStarted = new AtomicBoolean(false);
    private final long serverLifecycleReadyTimeoutSeconds;

    /**
     * 기본 timeout 설정으로 coordinator 를 생성한다.
     */
    @Autowired
    public RoutingBootstrapCoordinator(RoutingInstanceProvider instanceProvider,
                                       CookieCheckEvent cookieCheckEvent) {
        this(instanceProvider, cookieCheckEvent, DEFAULT_SERVER_LIFECYCLE_READY_TIMEOUT_SECONDS);
    }

    /**
     * 테스트 가능한 timeout 설정으로 coordinator 를 생성한다.
     */
    RoutingBootstrapCoordinator(RoutingInstanceProvider instanceProvider,
                                CookieCheckEvent cookieCheckEvent,
                                long serverLifecycleReadyTimeoutSeconds) {
        this.instanceProvider = instanceProvider;
        this.cookieCheckEvent = cookieCheckEvent;
        this.serverLifecycleReadyTimeoutSeconds = serverLifecycleReadyTimeoutSeconds;
    }

    /**
     * 웹 서버 기동 이후 routing startup 순서를 명시적으로 실행한다.
     * 순서는 로컬 상태 초기화, listener 준비 대기, cluster announce, cookie 수집 시작이다.
     */
    @Async("taskExecutor")
    @EventListener(WebServerInitializedEvent.class)
    public void bootstrapRoutingLifecycle() {
        if (!bootstrapStarted.compareAndSet(false, true)) {
            return;
        }

        try {
            instanceProvider.initializeLocalRoutingState();
            // 현재 운영 정책은 listener readiness timeout 이어도 startup 전체를 중단하지 않는다.
            // routing/local state 는 이미 준비되어 있으므로 degraded 상태로 cluster announce 와
            // cookie 수집을 계속 진행해 인스턴스가 완전히 dead-on-startup 되지 않게 한다.
            boolean listenerReady = awaitServerLifecycleListenerReady();
            if (!listenerReady) {
                log.info("Continuing routing bootstrap with degraded startup policy after listener readiness timeout");
            }

            instanceProvider.announceClusterPresence();
            cookieCheckEvent.collectOwnCookie();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Routing bootstrap interrupted before completion", e);
        }
    }

    /**
     * Kafka consumer 시작 이벤트를 받아 server-lifecycle listener readiness 를 기록한다.
     */
    @EventListener
    public void onServerLifecycleConsumerStarted(ConsumerStartedEvent event) {
        MessageListenerContainer container = event.getContainer(MessageListenerContainer.class);
        if (container == null) {
            return;
        }
        markServerLifecycleListenerReady(container.getListenerId());
    }

    /**
     * 특정 listener 가 실제로 준비되었다는 신호를 coordinator 에 전달한다.
     * 소비하지 못할 수도 있기때문에 listener 의 준비 완료 시점을 명시적으로 알려주는 용도다.
     */
    public void markServerLifecycleListenerReady(String listenerId) {
        if (!InstanceProvider.SERVER_LIFECYCLE_LISTENER_ID.equals(listenerId)) {
            return;
        }

        if (serverLifecycleListenerReadyLatch.getCount() == 0) {
            return;
        }

        serverLifecycleListenerReadyLatch.countDown();
        log.info("Routing bootstrap coordinator marked listener [{}] ready", listenerId);
    }

    /**
     * server-lifecycle listener 의 준비 완료 신호를 제한 시간 동안 기다린다.
     * bootstrap 을 무한 대기시키지 않고 discovery/start 이벤트를 너무 일찍 보내지 않기 위한 보호 구간이다.
     */
    public boolean awaitServerLifecycleListenerReady() throws InterruptedException {
        boolean ready = serverLifecycleListenerReadyLatch.await(
                serverLifecycleReadyTimeoutSeconds, TimeUnit.SECONDS);

        if (!ready) {
            log.warn("Routing bootstrap coordinator timed out waiting for listener [{}] readiness",
                    InstanceProvider.SERVER_LIFECYCLE_LISTENER_ID);
        }

        return ready;
    }
}
