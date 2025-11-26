package webChat.controller;

import com.google.firebase.auth.FirebaseToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.coyote.BadRequestException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import webChat.model.friend.FriendDto;
import webChat.model.response.common.ChatForYouResponse;
import webChat.utils.TokenUtils;

import java.util.List;

@RestController
@Slf4j
@RequestMapping("/chatforyou/api/friend")
@RequiredArgsConstructor
public class FriendController {


    @GetMapping(value = "/list", produces = "application/json; charset=UTF8")
    public ResponseEntity<ChatForYouResponse> friendList(@RequestHeader("Authorization") String authorization) throws Exception {
        FirebaseToken token = TokenUtils.checkGoogleOAuthToken(authorization);
        if (!token.isEmailVerified()) {
            throw new BadRequestException("This account is not verified.");
        }
        List<FriendDto> friendList; // TODO
        return ResponseEntity.ok(ChatForYouResponse.builder()
                .result("success")
                .data(null)
                .build());
    }
}
