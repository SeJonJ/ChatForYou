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

@Component
@RequiredArgsConstructor
@Slf4j
@EnableAsync
public class CookieCheckEvent {
    private final RedisService redisService;
    private final RoutingInstanceProvider instanceProvider;

    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${server.local:true}")
    private boolean isLocal;

    private static final String COOKIE_HEADER_NAME = "Set-Cookie";
    private static final String TARGET_COOKIE_NAME = "chatforyou-server";
    private String SELF_URL = "https://hjproject.kro.kr/chatforyou/health/cookie";


    /**
     * 앱 부팅 완료 후 비동기로 자기 쿠키 확보 시도
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void collectOwnCookieAsync() {
        if (isLocal) {
            redisService.saveInstanceCookieMapping(instanceProvider.getInstanceId(), instanceProvider.getInstanceId());
            return;
        }

        String currentInstanceId = instanceProvider.getInstanceId();

        while (true) {
            try {
                HttpResponse response = HttpUtil.getWithFullResponse(
                        SELF_URL,
                        new HttpHeaders(),
                        null
                );

                Header[] setCookieHeaders = response.getHeaders(COOKIE_HEADER_NAME);
                List<String> cookies = Arrays.stream(setCookieHeaders)
                        .map(Header::getValue)
                        .toList();

                for (String cookie : cookies) {
                    if (cookie.startsWith(TARGET_COOKIE_NAME + "=")) {
                        String cookieValue = extractCookieValue(cookie);
                        if (cookieValue != null && cookieValue.contains("|")) {
                            redisService.saveInstanceCookieMapping(currentInstanceId, cookieValue);
                            log.info("✅ Successfully collected own nginx cookie: {}", cookieValue);
                            return; // 쿠키 확보 성공 → 루프 종료
                        }
                    }
                }

                log.warn("Cookie not found yet for instance {}, retrying...", currentInstanceId);

            } catch (Exception e) {
                log.error("Failed to collect own cookie for instance {}, retrying...", currentInstanceId, e);
            }

            // 재시도 간격 (2초)
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
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
            log.warn("Failed to extract cookie value from: {}", cookieString);
        }
        return null;
    }
}