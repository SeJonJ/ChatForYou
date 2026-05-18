package webChat.service.routing.impl;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseCookie;
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

    /**
     * 채팅방 생성 직후 라우팅 쿠키와 roomId 쿠키를 설정한다.
     *
     * @param request 현재 요청
     * @param response 현재 응답
     * @param roomId 채팅방 ID
     * @param selectedInstanceId 선택된 인스턴스 ID
     */
    @Override
    public void setRoutingInfo(HttpServletRequest request, HttpServletResponse response, String roomId, String selectedInstanceId) {
        // 1. roomId 기준 roomRoutingInfo 객체 조회
        String myInstanceId = instanceProvider.getInstanceId();
        RoomRoutingInfo roomRoutingInfo = redisService.getRedisDataByDataType(RedisKeyPrefix.ROOM_ROUTING_PREFIX.getPrefix() + roomId, DataType.ROOM_ROUTING, RoomRoutingInfo.class);

        if (roomRoutingInfo != null) { // 2. redis 조회해서 nginxCookie 가 있다면 세팅
            this.setServerCookie(response, roomRoutingInfo.getNginxCookie());
            this.setRoomIdCookie(response, roomId);
        } else {
            int redirectCount = this.getRedirectCount(request);
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
                redisService.saveRoomRoutingInfo(RoomRoutingInfo.of(roomId, myInstanceId, instanceCookie, System.currentTimeMillis()));
                this.setServerCookie(response, instanceCookie);
                this.setRoomIdCookie(response, roomId);
                this.setRoomRedirectCookie(response, redirectCount + 1, 60);
            }
        }
    }

    /**
     * 이미 계산된 nginx 쿠키 기준으로 라우팅 쿠키를 재설정한다.
     *
     * @param response 현재 응답
     * @param roomId 채팅방 ID
     * @param nginxCookie nginx sticky 쿠키 값
     * @param redirectCount 현재 리다이렉트 횟수
     */
    @Override
    public void setRoutingInfo(HttpServletResponse response, String roomId, String nginxCookie, int redirectCount) {
        this.setServerCookie(response, nginxCookie);
        this.setRoomIdCookie(response, roomId);
        this.setRoomRedirectCookie(response, redirectCount, 60);
    }

    /**
     * 요청 쿠키에서 현재 리다이렉트 횟수를 조회한다.
     *
     * @param request 현재 요청
     * @return 리다이렉트 횟수
     */
    @Override
    public int getRedirectCount(HttpServletRequest request){
        String roomRedirectCount = this.getCookie(request, ROOM_REDIRECT_COUNT);
        return StringUtil.isNullOrEmpty(roomRedirectCount) ? 0 : Integer.parseInt(roomRedirectCount);
    }

    /**
     * roomId 기준 라우팅 정보를 조회한다.
     *
     * @param roomId 채팅방 ID
     * @return 라우팅 정보
     */
    @Override
    public RoomRoutingInfo getRoomRoutingInfoByRoomId(String roomId) {
         return redisService.getRedisDataByDataType(RedisKeyPrefix.ROOM_ROUTING_PREFIX.getPrefix() + roomId, DataType.ROOM_ROUTING, RoomRoutingInfo.class);
    }

    /**
     * 현재 요청에서 nginx sticky 쿠키를 조회한다.
     *
     * @param request 현재 요청
     * @return nginx 쿠키 값 또는 현재 인스턴스 ID
     */
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

    /**
     * 요청에서 지정한 라우팅 쿠키 값을 조회한다.
     *
     * @param request 현재 요청
     * @param routingCookie 조회 대상 쿠키 타입
     * @return 쿠키 값
     */
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
        ResponseCookie responseCookie = ResponseCookie.from(CHATFORYOU_SERVER_COOKIE.getName(), nginxCookie)
                .path("/")
                .maxAge(24 * 60 * 60)
                .httpOnly(true)
                .secure(true)
                .sameSite("None")
                .build();
        response.addHeader("Set-Cookie", responseCookie.toString());
    }

    private void setRoomIdCookie(HttpServletResponse response, String roomId){
        ResponseCookie responseCookie = ResponseCookie.from(ROOM_ID_COOKIE.getName(), roomId)
                .path("/")
                .maxAge(60)
                .httpOnly(false)
                .secure(true)
                .sameSite("None")
                .build();
        response.addHeader("Set-Cookie", responseCookie.toString());
    }

    private void setRoomRedirectCookie(HttpServletResponse response, int redirectCount, Integer age){
        ResponseCookie responseCookie = ResponseCookie.from(ROOM_REDIRECT_COUNT.getName(), String.valueOf(redirectCount))
                .path("/")
                .maxAge(age == null ? 60 : age)
                .httpOnly(true)
                .secure(true)
                .sameSite("None")
                .build();
        response.addHeader("Set-Cookie", responseCookie.toString());
    }
}
