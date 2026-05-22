package webChat.service.routing;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpHeaders;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Component;
import webChat.model.kafka.*;
import webChat.model.redis.DataType;
import webChat.model.redis.RedisKeyPrefix;
import webChat.model.routing.RoutingCookie;
import webChat.model.routing.RoutingCookieInfo;
import webChat.service.redis.RedisService;
import webChat.utils.HttpUtil;
import webChat.utils.StringUtil;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

/**
 * Nginx가 발급하는 sessionAffinity 쿠키를 수집하고,
 * 내 instanceId와 쿠키를 Redis에 매핑해 저장하는 컴포넌트
 *
 * - Phase 1: 파드간 협력 유지 (첫 번째 파드 성공 방식)
 * - Phase 2: 85% 목표 성공률 기반 Dynamic Retry Count로 요청 횟수 최적화
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
    private final ApplicationContext applicationContext;

    @Value("${cookie.check.domain:}")
    private String cookieCheckDomain;
    private volatile boolean cookieCollected = false;
    private final String COOKIE_CHECK_PATH = "/chatforyou/api/health/cookie";

    // Phase별 개선된 설정값
    private static final int PHASE1_TIMEOUT_MS = 5000;
    private static final int PHASE1_MAX_RETRIES = 3;
    private static final double TARGET_SUCCESS_RATE = 0.85; // 85% 목표 성공률로 최적화
    private static final int MIN_RETRY_COUNT = 3; // 최소 시도 횟수
    private static final int MAX_RETRY_COUNT = 25; // 최대 시도 횟수 (안전장치)
    private static final int BASE_INTERVAL_MS = 50; // 기본 간격 50ms
    private static final double BACKOFF_MULTIPLIER = 1.5; // 지수적 백오프 승수
    private static final int MAX_INTERVAL_MS = 1000; // 최대 간격 1초

    /**
     * 현재 인스턴스의 sessionAffinity 쿠키를 수집하거나 로컬 대체 쿠키를 초기화한다.
     * 호출 순서는 RoutingBootstrapCoordinator 가 관리한다.
     * 이 메서드는 listener 준비 확인 이후에만 실행된다는 전제에서 쿠키 수집 자체에만 집중한다.
     *
     * @throws InterruptedException Kafka consumer 준비 대기 또는 retry sleep 중 인터럽트가 발생한 경우
     */
    public void collectOwnCookie() throws InterruptedException {
        log.info("=== 최적화 쿠키 수집 시작 ===");

        // 로컬환경인 경우 쿠키 탐색 무시
        if (!isCookieCheckDomainConfigured()) {
            String localCookie = "local_cookie|" + this.instanceProvider.getInstanceId();
            saveCookieAndComplete(localCookie);
            return;
        }

        // Phase 1: 파드간 협력
        String cookie = collectFromPeers();
        if (cookie != null) {
            saveCookieAndComplete(cookie);
            return;
        }

        // Phase 2: 85% 목표 성공률 기반 최적화
        cookie = tryOptimizedCollection(instanceProvider.getActiveServers().size(), false);
        if (cookie != null) {
            saveCookieAndComplete(cookie);
            return;
        }

        // Phase 3: 개선된 Fallback
        handleFallback();
    }

    /**
     * Phase 1: 파드간 협력
     */
    private String collectFromPeers() throws InterruptedException {
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

                for (Map.Entry<String, String> entry : peerCookies.entrySet()) {
                    String peerInstanceId = entry.getKey();
                    String peerCookie = entry.getValue();

                    log.debug("발견된 파드: {} -> 쿠키: {}", peerInstanceId, peerCookie);

                    // 실제 nginx 응답을 통한 검증만 수행
                    if (validateCookie(peerCookie)) {
                        log.info("=== Phase 1 성공: 기존 쿠키 검증 완료 ===");
                        return peerCookie;
                    }
                }
            }

            log.info("=== Phase 1 실패: Phase 2로 이동 ===");
            return null;

        } catch (Exception e) {
            log.error("Phase 1 오류", e);
            return null;
        }
    }

    /**
     * Phase 2: Dynamic Retry Count 최적화
     */
    private String tryOptimizedCollection(int activePods, boolean isFallback) {
        try {
            log.info("=== Phase 2: 네트워크 요청을 통해 확률적 cookie 체크 수행 ===");

            int optimalRetries = calculateRetryCount(activePods, isFallback);

            log.info("활성 파드 수: {}, 시도 횟수: {}", activePods, optimalRetries);

            int currentInterval = BASE_INTERVAL_MS;

            for (int attempt = 1; attempt <= optimalRetries; attempt++) {
                // Early termination: 다른 파드가 성공했는지 체크
                if (cookieCollected) {
                    log.info("=== Phase 2 조기 종료: 다른 파드가 이미 성공 ===");
                    return null;
                }

                try {
                    HttpResponse response = HttpUtil.getWithFullResponse(
                            cookieCheckDomain + COOKIE_CHECK_PATH, new HttpHeaders(), null);

                    String respInstanceId = extractResponseInstanceId(response);
                    String cookie = extractCookieFromResponse(response);

                    if (!StringUtil.isNullOrEmpty(cookie) && cookie.contains("|")) {
                        if (respInstanceId.equals(instanceProvider.getInstanceId())) {
                            log.info("=== Phase 2 성공: {} 회 시도만에 실제 nginx 쿠키 획득 ===", attempt);
                            return cookie;
                        } else {
                            this.publishCookieResponse(respInstanceId, cookie);
                        }
                    }

                    // Exponential backoff with jitter
                    Thread.sleep(currentInterval + new Random().nextInt(50));
                    currentInterval = Math.min((int)(currentInterval * BACKOFF_MULTIPLIER), MAX_INTERVAL_MS);

                } catch (Exception e) {
                    log.warn("Phase 2 시도 {}/{} 실패: {}", attempt, optimalRetries, e.getMessage());
                    Thread.sleep(currentInterval);
                }

                // 진행상황 로깅 (매 5회마다)
                if (attempt % 5 == 0) {
                    log.info("Phase 2 진행중: {}/{} 시도 완료 (현재 간격: {}ms)", attempt, optimalRetries, currentInterval);
                }
            }

            if (isFallback) {
                log.info("=== Phase 2 실패: {} 회 최적화 시도 실패, Phase 3 Fallback 으로 이동 ===", optimalRetries);
            } else {
                log.info("=== Phase 2 재시도 실패: {} 회 최적화 시도 실패, 파드 재시작 진행 ===", optimalRetries);
            }
            return null;

        } catch (Exception e) {
            log.error("Phase 2 오류", e);
            return null;
        }
    }

    /**
     * 성공률(TARGET_SUCCESS_RATE) 기반 최적 재시도 횟수 계산
     * 수학적 공식: k >= ln(1 - TARGET_SUCCESS_RATE) / ln(1 - 1/N)
     * @param activePods 활성 파드 수 (N)
     * @return 최적 재시도 횟수
     */
    private int calculateRetryCount(int activePods, boolean isFallback) {
        if (activePods <= 1) {
            return MIN_RETRY_COUNT;
        }

        // fallback 시 90% 를 목표로 재시도
        double targetRate = isFallback ? 0.90 : TARGET_SUCCESS_RATE;

        // p = TARGET_SUCCESS_RATE (0.85)
        // N = activePods
        double probability = 1.0 / activePods;
        double optimalRetries = Math.log(1 - targetRate) / Math.log(1 - probability);

        int result = (int) Math.ceil(optimalRetries);

        // 최소값과 최대값 사이로 제한
        result = Math.max(result, MIN_RETRY_COUNT);
        result = Math.min(result, MAX_RETRY_COUNT);

        log.info("최적화 계산: N = [{}], 목표성공률 = [{}%], 계산된시도횟수 = [{}], 적용시도횟수 = [{}]",
                activePods, (int)(targetRate * 100), (int)Math.ceil(optimalRetries), result);

        return result;
    }

    /**
     * 실제 쿠키 값을 health endpoint 응답의 instanceId 기준으로 검증한다.
     *
     * @param cookie 검증 대상 sessionAffinity 쿠키
     * @return 현재 인스턴스로 라우팅되는 쿠키이면 true, 아니면 false
     */
    private boolean validateCookie(String cookie) {
        if (!isCookieCheckDomainConfigured()) {
            log.debug("cookie.check.domain 미설정 - HTTP 쿠키 검증 스킵");
            return false;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.add("Cookie", RoutingCookie.CHATFORYOU_SERVER_COOKIE.getName() + "=" + cookie);

            HttpResponse response = HttpUtil.getWithFullResponse(
                    cookieCheckDomain + COOKIE_CHECK_PATH, headers, null);

            String respInstanceId = extractResponseInstanceId(response);
            boolean isValid = respInstanceId.equals(instanceProvider.getInstanceId());

            log.info("=== 실제 쿠키 검증 결과 :: 쿠키 = [{}], 응답 instanceId = [{}], 내 instanceId = [{}], 결과 = [{}]",
                    cookie, respInstanceId, instanceProvider.getInstanceId(), isValid);

            return isValid;

        } catch (Exception e) {
            log.warn("실제 쿠키 검증 실패: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 개선된 Fallback 
     */
    private void handleFallback() {
        // Phase 2 retry : 90% 목표 성공률 기반 최적화
        String cookie = tryOptimizedCollection(instanceProvider.getActiveServers().size(), true);
        if (cookie != null) {
            saveCookieAndComplete(cookie);
            return;
        }

        log.error("=== Phase 3 Fallback: 최적화 방법으로 nginx sessionAffinity 쿠키 수집 실패 ===");
        log.error("=== 현재 파드에 대해 정확한 sessionAffinity 쿠키 수집 불가 ===");
        log.error("=== k8s/nginx 설정 또는 로드밸랜싱 동작을 확인 필요 ===");

        // 잘못된 값(instanceId)을 Redis에 저장하지 않음
        cookieCollected = false;

        // 3초 후 애플리케이션 graceful shutdown
        scheduleApplicationShutdown();

        // 필요시 모니터링을 위한 메트릭 기록
        log.warn("파드 {}는 sessionAffinity 기능을 사용할 수 없는 상태입니다", instanceProvider.getInstanceId());
    }

    /**
     * 파드 재시작을 위한 애플리케이션 graceful shutdown
     */
    private void scheduleApplicationShutdown() {
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(3000); // 3초 대기 (로그 출력 및 정리 시간)
                log.info("=== 파드 재시작을 위한 애플리케이션 graceful shutdown 시작 ===");
                int exitCode = SpringApplication.exit(applicationContext, () -> 1);
                System.exit(exitCode);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("애플리케이션 종료 중 인터럽트 발생", e);
            }
        });
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
            log.error("쿠키 요청 이벤트 발행 실패", e);
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
            log.error("쿠키 응답 이벤트 발행 실패", e);
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
                log.debug("쿠키 응답 수신: {} -> {} (쿠키: {})", responseFrom, requesterId, cookie);

                if (cookie != null && !cookie.isEmpty()) {
                    if (!isCookieCheckDomainConfigured()) {
                        log.debug("cookie.check.domain 미설정 - 쿠키 응답 검증 스킵: requesterId={}, responseFrom={}",
                                requesterId, responseFrom);
                        return;
                    }

                    // 응답받은 실제 쿠키로 검증
                    if (validateCookie(cookie)) {
                        saveCookieAndComplete(cookie);
                        log.debug("쿠키 응답으로부터 성공적으로 실제 nginx 쿠키 획득: {}", cookie);
                    }
                }
            }
        } catch (Exception e) {
            log.error("쿠키 응답 이벤트 처리 실패", e);
        }
    }

    /**
     * 쿠키 발견 이벤트 처리 
     */
    public void handleCookieDiscovered(KafkaServerEvent event) {
        try {
            String discovererId = event.getInstanceId();

            if (!instanceProvider.getInstanceId().equals(discovererId)) {
                log.debug("다른 인스턴스의 쿠키 발견 이벤트 수신: {}", discovererId);

                RoutingCookieInfo cookieInfo = event.getCookieInfo();
                if (cookieInfo != null && cookieInfo.isValidForDiscovery() && !cookieCollected) {
                    if (!isCookieCheckDomainConfigured()) {
                        log.debug("cookie.check.domain 미설정 - 쿠키 발견 검증 스킵: discovererId={}", discovererId);
                        return;
                    }

                    String discoveredCookie = cookieInfo.getCookie();
                    // 발견된 실제 쿠키로 검증
                    if (validateCookie(discoveredCookie)) {
                        saveCookieAndComplete(discoveredCookie);
                        log.debug("발견된 쿠키로부터 성공적으로 실제 nginx 쿠키 획득: {}", discoveredCookie);
                    }
                }
            }
        } catch (Exception e) {
            log.error("쿠키 발견 이벤트 처리 실패", e);
        }
    }

    /**
     * 실제 도메인 기반 쿠키 검증을 수행할 수 있는 설정 상태인지 확인한다.
     *
     * @return cookie.check.domain 설정 여부
     */
    private boolean isCookieCheckDomainConfigured() {
        return !StringUtil.isNullOrEmpty(cookieCheckDomain);
    }

    // 기존 유틸리티 메서드들 완전 유지
    private String extractResponseInstanceId(HttpResponse response) {
        try {
            return new String(response.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            log.error("Response instanceId 추출 실패", e);
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
        String instanceCookie = redisService.getRedisDataByDataType(RedisKeyPrefix.INSTANCE_COOKIE_PREFIX.getPrefix() + instanceProvider.getInstanceId(), DataType.INSTANCE_COOKIE, String.class);
        if(!StringUtil.isNullOrEmpty(cookieCheckDomain) && !StringUtil.isNullOrEmpty(instanceCookie) && instanceCookie.contains("|")) {
            log.warn("=== 이미 쿠키가 존재 : [{}] :: [{}] ===", instanceProvider.getInstanceId(), instanceCookie);
            return;
        }
        redisService.saveInstanceCookieMapping(instanceProvider.getInstanceId(), cookie);
        publishCookieDiscovered(cookie);
        cookieCollected = true;
        log.info("=== nginx sessionAffinity 쿠키 수집 성공: [{}] :: [{}] ===", instanceProvider.getInstanceId(), cookie);
    }

    /**
     * 쿠키 발견 이벤트 발행 
     */
    private void publishCookieDiscovered(String cookie) {
        try {
            KafkaServerEvent event = KafkaServerEvent.createCookieDiscovered(
                    instanceProvider.getInstanceId(), cookie);

            kafkaTemplate.send(KafkaTopic.SERVER_LIFECYCLE_EVENTS, KafkaSendKey.EVENT_TYPE, event);
            log.debug("쿠키 발견 이벤트 발행 완료");
        } catch (Exception e) {
            log.error("쿠키 발견 이벤트 발행 실패", e);
        }
    }
}
