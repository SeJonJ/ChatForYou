package webChat.service.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.dengliming.redismodule.redisearch.index.Document;
import lombok.NonNull;
import webChat.model.login.GoogleOAuth;
import webChat.model.login.OauthRedis;
import webChat.model.login.QRSession;
import webChat.model.redis.DataType;
import webChat.model.redis.RoomSearchCriteria;
import webChat.model.room.ChatRoom;
import webChat.model.room.KurentoRoom;
import webChat.model.routing.RoomRoutingInfo;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public interface RedisService {
    <T> void setObject(@NonNull String key, T object);

    <T> void setObject(@NonNull String key, T object, long timeout, TimeUnit unit);

    <T> T getObject(@NonNull String key, Class<T> clazz);

    <T> T getObject(@NonNull String key, TypeReference<T> typeReference);

    void setObjectOpsHash(@NonNull String roomId, DataType dataType, Object o);

    void delete(@NonNull String key);

    boolean hasKey(@NonNull String key);

    long getExpired(@NonNull String key);

    long getExpiredByTimeUnit(@NonNull String key, TimeUnit timeUnit);

    void syncUserCount(KurentoRoom kurentoRoom, int actualCount);

    long increment(String key, long delta);

    long decrement(String key, long delta);

    Set<String> getKeysByPattern(String pattern);

    List<KurentoRoom> getChatRoomListForDelete(int searchCount);

    void saveChatRoom(ChatRoom chatRoom);

    <T> T getRedisDataByDataType(String key, DataType dataType, Class<T> clazz);

    Map<Object, Object> getAllChatRoomData(String roomId);

    List<Document> searchRoomListByOptions(RoomSearchCriteria searchCriteria);

    boolean deleteAllChatRoomData(String str);

    void updateChatRoom(ChatRoom chatRoom);

    boolean checkRoomName(String roomName);

    /**
     * roomID 를 기준으로 instanceId 및 cookie 를 저장
     * @param roomRoutingInfo roomId, instanceId, cookie 가 매핑된 객체
     */
    void saveRoomRoutingInfo(RoomRoutingInfo roomRoutingInfo);

    long getInstanceRoomCount(String key);

    /**
     * redis 에서 instanceId 와 관련된 정보 모두 삭제
     * @param instanceId
     */
    void delInstanceInfo(String instanceId);

    void saveInstanceCookieMapping(String currentInstanceId, String cookie);

    Map<String, String> getAllInstanceCookies();
    void insertGoogleOauthToken(OauthRedis oauthRedis, long time);
    void deleteLoginInfo(long idx);
    void insertQRSession(QRSession qrSession);
    QRSession getQRSession(String sessionId);

    /**
     * 방 입장이 확정된 참가자 email 을 방 멤버십 ledger 에 기록한다.
     * 녹화 다운로드 권한 검증의 기준 데이터로 사용된다.
     */
    void addRoomMember(String roomId, String email);

    /**
     * 요청자 email 이 해당 방의 멤버십 ledger 에 존재하는지 확인한다.
     * 키 미존재 또는 비멤버이면 false 를 반환한다.
     */
    boolean isRoomMember(String roomId, String email);

    /**
     * 방 영구 삭제 시 멤버십 ledger 키를 정리한다.
     */
    void deleteRoomMembers(String roomId);
}
