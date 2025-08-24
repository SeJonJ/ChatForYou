package webChat.service.routing;

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
import webChat.service.redis.RedisService;
import webChat.utils.HttpUtil;
import java.util.Arrays;
import java.util.List;

/**
 * nginx cookie 를 체크를 위한 health check event
 */
@Component
@RequiredArgsConstructor
@Slf4j
@EnableAsync
public class CookieCheckEvent {
    private final RedisService redisService;
    private final RoutingInstanceProvider instanceProvider;

    @Value("${server.port:8080}")
    private int serverPort;

//    @EventListener(ApplicationReadyEvent.class)
//    @Async
//    public void collectOwnCookieAsync() {
//        try {
//            Thread.sleep(5000);
//            collectOwnCookie();
//        } catch (Exception e) {
//            this.handleLocalEnvironment();
//            log.error("Failed to collect own cookie", e);
//        }
//    }

    private void collectOwnCookie() {
        try {
            String selfUrl = "https://localhost:" + serverPort + "/health/cookie";
            // HttpUtil 사용해서 전체 응답 받기
            HttpResponse response = HttpUtil.getWithFullResponse(selfUrl, new HttpHeaders(), null);

            // Set-Cookie 헤더 추출
            Header[] setCookieHeaders = response.getHeaders("Set-Cookie");
            List<String> cookies = Arrays.stream(setCookieHeaders)
                    .map(Header::getValue)
                    .toList();

            String currentInstanceId = instanceProvider.getInstanceId();
            if (!cookies.isEmpty()) {  // null 체크 추가
                for (String cookie : cookies) {
                    if (cookie.startsWith("chatforyou-server=")) {
                        String cookieValue = extractCookieValue(cookie);
                        if (cookieValue != null && cookieValue.contains("|")) {
                            // nginx 형태 쿠키 발견!
                            redisService.saveInstanceCookieMapping(currentInstanceId, cookieValue);
                            log.info("Successfully collected own nginx cookie: {}", cookieValue);
                            return;
                        }
                    }
                }
            }
            this.handleLocalEnvironment();
        } catch (Exception e) {
            this.handleLocalEnvironment();
            log.error("Failed to collect own cookie via self-request", e);
        }
    }

    private String extractCookieValue(String cookieString) {
        try {
            String[] parts = cookieString.split("=", 2);
            if (parts.length >= 2) {
                // 세미콜론으로 분리해서 실제 값만 추출 (Path, MaxAge 등 제거)
                String value = parts[1].split(";")[0];
                return value.trim();
            }
        } catch (Exception e) {
            log.warn("Failed to extract cookie value from: {}", cookieString);
        }
        return null;
    }

    private void handleLocalEnvironment() {
        String currentInstanceId = instanceProvider.getInstanceId();
        redisService.saveInstanceCookieMapping(currentInstanceId, currentInstanceId);
        log.info("Fallback: Local development environment, using instanceId: {}", currentInstanceId);
    }
}
