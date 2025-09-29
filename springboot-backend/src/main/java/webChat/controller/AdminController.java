package webChat.controller;

import lombok.RequiredArgsConstructor;
import org.apache.coyote.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import webChat.model.redis.DataType;
import webChat.model.response.common.ChatForYouResponse;
import webChat.model.room.KurentoRoom;
import webChat.model.room.out.ChatRoomOutVo;
import webChat.service.chatroom.ChatRoomService;
import webChat.service.redis.RedisService;
import webChat.utils.JwtUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/chatforyou/api/admin")
public class AdminController {

    private final ChatRoomService chatRoomService;
    private final JwtUtil jwtUtil;
    private final RedisService redisService;

    @Value("${turn.server.urls}")
    private String turnServerUrl;

    @Value("${turn.server.username}")
    private String turnServerUserName;

    @Value("${turn.server.credential}")
    private String turnServerCredential;

    @PostMapping("/gentoken")
    public String generateToken(
            @RequestParam String key,
            @RequestParam String user) throws BadRequestException, ExceptionController.UnauthorizedException {
        return jwtUtil.generateToken(key, user);
    }

    /**
     * room list return
     *
     * @param token
     * @return room list
     * @throws Exception 400, 401
     */
    @GetMapping("/allrooms")
    public ResponseEntity<List<ChatRoomOutVo>> allRooms(
     @RequestHeader("Authorization") String token,
     @RequestParam(value = "keyword", required = false) String keyword,
     @RequestParam(value = "pageNum", required = false, defaultValue = "0") String pageNumStr,
     @RequestParam(value = "pageSize", required = false, defaultValue = "20") String pageSizeStr) throws Exception {

        String jwtToken = token.replace("Bearer ", "");

        if (!token.startsWith("Bearer ")) {
            throw new ExceptionController.UnauthorizedException("Invalid token format");
        }

        if (!jwtUtil.validateToken(jwtToken)) {
            throw new ExceptionController.UnauthorizedException("Invalid token format or you have No Auth");
        }
        List<ChatRoomOutVo> responses = new ArrayList<>();
        chatRoomService.getRoomList("", Integer.parseInt(pageNumStr), Integer.parseInt(pageSizeStr), true).forEach(room -> {
            responses.add(ChatRoomOutVo.ofJoin(room));
        });
        return ResponseEntity.ok(responses);
    }

    /**
     * roomId 를 받아서 해당 room 삭제
     *
     * @param roomId
     * @param token
     * @return del room result
     * @throws Exception 400, 401
     */
    @DeleteMapping("/{roomId}")
    public ResponseEntity<ChatForYouResponse> delRoom(@PathVariable String roomId, @RequestHeader("Authorization") String token) throws Exception {
        String jwtToken = token.replace("Bearer ", "");

        if (!token.startsWith("Bearer ")) {
            throw new ExceptionController.UnauthorizedException("Invalid token format");
        }

        if (!jwtUtil.validateToken(jwtToken)) {
            throw new ExceptionController.UnauthorizedException("Invalid token format or you have No Auth");
        }

        KurentoRoom kurentoRoom = redisService.getRedisDataByDataType(roomId, DataType.CHATROOM, KurentoRoom.class);
        chatRoomService.delChatRoom(kurentoRoom);
        // roomId 기준으로 chatRoomMap 에서 삭제, 해당 채팅룸 안에 있는 사진 삭제
        return ResponseEntity.ok(ChatForYouResponse.builder()
                .result("success")
                .message("Success to delete room [ " + roomId + " ] with all related data.")
                .build());
    }

    // turn server config
    @PostMapping("/turnconfig")
    @ResponseBody
    public Map<String, String> turnServerConfig(){
        Map<String, String> turnServerConfig = new HashMap<>();
        turnServerConfig.put("url", turnServerUrl);
        turnServerConfig.put("username", turnServerUserName);
        turnServerConfig.put("credential", turnServerCredential);

        return turnServerConfig;
    }
}
