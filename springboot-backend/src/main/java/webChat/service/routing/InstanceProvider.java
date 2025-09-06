package webChat.service.routing;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.coyote.BadRequestException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy; // 이것만 추가!
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import webChat.model.kafka.*;
import webChat.model.redis.DataType;
import webChat.model.redis.RedisKeyPrefix;
import webChat.service.redis.RedisService;
import webChat.utils.StringUtil;

import javax.annotation.PreDestroy;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

@Component
@Getter
@Slf4j
@RequiredArgsConstructor
public abstract class InstanceProvider {
    // 서버당 가상 노드 수
    private final int DEFAULT_VIRTUAL_NODES = 150;
    // Thread-safe 해시 링
    private final ConcurrentSkipListMap<Long, String> hashRing = new ConcurrentSkipListMap<>();
    // 고품질 해시 함수
    private final HashFunction hashFunction = Hashing.murmur3_128();
    // 메모리 효율적 캐싱
    private volatile Map<String, List<Long>> serverHashCache = new ConcurrentHashMap<>();
    // 현재 활성 서버들
    private final Set<String> activeServers = ConcurrentHashMap.newKeySet();

    private final KafkaTemplate<String, KafkaEvent> kafkaTemplate;
    private final RedisService redisService;
    private ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();

    @Autowired
    @Lazy
    private CookieCheckEvent cookieCheckEvent;

    private String instanceId;

    public void initInstanceId() {
        // 1. instanceId 생성
        String podName = System.getenv("POD_NAME");
        if(podName == null){
            podName = "chatforyou-backend";
        }
        long startTime = ManagementFactory.getRuntimeMXBean().getStartTime();
        String shortHash = Integer.toHexString(podName.hashCode()).substring(0, 6);

        // 실제 파드이름이 생성되는 방식
        // podName-startTime-shortHash 혹은 chatforyou-startTime-shortHash
        // ex) chat-server-abc123-1692547200000-a1b2c3
        this.instanceId = podName + "-" + startTime + "-" + shortHash;
    }

    public void initInstanceProviderEvent() {
        log.info("Initializing Consistent Hash Router with {} virtual nodes per server", DEFAULT_VIRTUAL_NODES);

        // 2. 자신을 해시 링에 먼저 추가 (부팅 시 즉시 사용 가능하도록)
        addServer(instanceId);

        // 3. Discovery 요청 발행 (기존 서버들에게 자신의 존재를 알림)
        publishServerEvent(ServerEvent.SERVER_DISCOVERY_REQUEST, instanceId);

        // 4. Kafka를 통해 다른 서버들에게 자신의 시작을 알림
        // 이때 약간의 지연 후 정식 시작 알림 (다른 서버들의 응답을 받을 시간 확보)
        CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS)
                .execute(() -> publishServerEvent(ServerEvent.SERVER_STARTED, instanceId));

        // 5. heartbeat 시작
        startHeartbeat();

