package webChat.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequestMapping("/health")
@RestController
public class HealthController {
    @GetMapping("/cookie")
    public ResponseEntity<String> cookieTest() {
        return ResponseEntity.ok("Health check for cookie collection");
    }
}
