package webChat.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import webChat.model.login.GoogleOAuth;
import webChat.model.response.common.ChatForYouResponse;
import webChat.service.chatroom.LoginService;

@RestController
@RequestMapping("/chatforyou/api/login")
public class LoginController {

    @Autowired
    private LoginService loginService;

    @GetMapping("/chatlogin")
    public String goLogin(){
        // docker 로 실행 시 thymeleaf 에러 발생 => 경로 문제로 인한 에러인듯
        // 따라서 기존의 /chatlogin -> chatlogin 으로 변경
        return "chatlogin";
    }

    @PostMapping(value = "/googleOauth", produces = "application/json; charset=UTF8")
    public ResponseEntity<ChatForYouResponse> googleOauth(@RequestParam("accessToken")String accessToken,
                                                          @RequestParam("refreshToken")String refreshToken,
                                                          @RequestParam("name")String name,
                                                          @RequestParam("email")String email,
                                                          @RequestParam("emailVerified")boolean emailVerified,
                                                          @RequestParam("photo")String photo) {

        GoogleOAuth auth = GoogleOAuth.builder().build();
        auth.setAccessToken(accessToken);
        auth.setRefreshToken(refreshToken);
        auth.setName(name);
        auth.setEmail(email);
        auth.setEmailVerified(emailVerified);
        auth.setPhoto(photo);
        GoogleOAuth response = loginService.checkSocialUser(auth);

        return ResponseEntity.ok(ChatForYouResponse.builder()
                .result("success")
                .data(response)
                .build());
    }
}
