package webChat.service.routing;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public interface RoutingService {
    String getNginxCookie(HttpServletRequest request);
    String getRoomIdCookie(HttpServletRequest request);
    void setRoomCookie(HttpServletRequest request, HttpServletResponse response, String roomId, String selectedInstanceId);
    void setRoomCookie(HttpServletResponse response, String roomId, String nginxCookie);
}
