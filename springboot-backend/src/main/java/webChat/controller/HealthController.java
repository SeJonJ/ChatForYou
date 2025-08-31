package webChat.controller;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import webChat.service.routing.RoutingInstanceProvider;

@RequestMapping("/chatforyou/health")
@RequiredArgsConstructor
@RestController
public class HealthController {
    private final RoutingInstanceProvider instanceProvider;

    @GetMapping("/cookie")
    public ResponseEntity<String> cookieHealth(HttpServletResponse response) {
        // nginx 가 라우팅한 후 이 응답을 돌려줌
        return ResponseEntity.ok(instanceProvider.getInstanceId());
    }
}