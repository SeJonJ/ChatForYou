package webChat.service.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.dengliming.redismodule.redisearch.index.Document;
import lombok.NonNull;
import org.apache.coyote.BadRequestException;
import webChat.model.login.GoogleOAuth;
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

    void incrementUserCount(KurentoRoom kurentoRoom);

    void decrementUserCount(KurentoRoom kurentoRoom);

    long increment(String key, long delta);

    long decrement(String key, long delta);

    Set<String> getKeysByPattern(String pattern);

    List<KurentoRoom> getChatRoomListForDelete(int searchCount) throws BadRequestException;

    void saveChatRoom(ChatRoom chatRoom);

    <T> T getRedisDataByDataType(String key, DataType dataType, Class<T> clazz) throws BadRequestException;

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

    Map<String, String> getAllInstanceCookies() throws BadRequestException;
    void insertGoogleOauthToken(GoogleOAuth auth);
}
