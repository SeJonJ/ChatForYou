package webChat.service.routing;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.coyote.BadRequestException;
import webChat.model.routing.RoutingCookie;

public interface RoutingService {
    String getNginxCookie(HttpServletRequest request);
    String getCookie(HttpServletRequest request, RoutingCookie routingCookie);
    void setRoutingInfo(HttpServletRequest request, HttpServletResponse response, String roomId, String selectedInstanceId) throws BadRequestException;
    void setRoutingInfo(HttpServletResponse response, String roomId, String nginxCookie, int redirectCount);
    int getRedirectCount(HttpServletRequest request);
}
