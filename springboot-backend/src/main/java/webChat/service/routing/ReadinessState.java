package webChat.service.routing;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 애플리케이션 기동 완료 여부를 보관하는 readiness 신호.
 * readinessProbe 판정을 쿠키 수집(ingress 왕복 의존)에서 분리하기 위해,
 * ingress 비의존 신호인 ApplicationReadyEvent 만으로 ready 를 결정한다.
 */
@Component
public class ReadinessState {

    // 발행 스레드와 probe HTTP 스레드 간 가시성 보장을 위해 volatile.
    private volatile boolean appReady = false;

    /**
     * 컨텍스트가 완전히 기동된 뒤 1회 호출되어 ready 상태로 전환한다.
     * ApplicationReadyEvent 는 ingress 라우팅/쿠키 수집과 무관하게 발생하므로
     * 전체 Pod 동시 재기동에서도 순환 교착 없이 독립적으로 ready 에 도달한다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        appReady = true;
    }

    public boolean isReady() {
        return appReady;
    }
}
