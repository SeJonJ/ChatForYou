package webChat.service.routing;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public interface RoutingService {
    String saveRoomInstanceId(String roomId);
//    String getRoomInstanceId(String roomId);
    void setRoomCookie(String roomId, String instanceId, HttpServletResponse response);
    void setRoomCookie(String roomId, HttpServletResponse response);
    String getInstanceIdFromCookie(String roomId, HttpServletRequest request);
}
