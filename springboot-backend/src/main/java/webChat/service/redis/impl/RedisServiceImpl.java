package webChat.service.redis.impl;

import ch.qos.logback.core.util.StringUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dengliming.redismodule.redisearch.RediSearch;
import io.github.dengliming.redismodule.redisearch.client.RediSearchClient;
import io.github.dengliming.redismodule.redisearch.index.Document;
import io.github.dengliming.redismodule.redisearch.search.SearchOptions;
import io.github.dengliming.redismodule.redisearch.search.SortBy;
import io.lettuce.core.RedisException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import webChat.exception.ChatForYouException;
import webChat.exception.ErrorCode;
import org.redisson.api.SortOrder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.*;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import webChat.model.login.OauthRedis;
import webChat.model.login.QRSession;
import webChat.model.redis.DataType;
import webChat.model.redis.RoomSearchCriteria;
import webChat.model.room.ChatRoom;
import webChat.model.room.KurentoRoom;
import webChat.model.room.RoomState;
import webChat.model.routing.RoomRoutingInfo;
import webChat.service.redis.RedisService;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static webChat.model.redis.RedisKeyPrefix.*;

/**
 * redis 사용을 위한 서비스 클래스
 * masterTemplate : 주로 Create(insert), Update, Delete 를 위해 사용
 * slaveTemplate : 주로 Read(select) 를 위해 사용
 */
@Component
@Slf4j
public class RedisServiceImpl implements RedisService {

    private final RedisTemplate<String, Object> masterTemplate;
    private final RedisTemplate<String, Object> slaveTemplate;
    private final ObjectMapper objectMapper;
    private final RediSearchClient rediSearchClient;
    private final long REDIS_TIMEOUT = 1L;

    private static final long INSTANCE_MAPPING_TTL = 86400L; // 24시간

    public RedisServiceImpl(
            @Qualifier("masterRedisTemplate") RedisTemplate<String, Object> masterTemplate,
            @Qualifier("slaveRedisTemplate") RedisTemplate<String, Object> slaveTemplate,
            ObjectMapper objectMapper, RediSearchClient rediSearchClient) {
        this.masterTemplate = masterTemplate;
        this.slaveTemplate = slaveTemplate;
        this.objectMapper = objectMapper;
        this.rediSearchClient = rediSearchClient;
    }

    /**
     * 객체를 JSON 으로 변환하여 Redis에 저장
     *
     * @param key    Redis 키
     * @param object 저장할 객체
     * @param <T>    객체 타입
     */
    @Override
    public <T> void setObject(@NonNull String key, T object) {
        ValueOperations<String, Object> ops = masterTemplate.opsForValue();
        ops.set(key, object);  // RedisTemplate 이 자동으로 직렬화 처리
    }

    /**
     * 객체를 Redis에 저장하며, TTL(Expired Time) 설정
     *
     * @param key     Redis 키
     * @param object  저장할 객체
     * @param timeout 만료 시간 (TTL)
     * @param unit    시간 단위
     * @param <T>     객체 타입
     */
    @Override
    public <T> void setObject(@NonNull String key, T object, long timeout, TimeUnit unit) {
        ValueOperations<String, Object> ops = masterTemplate.opsForValue();
        ops.set(key, object, timeout, unit);  // RedisTemplate 이 자동으로 직렬화 처리
    }

    /**
     * Redis에서 JSON 데이터를 가져와서 객체로 변환
     *
     * @param key   Redis 키
     * @param clazz 변환할 클래스 타입
     * @param <T>   객체 타입
     * @return 변환된 객체 (키가 존재하지 않으면 null)
     */
    @Override
    public <T> T getObject(@NonNull String key, Class<T> clazz) {
        ValueOperations<String, Object> ops = slaveTemplate.opsForValue();
        Object value = ops.get(key);
        if (value == null) {
            return null;
        }
        return clazz.cast(value);  // 캐스트를 사용하여 값을 반환
    }

