package webChat.controller;

import com.google.firebase.auth.FirebaseToken;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import webChat.exception.ChatForYouException;
import webChat.exception.ErrorCode;
import webChat.model.chat.ChatType;
import webChat.model.login.OauthRedis;
import webChat.model.response.ChatForYouResponseResult;
import webChat.model.response.common.ChatForYouResponse;
import webChat.model.room.ChatRoom;
import webChat.model.room.RoomState;
import webChat.model.room.in.ChatRoomInVo;
import webChat.model.room.out.ChatRoomOutVo;
import webChat.model.routing.RoomRoutingInfo;
import webChat.model.routing.RoutingCookie;
import webChat.model.user.UserDto;
import webChat.security.jwt.JwtRoomProvider;
import webChat.service.chatroom.ChatRoomService;
import webChat.service.redis.RedisService;
import webChat.service.routing.RoutingInstanceProvider;
import webChat.service.routing.RoutingService;
import webChat.service.user.UserService;
import webChat.utils.StringUtil;
import webChat.utils.TokenUtils;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/chatforyou/api/chat")
@RequiredArgsConstructor
@Slf4j
public class ChatRoomController {

    private final ChatRoomService chatRoomService;
    private final RoutingService routingService;
    private final RoutingInstanceProvider instanceProvider;
    private final UserService userService;
    private final JwtRoomProvider jwtRoomProvider;
    private final RedisService redisService;

    /**
     * 새 채팅방을 생성하고 라우팅 쿠키를 설정한다.
     *
     * @param request HTTP 요청
     * @param response HTTP 응답
     * @param authorization Firebase 인증 토큰
     * @param chatRoomInVo 채팅방 생성 요청
     * @return 생성 결과 또는 다른 인스턴스로의 리다이렉트 응답
     */
    @PostMapping("/room")
    public ResponseEntity<ChatForYouResponse> createRoom (
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody ChatRoomInVo chatRoomInVo) {

        // token 확인
        FirebaseToken token = TokenUtils.checkGoogleOAuthToken(authorization);
        // 유저 검증 및 로그인(레디스 저장) 정보 확인
        userService.getValidatedOauthUser(token.getEmail());

        String roomId = routingService.getCookie(request, RoutingCookie.ROOM_ID_COOKIE);
        // 매개변수 : 방 이름, 패스워드, 방 잠금 여부, 방 인원수
        ChatRoom room = chatRoomService.createChatRoom(chatRoomInVo, roomId);
        // cookie 설정
        routingService.setRoutingInfo(request, response, room.getRoomId(), room.getInstanceId());
        if(RoomState.REDIRECT.equals(room.getRoomState())){
            // 현재 서버가 선택된 서버가 아니면 리다이렉트
            return ResponseEntity.status(HttpStatus.PERMANENT_REDIRECT)
                    .header("Location", "/chatforyou/api/chat/room")
                    .build();
        }

        log.info("CREATE Chat [Room Id {}] :: [Room Name {}] :: [InstanceId {}]", room.getRoomId(), room.getRoomName(), room.getInstanceId());

        return ResponseEntity.ok(ChatForYouResponse.ofCreateRoom(room));
    }

