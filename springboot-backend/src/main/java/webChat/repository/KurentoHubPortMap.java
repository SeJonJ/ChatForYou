package webChat.repository;

import org.kurento.client.HubPort;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class KurentoHubPortMap {
    private KurentoHubPortMap(){}
    private static KurentoHubPortMap kurentoHubPortMap = new KurentoHubPortMap();
    private Map<String, Map<String, HubPort>> userHubPortMap = new ConcurrentHashMap<>();
    private Map<String, HubPort> userHubPort = new ConcurrentHashMap<>();

    public static void setUserHubPort(String roomId, String userId, HubPort hubPort){
        kurentoHubPortMap.userHubPort.put(userId, hubPort);

        kurentoHubPortMap.userHubPortMap.put(roomId, kurentoHubPortMap.userHubPort);
    }

    public static HubPort getUserHubPort(String roomId, String userId){
        Map<String, HubPort> userHubPort = kurentoHubPortMap.userHubPortMap.get(roomId);
        if(userHubPort == null){
            return null;
        }
        return userHubPort.get(userId);
    }

    public static void removeUserHubPort(String roomId, String userId) {
        kurentoHubPortMap.userHubPortMap.get(roomId).remove(userId);
    }
}
