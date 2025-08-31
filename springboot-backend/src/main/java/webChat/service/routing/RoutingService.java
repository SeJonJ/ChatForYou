package webChat.service.routing;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import webChat.model.routing.RoutingCookie;

public interface RoutingService {
    String getNginxCookie(HttpServletRequest request);
    String getCookie(HttpServletRequest request, RoutingCookie routingCookie);
    void setRoutingInfo(HttpServletRequest request, HttpServletResponse response, String roomId, String selectedInstanceId);
    void setRoutingInfo(HttpServletResponse response, String roomId, String nginxCookie);
}
