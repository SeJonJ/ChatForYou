package webChat.controller;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import webChat.service.routing.ReadinessState;
import webChat.service.routing.RoutingInstanceProvider;

/**
 * health 체크를 위한 controller
 */
@RestController
@RequestMapping("/chatforyou/api/health")
@RequiredArgsConstructor
@Slf4j
public class HealthController {
    private final RoutingInstanceProvider instanceProvider;
    private final ReadinessState readinessState;

    /**
     * 자신(pod) 의 cookie 값 및 instanceID return
     * @param response
     * @return
     */
    @GetMapping("/cookie")
    public ResponseEntity<String> cookieHealth(HttpServletResponse response) {
//        log.info("cookie : [{}] :: instanceId : [{}]", routingService.getCookie(request, RoutingCookie.CHATFORYOU_SERVER_COOKIE), instanceProvider.getInstanceId());
        // nginx 가 라우팅한 후 이 응답을 돌려줌
        return ResponseEntity.ok(instanceProvider.getInstanceId());
    }

    /**
     * readinessProbe 판정. 종료 중이면 트래픽을 받지 않도록 drain(503),
     * 그 외 앱 기동이 완료되면 READY 를 반환한다.
     * @return
     */
    @GetMapping("/readiness")
    public ResponseEntity<String> readiness() {
        if (instanceProvider.isShuttingDown()) {
            return ResponseEntity.status(503).body("Server is shutting down");
        }

        if (!readinessState.isReady()) {
            return ResponseEntity.status(503).body("Starting");
        }

        return ResponseEntity.ok("READY");
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
