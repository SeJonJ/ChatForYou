package webChat.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import webChat.exception.ChatForYouException;
import webChat.exception.ErrorCode;
import webChat.model.response.common.ChatForYouResponse;
import webChat.service.chatroom.recovery.ChatRoomRecoveryService;
import webChat.service.routing.InstanceProvider;

@RestController
@RequestMapping("/chatforyou/api/internal")
@RequiredArgsConstructor
@Slf4j
public class InternalLifecycleController {

    private static final String LOOPBACK_V4 = "127.0.0.1";
    private static final String LOOPBACK_V6 = "0:0:0:0:0:0:0:1";

    private final ChatRoomRecoveryService chatRoomRecoveryService;
    private final InstanceProvider instanceProvider;

    /**
     * 현재 인스턴스가 소유한 방을 종료 전 복구 후보로 표시한다.
     *
     * @param request loopback 호출 여부를 확인할 HTTP 요청
     * @return 복구 후보로 표시된 방 요약
     */
    @PostMapping("/pre-shutdown")
    public ResponseEntity<ChatForYouResponse> preShutdown(HttpServletRequest request) {
        validateLoopbackRequest(request.getRemoteAddr());
        instanceProvider.beginShutdown();

        return ResponseEntity.ok(ChatForYouResponse.ofSuccess(
                chatRoomRecoveryService.markOwnedRoomsRecoverable()
        ));
    }

    private void validateLoopbackRequest(String remoteAddr) {
        if (!LOOPBACK_V4.equals(remoteAddr) && !LOOPBACK_V6.equals(remoteAddr)) {
            log.warn("Blocked non-loopback pre-shutdown request: remoteAddr={}", remoteAddr);
            throw new ChatForYouException(ErrorCode.ACCESS_DENIED);
        }
    }
}
