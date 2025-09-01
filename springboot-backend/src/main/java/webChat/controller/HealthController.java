package webChat.controller;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import webChat.model.routing.RoutingCookie;
import webChat.service.routing.CookieCheckEvent;
import webChat.service.routing.RoutingInstanceProvider;
import webChat.utils.StringUtil;

/**
 * health 체크를 위한 controller
 */
@RestController
@RequestMapping("/chatforyou/api/health")
@RequiredArgsConstructor
public class HealthController {
    private final RoutingInstanceProvider instanceProvider;
    private final CookieCheckEvent cookieCheckEvent;

    @Value("${cookie.check.domain:}")
    private String cookieCheckDomain;

    /**
     * 자신(pod) 의 cookie 값 및 instanceID return
     * @param response
     * @return
     */
    @GetMapping("/cookie")
    public ResponseEntity<String> cookieHealth(HttpServletResponse response) {
        if (StringUtil.isNullOrEmpty(cookieCheckDomain)) {
            response.addCookie(new jakarta.servlet.http.Cookie(RoutingCookie.CHATFORYOU_SERVER_COOKIE.getName(), instanceProvider.getInstanceId()));
        }
        // nginx 가 라우팅한 후 이 응답을 돌려줌
        return ResponseEntity.ok(instanceProvider.getInstanceId());
    }

    /**
     * readinessProbe 시 pod 가 자신의 쿠키를 확보했는지 확인
     * @return
     */
    @GetMapping("/readiness")
    public ResponseEntity<String> readiness() {
        if (cookieCheckEvent.isCookieCollected()) {
            return ResponseEntity.ok("READY");
        } else {
            return ResponseEntity.status(503).body("Cookie not yet collected");
        }
    }

    /**
     * livenessProbe 시 상태 확인
     * @return
     */
    @GetMapping("/liveness")
    public ResponseEntity<String> liveness() {
        return ResponseEntity.ok("ALIVE");
    }
}