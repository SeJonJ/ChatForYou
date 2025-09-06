package webChat.service.routing.impl;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.coyote.BadRequestException;
import org.springframework.stereotype.Service;
import webChat.model.redis.DataType;
import webChat.model.redis.RedisKeyPrefix;
import webChat.model.routing.RoomRoutingInfo;
import webChat.model.routing.RoutingCookie;
import webChat.service.redis.RedisService;
import webChat.service.routing.RoutingInstanceProvider;
import webChat.service.routing.RoutingService;
import webChat.utils.StringUtil;

import static webChat.model.routing.RoutingCookie.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class RoutingServiceImpl implements RoutingService {

    private final RedisService redisService;
    private final RoutingInstanceProvider instanceProvider;

    @Override
    public void setRoutingInfo(HttpServletRequest request, HttpServletResponse response, String roomId, String selectedInstanceId) throws BadRequestException {
        // 1. roomId 기준 roomRoutingInfo 객체 조회
        String myInstanceId = instanceProvider.getInstanceId();
        RoomRoutingInfo roomRoutingInfo = redisService.getRedisDataByDataType(RedisKeyPrefix.ROOM_ROUTING_PREFIX.getPrefix() + roomId, DataType.ROOM_ROUTING, RoomRoutingInfo.class);

        if (roomRoutingInfo != null) { // 2. redis 조회해서 nginxCookie 가 있다면 세팅
            this.setServerCookie(response, roomRoutingInfo.getNginxCookie());
            this.setRoomIdCookie(response, roomId);
        } else {
            String roomRedirectCookie = this.getCookie(request, ROOM_REDIRECT_COUNT);
            int redirectCount = StringUtil.isNullOrEmpty(roomRedirectCookie) ? 0 : Integer.parseInt(roomRedirectCookie);
            if(redirectCount > 3) { // 리다이렉트가 3번 초과시에만 selectedInstanceId 를 현재 instanceId 로 수정
                String currentNginxCookie = this.getNginxCookie(request);
                if(currentNginxCookie == null) {
                    currentNginxCookie = myInstanceId;
                }
                // 결국 selectedInstanceId 의 cookie 를 알 수 없음으로 cookie 확인 가능한 instance 로 수정
                redisService.saveRoomRoutingInfo(RoomRoutingInfo.of(roomId, myInstanceId, currentNginxCookie, System.currentTimeMillis()));
                this.setServerCookie(response, myInstanceId);
                this.setRoomIdCookie(response, roomId);
                this.setRoomRedirectCookie(response, 1, 0);
            } else {
                String instanceCookie = redisService.getRedisDataByDataType(RedisKeyPrefix.INSTANCE_COOKIE_PREFIX.getPrefix() + myInstanceId, DataType.INSTANCE_COOKIE, String.class);
                this.setServerCookie(response, instanceCookie);
                this.setRoomIdCookie(response, roomId);
                this.setRoomRedirectCookie(response, redirectCount + 1, 60);
            }
        }


    }

    @Override
    public void setRoutingInfo(HttpServletResponse response, String roomId, String nginxCookie) {
        this.setServerCookie(response, nginxCookie);
        this.setRoomIdCookie(response, roomId);
    }

    @Override
    public String getNginxCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ((CHATFORYOU_SERVER_COOKIE.getName()).equals(cookie.getName())) {
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
    public String getCookie(HttpServletRequest request, RoutingCookie routingCookie) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ((routingCookie.getName()).equals(cookie.getName())) {
                    log.info(cookie.getName() + " : " + cookie.getValue());
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    private void setServerCookie(HttpServletResponse response, String nginxCookie){
        Cookie cookie = new Cookie(CHATFORYOU_SERVER_COOKIE.getName(), nginxCookie);
        cookie.setPath("/");
        cookie.setMaxAge(24 * 60 * 60); // 24시간
        cookie.setHttpOnly(true); // JavaScript 접근 차단 (보안)
        cookie.setSecure(false); // HTTP에서도 전송 (개발환경)
        response.addCookie(cookie);
    }

    private void setRoomIdCookie(HttpServletResponse response, String roomId){
        Cookie cookie = new Cookie(ROOM_ID_COOKIE.getName(), roomId);
        cookie.setPath("/");
        cookie.setMaxAge(60); // 60초
        cookie.setHttpOnly(false); // JavaScript 접근 차단 (보안)
        cookie.setSecure(false); // HTTP에서도 전송 (개발환경)
        response.addCookie(cookie);
    }

    private void setRoomRedirectCookie(HttpServletResponse response, int redirectCount, int age){
        Cookie cookie = new Cookie(ROOM_REDIRECT_COUNT.getName(), String.valueOf(redirectCount));
        cookie.setPath("/");
        cookie.setMaxAge(age); // 60초
        cookie.setHttpOnly(true); // JavaScript 접근 차단 (보안)
        cookie.setSecure(false); // HTTP에서도 전송 (개발환경)
        response.addCookie(cookie);
    }
}
