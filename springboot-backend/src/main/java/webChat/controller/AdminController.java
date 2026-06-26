package webChat.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import webChat.exception.ChatForYouException;
import webChat.exception.ErrorCode;
import webChat.model.redis.DataType;
import webChat.model.response.common.ChatForYouResponse;
import webChat.model.room.KurentoRoom;
import webChat.model.room.out.ChatRoomOutVo;
import webChat.model.user.UserDto;
import webChat.service.chatroom.ChatRoomService;
import webChat.service.redis.RedisService;
import webChat.utils.JwtUtil;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/chatforyou/api/admin")
public class AdminController {

    private final ChatRoomService chatRoomService;
    private final JwtUtil jwtUtil;
    private final RedisService redisService;

    /**
     * 관리자용 JWT 토큰을 발급한다.
     *
     * @param key 관리자 키
     * @param user 사용자 식별자
     * @return 발급된 JWT 토큰
     */
    @PostMapping("/gentoken")
    public ResponseEntity<ChatForYouResponse> generateToken(
            @RequestParam String key,
            @RequestParam String user) {
        return ResponseEntity.ok(ChatForYouResponse.ofSuccess(jwtUtil.generateToken(key, user)));
    }

    /**
     * 관리자 관점에서 전체 채팅방 목록을 조회한다.
     *
     * @param token 관리자 인증 토큰
     * @param keyword 검색 키워드
     * @param pageNumStr 페이지 번호
     * @param pageSizeStr 페이지 크기
     * @return 전체 채팅방 목록
     */
    @GetMapping("/allrooms")
    public ResponseEntity<ChatForYouResponse> allRooms(
     @RequestHeader("Authorization") String token,
     @RequestParam(value = "keyword", required = false) String keyword,
     @RequestParam(value = "pageNum", required = false, defaultValue = "0") String pageNumStr,
     @RequestParam(value = "pageSize", required = false, defaultValue = "20") String pageSizeStr) {

        String jwtToken = token.replace("Bearer ", "");

        if (!token.startsWith("Bearer ")) {
            throw new ChatForYouException(ErrorCode.UNAUTHORIZED);
        }

        if (!jwtUtil.validateToken(jwtToken)) {
            throw new ChatForYouException(ErrorCode.UNAUTHORIZED);
        }
        List<ChatRoomOutVo> responses = new ArrayList<>();
        chatRoomService.getRoomList("", Integer.parseInt(pageNumStr), Integer.parseInt(pageSizeStr), true).forEach(room -> {
            responses.add(ChatRoomOutVo.ofJoin(room, UserDto.ofAdmin()));
        });
        return ResponseEntity.ok(ChatForYouResponse.ofSuccess(responses));
    }

    /**
     * 관리자 권한으로 채팅방을 강제 삭제한다.
     *
     * @param roomId 채팅방 ID
     * @param token 관리자 인증 토큰
     * @return 삭제 결과
     */
    @DeleteMapping("/{roomId}")
    public ResponseEntity<ChatForYouResponse> delRoom(@PathVariable String roomId, @RequestHeader("Authorization") String token) {
        String jwtToken = token.replace("Bearer ", "");

        if (!token.startsWith("Bearer ")) {
            throw new ChatForYouException(ErrorCode.UNAUTHORIZED);
        }

        if (!jwtUtil.validateToken(jwtToken)) {
            throw new ChatForYouException(ErrorCode.UNAUTHORIZED);
        }

        KurentoRoom kurentoRoom = redisService.getRedisDataByDataType(roomId, DataType.CHATROOM, KurentoRoom.class);
        chatRoomService.delChatRoom(kurentoRoom);
        // roomId 기준으로 chatRoomMap 에서 삭제, 해당 채팅룸 안에 있는 사진 삭제
        return ResponseEntity.ok(ChatForYouResponse.builder()
                .result("SUCCESS")
                .message("Success to delete room [ " + roomId + " ] with all related data.")
                .build());
    }
}
