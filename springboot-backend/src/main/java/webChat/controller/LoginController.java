package webChat.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/chatforyou/api/file")
public class LoginController {

    @GetMapping("/chatlogin")
    public String goLogin(){
        // docker 로 실행 시 thymeleaf 에러 발생 => 경로 문제로 인한 에러인듯
        // 따라서 기존의 /chatlogin -> chatlogin 으로 변경
        return "chatlogin";
    }

    @GetMapping("/googleOauth")
    public void googleOauth(HttpServletRequest request) {
        String requestURL = request.getRequestURL().toString(); // 기본 URL
        String queryString = request.getQueryString();
    }
}
