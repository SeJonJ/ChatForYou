package webChat.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.coyote.BadRequestException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import webChat.model.login.GoogleOAuth;
import webChat.model.response.common.ChatForYouResponse;
import webChat.model.response.common.QRLoginResponse;
import webChat.service.login.LoginService;

@RestController
@Slf4j
@RequestMapping("/chatforyou/api/login")
@RequiredArgsConstructor
public class LoginController {

    private final LoginService loginService;

    @GetMapping("/chatlogin")
    public String goLogin() {
        // docker 로 실행 시 thymeleaf 에러 발생 => 경로 문제로 인한 에러인듯
        // 따라서 기존의 /chatlogin -> chatlogin 으로 변경
        return "chatlogin";
    }

    @PostMapping(value = "/googleOauth", produces = "application/json; charset=UTF8")
    public ResponseEntity<ChatForYouResponse> googleOauth(@RequestParam("accessToken") String accessToken,
                                                          @RequestParam("refreshToken") String refreshToken,
                                                          @RequestParam("name") String name,
                                                          @RequestParam("email") String email,
                                                          @RequestParam("emailVerified") boolean emailVerified,
                                                          @RequestParam("photo") String photo) throws Exception {

        GoogleOAuth auth = GoogleOAuth.of(accessToken, refreshToken, name, email, emailVerified, photo);
        GoogleOAuth response = loginService.checkSocialUser(auth);

        return ResponseEntity.ok(ChatForYouResponse.builder()
                .result("success")
                .data(response)
                .build());
    }

    @PostMapping(value = "/logout", produces = "application/json; charset=UTF8")
    public ResponseEntity<ChatForYouResponse> logout(@RequestHeader("Authorization") String authorization,
                                                     @RequestParam("email") String email) throws Exception {

        loginService.logout(authorization, email);

        return ResponseEntity.ok(ChatForYouResponse.builder()
                .result("success")
                .build());
    }

    @GetMapping(value = "/qr/create", produces = "application/json; charset=UTF8")
    public ResponseEntity<ChatForYouResponse> createQrSession() throws Exception {
        log.info("QR 세션 생성 요청");

        QRLoginResponse response = loginService.createQRSession();

        return ResponseEntity.ok(ChatForYouResponse.builder()
                .result("success")
                .data(response)
                .build());
    }

    @PostMapping(value = "/qr/authenticate", produces = "application/json; charset=UTF8")
    public ResponseEntity<ChatForYouResponse> authenticateQRSession(
            @RequestParam("sessionId") String sessionId,
                    @RequestParam("accessToken") String accessToken,
                    @RequestParam("refreshToken") String refreshToken,
                    @RequestParam("name") String name,
                    @RequestParam("email") String email,
                    @RequestParam("emailVerified") boolean emailVerified,
                    @RequestParam("photo") String photo) throws BadRequestException {

        log.info("QR 세션 인증 요청: sessionId={}", sessionId);

        GoogleOAuth auth = GoogleOAuth.of(accessToken, refreshToken, name, email, emailVerified, photo);
        
        loginService.authenticateSession(sessionId, auth);
        
        return ResponseEntity.ok(ChatForYouResponse.builder()
                .result("success")
                .data(auth)
                .build());
    }


    @GetMapping(value = "/qr/status/{sessionId}", produces = "application/json; charset=UTF8")
    public ResponseEntity<ChatForYouResponse> checkQRSessionStatus(
            @PathVariable("sessionId") String sessionId) throws BadRequestException {

        log.debug("QR 세션 상태 조회: sessionId={}", sessionId);

        QRLoginResponse response = loginService.getSessionStatus(sessionId);

        return ResponseEntity.ok(ChatForYouResponse.builder()
                .result("success")
                .data(response)
                .build());
        }
}
