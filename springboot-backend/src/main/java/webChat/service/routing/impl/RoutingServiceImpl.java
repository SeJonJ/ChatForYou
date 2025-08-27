package webChat.service.routing.impl;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import webChat.model.routing.RoomRoutingInfo;
import webChat.service.redis.RedisService;
import webChat.service.routing.RoutingInstanceProvider;
import webChat.service.routing.RoutingService;

@Service
@RequiredArgsConstructor
@Slf4j
public class RoutingServiceImpl implements RoutingService {
    private final String CHATFORYOU_SERVER_COOKIE = "chatforyou-server";
    private final String ROOM_ID_COOKIE = "room-id";

    private final RedisService redisService;
    private final RoutingInstanceProvider instanceProvider;

    @Override
    public void setRoomCookie(HttpServletRequest request, HttpServletResponse response, String roomId, String selectedInstanceId) {
        // 1. roomId 기준 roomRoutingInfo 객체 조회
        RoomRoutingInfo roomRoutingInfo = redisService.getRoomRoutingInfoByRoomId(roomId);

        if (roomRoutingInfo != null) { // 2. redis 조회해서 nginxCookie 가 있다면 세팅
            Cookie nginxCookie = new Cookie(CHATFORYOU_SERVER_COOKIE, roomRoutingInfo.getNginxCookie());
            nginxCookie.setPath("/");
            nginxCookie.setMaxAge(24 * 60 * 60); // 24시간
            nginxCookie.setHttpOnly(true); // JavaScript 접근 차단 (보안)
            nginxCookie.setSecure(false); // HTTP에서도 전송 (개발환경)
            response.addCookie(nginxCookie);

            Cookie roomIdCookie = new Cookie(ROOM_ID_COOKIE, roomId);
            roomIdCookie.setPath("/");
            roomIdCookie.setMaxAge(60); // 60초
            roomIdCookie.setHttpOnly(false); // JavaScript 접근 차단 (보안)
            roomIdCookie.setSecure(false); // HTTP에서도 전송 (개발환경)
            response.addCookie(roomIdCookie);

        } else { // 2. redis 조회 시 없다면 request 에서 cookie 가져와서 세팅
            String currentNginxCookie = this.getNginxCookie(request);
            if(currentNginxCookie == null) {
                currentNginxCookie = instanceProvider.getInstanceId();
            }

            redisService.saveRoomRoutingInfo(RoomRoutingInfo.of(roomId, selectedInstanceId, currentNginxCookie, System.currentTimeMillis()));
            log.info("roomId : [{}] instanceId : [{}] : cookie : [{}] saved", roomId, selectedInstanceId, currentNginxCookie);
            Cookie nginxCookie = new Cookie(CHATFORYOU_SERVER_COOKIE, currentNginxCookie);
            nginxCookie.setPath("/");
            nginxCookie.setMaxAge(24 * 60 * 60); // 24시간
            nginxCookie.setHttpOnly(true); // JavaScript 접근 차단 (보안)
            nginxCookie.setSecure(false); // HTTP에서도 전송 (개발환경)
            response.addCookie(nginxCookie);

            Cookie roomIdCookie = new Cookie(ROOM_ID_COOKIE, roomId);
            roomIdCookie.setPath("/");
            roomIdCookie.setMaxAge(60); // 60초
            roomIdCookie.setHttpOnly(false); // JavaScript 접근 차단 (보안)
            roomIdCookie.setSecure(false); // HTTP에서도 전송 (개발환경)
            response.addCookie(roomIdCookie);
        }
    }

    @Override
    public void setRoomCookie(HttpServletResponse response, String roomId, String nginxCookie) {
        Cookie nginxInstanceCookie = new Cookie(CHATFORYOU_SERVER_COOKIE, nginxCookie);
        nginxInstanceCookie.setPath("/");
        nginxInstanceCookie.setMaxAge(24 * 60 * 60); // 24시간
        nginxInstanceCookie.setHttpOnly(true); // JavaScript 접근 차단 (보안)
        nginxInstanceCookie.setSecure(false); // HTTP에서도 전송 (개발환경)
        response.addCookie(nginxInstanceCookie);

        Cookie roomIdCookie = new Cookie(ROOM_ID_COOKIE, roomId);
        roomIdCookie.setPath("/");
        roomIdCookie.setMaxAge(60); // 60초
        roomIdCookie.setHttpOnly(false); // JavaScript 접근 차단 (보안)
        roomIdCookie.setSecure(false); // HTTP에서도 전송 (개발환경)
        response.addCookie(roomIdCookie);
    }

    @Override
    public String getNginxCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ((CHATFORYOU_SERVER_COOKIE).equals(cookie.getName())) {
                    String nginxCookieVal = cookie.getValue();
                    log.info(cookie.getName() + " : " + nginxCookieVal);
                    // Nginx가 생성한 쿠키 형태인지 확인 (|가 포함된 형태)
                    if (nginxCookieVal.contains("|")) {
                        log.info("Found nginx cookie in current request: {}", nginxCookieVal);
                        return nginxCookieVal;
                    }
                }
            }
        }
        log.info("No nginx cookie found in current request");
        return instanceProvider.getInstanceId();
    }

    @Override
    public String getRoomIdCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ((ROOM_ID_COOKIE).equals(cookie.getName())) {
                    log.info(cookie.getName() + " : " + cookie.getValue());
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}
