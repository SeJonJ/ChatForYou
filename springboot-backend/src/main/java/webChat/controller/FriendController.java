package webChat.controller;

import com.google.firebase.auth.FirebaseToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.coyote.BadRequestException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import webChat.model.friend.FriendDto;
import webChat.model.friend.FriendInVo;
import webChat.model.response.common.ChatForYouResponse;
import webChat.service.friend.FriendService;
import webChat.utils.TokenUtils;

import java.util.List;

@RestController
@Slf4j
@RequestMapping("/chatforyou/api/friend")
@RequiredArgsConstructor
public class FriendController {

    private final FriendService friendService;

    /**
     * 친구 목록 조회
     *
     */
    @GetMapping(value = "/list", produces = "application/json; charset=UTF8")
    public ResponseEntity<ChatForYouResponse> friendList(@RequestHeader("Authorization") String authorization) throws Exception {
        FirebaseToken token = TokenUtils.checkGoogleOAuthToken(authorization);
        if (!token.isEmailVerified()) {
            throw new BadRequestException("This account is not verified.");
        }
        String userId = token.getEmail();
        List<FriendDto> friendList = friendService.getFriendList(userId);
        return ResponseEntity.ok(ChatForYouResponse.builder()
                .result("success")
                .data(friendList)
                .build());
    }

    /**
     *  친구 추가
     *
     */
    @PostMapping(value = "/request", produces = "application/json; charset=UTF8")
    public ResponseEntity<ChatForYouResponse> addFriend(@RequestHeader("Authorization") String authorization,
                                                        @RequestBody FriendInVo friendInVo) throws Exception {
        FirebaseToken token = TokenUtils.checkGoogleOAuthToken(authorization);
        if (!token.isEmailVerified()) {
            throw new BadRequestException("This account is not verified.");
        }
        String userId = token.getEmail();

        friendService.addFriend(friendInVo, userId);

        return ResponseEntity.ok(ChatForYouResponse.builder()
                .result("success")
                .data(null)
                .build());
    }


    /**
     *  친구 삭제
     *
     */
    @DeleteMapping(value = "/delete/{friend_id}", produces = "application/json; charset=UTF8")
    public ResponseEntity<ChatForYouResponse> deleteFriend(@RequestHeader("Authorization") String authorization,
                                                           @PathVariable("friend_id") String friendId) throws Exception {
        FirebaseToken token = TokenUtils.checkGoogleOAuthToken(authorization);
        if (!token.isEmailVerified()) {
            throw new BadRequestException("This account is not verified.");
        }
        String userId = token.getEmail();
        friendService.deleteFriend(userId, friendId);
        return ResponseEntity.ok(ChatForYouResponse.builder()
                .result("success")
                .build());
    }

    /**
     *  친구 수정
     *  닉네임 수정만 임시 개발
     *
     */
    @PostMapping(value = "/update", produces = "application/json; charset=UTF8")
    public ResponseEntity<ChatForYouResponse> updateFriend(@RequestHeader("Authorization") String authorization,
                                                           @RequestBody FriendInVo friendInVo) throws Exception {
        FirebaseToken token = TokenUtils.checkGoogleOAuthToken(authorization);
        if (!token.isEmailVerified()) {
            throw new BadRequestException("This account is not verified.");
        }
        String userId = token.getEmail();
        friendService.updateFriend(friendInVo, userId);

        return ResponseEntity.ok(ChatForYouResponse.builder()
                .result("success")
                .build());
    }

    // 친구 승인
    @PostMapping(value = "/accept", produces = "application/json; charset=UTF8")
    public ResponseEntity<ChatForYouResponse> acceptFriend(@RequestHeader("Authorization") String authorization,
                                                           @RequestBody FriendInVo friendInVo) throws Exception {
        FirebaseToken token = TokenUtils.checkGoogleOAuthToken(authorization);
        if (!token.isEmailVerified()) {
            throw new BadRequestException("This account is not verified.");
        }
        String userId = token.getEmail();
        friendService.acceptFriend(friendInVo, userId);

        return ResponseEntity.ok(ChatForYouResponse.builder()
                .result("success")
                .build());
    }

    // 친구 거절
    @PostMapping(value = "/reject", produces = "application/json; charset=UTF8")
    public ResponseEntity<ChatForYouResponse> rejectFriend(@RequestHeader("Authorization") String authorization,
                                                           @RequestBody FriendInVo friendInVo) throws Exception {
        FirebaseToken token = TokenUtils.checkGoogleOAuthToken(authorization);
        if (!token.isEmailVerified()) {
            throw new BadRequestException("This account is not verified.");
        }
        String userId = token.getEmail();
        friendService.rejectFriend(friendInVo, userId);

        return ResponseEntity.ok(ChatForYouResponse.builder()
                .result("success")
                .build());
    }


    // 친구 요청 목록

}
