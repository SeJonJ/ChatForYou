package webChat.service.routing;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.annotation.Lazy; // 이것만 추가!
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
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Random;

/**
 * Nginx가 발급하는 sessionAffinity 쿠키를 수집하고,
 * 내 instanceId와 쿠키를 Redis에 매핑해 저장하는 컴포넌트
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Getter
@EnableAsync
public class CookieCheckEvent {

    private final RedisService redisService;
    @Lazy // 이 어노테이션만 추가! 나머지는 모두 기존 그대로
    private final RoutingInstanceProvider instanceProvider;
    private final KafkaTemplate<String, KafkaEvent> kafkaTemplate;

    @Value("${cookie.check.domain:https://localhost:8443}")
    private String cookieCheckDomain;
    private volatile boolean cookieCollected = false;
    private final String COOKIE_CHECK_PATH = "/chatforyou/api/health/cookie";

    // Phase별 설정값
    private static final int PHASE1_TIMEOUT_MS = 5000;
    private static final int PHASE2_MAX_RETRIES = 5;
    private static final double CONFIDENCE_LEVEL = 0.90;

    @EventListener(WebServerInitializedEvent.class)
    @Async
    public void collectOwnCookieAsync() {
        log.info("=== 인스턴스 init 시작 ===");
        instanceProvider.initInstanceId(); // 기존 그대로!

        log.info("=== 하이브리드 쿠키 수집 시작 ===");

        // Phase 1: 파드간 협력
        String cookie = tryCollectFromPeers();
        if (cookie != null) {
            saveCookieAndComplete(cookie);
            return;
        }

        // Phase 2: 확률적 최적화
        cookie = tryOptimizedCollection();
        if (cookie != null) {
            saveCookieAndComplete(cookie);
            return;
        }

        // Phase 3: Fallback
        handleFallback();
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
                String predictedCookie = predictMyCookieFromPeers(peerCookies);
                if (predictedCookie != null && validatePredictedCookie(predictedCookie)) {
                    log.info("=== Phase 1 성공: 예측된 쿠키 검증 완료 ===");
                    return predictedCookie;
                }
            }

            log.info("=== Phase 1 실패: Phase 2로 이동 ===");
            return null;

        } catch (Exception e) {
            log.error("Phase 1 오류: {}", e.getMessage());
            return null;
        }
    }

    private String tryOptimizedCollection() {
        try {
            log.info("=== Phase 2: 확률적 최적화 시작 ===");

            int activePods = instanceProvider.getActiveServers().size(); // 기존 그대로!
            int optimalRetries = calculateOptimalRetries(activePods, CONFIDENCE_LEVEL);
            int maxRetries = Math.min(optimalRetries, PHASE2_MAX_RETRIES);

            log.info("활성 파드 수: {}, 최적 시도 횟수: {}, 실제 시도 횟수: {}",
                    activePods, optimalRetries, maxRetries);

            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    HttpResponse response = HttpUtil.getWithFullResponse(
                            cookieCheckDomain + COOKIE_CHECK_PATH, new HttpHeaders(), null);

                    String respInstanceId = extractResponseInstanceId(response);
                    if (respInstanceId.equals(instanceProvider.getInstanceId())) { // 기존 그대로!
                        String cookie = extractCookieFromResponse(response);
                        log.info("=== Phase 2 성공: {}회 시도만에 쿠키 획득 ===", attempt);
                        return cookie;
                    }

                    long delay = Math.min(800 * (long)Math.pow(1.4, attempt), 3000);
                    delay += new Random().nextInt(500);
                    Thread.sleep(delay);

                } catch (Exception e) {
                    log.warn("Phase 2 시도 {}/{} 실패: {}", attempt, maxRetries, e.getMessage());
                }
            }

            log.info("=== Phase 2 실패: Phase 3 Fallback으로 이동 ===");
            return null;

        } catch (Exception e) {
            log.error("Phase 2 오류: {}", e.getMessage());
            return null;
        }
    }

    private void handleFallback() {
        String currentInstanceId = instanceProvider.getInstanceId(); // 기존 그대로!
        redisService.saveInstanceCookieMapping(currentInstanceId, currentInstanceId);
        log.warn("=== Phase 3 Fallback: instanceId를 쿠키로 사용 - {} ===", currentInstanceId);
        cookieCollected = false;
    }

    /**
     * Kafka로 쿠키 요청 이벤트 발행 - CookieInfo 사용
     */
    private void publishCookieRequest() {
        try {
            KafkaServerEvent event = KafkaServerEvent.createCookieRequest(instanceProvider.getInstanceId());

            kafkaTemplate.send(KafkaTopic.SERVER_LIFECYCLE_EVENTS, KafkaSendKey.EVENT_TYPE, event);
            log.info("쿠키 요청 이벤트 발행 완료: {}", instanceProvider.getInstanceId());
        } catch (Exception e) {
            log.error("쿠키 요청 이벤트 발행 실패: {}", e.getMessage());
        }
    }

    /**
     * 쿠키 응답 이벤트 발행 - CookieInfo 사용
     */
    public void publishCookieResponse(String requesterId, String cookie) {
        try {
            KafkaServerEvent event = KafkaServerEvent.createCookieResponse(
                    instanceProvider.getInstanceId(), requesterId, cookie);

            kafkaTemplate.send(KafkaTopic.SERVER_LIFECYCLE_EVENTS, KafkaSendKey.EVENT_TYPE, event);
            log.info("쿠키 응답 이벤트 발행 완료: {} -> {}", instanceProvider.getInstanceId(), requesterId);
        } catch (Exception e) {
            log.error("쿠키 응답 이벤트 발행 실패: {}", e.getMessage());
        }
    }

    /**
     * 쿠키 응답 이벤트 처리 - CookieInfo 사용
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
                    String predictedCookie = predictMyCookieFromPattern(cookie, responseFrom);
                    if (predictedCookie != null && validatePredictedCookie(predictedCookie)) {
                        saveCookieAndComplete(predictedCookie);
                        log.info("쿠키 응답으로부터 성공적으로 쿠키 획득: {}", predictedCookie);
                    }
                }
            }
        } catch (Exception e) {
            log.error("쿠키 응답 이벤트 처리 실패: {}", e.getMessage());
        }
    }

    /**
     * 쿠키 발견 이벤트 처리 - CookieInfo 사용
     */
    public void handleCookieDiscovered(KafkaServerEvent event) {
        try {
            String discovererId = event.getInstanceId();

            if (!instanceProvider.getInstanceId().equals(discovererId)) {
                log.info("다른 인스턴스의 쿠키 발견 이벤트 수신: {}", discovererId);

                RoutingCookieInfo cookieInfo = event.getCookieInfo();
                if (cookieInfo != null && cookieInfo.isValidForDiscovery() && !cookieCollected) {
                    String discoveredCookie = cookieInfo.getCookie();
                    String predictedCookie = predictMyCookieFromPattern(discoveredCookie, discovererId);
                    if (predictedCookie != null && validatePredictedCookie(predictedCookie)) {
                        saveCookieAndComplete(predictedCookie);
                        log.info("발견된 쿠키로부터 성공적으로 내 쿠키 획득: {}", predictedCookie);
                    }
                }
            }
        } catch (Exception e) {
            log.error("쿠키 발견 이벤트 처리 실패: {}", e.getMessage());
        }
    }

    private String predictMyCookieFromPeers(Map<String, String> peerCookies) {
        String myInstanceId = instanceProvider.getInstanceId();

        for (Map.Entry<String, String> entry : peerCookies.entrySet()) {
            String peerInstanceId = entry.getKey();
            String peerCookie = entry.getValue();

            String predictedCookie = predictMyCookieFromPattern(peerCookie, peerInstanceId);
            if (predictedCookie != null && validatePredictedCookie(predictedCookie)) {
                return predictedCookie;
            }
        }
        return null;
    }

    private String predictMyCookieFromPattern(String peerCookie, String peerInstanceId) {
        String myInstanceId = instanceProvider.getInstanceId();

        // 패턴 1: "instanceId|해시값" 형태
        if (peerCookie.contains("|")) {
            String[] parts = peerCookie.split("\\|");
            if (parts.length == 2 && parts[0].equals(peerInstanceId)) {
                return myInstanceId + "|" + generateHash(myInstanceId);
            }
        }

        // 패턴 2: 단순 해시값인 경우
        if (peerCookie.length() > 10 && !peerCookie.equals(peerInstanceId)) {
            return generateHash(myInstanceId);
        }

        // 패턴 3: instanceId 그대로 사용하는 경우
        if (peerCookie.equals(peerInstanceId)) {
            return myInstanceId;
        }

        return null;
    }

    private boolean validatePredictedCookie(String predictedCookie) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.add("Cookie", RoutingCookie.CHATFORYOU_SERVER_COOKIE.getName() + "=" + predictedCookie);

            HttpResponse response = HttpUtil.getWithFullResponse(
                    cookieCheckDomain + COOKIE_CHECK_PATH, headers, null);

            String respInstanceId = extractResponseInstanceId(response);
            return respInstanceId.equals(instanceProvider.getInstanceId());

        } catch (Exception e) {
            log.warn("예측 쿠키 검증 실패: {}", e.getMessage());
            return false;
        }
    }

    private int calculateOptimalRetries(int podCount, double confidence) {
        if (podCount <= 1) return 1;
        double probability = 1.0 / podCount;
        int optimal = (int) Math.ceil(Math.log(1 - confidence) / Math.log(1 - probability));
        return Math.max(optimal, 2);
    }

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

    private String generateHash(String instanceId) {
        return Integer.toHexString(instanceId.hashCode()).substring(0, 8);
    }

    private void saveCookieAndComplete(String cookie) {
        redisService.saveInstanceCookieMapping(instanceProvider.getInstanceId(), cookie);
        publishCookieDiscovered(cookie);
        cookieCollected = true;
        log.info("=== 쿠키 수집 완료: {} ===", cookie);
    }

    /**
     * 쿠키 발견 이벤트 발행 - CookieInfo 사용
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