package webChat.repository;

import org.kurento.client.HubPort;
import org.kurento.client.RecorderEndpoint;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 방별 녹화 객체들을 메모리에서 관리하는 Map
 * RecorderEndpoint와 HubPort는 직렬화될 수 없으므로 메모리에서만 관리
 */
@Slf4j
public class KurentoRecorderMap {
    
    // 방별 RecorderEndpoint 저장
    private static final ConcurrentMap<String, RecorderEndpoint> roomRecorderMap = new ConcurrentHashMap<>();
    
    // 방별 RecorderHubPort 저장  
    private static final ConcurrentMap<String, HubPort> roomRecorderHubPortMap = new ConcurrentHashMap<>();
    
    /**
     * 방의 RecorderEndpoint 저장
     */
    public static void setRecorderEndpoint(String roomId, RecorderEndpoint recorderEndpoint) {
        roomRecorderMap.put(roomId, recorderEndpoint);
        log.info("RecorderEndpoint stored for room: {}", roomId);
    }
    
    /**
     * 방의 RecorderEndpoint 조회
     */
    public static RecorderEndpoint getRecorderEndpoint(String roomId) {
        return roomRecorderMap.get(roomId);
    }
    
    /**
     * 방의 RecorderHubPort 저장
     */
    public static void setRecorderHubPort(String roomId, HubPort hubPort) {
        roomRecorderHubPortMap.put(roomId, hubPort);
        log.info("RecorderHubPort stored for room: {}", roomId);
    }
    
    /**
     * 방의 RecorderHubPort 조회
     */
    public static HubPort getRecorderHubPort(String roomId) {
        return roomRecorderHubPortMap.get(roomId);
    }
    
    /**
     * 방의 녹화 관련 객체들 제거
     */
    public static void removeRecorder(String roomId) {
        RecorderEndpoint recorder = roomRecorderMap.remove(roomId);
        HubPort hubPort = roomRecorderHubPortMap.remove(roomId);
        
        if (recorder != null || hubPort != null) {
            log.info("Removed recording objects for room: {}", roomId);
        }
    }
    
    /**
     * 방의 녹화 객체 존재 여부 확인
     */
    public static boolean hasRecorder(String roomId) {
        return roomRecorderMap.containsKey(roomId);
    }
}