package webChat.service.routing;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public interface RoutingService {
    String saveRoomInstanceId(String roomId);
//    String getRoomInstanceId(String roomId);
   String getInstanceIdFromCookie(HttpServletRequest request);
    void setRoomCookie(HttpServletRequest request, HttpServletResponse response, String instanceId);
}
