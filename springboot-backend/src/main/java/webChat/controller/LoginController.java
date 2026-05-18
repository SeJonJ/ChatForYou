package webChat.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    /**
     * 로그인 화면 뷰 이름을 반환한다.
     *
     * @return 로그인 뷰 이름
     */
    @GetMapping("/chatlogin")
    public String goLogin() {
        // docker 로 실행 시 thymeleaf 에러 발생 => 경로 문제로 인한 에러인듯
        // 따라서 기존의 /chatlogin -> chatlogin 으로 변경
        return "chatlogin";
    }

    /**
     * Google OAuth 로그인 정보를 검증하고 로그인 세션을 생성한다.
     *
     * @param accessToken Google access token
     * @param refreshToken Google refresh token
     * @param name 사용자 이름
     * @param email 사용자 이메일
     * @param emailVerified 이메일 인증 여부
     * @param photo 프로필 이미지 URL
     * @return 로그인 결과
     */
    @PostMapping(value = "/googleOauth", produces = "application/json; charset=UTF8")
    public ResponseEntity<ChatForYouResponse> googleOauth(@RequestParam("accessToken") String accessToken,
                                                          @RequestParam("refreshToken") String refreshToken,
                                                          @RequestParam("name") String name,
                                                          @RequestParam("email") String email,
                                                          @RequestParam("emailVerified") boolean emailVerified,
                                                          @RequestParam("photo") String photo) {

        GoogleOAuth response = loginService.checkSocialUser(accessToken, refreshToken, name, email, emailVerified, photo);

        return ResponseEntity.ok(ChatForYouResponse.ofSuccess(response));
    }

    /**
     * 로그인 정보를 제거하고 세션을 종료한다.
     *
     * @param authorization 인증 헤더
     * @param email 사용자 이메일
     * @return 로그아웃 결과
     */
    @PostMapping(value = "/logout", produces = "application/json; charset=UTF8")
    public ResponseEntity<ChatForYouResponse> logout(@RequestHeader("Authorization") String authorization,
                                                     @RequestParam("email") String email) {

        loginService.logout(authorization, email);

        return ResponseEntity.ok(ChatForYouResponse.ofSuccess(null));
    }

    /**
     * QR 로그인 세션과 QR 이미지를 생성한다.
     *
     * @return QR 로그인 세션 정보
     */
    @GetMapping(value = "/qr/create", produces = "application/json; charset=UTF8")
    public ResponseEntity<ChatForYouResponse> createQrSession() {
        log.debug("QR 세션 생성 요청");

        QRLoginResponse response = loginService.createQRSession();

        return ResponseEntity.ok(ChatForYouResponse.ofSuccess(response));
    }

    /**
     * 모바일에서 스캔한 QR 세션에 로그인 정보를 연결한다.
     *
     * @param sessionId QR 세션 ID
     * @param accessToken Google access token
     * @param refreshToken Google refresh token
     * @param name 사용자 이름
     * @param email 사용자 이메일
     * @param emailVerified 이메일 인증 여부
     * @param photo 프로필 이미지 URL
     * @return 인증된 로그인 결과
     */
    @PostMapping(value = "/qr/authenticate", produces = "application/json; charset=UTF8")
    public ResponseEntity<ChatForYouResponse> authenticateQRSession(
            @RequestParam("sessionId") String sessionId,
                    @RequestParam("accessToken") String accessToken,
                    @RequestParam("refreshToken") String refreshToken,
                    @RequestParam("name") String name,
                    @RequestParam("email") String email,
                    @RequestParam(value = "emailVerified", defaultValue = "false") boolean emailVerified,
                    @RequestParam("photo") String photo) {

        log.debug("QR 세션 인증 요청: sessionId={}", sessionId);

        GoogleOAuth response = loginService.authenticateQRSession(sessionId, accessToken, refreshToken, name, email, emailVerified, photo);

        return ResponseEntity.ok(ChatForYouResponse.ofSuccess(response));
    }


    /**
     * QR 세션의 현재 상태를 조회한다.
     *
     * @param sessionId QR 세션 ID
     * @return QR 세션 상태
     */
    @GetMapping(value = "/qr/status/{sessionId}", produces = "application/json; charset=UTF8")
    public ResponseEntity<ChatForYouResponse> checkQRSessionStatus(
            @PathVariable("sessionId") String sessionId) {

        log.debug("QR 세션 상태 조회: sessionId={}", sessionId);

        QRLoginResponse response = loginService.getSessionStatus(sessionId);

        return ResponseEntity.ok(ChatForYouResponse.ofSuccess(response));
    }
}
