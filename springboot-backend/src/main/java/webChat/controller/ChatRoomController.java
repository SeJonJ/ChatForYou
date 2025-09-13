package webChat.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.coyote.BadRequestException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import webChat.model.response.ChatForYouResponseResult;
import webChat.model.routing.RoomRoutingInfo;
import webChat.model.routing.RoutingCookie;
import webChat.model.response.common.ChatForYouResponse;
import webChat.model.room.ChatRoom;
import webChat.model.room.RoomState;
import webChat.model.room.in.ChatRoomInVo;
import webChat.model.room.out.ChatRoomOutVo;
import webChat.model.chat.ChatType;
import webChat.service.chatroom.ChatRoomService;
import webChat.service.routing.RoutingInstanceProvider;
import webChat.service.routing.RoutingService;
import webChat.service.social.PrincipalDetails;
import webChat.utils.StringUtil;

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

    // 채팅방 생성
    @PostMapping("/room")
    public ResponseEntity<ChatForYouResponse> createRoom(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestBody ChatRoomInVo chatRoomInVo) throws BadRequestException {

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

        log.info("CREATE Chat [Room Id {}] :: [Room Name {}] :: [InstanceId {}]", room.getRoomName(), room.getRoomName(), room.getInstanceId());

        return ResponseEntity.ok(ChatForYouResponse.ofCreateRoom(room));
    }

    // 채팅방 입장
    @GetMapping("/room/{roomId}")
    public ResponseEntity<ChatForYouResponse> joinRoom(
            Model model,
            @PathVariable String roomId,
            HttpServletRequest request,
            HttpServletResponse response) throws BadRequestException {

        ChatRoom chatRoom = chatRoomService.findRoomById(roomId);

        if (StringUtil.isNullOrEmpty(chatRoom.getInstanceId()) || !instanceProvider.isHealthy(chatRoom.getInstanceId())){
            // TODO 서버가 죽었을때 예외처리 필요 :: 방 삭제 및 임시 조치로 메인 대시보드로 이동
            chatRoomService.delChatRoom(roomId);
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

        model.addAttribute("room", chatRoom);

        if (ChatType.MSG.equals(chatRoom.getChatType())) {
            return ResponseEntity.ok(null);
        }else{
            return ResponseEntity.ok(ChatForYouResponse.ofJoinRoom(chatRoom));
        }
    }

    @GetMapping("/room/list")
    public ResponseEntity<List<ChatRoomOutVo>> goChatRooms(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "pageNum", required = false, defaultValue = "0") String pageNumStr,
            @RequestParam(value = "pageSize", required = false, defaultValue = "20") String pageSizeStr,
            @AuthenticationPrincipal PrincipalDetails principalDetails){
        List<ChatRoomOutVo> responses = new ArrayList<>();

        // TODO 로그인 기능 도입 시 필요
//        // principalDetails 가 null 이 아니라면 로그인 된 상태!!
//        if (principalDetails != null) {
//            // 세션에서 로그인 유저 정보를 가져옴
//            model.addAttribute("user", principalDetails.getUser());
//            log.debug("user [{}] ",principalDetails);
//        }

//        model.addAttribute("user", "hey");
        chatRoomService.getRoomList(keyword, Integer.parseInt(pageNumStr), Integer.parseInt(pageSizeStr), false).forEach(room -> {
            responses.add(ChatRoomOutVo.of(room));
        });
        return ResponseEntity.ok(responses);
    }

    // 채팅방 비밀번호 확인
    @PostMapping(value = "/room/validatePwd/{roomId}")
    public ResponseEntity<ChatForYouResponse> validatePwd(
            @PathVariable String roomId,
            @RequestParam("roomPwd") String roomPwd) throws BadRequestException {

        // 넘어온 roomId 와 roomPwd 를 이용해서 비밀번호 찾기
        // 찾아서 입력받은 roomPwd 와 room pwd 와 비교해서 맞으면 true, 아니면  false
        // TODO 추후 401 권한 에러로 수정할 것
        return ResponseEntity.ok(ChatForYouResponse.builder()
                .result("success")
                .data(chatRoomService.validatePwd(roomId, roomPwd))
                .build());
    }

    // 채팅방 수정
    @PutMapping(value = "/room/{roomId}")
    public ResponseEntity<ChatForYouResponse> modifyChatRoom(
            @PathVariable String roomId,
            @RequestBody ChatRoomInVo chatRoom) throws BadRequestException {
        return ResponseEntity.ok(ChatForYouResponse.builder()
                .result("success")
                .data(chatRoomService.updateRoom(roomId, chatRoom.getRoomName(), chatRoom.getRoomPwd(), chatRoom.getMaxUserCnt()))
                .build());
    }

    // 채팅방 삭제
    @DeleteMapping("/room/{roomId}")
    public ResponseEntity<ChatForYouResponse> delChatRoom(@PathVariable String roomId) throws BadRequestException, ExceptionController.DelRoomException {

        // roomId 기준으로 chatRoomMap 에서 삭제, 해당 채팅룸 안에 있는 사진 삭제
        return ResponseEntity.ok(ChatForYouResponse.builder()
                .result("success")
                .data(chatRoomService.delChatRoom(roomId))
                .build());

    }

    // 유저 카운트
    @GetMapping("/room/chkUserCnt/{roomId}")
    public ResponseEntity<ChatForYouResponse> chUserCnt(@PathVariable String roomId) throws BadRequestException {
        return ResponseEntity.ok(ChatForYouResponse.builder()
                .result("success")
                .data(chatRoomService.chkRoomUserCnt(roomId))
                .build());
    }
}
