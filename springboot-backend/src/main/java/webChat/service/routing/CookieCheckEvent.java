package webChat.service.routing;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Component;
import webChat.model.kafka.*;
import webChat.model.routing.RoutingCookie;
import webChat.model.routing.RoutingCookieInfo;
import webChat.service.redis.RedisService;
import webChat.utils.HttpUtil;
import webChat.utils.StringUtil;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Random;

/**
 * Nginx가 발급하는 sessionAffinity 쿠키를 수집하고,
 * 내 instanceId와 쿠키를 Redis에 매핑해 저장하는 컴포넌트
 *
 * 개선사항 (기존 아키텍처 최소 변경):
 * - Phase 1: 파드간 협력 유지 (첫 번째 파드 성공 방식)
 * - Phase 2: 시도 횟수 대폭 증가로 확률적 성공률 향상
 * - Phase 3: 잘못된 값 저장 방지
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Getter
@EnableAsync
public class CookieCheckEvent {

    private final RedisService redisService;
    @Lazy
    private final RoutingInstanceProvider instanceProvider;
    private final KafkaTemplate<String, KafkaEvent> kafkaTemplate;

    @Value("${cookie.check.domain:https://localhost:8443}")
    private String cookieCheckDomain;
    private volatile boolean cookieCollected = false;
    private final String COOKIE_CHECK_PATH = "/chatforyou/api/health/cookie";

    // Phase별 개선된 설정값
    private static final int PHASE1_TIMEOUT_MS = 5000;
    private static final int PHASE1_MAX_RETRIES = 3;
    private static final int PHASE2_MAX_RETRIES = 50; // 기존 5회 → 50회로 대폭 증가
    private static final int PHASE2_REQUEST_INTERVAL_MS = 200; // 요청 간격 조정

    @EventListener(WebServerInitializedEvent.class)
    @Async
    public void collectOwnCookieAsync() throws InterruptedException {
        instanceProvider.initInstanceId();
        // Kafka consumer 준비 대기
        waitForKafkaConsumerReady();

        log.info("=== 인스턴스 제공 이벤트 init 시작 ===");
        instanceProvider.initInstanceProviderEvent();

        log.info("=== 쿠키 수집 시작 ===");

        // Phase 1: 파드간 협력
        String cookie = tryCollectFromPeersWithRetry();
        if (cookie != null) {
            saveCookieAndComplete(cookie);
            return;
        }

        // Phase 2: 확률적 최적화
        cookie = tryOptimizedCollectionImproved();
        if (cookie != null) {
            saveCookieAndComplete(cookie);
            return;
        }

        // Phase 3: 개선된 Fallback
        handleImprovedFallback();
    }

    private void waitForKafkaConsumerReady() throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            if (instanceProvider != null && !instanceProvider.getActiveServers().isEmpty()) break;
            Thread.sleep(500);
        }
    }

    /**
     * Phase 1: 파드간 협력
     */
    private String tryCollectFromPeersWithRetry() throws InterruptedException {
        for (int attempt = 0; attempt < PHASE1_MAX_RETRIES; attempt++) {
            String cookie = tryCollectFromPeers();
            if (cookie != null) return cookie;

            // 점진적 지연: 2초, 4초, 6초
            Thread.sleep(2000 * (attempt + 1));
            log.info("Phase 1 쿠키 수집 재시도: {}/{}", attempt + 1, PHASE1_MAX_RETRIES);
        }
        return null;
    }

    private String tryCollectFromPeers() {
        try {
            log.info("=== Phase 1: 파드간 협력 시작 ===");

            Map<String, String> peerCookies = redisService.getAllInstanceCookies();

            if (peerCookies.isEmpty()) {
                log.info("다른 파드 쿠키가 없음 - Kafka로 쿠키 정보 요청");
                publishCookieRequest();
                Thread.sleep(PHASE1_TIMEOUT_MS);
                peerCookies = redisService.getAllInstanceCookies();
            }

            if (!peerCookies.isEmpty()) {
                log.info("발견된 파드 쿠키 수: {}", peerCookies.size());

                // 쿠키 예측 제거, 실제 응답만 사용
                for (Map.Entry<String, String> entry : peerCookies.entrySet()) {
                    String peerInstanceId = entry.getKey();
                    String peerCookie = entry.getValue();

                    log.info("발견된 파드: {} -> 쿠키: {}", peerInstanceId, peerCookie);

                    // 실제 nginx 응답을 통한 검증만 수행
                    if (validateActualCookie(peerCookie)) {
                        log.info("=== Phase 1 성공: 기존 쿠키 검증 완료 ===");
                        return peerCookie;
                    }
                }
            }

            log.info("=== Phase 1 실패: Phase 2로 이동 ===");
            return null;

        } catch (Exception e) {
            log.error("Phase 1 오류: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Phase 2: 대폭 개선된 확률적 최적화
     */
    private String tryOptimizedCollectionImproved() {
        try {
            log.info("=== Phase 2: 확률적 최적화 시작 ===");

            int activePods = instanceProvider.getActiveServers().size();
            int maxRetries = PHASE2_MAX_RETRIES;

            log.info("활성 파드 수: {}, 최대 시도 횟수: {}", activePods, maxRetries);

            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    HttpResponse response = HttpUtil.getWithFullResponse(
                            cookieCheckDomain + COOKIE_CHECK_PATH, new HttpHeaders(), null);

                    String respInstanceId = extractResponseInstanceId(response);
                    String cookie = extractCookieFromResponse(response);

                    if (!StringUtil.isNullOrEmpty(cookie) && cookie.contains("|")) {
                        if (respInstanceId.equals(instanceProvider.getInstanceId())) {
                            log.info("=== Phase 2 성공: {}회 시도만에 실제 nginx 쿠키 획득 ===", attempt);
                            return cookie;
                        }else {
                            this.publishCookieResponse(respInstanceId, cookie);
                        }
                    }

                    // 요청 간격 조정
                    Thread.sleep(PHASE2_REQUEST_INTERVAL_MS);

                } catch (Exception e) {
                    log.warn("Phase 2 시도 {}/{} 실패: {}", attempt, maxRetries, e.getMessage());
                    Thread.sleep(PHASE2_REQUEST_INTERVAL_MS);
                }

                // 진행상황 로깅
                if (attempt % 10 == 0) {
                    log.info("Phase 2 진행중: {}/{} 시도 완료", attempt, maxRetries);
                }
            }

            log.info("=== Phase 2 실패: {}회 모든 시도 실패, Phase 3 Fallback으로 이동 ===", maxRetries);
            return null;

        } catch (Exception e) {
            log.error("Phase 2 오류: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 실제 쿠키 검증
     */
    private boolean validateActualCookie(String cookie) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.add("Cookie", RoutingCookie.CHATFORYOU_SERVER_COOKIE.getName() + "=" + cookie);

            HttpResponse response = HttpUtil.getWithFullResponse(
                    cookieCheckDomain + COOKIE_CHECK_PATH, headers, null);

            String respInstanceId = extractResponseInstanceId(response);
            boolean isValid = respInstanceId.equals(instanceProvider.getInstanceId());

            log.debug("실제 쿠키 검증: 쿠키={}, 응답 instanceId={}, 내instanceId={}, 결과={}",
                    cookie, respInstanceId, instanceProvider.getInstanceId(), isValid);

            return isValid;

        } catch (Exception e) {
            log.warn("실제 쿠키 검증 실패: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 개선된 Fallback - 잘못된 값 저장하지 않음
     */
    private void handleImprovedFallback() {
        log.error("=== Phase 3 Fallback: 모든 방법으로 nginx sessionAffinity 쿠키 수집 실패 ===");
        log.error("=== 현재 파드는 정확한 sessionAffinity 쿠키를 얻을 수 없습니다 ===");
        log.error("=== k8s/nginx 설정 또는 로드밸랜싱 동작을 확인하시기 바랍니다 ===");

        // 잘못된 값(instanceId)을 Redis에 저장하지 않음
        cookieCollected = false;

        // 필요시 모니터링을 위한 메트릭 기록
        log.warn("파드 {}는 sessionAffinity 기능을 사용할 수 없는 상태입니다", instanceProvider.getInstanceId());
    }

    /**
     * Kafka로 쿠키 요청 이벤트 발행
     */
    private void publishCookieRequest() {
        try {
            KafkaServerEvent event = KafkaServerEvent.createCookieRequest(instanceProvider.getInstanceId());
            kafkaTemplate.send(KafkaTopic.SERVER_LIFECYCLE_EVENTS, KafkaSendKey.EVENT_TYPE, event);
            log.debug("쿠키 요청 이벤트 발행 완료: {}", instanceProvider.getInstanceId());
        } catch (Exception e) {
            log.error("쿠키 요청 이벤트 발행 실패: {}", e.getMessage());
        }
    }

    /**
     * 쿠키 응답 이벤트 발행
     */
    public void publishCookieResponse(String requesterId, String cookie) {
        try {
            KafkaServerEvent event = KafkaServerEvent.createCookieResponse(
                    instanceProvider.getInstanceId(), requesterId, cookie);

            kafkaTemplate.send(KafkaTopic.SERVER_LIFECYCLE_EVENTS, KafkaSendKey.EVENT_TYPE, event);
            log.info("쿠키 응답 이벤트 발행 완료: {} -> {} (쿠키: {})", instanceProvider.getInstanceId(), requesterId, cookie);
        } catch (Exception e) {
            log.error("쿠키 응답 이벤트 발행 실패: {}", e.getMessage());
        }
    }

    /**
     * 쿠키 응답 이벤트 처리
     */
    public void handleCookieResponse(KafkaServerEvent event) {
        try {
            RoutingCookieInfo cookieInfo = event.getCookieInfo();
            if (cookieInfo == null || !cookieInfo.isValidForResponse()) {
                log.warn("유효하지 않은 쿠키 응답 이벤트: {}", event);
                return;
            }

            String requesterId = cookieInfo.getRequesterId();
            String cookie = cookieInfo.getCookie();
            String responseFrom = cookieInfo.getResponseFrom();

            // 내가 요청한 쿠키 응답인지 확인
            if (instanceProvider.getInstanceId().equals(requesterId)) {
                log.info("쿠키 응답 수신: {} -> {} (쿠키: {})", responseFrom, requesterId, cookie);

                if (cookie != null && !cookie.isEmpty()) {
                    // 응답받은 실제 쿠키로 검증
                    if (validateActualCookie(cookie)) {
                        saveCookieAndComplete(cookie);
                        log.info("쿠키 응답으로부터 성공적으로 실제 nginx 쿠키 획득: {}", cookie);
                    }
                }
            }
        } catch (Exception e) {
            log.error("쿠키 응답 이벤트 처리 실패: {}", e.getMessage());
        }
    }

    /**
     * 쿠키 발견 이벤트 처리
     */
    public void handleCookieDiscovered(KafkaServerEvent event) {
        try {
            String discovererId = event.getInstanceId();

            if (!instanceProvider.getInstanceId().equals(discovererId)) {
                log.info("다른 인스턴스의 쿠키 발견 이벤트 수신: {}", discovererId);

                RoutingCookieInfo cookieInfo = event.getCookieInfo();
                if (cookieInfo != null && cookieInfo.isValidForDiscovery() && !cookieCollected) {
                    String discoveredCookie = cookieInfo.getCookie();
                    // 발견된 실제 쿠키로 검증
                    if (validateActualCookie(discoveredCookie)) {
                        saveCookieAndComplete(discoveredCookie);
                        log.info("발견된 쿠키로부터 성공적으로 실제 nginx 쿠키 획득: {}", discoveredCookie);
                    }
                }
            }
        } catch (Exception e) {
            log.error("쿠키 발견 이벤트 처리 실패: {}", e.getMessage());
        }
    }

    // 기존 유틸리티 메서드들 유지
    private String extractResponseInstanceId(HttpResponse response) {
        try {
            return new String(response.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            log.error("Response instanceId 추출 실패: {}", e.getMessage());
            return "";
        }
    }

    private String extractCookieFromResponse(HttpResponse response) {
        Header[] cookieHeaders = response.getHeaders("Set-Cookie");
        for (Header header : cookieHeaders) {
            String cookie = header.getValue();
            if (cookie.startsWith(RoutingCookie.CHATFORYOU_SERVER_COOKIE.getName() + "=")) {
                return extractCookieValue(cookie);
            }
        }
        return null;
    }

    private String extractCookieValue(String cookieString) {
        try {
            String[] parts = cookieString.split("=", 2);
            if (parts.length >= 2) {
                return parts[1].split(";")[0].trim();
            }
        } catch (Exception e) {
            log.warn("쿠키 값 추출 실패: {}", cookieString);
        }
        return null;
    }

    private void saveCookieAndComplete(String cookie) {
        redisService.saveInstanceCookieMapping(instanceProvider.getInstanceId(), cookie);
        publishCookieDiscovered(cookie);
        cookieCollected = true;
        log.info("=== 실제 nginx sessionAffinity 쿠키 수집 성공: [{}] :: [{}] ===", instanceProvider.getInstanceId(), cookie);
    }

    /**
     * 쿠키 발견 이벤트 발행
     */
    private void publishCookieDiscovered(String cookie) {
        try {
            KafkaServerEvent event = KafkaServerEvent.createCookieDiscovered(
                    instanceProvider.getInstanceId(), cookie);

            kafkaTemplate.send(KafkaTopic.SERVER_LIFECYCLE_EVENTS, KafkaSendKey.EVENT_TYPE, event);
            log.info("쿠키 발견 이벤트 발행 완료");
        } catch (Exception e) {
            log.error("쿠키 발견 이벤트 발행 실패: {}", e.getMessage());
        }
    }
}