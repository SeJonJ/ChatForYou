package webChat.service.routing.impl;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import webChat.config.InstanceProvider;
import webChat.service.redis.RedisService;
import webChat.service.routing.RoutingService;
import webChat.utils.StringUtil;

@Service
@RequiredArgsConstructor
@Slf4j
public class RoutingServiceImpl implements RoutingService {
    private final String CHATFORYOU_SERVER_COOKIE = "chatforyou-server";

    private final RedisService redisService;
    private final InstanceProvider instanceProvider;


    @Override
    public String saveRoomInstanceId(String roomId) {
        // 1. room 을 최적의 instance 에 배치
        String instanceId = instanceProvider.getServerForRoom(roomId);
        // 2. roomId 와 instanceId 매칭하여 저장
        redisService.saveRoomServerMapping(roomId, instanceId);

        log.info("roomId : {} instanceId : {} saved", roomId, instanceId);
        return instanceId;
    }

    @Override
    public void setRoomCookie(String roomId, String instanceId, HttpServletResponse response) {
        Cookie roomCookie = new Cookie(CHATFORYOU_SERVER_COOKIE, instanceId);
        roomCookie.setPath("/");
        roomCookie.setMaxAge(24 * 60 * 60); // 24시간
        response.addCookie(roomCookie);

    }

    @Override
    public void setRoomCookie(String roomId, HttpServletResponse response) {
        String instanceId = redisService.getServerByRoomId(roomId);
        Cookie roomCookie = new Cookie(CHATFORYOU_SERVER_COOKIE, instanceId);
        roomCookie.setPath("/");
        roomCookie.setMaxAge(24 * 60 * 60); // 24시간
        roomCookie.setHttpOnly(true); // JavaScript 접근 차단 (보안)
        roomCookie.setSecure(false); // HTTP에서도 전송 (개발환경)
        response.addCookie(roomCookie);
    }

    @Override
    public String getInstanceIdFromCookie(String roomId, HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ((CHATFORYOU_SERVER_COOKIE).equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}
