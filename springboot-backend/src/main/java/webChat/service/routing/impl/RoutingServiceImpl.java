package webChat.service.routing.impl;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import webChat.service.redis.RedisService;
import webChat.service.routing.RoutingInstanceProvider;
import webChat.service.routing.RoutingService;

@Service
@RequiredArgsConstructor
@Slf4j
public class RoutingServiceImpl implements RoutingService {
    private final String CHATFORYOU_SERVER_COOKIE = "chatforyou-server";

    private final RedisService redisService;
    private final RoutingInstanceProvider instanceProvider;

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
    public void setRoomCookie(HttpServletRequest request, HttpServletResponse response, String instanceId) {
        // 1. 해당 instanceId의 nginx 쿠키 조회
        String nginxCookie = redisService.getCookieByInstanceId(instanceId);

        if (nginxCookie != null) { // 2. redis 조회해서 nginxCookie 가 있다면 세팅
            Cookie roomCookie = new Cookie(CHATFORYOU_SERVER_COOKIE, nginxCookie);
            roomCookie.setPath("/");
            roomCookie.setMaxAge(24 * 60 * 60); // 24시간
            roomCookie.setHttpOnly(true); // JavaScript 접근 차단 (보안)
            roomCookie.setSecure(false); // HTTP에서도 전송 (개발환경)
            response.addCookie(roomCookie);
        } else { // 3. redis 조회 시 없다면 request 에서 cookie 가져와서 세팅
            String currentNginxCookie = this.getNginxCookie(request);
            if(currentNginxCookie == null) {
                currentNginxCookie = instanceProvider.getInstanceId();
            }

            redisService.saveInstanceCookieMapping(instanceId, currentNginxCookie);
            Cookie roomCookie = new Cookie(CHATFORYOU_SERVER_COOKIE, currentNginxCookie);
            roomCookie.setPath("/");
            roomCookie.setMaxAge(24 * 60 * 60); // 24시간
            roomCookie.setHttpOnly(true); // JavaScript 접근 차단 (보안)
            roomCookie.setSecure(false); // HTTP에서도 전송 (개발환경)
            response.addCookie(roomCookie);
        }
    }

    private String getNginxCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ((CHATFORYOU_SERVER_COOKIE).equals(cookie.getName())) {
                    log.info(cookie.getName() + " : " + cookie.getValue());
                    return cookie.getValue();
                }
            }
        }
        return instanceProvider.getInstanceId();
    }

    @Override
    public String getInstanceIdFromCookie(HttpServletRequest request) {
        String nginxCookieVal = this.getNginxCookie(request);
        // Nginx가 생성한 쿠키 형태인지 확인 (|가 포함된 형태)
        if (nginxCookieVal.contains("|")) {
            log.info("Found nginx cookie in current request: {}", nginxCookieVal);
            return redisService.getInstanceIdByCookie(nginxCookieVal);
        } else {
            log.info("No nginx cookie found in current request");
            return this.instanceProvider.getInstanceId();
        }
    }
}