    @Override
    public <T> T getObject(@NonNull String key, TypeReference<T> typeReference) {
        ValueOperations<String, Object> ops = slaveTemplate.opsForValue();
        String json = (String) ops.get(key);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, typeReference);
        } catch (JsonProcessingException e) {
            throw new ChatForYouException(ErrorCode.INTERNAL_SERVER_ERROR, "Redis JSON 역직렬화 실패", e);
        }
    }

    @Override
    public void setObjectOpsHash(@NonNull String roomId, DataType dataType, Object o) {
        String redisKey = ROOM_ID_PREFIX.getPrefix() + roomId;

        // Redis에 객체 저장
        masterTemplate.opsForHash().put(redisKey, dataType.getType(), o);
    }

    /**
     * Redis에서 키를 삭제
     *
     * @param key Redis 키
     */
    @Override
    public void delete(@NonNull String key) {
        masterTemplate.delete(key);
    }

    /**
     * Redis에 키가 존재하는지 확인
     *
     * @param key Redis 키
     * @return 키가 존재하면 true, 존재하지 않으면 false
     */
    @Override
    public boolean hasKey(@NonNull String key) {
        return slaveTemplate.hasKey(key);
    }

    /**
     * Redis expired 확인
     *
     * @param key Redis 키
     * @return 키가 존재하면 true, 존재하지 않으면 false
     */
    @Override
    public long getExpired(@NonNull String key) {
        return slaveTemplate.getExpire(key);
    }

    /**
     * Redis expired TimeUnit 으로 변환한 값
     *
     * @param key      Redis 키
     * @param timeUnit timeUnit 으로 변환
     * @return 키가 존재하면 true, 존재하지 않으면 false
     */
    @Override
    public long getExpiredByTimeUnit(@NonNull String key, TimeUnit timeUnit) {
        return slaveTemplate.getExpire(key, timeUnit);
    }

    /**
     * 방의 userCount 를 실제 참가자 수로 동기화한다.
     *
     * ±1 산술(increment/decrement) 대신 참가자 맵의 실제 size 를 권위 소스로 그대로 기록한다.
     * 비원자 read-modify-write 로 인한 lost update 와, 재연결(세션 교체) 시 발생하던
     * decrement-without-increment 비대칭을 원천 제거한다. 음수 하한이 불필요한 이유는
     * 입력값이 참가자 맵 size(0 이상)이기 때문이다.
     *
     * @param kurentoRoom 동기화 대상 방
     * @param actualCount 참가자 맵의 실제 참가자 수
     */
    @Override
    public void syncUserCount(KurentoRoom kurentoRoom, int actualCount) {
        kurentoRoom.setUserCount(actualCount);
        this.updateChatRoom(kurentoRoom);
    }

    @Override
    public long increment(String key, long delta) {
        return Optional.ofNullable(masterTemplate.opsForValue().increment(key, delta))
                .orElse(0L);
    }

    @Override
    public long decrement(String key, long delta) {
        return Optional.ofNullable(masterTemplate.opsForValue().decrement(key, delta))
                .orElse(0L);
    }

    /**
     * redis 에 저장된 데이터 key 를 특정 pattern 에 맞춰 가져옴
     *
     * @param pattern
     * @return pattern 에 맞는 key set
     */
    @Override
    public Set<String> getKeysByPattern(String pattern) {
        Set<String> keys = new HashSet<>();

        // SCAN 옵션 설정 :: 와일드카드 검색할때는 뒤에 * 도 함께 붙여주자
        ScanOptions scanOptions = ScanOptions.scanOptions().match("*" + pattern + "*").count(100).build();

        // Redis 커넥션에서 커서를 사용해 SCAN 명령 실행
        try (Cursor<byte[]> cursor = slaveTemplate.getConnectionFactory().getConnection().scan(scanOptions)) {
            while (cursor.hasNext()) {
                // 커서가 반환하는 키를 Set에 추가
                keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            throw new ChatForYouException(ErrorCode.INTERNAL_SERVER_ERROR, "Redis key scan 실패", e);
        }

        return keys;
    }

    /**
     * 삭제가 필요한 chatroom 을 검색한다.
     * 조건 : userCount <= 0 || userCount > maxUserCount && state == "active"
     * 현재는 SCAN 기반으로 조회하며, 대량 트래픽 환경에서는 Lua script 최적화를 검토한다.
     */
    @Override
    public List<KurentoRoom> getChatRoomListForDelete(int searchCount) {
        String pattern = "*roomId:" + "*";
        List<KurentoRoom> roomList = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(searchCount).build();

        try (Cursor<byte[]> cursor = slaveTemplate.getConnectionFactory().getConnection().scan(options)) {
            while (cursor.hasNext()) {
                String key = cleanKey(new String(cursor.next()));
                KurentoRoom kurentoRoom = this.getRedisDataByDataType(key, DataType.CHATROOM, KurentoRoom.class);

                if (kurentoRoom == null) {
                    continue;
                }

                // State가 active인 것만 필터링
                boolean isActiveState = RoomState.ACTIVE.equals(kurentoRoom.getRoomState());

                // 방 active 여부 체크
                if (isActiveState) {
                    // 방 userCount 조건 확인
                    boolean isDeletionCandidate = kurentoRoom.getUserCount() <= 0 ||
                            kurentoRoom.getUserCount() > kurentoRoom.getMaxUserCnt();
                    if(isDeletionCandidate) roomList.add(kurentoRoom);
                } else { // inactive || crated 인 경우 제거
                    roomList.add(kurentoRoom);
                }

            }
        } catch (Exception e) {
            log.error("Error scanning Redis keys: ", e);
            throw new ChatForYouException(ErrorCode.INTERNAL_SERVER_ERROR);
        }

        return roomList;
    }

    /**
     * 불필요한 문자를 제거하여 주어진 Redis 키를 정리합니다.
     *
     * @param key 원본 키
     * @return 정리된 키
     */
    private String cleanKey(String key) {
        return key.replace("\"", "").replace(ROOM_ID_PREFIX.getPrefix(), "");
    }

    @Override
    public boolean deleteAllChatRoomData(String str) {
        String pattern = "*" + str + "*";
        try {
            // SCAN 명령어를 사용하여 키 검색 및 삭제
            ScanOptions scanOptions = ScanOptions.scanOptions().match(pattern).count(100).build();

            List<String> keysToDelete = new ArrayList<>();
            try (Cursor<byte[]> cursor = slaveTemplate.execute(
                    (RedisCallback<Cursor<byte[]>>) connection -> connection.scan(scanOptions))) {

                while (cursor.hasNext()) {
                    keysToDelete.add(new String(cursor.next(), StandardCharsets.UTF_8).replace("\"", ""));
                }
            } catch (Exception e) {
                log.error("Error occurred while scanning and deleting keys", e);
                return false;
            }

            if (!keysToDelete.isEmpty()) {
                masterTemplate.delete(keysToDelete);
            }
            return true;
        } catch (RedisException e) {
            log.error("UnExpected Redis Exception", e);
            return false;
        }
    }

    @Override
    public void updateChatRoom(ChatRoom chatRoom) {
        String redisKey = ROOM_ID_PREFIX.getPrefix() + chatRoom.getRoomId();
        masterTemplate.opsForHash().put(redisKey, DataType.CHATROOM.getType(), chatRoom);
        masterTemplate.opsForHash().put(redisKey, "roomName", chatRoom.getRoomName());
        masterTemplate.opsForHash().put(redisKey, "state", chatRoom.getRoomState());
    }

    @Override
    public void saveChatRoom(ChatRoom chatRoom) {
        String redisKey = ROOM_ID_PREFIX.getPrefix() + chatRoom.getRoomId();
        // 채팅방 객체 저장
        masterTemplate.opsForHash().put(redisKey, DataType.CHATROOM.getType(), chatRoom);
        masterTemplate.opsForHash().put(redisKey, "roomId", chatRoom.getRoomId());
        masterTemplate.opsForHash().put(redisKey, "creator", chatRoom.getCreator());
        masterTemplate.opsForHash().put(redisKey, "roomName", chatRoom.getRoomName());
        masterTemplate.opsForHash().put(redisKey, "createDate", chatRoom.getCreateDate());
        masterTemplate.opsForHash().put(redisKey, "state", chatRoom.getRoomState());
    }

    @Override
    public <T> T getRedisDataByDataType(String key, DataType dataType, Class<T> clazz) {
        String redisKey = "";
        if (DataType.LOGIN_USER.equals(dataType) || DataType.USER_REFRESH_TOKEN.equals(dataType) || DataType.USER_LAST_LOGIN_DATE.equals(dataType)) {
            redisKey = key.contains("user:") ? key : "user:" + key;
        } else if (DataType.SOCIAL_USER.equals(dataType)) {
            redisKey = OAUTH_PREFIX.getPrefix() + key;
        } else {
            redisKey = makeRedisKey(key);
        }
        switch (dataType) {
            case CHATROOM:
                return clazz.cast(slaveTemplate.opsForHash().get(redisKey, DataType.CHATROOM.getType()));
                // 로그인 저장 구조가 확정되면 USER 관련 조회 분기를 다시 활성화한다.
//            case USER_LIST:
//                String userListKey = redisKey + ":userList";
//                return clazz.cast(slaveTemplate.opsForSet().members(userListKey).stream().collect(Collectors.toList()));
//            case LOGIN_USER:
//                return clazz.cast(slaveTemplate.opsForHash().get(redisKey, DataType.LOGIN_USER.getType()));
//            case USER_REFRESH_TOKEN:
//                return clazz.cast(slaveTemplate.opsForHash().get(redisKey, DataType.USER_REFRESH_TOKEN.getType()));
//            case USER_LAST_LOGIN_DATE:
//                return clazz.cast(slaveTemplate.opsForHash().get(redisKey, DataType.USER_LAST_LOGIN_DATE.getType()));
            case ROOM_ROUTING:
                return clazz.cast(slaveTemplate.opsForValue().get(key));
            case INSTANCE_COOKIE:
                return clazz.cast(slaveTemplate.opsForValue().get(key));
            case SOCIAL_USER:
                return clazz.cast(slaveTemplate.opsForHash().get(redisKey, DataType.SOCIAL_USER.getType()));
            default:
                throw new ChatForYouException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    @NotNull
    private String makeRedisKey(String roomId) {
        return roomId.contains(ROOM_ID_PREFIX.getPrefix()) ? roomId : ROOM_ID_PREFIX.getPrefix() + roomId;
    }

    @Override
    public Map<Object, Object> getAllChatRoomData(String roomId) {
        String redisKey = this.makeRedisKey(roomId);
        return slaveTemplate.opsForHash().entries(redisKey);
    }

    @Override
    public List<Document> searchRoomListByOptions(RoomSearchCriteria searchCriteria) {
        // searchType 에 맞춰 indexName 을 가져옴
        RediSearch rediSearch = rediSearchClient.getRediSearch(searchCriteria.getRedisIndex().getType());

        // Redis 검색 결과에서 openvidu 필드의 JSON 문자열 가져오기
//        int pageNumber = 0;  // 원하는 페이지 번호
//        int pageSize = 5;    // 한 페이지에 표시할 항목 수
        SearchOptions searchOptions = null;
        StringBuilder queryBuilder = new StringBuilder();

        // or 조건이 제대로 동작하려면 조건과 조건을 () 로 구분해서 묶어야함
        switch (searchCriteria.getRedisIndex()) {
            case CHATROOM:
                // keyword 조건 추가
                if (!StringUtil.isNullOrEmpty(searchCriteria.getKeyword())) {
                    queryBuilder.append("((@creator:*")
                            .append(searchCriteria.getKeyword())
                            .append("*) | (@roomName:*")
                            .append(searchCriteria.getKeyword())
                            .append("*))");
                }

                // State OR 조건 추가
                if (!CollectionUtils.isEmpty(searchCriteria.getRoomStates())) {
                    if (!queryBuilder.isEmpty()) {
                        queryBuilder.append(" ");
                    }
                    queryBuilder.append("(");
                    for (int i = 0; i < searchCriteria.getRoomStates().size(); i++) {
                        if (i > 0) {
                            queryBuilder.append(" | ");
                        }
                        queryBuilder.append("@state:")
                                .append(searchCriteria.getRoomStates().get(i));
                    }
                    queryBuilder.append(")");
                }

                searchOptions = new SearchOptions()
                        .page(searchCriteria.getPageNum() * searchCriteria.getPageSize(), searchCriteria.getPageSize())  // 페이지 설정
                        .returnFields("roomId")  // roomId 필드만 반환
                        .sort(new SortBy("createDate", SortOrder.DESC));  // createDate 기준 내림차순 정렬
                break;

            // 로그인 검색 구조가 확정되면 USER 인덱스 검색 분기를 다시 활성화한다.
//            case LOGIN_USER:
//                if (!StringUtil.isNullOrEmpty(keyword)) {
//                    // 검색어가 있을 때: userId 또는 nickName 필드 검색
//                    queryParam = "((@USER_ID_PREFIX*" + keyword + "*) | (@nickName:*" + keyword + "*))";
//                }
//                searchOptions = new SearchOptions()
//                        .page(pageNum * pageSize, pageSize)  // 페이지 설정
//                        .returnFields(DataType.LOGIN_USER.getType())
//                        .sort(new SortBy("userId", SortOrder.DESC));  // userId 기준 내림차순 정렬
//                break;
        }

        // 조건이 없으면 모든 결과
        String finalQuery = !queryBuilder.isEmpty() ? queryBuilder.toString() : "*";
        List<Document> documents = rediSearch.search(
                finalQuery,
                searchOptions
        ).getDocuments();

        return documents;
    }

    // 현재는 roomId 패턴 검색을 사용하며, 성능 문제가 커지면 roomName 전용 set 구조로 분리한다.
    @Override
    public boolean checkRoomName(String roomName) {
        if (roomName == null || roomName.trim().isEmpty()) {
            return false; // 유효하지 않은 입력
        }

        Set<String> keys = slaveTemplate.keys(ROOM_ID_PREFIX.getPrefix()+"*");
        if (keys.isEmpty()) {
            return false;
        }

        return keys.stream().anyMatch(key -> {
            try {
                Object roomNameVal = slaveTemplate.opsForHash().get(key, "roomName");
                Object stateVal = slaveTemplate.opsForHash().get(key, "state");

                if (roomNameVal == null || stateVal == null) {
                    return false;
                }

                return roomName.equals(roomNameVal.toString())
                        && (RoomState.ACTIVE.equals(stateVal) || RoomState.CREATED.equals(stateVal));
            } catch (Exception e) {
                log.error("Error occurred while checking room state in Redis for key: {}", key, e);
                return false;
            }
        });
    }

    @Override
    public void saveRoomRoutingInfo(RoomRoutingInfo roomRoutingInfo) {
        masterTemplate.opsForValue().set(ROOM_ROUTING_PREFIX.getPrefix()+roomRoutingInfo.getRoomId(), roomRoutingInfo);
    }

    @Override
    public long getInstanceRoomCount(String key) {
        return Optional.ofNullable(slaveTemplate.opsForValue().get(key))
                .map(val -> {
                    if (val instanceof String s) { // 문자열로 저장된 경우
                        return Long.parseLong(s);
                    } else if (val instanceof Number n) { // 이미 숫자 타입으로 들어온 경우
                        return n.longValue();
                    } else {
                        return 0L;
                    }
                })
                .orElse(0L);
    }

    /**
     * 인스턴스 정보 삭제
     */
    @Override
    public void delInstanceInfo(String instanceId) {
        masterTemplate.delete(ROOM_COUNT_PREFIX.getPrefix() + instanceId);
        masterTemplate.delete(INSTANCE_COOKIE_PREFIX.getPrefix() + instanceId);
        masterTemplate.delete(INSTANCE_INFO_PREFIX.getPrefix() + instanceId);
        masterTemplate.delete(INSTANCE_HEARTBEAT_PREFIX.getPrefix() + instanceId);
    }

    @Override
    public void saveInstanceCookieMapping(String currentInstanceId, String cookie) {
        masterTemplate.opsForValue().set(INSTANCE_COOKIE_PREFIX.getPrefix() + currentInstanceId, cookie);
    }

    /**
     * 모든 인스턴스의 쿠키 매핑 조회
     */
    @Override
    public Map<String, String> getAllInstanceCookies() {
        String pattern = INSTANCE_COOKIE_PREFIX.getPrefix() + "*";
        Set<String> keys = slaveTemplate.keys(pattern);

        Map<String, String> cookieMap = new HashMap<>();
        if (!CollectionUtils.isEmpty(keys)) {
            for (String key : keys) {
                String instanceId = key.replace(INSTANCE_COOKIE_PREFIX.getPrefix(), "");
                // Replication lag 보안을 위해 master 에서 읽어옴
                String cookie = (String) masterTemplate.opsForValue().get(key);
                if (cookie != null) {
                    cookieMap.put(instanceId, cookie);
                }
            }
        }
        return cookieMap;
    }

    @Override
    public void insertGoogleOauthToken(OauthRedis oauthRedis, long time) {
        String redisKey = OAUTH_PREFIX.getPrefix() + oauthRedis.getIdx();
        masterTemplate.opsForHash().put(redisKey, DataType.SOCIAL_USER.getType(), oauthRedis);
        masterTemplate.opsForHash().put(redisKey, "idx", oauthRedis.getIdx());
        masterTemplate.opsForHash().put(redisKey, "email", oauthRedis.getEmail());
        masterTemplate.opsForHash().put(redisKey, "nickname", oauthRedis.getEmail().split("@")[0]);
        masterTemplate.opsForHash().put(redisKey, "lastLoginDate", time);
    }

    @Override
    public void deleteLoginInfo(long idx) {
        masterTemplate.delete(OAUTH_PREFIX.getPrefix() + idx);
    }

    @Override
    public void insertQRSession(QRSession qrSession){
        String redisKey = QR_SESSION_PREFIX.getPrefix() + qrSession.getSessionId();
        masterTemplate.opsForValue().set(redisKey, qrSession, 5, TimeUnit.MINUTES);
    }

    @Override
    public QRSession getQRSession(String sessionId){
        String redisKey = QR_SESSION_PREFIX.getPrefix() + sessionId;
        return (QRSession) slaveTemplate.opsForValue().get(redisKey);
    }

    @Override
    public void addRoomMember(String roomId, String email) {
        masterTemplate.opsForSet().add(ROOM_MEMBERS_PREFIX.getPrefix() + roomId, email);
    }

    @Override
    public boolean isRoomMember(String roomId, String email) {
        Boolean result = slaveTemplate.opsForSet().isMember(ROOM_MEMBERS_PREFIX.getPrefix() + roomId, email);
        return Boolean.TRUE.equals(result);
    }

    @Override
    public void deleteRoomMembers(String roomId) {
        masterTemplate.delete(ROOM_MEMBERS_PREFIX.getPrefix() + roomId);
    }
}