    /**
     * 채팅방 입장 정보를 조회하고 필요 시 인스턴스 리다이렉트를 수행한다.
     *
     * @param roomId 채팅방 ID
     * @param authorization Firebase 인증 토큰
     * @param roomToken 비밀방 접근 토큰
     * @param request HTTP 요청
     * @param response HTTP 응답
     * @return 입장 정보 또는 리다이렉트 응답
     */
    @GetMapping("/room/{roomId}")
    public ResponseEntity<ChatForYouResponse> joinRoom (
            @PathVariable String roomId,
            @RequestHeader("Authorization") String authorization,
            @RequestHeader(value = "X-Room-Token", required = false) String roomToken,
            HttpServletRequest request,
            HttpServletResponse response) {

        // token 확인
        FirebaseToken token = TokenUtils.checkGoogleOAuthToken(authorization);
        // 유저 검증 및 로그인(레디스 저장) 정보 확인
        OauthRedis oauthRedis = userService.getValidatedOauthUser(token.getEmail());

        ChatRoom chatRoom = chatRoomService.findRoomById(roomId);
        if (chatRoom == null) {
            throw new ChatForYouException(ErrorCode.ROOM_NOT_FOUND);
        }
        // JWT 토큰 검증
        if (chatRoom.isSecretChk()) {
            jwtRoomProvider.validate(roomToken, chatRoom.getRoomId(), oauthRedis.getEmail());
        }

        if (StringUtil.isNullOrEmpty(chatRoom.getInstanceId()) || !instanceProvider.isHealthy(chatRoom.getInstanceId())){
            // 비정상 인스턴스의 방은 제거 후 대시보드로 우회시킨다.
            chatRoomService.delChatRoom(roomId, true);
            return ResponseEntity.ok(ChatForYouResponse.ofRedirectRoom(chatRoom, ChatForYouResponseResult.REDIRECT_DASHBOARD));
        }


        if (!instanceProvider.getInstanceId().equals(chatRoom.getInstanceId())) {
            RoomRoutingInfo roomRoutingInfo = routingService.getRoomRoutingInfoByRoomId(roomId);
            int redirectCount = routingService.getRedirectCount(request);
            if(redirectCount > 3){
                return ResponseEntity.ok(ChatForYouResponse.ofRedirectRoom(chatRoom, ChatForYouResponseResult.REDIRECT_DASHBOARD));
            } else {
                // cookieInstanceId 로 올바른 쿠키 조회 후 세팅
                routingService.setRoutingInfo(response, roomRoutingInfo.getRoomId(), roomRoutingInfo.getNginxCookie(), redirectCount + 1);
                return ResponseEntity.ok(ChatForYouResponse.ofRedirectRoom(chatRoom, ChatForYouResponseResult.REDIRECT_ROOM));
            }
        }

        if (ChatType.MSG.equals(chatRoom.getChatType())) {
            return ResponseEntity.ok(ChatForYouResponse.ofSuccess(null));
        }else{
            // RTC 입장이 확정된 시점에만 멤버십을 기록한다 (녹화 다운로드 권한 검증 기준)
            redisService.addRoomMember(roomId, oauthRedis.getEmail());
            UserDto userDto = userService.getUserInfo(oauthRedis);
            return ResponseEntity.ok(ChatForYouResponse.ofJoinRoom(chatRoom, userDto));
        }
    }

