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


    Set<String> getKeysByPattern(String pattern);

    List<KurentoRoom> getChatRoomListForDelete(int searchCount) throws BadRequestException;

    void insertChatRoom(ChatRoom chatRoom);

    <T> T getRedisDataByDataType(String key, DataType dataType, Class<T> clazz) throws BadRequestException;

    Map<Object, Object> getAllChatRoomData(String roomId);

    List<Document> searchRoomListByOptions(RoomSearchCriteria searchCriteria);

    boolean deleteAllChatRoomData(String str);

    void updateChatRoom(ChatRoom chatRoom);

    boolean checkRoomName(String roomName);

    void insertGoogleOauthToken(GoogleOAuth auth);
}
