package webChat.controller;

import com.google.firebase.auth.FirebaseToken;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import webChat.exception.ChatForYouException;
import webChat.exception.ErrorCode;
import webChat.model.login.OauthRedis;
import webChat.model.response.common.ChatForYouResponse;
import webChat.model.room.ChatRoom;
import webChat.model.turn.in.TurnCredentialInVo;
import webChat.model.turn.out.TurnCredentialOutVo;
import webChat.security.jwt.JwtRoomProvider;
import webChat.service.chatroom.ChatRoomService;
import webChat.service.turn.TurnCredentialService;
import webChat.service.user.UserService;
import webChat.utils.TokenUtils;

@RestController
@RequestMapping("/chatforyou/api/turn")
@RequiredArgsConstructor
@Slf4j
public class TurnController {

    private final UserService userService;
    private final ChatRoomService chatRoomService;
    private final JwtRoomProvider jwtRoomProvider;
    private final TurnCredentialService turnCredentialService;

    /**
     * 입장 인증을 통과한 사용자에게 세션별 단기 TURN 자격증명을 발급한다.
     */
    @PostMapping("/credential")
    public ResponseEntity<ChatForYouResponse> issueCredential(
            @RequestHeader("Authorization") String authorization,
            @RequestHeader(value = "X-Room-Token", required = false) String roomToken,
            @Valid @RequestBody TurnCredentialInVo request) {

        // joinRoom 과 동형 인증: OAuth 토큰 -> 로그인 세션 -> (비밀방) room token. 발급 전 동일 게이트를 통과해야 자격증명이 새지 않는다.
        FirebaseToken token = TokenUtils.checkGoogleOAuthToken(authorization);
        OauthRedis oauthRedis = userService.getValidatedOauthUser(token.getEmail());

        ChatRoom chatRoom = chatRoomService.findRoomById(request.getRoomId());
        if (chatRoom == null) {
            throw new ChatForYouException(ErrorCode.ROOM_NOT_FOUND);
        }

        if (chatRoom.isSecretChk()) {
            jwtRoomProvider.validate(roomToken, chatRoom.getRoomId(), oauthRedis.getEmail());
        }

        TurnCredentialOutVo outVo = turnCredentialService.issueForBrowser(oauthRedis.getEmail());
        return ResponseEntity.ok(ChatForYouResponse.ofSuccess(outVo));
    }
}