    /**
     * 공개 채팅방 목록을 조회한다.
     *
     * @param keyword 검색 키워드
     * @param pageNumStr 페이지 번호
     * @param pageSizeStr 페이지 크기
     * @return 채팅방 목록
     */
    @GetMapping("/room/list")
    public ResponseEntity<ChatForYouResponse> getChatRoomList (
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "pageNum", required = false, defaultValue = "0") String pageNumStr,
            @RequestParam(value = "pageSize", required = false, defaultValue = "20") String pageSizeStr){
        List<ChatRoomOutVo> responses = new ArrayList<>();
        chatRoomService.getRoomList(keyword, Integer.parseInt(pageNumStr), Integer.parseInt(pageSizeStr), false).forEach(room -> {
            responses.add(ChatRoomOutVo.of(room));
        });
        return ResponseEntity.ok(ChatForYouResponse.ofSuccess(responses));
    }

    /**
     * 비밀방 비밀번호를 검증하고 방 접근 토큰을 발급한다.
     *
     * @param roomId 채팅방 ID
     * @param roomPwd 입력 비밀번호
     * @param authorization Firebase 인증 토큰
     * @return 방 접근 토큰
     */
    @PostMapping(value = "/room/validatePwd/{roomId}")
    public ResponseEntity<ChatForYouResponse> validatePwd (
            @PathVariable String roomId,
            @RequestParam("roomPwd") String roomPwd,
            @RequestHeader("Authorization") String authorization) {

        FirebaseToken token = TokenUtils.checkGoogleOAuthToken(authorization);
        OauthRedis oauthRedis = userService.getValidatedOauthUser(token.getEmail());

        // room 비밀번호 검증 후 유효하면 room 접근 토큰을 발급한다.
        return ResponseEntity.ok(ChatForYouResponse.ofSuccess(chatRoomService.validatePwd(oauthRedis.getEmail(), roomId, roomPwd)));
    }

    /**
     * 만료되었지만 서명과 소유자가 유효한 room token 을 새 토큰으로 교체한다.
     *
     * @param roomId 채팅방 ID
     * @param authorization Firebase 인증 토큰
     * @param roomToken 기존 room 접근 토큰
     * @return 새 room 접근 토큰
     */
    @PostMapping(value = "/room/token/refresh/{roomId}")
    public ResponseEntity<ChatForYouResponse> refreshRoomToken(
            @PathVariable String roomId,
            @RequestHeader("Authorization") String authorization,
            @RequestHeader("X-Room-Token") String roomToken) {

        FirebaseToken token = TokenUtils.checkGoogleOAuthToken(authorization);
        OauthRedis oauthRedis = userService.getValidatedOauthUser(token.getEmail());

        jwtRoomProvider.validateRefreshable(roomToken, roomId, oauthRedis.getEmail());
        return ResponseEntity.ok(ChatForYouResponse.ofSuccess(
                chatRoomService.refreshRoomToken(oauthRedis.getEmail(), roomId)
        ));
    }

    /**
     * 채팅방 설정을 수정한다.
     *
     * @param roomId 채팅방 ID
     * @param authorization Firebase 인증 토큰
     * @param chatRoom 채팅방 수정 요청
     * @return 수정된 채팅방 정보
     */
    @PutMapping(value = "/room/{roomId}")
    public ResponseEntity<ChatForYouResponse> modifyChatRoom (
            @PathVariable String roomId,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody ChatRoomInVo chatRoom) {

        FirebaseToken token = TokenUtils.checkGoogleOAuthToken(authorization);
        userService.getValidatedOauthUser(token.getEmail());
        return ResponseEntity.ok(ChatForYouResponse.ofSuccess(chatRoomService.updateRoom(roomId, chatRoom.getRoomName(), chatRoom.getRoomPwd(), chatRoom.getMaxUserCnt())));
    }

    /**
     * 채팅방을 삭제한다.
     *
     * @param roomId 채팅방 ID
     * @param authorization Firebase 인증 토큰
     * @return 삭제 결과
     */
    @DeleteMapping("/room/{roomId}")
    public ResponseEntity<ChatForYouResponse> delChatRoom (
            @PathVariable String roomId,
            @RequestHeader("Authorization") String authorization) {
        FirebaseToken token = TokenUtils.checkGoogleOAuthToken(authorization);
        // 유저 검증 및 로그인(레디스 저장) 정보 확인
        userService.getValidatedOauthUser(token.getEmail());
        // roomId 기준으로 chatRoomMap 에서 삭제, 해당 채팅룸 안에 있는 사진 삭제
        return ResponseEntity.ok(ChatForYouResponse.ofSuccess(chatRoomService.delChatRoom(roomId, false)));
    }

    /**
     * 현재 채팅방 참가자 수를 조회한다.
     *
     * @param roomId 채팅방 ID
     * @return 참가자 수
     */
    @GetMapping("/room/chkUserCnt/{roomId}")
    public ResponseEntity<ChatForYouResponse> chUserCnt (@PathVariable String roomId) {
        return ResponseEntity.ok(ChatForYouResponse.ofSuccess(chatRoomService.chkRoomUserCnt(roomId)));
    }
}
