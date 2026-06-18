package webChat.service.routing;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import webChat.model.routing.RoomRoutingInfo;
import webChat.model.routing.RoutingCookie;

public interface RoutingService {
    RoomRoutingInfo getRoomRoutingInfoByRoomId(String roomId);
    String getNginxCookie(HttpServletRequest request);
    String getCookie(HttpServletRequest request, RoutingCookie routingCookie);
    void setRoutingInfo(HttpServletRequest request, HttpServletResponse response, String roomId, String selectedInstanceId);
    void setRoutingInfo(HttpServletResponse response, String roomId, String nginxCookie, int redirectCount);
    /**
     * 복구 완료 후 현재 인스턴스 기준 sticky routing cookie를 재발급한다.
     */
    void setRecoveryRoutingInfo(HttpServletResponse response, String roomId, String nginxCookie);
    int getRedirectCount(HttpServletRequest request);
}
