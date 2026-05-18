package webChat.service.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.event.ConsumerStartedEvent;
import org.springframework.kafka.listener.MessageListenerContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RoutingBootstrapCoordinatorTest {

    @Mock
    private RoutingInstanceProvider instanceProvider;

    @Mock
    private CookieCheckEvent cookieCheckEvent;

    @Mock
    private ConsumerStartedEvent consumerStartedEvent;

    @Mock
    private MessageListenerContainer messageListenerContainer;

    @Test
    @DisplayName("bootstrapRoutingLifecycle 는 listener 준비 확인 후 cluster announce 와 cookie 수집을 순서대로 진행한다")
    void bootstrapRoutingLifecycle_whenListenerReady_runsStartupSequenceInOrder() throws Exception {
        // given
        RoutingBootstrapCoordinator routingBootstrapCoordinator = new RoutingBootstrapCoordinator(instanceProvider, cookieCheckEvent, 0L);
        routingBootstrapCoordinator.markServerLifecycleListenerReady(InstanceProvider.SERVER_LIFECYCLE_LISTENER_ID);

        // when
        routingBootstrapCoordinator.bootstrapRoutingLifecycle();

        // then
        InOrder inOrder = inOrder(instanceProvider, cookieCheckEvent);
        inOrder.verify(instanceProvider).initializeLocalRoutingState();
        inOrder.verify(instanceProvider).announceClusterPresence();
        inOrder.verify(cookieCheckEvent).collectOwnCookie();
    }

    @Test
    @DisplayName("bootstrapRoutingLifecycle 는 listener readiness 가 timeout 되어도 degraded startup 정책으로 announce 와 cookie 수집을 계속 진행한다")
    void bootstrapRoutingLifecycle_whenListenerReadinessTimesOut_continuesBootstrapWithDegradedStartupPolicy() throws Exception {
        // given
        RoutingBootstrapCoordinator routingBootstrapCoordinator = new RoutingBootstrapCoordinator(instanceProvider, cookieCheckEvent, 0L);

        // when
        routingBootstrapCoordinator.bootstrapRoutingLifecycle();

        // then
        verify(instanceProvider).announceClusterPresence();
        verify(cookieCheckEvent).collectOwnCookie();
    }

    @Test
    @DisplayName("onServerLifecycleConsumerStarted 는 server lifecycle listener 시작 신호만 readiness 로 반영한다")
    void onServerLifecycleConsumerStarted_whenMatchingListener_marksReady() throws Exception {
        // given
        RoutingBootstrapCoordinator routingBootstrapCoordinator = new RoutingBootstrapCoordinator(instanceProvider, cookieCheckEvent, 0L);
        given(consumerStartedEvent.getContainer(MessageListenerContainer.class)).willReturn(messageListenerContainer);
        given(messageListenerContainer.getListenerId()).willReturn(InstanceProvider.SERVER_LIFECYCLE_LISTENER_ID);

        // when
        routingBootstrapCoordinator.onServerLifecycleConsumerStarted(consumerStartedEvent);
        boolean ready = routingBootstrapCoordinator.awaitServerLifecycleListenerReady();

        // then
        assertThat(ready).isTrue();
    }
}
