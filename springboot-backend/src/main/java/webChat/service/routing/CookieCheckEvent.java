package webChat.service.routing;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Component;
import webChat.model.routing.RoutingCookie;
import webChat.service.redis.RedisService;
import webChat.utils.HttpUtil;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
    private final RoutingInstanceProvider instanceProvider;

    private volatile boolean cookieCollected = false; // readiness 체크용 플래그

    @Value("${cookie.check.domain:https://localhost:8443}")
    private String cookieCheckDomain;
    private final String COOKIE_CHECK_PATH = "/chatforyou/api/health/cookie";

    private static final int MAX_RETRIES = 20;
    private static final long RETRY_INTERVAL_MS = 2000;

    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void collectOwnCookieAsync() {
        String currentInstanceId = instanceProvider.getInstanceId();

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                if(currentInstanceId == null) currentInstanceId = instanceProvider.getInstanceId();

                HttpResponse response = HttpUtil.getWithFullResponse(cookieCheckDomain + COOKIE_CHECK_PATH, new HttpHeaders(), null);

                String respInstanceId = new String(response.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8).trim();
                List<String> cookies = Arrays.stream(response.getHeaders("Set-Cookie"))
                        .map(Header::getValue)
                        .toList();

                if (respInstanceId.equals(currentInstanceId)) {
                    for (String cookie : cookies) {
                        if (cookie.startsWith(RoutingCookie.CHATFORYOU_SERVER_COOKIE.getName() + "=")) {
                            String cookieValue = extractCookieValue(cookie);
                            redisService.saveInstanceCookieMapping(currentInstanceId, cookieValue);
                            log.info("=== Collected cookie for instance {} → {}", currentInstanceId, cookieValue);

                            cookieCollected = true; // readiness 통과
                            return;
                        }
                    }
                } else if (attempt == MAX_RETRIES) {
                    // 실패 시 fallback → Redis 에 가장 마지막에 확인한 cookie 로 세팅
                    for (String cookie : cookies) {
                        if (cookie.startsWith(RoutingCookie.CHATFORYOU_SERVER_COOKIE.getName() + "=")) {
                            String cookieValue = extractCookieValue(cookie);
                            redisService.saveInstanceCookieMapping(currentInstanceId, cookieValue);
                            log.error("=== Collected cookie is FAIL ==> instanceID :: {} cookieValue :: {}", currentInstanceId, cookieValue);

                            cookieCollected = true; // readiness 통과
                            return;
                        }
                    }
                } else {
                    log.warn("=== Routed to another instance {} (expected {}). Retrying... [attempt {}/{}]",
                            respInstanceId, currentInstanceId, attempt, MAX_RETRIES);
                }

                TimeUnit.MILLISECONDS.sleep(RETRY_INTERVAL_MS);

            } catch (Exception e) {
                log.error("=== Error collecting cookie (attempt {}/{}). Retrying...", attempt, MAX_RETRIES, e);
                try {
                    TimeUnit.MILLISECONDS.sleep(RETRY_INTERVAL_MS);
                } catch (InterruptedException ignored) {}
            }
        }
    }

    private String extractCookieValue(String cookieString) {
        try {
            String[] parts = cookieString.split("=", 2);
            if (parts.length >= 2) {
                return parts[1].split(";")[0].trim();
            }
        } catch (Exception e) {
            log.warn("=== Failed to extract cookie value from: {}", cookieString);
        }
        return null;
    }

    private void handleFallback(String currentInstanceId) {
        redisService.saveInstanceCookieMapping(currentInstanceId, currentInstanceId);
        log.warn("=== Fallback: Could not collect nginx cookie for {}. Using instanceId as cookie.", currentInstanceId);

        cookieCollected = false; // readiness는 실패 상태 유지
    }
}