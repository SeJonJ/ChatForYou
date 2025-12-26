package webChat.repository;

import org.kurento.client.Composite;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class KurentoCompositeMap {
    private static KurentoCompositeMap kurentoCompositeMap = new KurentoCompositeMap();
    private KurentoCompositeMap(){}

    private Map<String, Composite> compositeMap = new ConcurrentHashMap<>();

    public static Composite getComposite(String roomId){
        return kurentoCompositeMap.compositeMap.get(roomId);
    }
    public static void setComposite(String roomId, Composite composite){
        kurentoCompositeMap.compositeMap.put(roomId, composite);
    }

    public static void removeComposite(String roomId){
        kurentoCompositeMap.compositeMap.remove(roomId);
    }
}
