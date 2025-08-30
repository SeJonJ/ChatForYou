package webChat.service.routing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import webChat.model.kafka.KafkaEvent;
import webChat.model.routing.RoomRoutingInfo;
import webChat.service.redis.RedisService;
import webChat.utils.StringUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;

@Service
@Slf4j
public class RoutingInstanceProvider extends InstanceProvider {
    @Autowired
    private RedisService redisService;

    private final String ROOM_COUNT_PREFIX = "server-room-count:";
    private final int CANDIDATE_SERVER_COUNT = 3;

    public RoutingInstanceProvider(KafkaTemplate<String, KafkaEvent> kafkaTemplate) {
        super(kafkaTemplate);
    }

    // 방 생성 시 현재 서버의 방 개수 증가
    public void incrementInstanceRoomCount() {
        String key = ROOM_COUNT_PREFIX + this.getInstanceId();
        Long newCount = redisService.increment(key, 1);
        log.info("Server {} room count increased to: {}", this.getInstanceId(), newCount);
    }

    // 방 삭제 시 현재 서버의 방 개수 감소
    public void decrementInstanceRoomCount() {
        String key = ROOM_COUNT_PREFIX + this.getInstanceId();
        Long newCount = redisService.decrement(key, 1);
        log.info("Server {} room count decreased to: {}", this.getInstanceId(), newCount);
    }

    // 서버의 현재 활성 방 개수 조회
    private long getRoomCount(String instanceId) {
        String key = ROOM_COUNT_PREFIX + instanceId;
        return redisService.getInstanceRoomCount(key);
    }

    @Override
    public String getServerForRoom(String roomId) {
        if (getHashRing().isEmpty()) {
            log.warn("No servers available for room: {}", roomId);
            return null;
        }

        RoomRoutingInfo roomRoutingInfo = redisService.getRoomRoutingInfoByRoomId(roomId);
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

    private String selectServerWithLeastRooms(List<String> candidates) {
        String bestServer = null;
        long minRoomCount = Integer.MAX_VALUE;

        for (String candidate : candidates) {
            String key = ROOM_COUNT_PREFIX + candidate;
            long roomCount = redisService.getInstanceRoomCount(key);
            log.info("Server {} has {} active rooms", candidate, roomCount);

            if (roomCount < minRoomCount) {
                minRoomCount = roomCount;
                bestServer = candidate;
            }
        }

        log.info("Selected server {} with {} active rooms", bestServer, minRoomCount);
        return bestServer != null ? bestServer : this.getInstanceId();
    }
}
