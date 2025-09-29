package webChat.service.routing;

import lombok.extern.slf4j.Slf4j;
import org.apache.http.Header;
import org.apache.http.HttpResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import webChat.service.redis.RedisService;
import webChat.utils.HttpUtil;
import java.util.Arrays;
import java.util.List;

@SpringBootTest
@TestPropertySource(locations = "/application.properties")
@Slf4j
public class CookieCheckEventTest {
    @Autowired
    private CookieCheckEvent cookieCheckEvent;
    @Autowired
    private RoutingService routingService;
    @Autowired
    private RedisService redisService;
    @Autowired
    private RoutingInstanceProvider instanceProvider;

    @Test
    @DisplayName("cookie 체크")
    public void cookieCheck() {
        String selfUrl = "https://hjproject.kro.kr/chatforyou/health/cookie";
        // HttpUtil 사용해서 전체 응답 받기
        try {
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

        } catch (Exception e) {
            String currentInstanceId = instanceProvider.getInstanceId();
            redisService.saveInstanceCookieMapping(currentInstanceId, currentInstanceId);
            log.info("Fallback: Local development environment, using instanceId: {}", currentInstanceId);
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
}