        log.info("===== Instance {} initialized and announced to cluster =====", instanceId);
    }

    /**
     * 서버 이벤트를 Kafka에 발행
     */
    private void publishServerEvent(ServerEvent eventType, String instanceId) {
        try {
            KafkaServerEvent event = KafkaServerEvent.of(eventType, instanceId, System.currentTimeMillis());

            kafkaTemplate.send(KafkaTopic.SERVER_LIFECYCLE_EVENTS, KafkaSendKey.EVENT_TYPE, event);
            log.info("===== Published {} event for server {} =====", eventType, instanceId);

        } catch (Exception e) {
            log.error("Failed to publish server event: {} for {}", eventType, instanceId, e);
        }
    }

    /**
     * 쿠키 응답 이벤트 발행
     */
    public void publishCookieResponse(String requesterId, String cookie) {
        try {
            KafkaServerEvent event = KafkaServerEvent.createCookieResponse(instanceId, requesterId, cookie);

            kafkaTemplate.send(KafkaTopic.SERVER_LIFECYCLE_EVENTS, KafkaSendKey.EVENT_TYPE, event);
            log.info("쿠키 응답 이벤트 발행 완료: {} -> {}", instanceId, requesterId);

        } catch (Exception e) {
            log.error("쿠키 응답 이벤트 발행 실패: {}", e.getMessage());
        }
    }

    /**
     * 서버 종료 시 이벤트 처리
     */
    @PreDestroy
    public synchronized void shutdown() {
        // 종료 시 다른 서버들에게 알림
        publishServerEvent(ServerEvent.SERVER_STOPPED, instanceId);

        // 현재 서버의 roomcount 초기화
        redisService.delInstanceInfo(instanceId);

        // Heartbeat 정리
        if (heartbeatScheduler != null && !heartbeatScheduler.isShutdown()) {
            heartbeatScheduler.shutdown();
        }

        log.info("Instance {} shutdown announced to cluster", instanceId);
    }

    /**
     * Kafka에서 서버 이벤트 수신
     */
    @KafkaListener(
            topics = KafkaTopic.SERVER_LIFECYCLE_EVENTS,
            containerFactory = "kafkaServerEventListenerContainerFactory",
            groupId = "server-lifecycle-group-#{T(java.util.UUID).randomUUID().toString().split(\"-\")[0]}" // 인스턴스별 고유 groupId
    )
    public void handleServerEvent(ConsumerRecord<String, KafkaEvent> record) {
        try {
            KafkaServerEvent event = KafkaEvent.of(record.value());
            if (StringUtil.isNullOrEmpty(event.getInstanceId()) || event.getEventType() == null) {
                log.warn("=== Received event from server with null or empty instance ID: {}", event);
                return;
            }

            // 1시간 이전 이벤트는 스킵
            if (isEventTooOld(event.getPublishedAt())) {
                log.debug("Skipping old event from {}", event.getInstanceId());
                return;
            }

            // 자신이 발행한 이벤트는 무시
            if (instanceId.equals(event.getInstanceId())) {
                return;
            }

            switch (event.getEventType()) {
                case SERVER_DISCOVERY_REQUEST:
                    // 새로운 서버가 discovery 요청 → 해당 서버를 등록하고 자신의 존재를 알림
                    if (!this.isHealthy(event.getInstanceId())) {
                        addServer(event.getInstanceId());
                        log.info("===== New server discovered via discovery request: {}", event.getInstanceId());
                    }
                    publishServerEvent(ServerEvent.SERVER_DISCOVERY_RESPONSE, instanceId);
                    break;

                case SERVER_DISCOVERY_RESPONSE:
                    // 기존 서버의 응답 → 해당 서버를 등록
                    if (!this.isHealthy(event.getInstanceId())) {
                        addServer(event.getInstanceId());
                        log.info("===== Added existing server via discovery response: {}", event.getInstanceId());
                    }
                    break;

                case SERVER_STARTED:
                    if (!this.isHealthy(event.getInstanceId())) {
                        addServer(event.getInstanceId());
                        log.info("===== Added server via SERVER_STARTED event: {}", event.getInstanceId());
                    }
                    break;

                case SERVER_STOPPED:
                    removeServer(event.getInstanceId());
                    log.info("===== Removed server via SERVER_STOPPED event: {}", event.getInstanceId());
                    break;

                case SERVER_COOKIE_REQUEST:
                    handleCookieRequest(event.getInstanceId());
                    break;

                case SERVER_COOKIE_RESPONSE:
                    handleCookieResponse(event);
                    break;

                case SERVER_COOKIE_DISCOVERED:
                    handleCookieDiscovered(event);
                    break;

                default:
                    log.warn("Unknown server event type: {}", event.getEventType());
            }

        } catch (Exception e) {
            log.error("Failed to handle server event from Kafka", e);
        }
    }

    /**
     * 쿠키 요청 이벤트 처리
     */
    private void handleCookieRequest(String requesterId) throws BadRequestException {
        // 내가 쿠키를 가지고 있다면 응답
        String myCookie = redisService.getRedisDataByDataType(RedisKeyPrefix.INSTANCE_COOKIE_PREFIX.getPrefix() + instanceId, DataType.INSTANCE_COOKIE, String.class);
        if (myCookie != null) {
            publishCookieResponse(requesterId, myCookie);
            log.info("쿠키 요청에 응답: {} -> {} (쿠키: {})", instanceId, requesterId, myCookie);
        } else {
            log.debug("쿠키 요청 수신했지만 내 쿠키가 없음: requester={}", requesterId);
        }
    }

    /**
     * 쿠키 응답 이벤트 처리
     */
    public void handleCookieResponse(KafkaServerEvent event) {
        try {
            // CookieCheckEvent에 위임
            if (cookieCheckEvent != null) {
                cookieCheckEvent.handleCookieResponse(event);
            } else {
                log.warn("CookieCheckEvent가 초기화되지 않음");
            }
        } catch (Exception e) {
            log.error("쿠키 응답 이벤트 처리 실패: {}", e.getMessage());
        }
    }

    /**
     * 쿠키 발견 이벤트 처리
     */
    public void handleCookieDiscovered(KafkaServerEvent event) {
        try {
            // CookieCheckEvent에 위임
            if (cookieCheckEvent != null) {
                cookieCheckEvent.handleCookieDiscovered(event);
            } else {
                log.warn("CookieCheckEvent가 초기화되지 않음");
            }
        } catch (Exception e) {
            log.error("쿠키 발견 이벤트 처리 실패: {}", e.getMessage());
        }
    }

    /**
     * 이벤트가 너무 오래된 것인지 확인
     */
    private boolean isEventTooOld(long eventTimestamp) {
        long oneHourAgo = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1);
        return eventTimestamp < oneHourAgo;
    }

    /**
     * 방 ID에 대한 최적의 서버를 반환
     *
     * @param roomId 방 ID
     * @return 최적 서버 ID, 서버가 없으면 null
     */
    public String getServerForRoom(String roomId) throws BadRequestException {
        if (hashRing.isEmpty()) {
            log.warn("No servers available for room: {}", roomId);
            return null;
        }

        long hash = computeHash(roomId);
        SortedMap<Long, String> tailMap = hashRing.tailMap(hash);
        long targetHash = tailMap.isEmpty() ? hashRing.firstKey() : tailMap.firstKey();

        String selectedServer = hashRing.get(targetHash);
        log.info("===== Room {} mapped to server {}", roomId, selectedServer);

        return selectedServer;
    }

    /**
     * 새 서버를 해시 링에 추가
     *
     * @param instanceId 서버 ID
     */
    public synchronized void addServer(String instanceId) {
        if (activeServers.contains(instanceId)) {
            log.warn("Server {} already exists", instanceId);
            return;
        }

        List<Long> hashes = computeVirtualNodeHashes(instanceId);
        for (Long hash : hashes) {
            hashRing.put(hash, instanceId);
        }

        activeServers.add(instanceId);
        serverHashCache.put(instanceId, hashes);

        log.info("===== Added server: {} with {} virtual nodes", instanceId, hashes.size());
    }

    /**
     * 서버를 해시 링에서 제거
     *
     * @param instanceId 서버 ID
     */
    public synchronized void removeServer(String instanceId) {
        if (!activeServers.contains(instanceId)) {
            log.warn("Server {} does not exist", instanceId);
            return;
        }

        List<Long> hashes = serverHashCache.get(instanceId);
        if (hashes != null) {
            for (Long hash : hashes) {
                hashRing.remove(hash);
            }
        }

        activeServers.remove(instanceId);
        serverHashCache.remove(instanceId);

        log.info("Removed server: {} with {} virtual nodes", instanceId, hashes != null ? hashes.size() : 0);
    }

    /**
     * 현재 활성 서버 목록 반환
     *
     * @return 활성 서버 집합 (복사본)
     */
    public Set<String> getActiveServers() {
        return new HashSet<>(activeServers);
    }

    /**
     * 전체 가상 노드 수 반환
     *
     * @return 가상 노드 총 개수
     */
    public int getTotalVirtualNodes() {
        return hashRing.size();
    }

    /**
     * 서버별 가상 노드 분산 현황 반환
     *
     * @return 서버별 가상 노드 개수 맵
     */
    public Map<String, Integer> getServerDistribution() {
        Map<String, Integer> distribution = new HashMap<>();
        for (String server : activeServers) {
            List<Long> hashes = serverHashCache.get(server);
            distribution.put(server, hashes != null ? hashes.size() : 0);
        }
        return distribution;
    }

    /**
     * 문자열에 대한 해시 값 계산 (Guava MurmurHash3 사용)
     *
     * @param input 입력 문자열
     * @return 64비트 해시 값
     */
    protected long computeHash(String input) {
        return hashFunction.hashString(input, StandardCharsets.UTF_8).asLong();
    }

    /**
     * 서버의 가상 노드들에 대한 해시 값 목록 계산
     *
     * @param instanceId 서버 ID
     * @return 가상 노드 해시 값 목록
     */
    private List<Long> computeVirtualNodeHashes(String instanceId) {
        List<Long> hashes = new ArrayList<>(DEFAULT_VIRTUAL_NODES);
        for (int i = 0; i < DEFAULT_VIRTUAL_NODES; i++) {
            String virtualNodeKey = instanceId + "-vnode-" + i;
            hashes.add(computeHash(virtualNodeKey));
        }
        return hashes;
    }

    /**
     * TODO 추후 failoverService 에서 상세하게 구현 후 사용(auto scale 대응)
     * 해시 링 상태 확인 (디버깅용)
     * @return 해시 링이 정상 상태인지 여부
     */
    public boolean isHealthy() {
        return !hashRing.isEmpty() && !activeServers.isEmpty() &&
                hashRing.size() == activeServers.size() * DEFAULT_VIRTUAL_NODES;
    }

    public boolean isHealthy(String instanceId) {
        return this.getActiveServers().contains(instanceId);
    }

    /**
     * Heartbeat 시작
     */
    private void startHeartbeat() {
        sendHeartbeat();
        checkInactiveServers();
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            sendHeartbeat();
            checkInactiveServers();
        }, 30, 30, TimeUnit.SECONDS); // 30초마다 실행

        log.info("Heartbeat 시작됨: {}", instanceId);
    }

    /**
     * Heartbeat 전송
     */
    private void sendHeartbeat() {
        String key = RedisKeyPrefix.INSTANCE_HEARTBEAT_PREFIX.getPrefix() + instanceId;
        redisService.setObject(key, System.currentTimeMillis(), 90, TimeUnit.SECONDS); // 90초 TTL
    }

    /**
     * 비활성 서버 체크
     */
    private void checkInactiveServers() {
        Set<String> serversToRemove = new HashSet<>();

        for (String serverId : getActiveServers()) {
            if (!serverId.equals(instanceId)) { // 자신은 제외
                String key = RedisKeyPrefix.INSTANCE_HEARTBEAT_PREFIX.getPrefix() + serverId;
                String lastHeartbeat = (String) redisService.getObject(key, Object.class);

                if (lastHeartbeat == null) {
                    // Redis TTL로 인해 키가 없어짐 -> 서버 비활성
                    serversToRemove.add(serverId);
                    log.warn("비활성 서버 감지됨: {}", serverId);
                }
            }
        }

        // 비활성 서버들 제거
        for (String serverId : serversToRemove) {
            removeServer(serverId);
        }

        log.info("===== {} inactive servers removed =====", serversToRemove.size());
        log.info("===== Active servers: {} =====", getActiveServers().size());
    }
}