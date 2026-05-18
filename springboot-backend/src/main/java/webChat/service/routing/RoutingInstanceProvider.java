package webChat.service.routing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import webChat.model.kafka.KafkaEvent;
import webChat.model.redis.RedisKeyPrefix;
import webChat.model.routing.RoomRoutingInfo;
import webChat.service.redis.RedisService;
import webChat.utils.StringUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;

@Service
@Slf4j
public class RoutingInstanceProvider extends InstanceProvider {
    private final int CANDIDATE_SERVER_COUNT = 3;

    public RoutingInstanceProvider(KafkaTemplate<String, KafkaEvent> kafkaTemplate,
                                   RedisService redisService,
                                   @Lazy CookieCheckEvent cookieCheckEvent) {
        super(kafkaTemplate, redisService, cookieCheckEvent);
    }

    /**
     * 방 생성 시 현재 서버의 방 개수를 증가시킨다.
     */
    public void incrementInstanceRoomCount() {
        String key = RedisKeyPrefix.ROOM_COUNT_PREFIX.getPrefix() + this.getInstanceId();
        Long newCount = getRedisService().increment(key, 1);
        log.debug("Server {} room count increased to: {}", this.getInstanceId(), newCount);
    }

    /**
     * 방 삭제 시 현재 서버의 방 개수를 감소시킨다.
     */
    public void decrementInstanceRoomCount() {
        String key = RedisKeyPrefix.ROOM_COUNT_PREFIX.getPrefix() + this.getInstanceId();
        Long newCount = getRedisService().decrement(key, 1);
        log.debug("Server {} room count decreased to: {}", this.getInstanceId(), newCount);
    }

    /**
     * 특정 서버의 현재 활성 방 개수를 조회한다.
     *
     * @param instanceId 인스턴스 ID
     * @return 활성 방 개수
     */
    private long getRoomCount(String instanceId) {
        String key = RedisKeyPrefix.ROOM_COUNT_PREFIX.getPrefix() + instanceId;
        return getRedisService().getInstanceRoomCount(key);
    }

    /**
     * 일관 해시 후보군 중 현재 방 수가 가장 적은 서버를 선택한다.
     *
     * @param roomId 채팅방 ID
     * @param roomRoutingInfo 기존 라우팅 정보
     * @return 선택된 인스턴스 ID
     */
    @Override
    public String getServerForRoom(String roomId, RoomRoutingInfo roomRoutingInfo) {
        if (getHashRing().isEmpty()) {
            log.warn("No servers available for room: {}", roomId);
            return null;
        }

        if(roomRoutingInfo != null && !StringUtil.isNullOrEmpty(roomRoutingInfo.getInstanceId())) {
            return roomRoutingInfo.getInstanceId();
        }

        // 1. Consistent hashing 으로 후보 서버들 선택 (상위 3개)
        List<String> candidateServers = getTopCandidateServers(roomId, CANDIDATE_SERVER_COUNT);

        // 2. 각 후보 서버의 활성 방 개수를 기준으로 최적 선택
        String bestServer = selectServerWithLeastRooms(candidateServers);

        log.info("===== Room {} mapped to server {} (room count based selection) =====", roomId, bestServer);
        return bestServer;
    }


    /**
     * 일관 해시 기준 상위 후보 서버 목록을 구한다.
     *
     * @param roomId 채팅방 ID
     * @param count 최대 후보 개수
     * @return 후보 서버 목록
     */
    private List<String> getTopCandidateServers(String roomId, int count) {
        long hash = computeHash(roomId);
        List<String> candidates = new ArrayList<>();

        SortedMap<Long, String> tailMap = this.getHashRing().tailMap(hash);

        // 해시링에서 가까운 서버들부터 선택
        for (String server : tailMap.values()) {
            if (!candidates.contains(server)) {
                candidates.add(server);
                if (candidates.size() >= count) break;
            }
        }

        // 부족하면 처음부터 추가
        if (candidates.size() < count) {
            for (String server : this.getHashRing().values()) {
                if (!candidates.contains(server)) {
                    candidates.add(server);
                    if (candidates.size() >= count) break;
                }
            }
        }

        return candidates;
    }

    /**
     * 후보 서버 중 활성 방 수가 가장 적은 서버를 선택한다.
     *
     * @param candidates 후보 서버 목록
     * @return 선택된 서버 ID
     */
    private String selectServerWithLeastRooms(List<String> candidates) {
        String bestServer = null;
        long minRoomCount = Integer.MAX_VALUE;

        for (String candidate : candidates) {
            String key = RedisKeyPrefix.ROOM_COUNT_PREFIX.getPrefix() + candidate;
            long roomCount = getRedisService().getInstanceRoomCount(key);
            log.info("=== Server {} has {} active rooms", candidate, roomCount);

            if (roomCount < minRoomCount) {
                minRoomCount = roomCount;
                bestServer = candidate;
            }
        }

        log.info("=== Selected server {} with {} active rooms", bestServer, minRoomCount);
        return bestServer != null ? bestServer : this.getInstanceId();
    }
}
