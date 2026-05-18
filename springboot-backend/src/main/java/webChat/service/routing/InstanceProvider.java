package webChat.service.routing;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.annotation.Lazy;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import webChat.model.kafka.*;
import webChat.model.redis.DataType;
import webChat.model.redis.RedisKeyPrefix;
import webChat.model.routing.RoomRoutingInfo;
import webChat.service.redis.RedisService;
import webChat.utils.StringUtil;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

@Component
@Getter
@Slf4j
@RequiredArgsConstructor
public abstract class InstanceProvider {
    public static final String SERVER_LIFECYCLE_LISTENER_ID = "serverLifecycleListener";
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

    @Lazy
    private final CookieCheckEvent cookieCheckEvent;

    private String instanceId;
    private boolean isShutdown = false;
    private volatile boolean localRoutingStateInitialized = false;
    private volatile boolean clusterPresenceAnnounced = false;

    /**
     * Kafka listener 보다 먼저 instance identity 를 준비한다.
     */
    @PostConstruct
    protected void initializeInstanceIdentity() {
        initInstanceId();
    }

    /**
     * 현재 애플리케이션 프로세스를 클러스터에서 식별할 instanceId 를 한 번만 생성한다.
     * 이후 Kafka lifecycle 이벤트 비교, Redis heartbeat key, room routing 로그의 기준 키로 재사용된다.
     */
    public void initInstanceId() {
        if (!StringUtil.isNullOrEmpty(instanceId)) {
            return;
        }

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
        log.info("===== My Instance ID [{}] :: now initialized and announced to cluster =====", instanceId);
    }

    /**
     * 부팅 초기에 외부 파드와 통신하기 전에 "내 로컬 상태"를 먼저 준비한다.
     * 자기 자신을 hash ring 에 등록하고 heartbeat 를 시작한다.
     * discovery/start 보다 먼저 실행하는 이유는 startup 초기에 자기 자신의 routing 상태를 먼저 안정화하기 위해서다.
     */
    public synchronized void initializeLocalRoutingState() {
        if (localRoutingStateInitialized) {
            return;
        }

        log.debug("Initializing local routing state with {} virtual nodes per server", DEFAULT_VIRTUAL_NODES);

        addServer(instanceId);
        startHeartbeat();
        localRoutingStateInitialized = true;
    }

    /**
     * 로컬 상태가 준비된 뒤에만 외부 파드에게 자신의 기동 사실을 알린다.
     * 먼저 discovery 요청을 보내고, 잠시 뒤 started 이벤트를 보내는 클러스터 합류 절차를 담당한다.
     */
    public synchronized void announceClusterPresence() {
        if (clusterPresenceAnnounced || isShutdown) {
            return;
        }

        publishServerEvent(ServerEvent.SERVER_DISCOVERY_REQUEST, instanceId);
        clusterPresenceAnnounced = true;

        // discovery 응답이 먼저 모일 시간을 조금 주고 started 이벤트를 알린다.
        CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS)
                .execute(() -> {
                    if (isShutdown || !clusterPresenceAnnounced) {
                        log.debug("Skipping delayed SERVER_STARTED publish for shutdown/stale instance {}", instanceId);
                        return;
                    }
                    publishServerEvent(ServerEvent.SERVER_STARTED, instanceId);
                });
    }

    /**
     * 서버 lifecycle 이벤트를 Kafka 에 발행한다.
     * 누가 클러스터에 들어오고 나가는지, 누가 쿠키 응답을 대신 전달하는지 공유하는 공통 채널이다.
     */
    private void publishServerEvent(ServerEvent eventType, String instanceId) {
        try {
            KafkaServerEvent event = KafkaServerEvent.of(eventType, instanceId, System.currentTimeMillis());

            kafkaTemplate.send(KafkaTopic.SERVER_LIFECYCLE_EVENTS, KafkaSendKey.EVENT_TYPE, event);
            log.debug("===== Published {} event for server {} =====", eventType, instanceId);

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
            log.debug("쿠키 응답 이벤트 발행 완료: {} -> {}", instanceId, requesterId);

        } catch (Exception e) {
            log.error("쿠키 응답 이벤트 발행 실패: {}", e.getMessage());
        }
    }

    /**
     * 서버 종료 시 이벤트 처리
     */
    public synchronized void shutdown() {
        // 종료 시 다른 서버들에게 알림
        if (!StringUtil.isNullOrEmpty(instanceId)) {
            publishServerEvent(ServerEvent.SERVER_STOPPED, instanceId);
            // 현재 서버의 roomcount 초기화
            redisService.delInstanceInfo(instanceId);
        } else {
            log.warn("Instance shutdown without initialized instanceId - skip SERVER_STOPPED publish");
        }

        isShutdown = true;

        // Heartbeat 정리
        if (heartbeatScheduler != null && !heartbeatScheduler.isShutdown()) {
            heartbeatScheduler.shutdown();
        }

        log.debug("Instance {} shutdown announced to cluster", instanceId);
    }

    /**
     * Kafka 에서 전달된 서버 lifecycle 이벤트를 처리한다.
     * membership 이벤트는 hash ring 에 반영하고, cookie 관련 이벤트는 CookieCheckEvent 로 위임한다.
     * startup race 를 줄이기 위해 진입 직후 instanceId 초기화 여부를 먼저 확인한다.
     */
    @KafkaListener(
            id = SERVER_LIFECYCLE_LISTENER_ID,
            topics = KafkaTopic.SERVER_LIFECYCLE_EVENTS,
            containerFactory = "kafkaServerEventListenerContainerFactory",
            groupId = "server-lifecycle-group-#{T(java.util.UUID).randomUUID().toString().split(\"-\")[0]}" // 인스턴스별 고유 groupId
    )
    public void handleServerEvent(ConsumerRecord<String, KafkaEvent> record) {
        try {
            if (StringUtil.isNullOrEmpty(instanceId)) {
                log.warn("Lifecycle event received before instanceId initialization");
                return;
            }

            KafkaServerEvent event = (KafkaServerEvent) record.value();
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
                        log.debug("===== New server discovered via discovery request: {}", event.getInstanceId());
                    }
                    publishServerEvent(ServerEvent.SERVER_DISCOVERY_RESPONSE, instanceId);
                    break;

                case SERVER_DISCOVERY_RESPONSE:
                    // 기존 서버의 응답 → 해당 서버를 등록
                    if (!this.isHealthy(event.getInstanceId())) {
                        addServer(event.getInstanceId());
                        log.debug("===== Added existing server via discovery response: {}", event.getInstanceId());
                    }
                    break;

                case SERVER_STARTED:
                    if (!this.isHealthy(event.getInstanceId())) {
                        addServer(event.getInstanceId());
                        log.debug("===== Added server via SERVER_STARTED event: {}", event.getInstanceId());
                    }
                    break;

                case SERVER_STOPPED:
                    removeServer(event.getInstanceId());
                    log.debug("===== Removed server via SERVER_STOPPED event: {}", event.getInstanceId());
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
    private void handleCookieRequest(String requesterId) {
        // 내가 쿠키를 가지고 있다면 응답
        String myCookie = redisService.getRedisDataByDataType(RedisKeyPrefix.INSTANCE_COOKIE_PREFIX.getPrefix() + instanceId, DataType.INSTANCE_COOKIE, String.class);
        if (myCookie != null) {
            publishCookieResponse(requesterId, myCookie);
            log.debug("쿠키 요청에 응답: {} -> {} (쿠키: {})", instanceId, requesterId, myCookie);
        } else {
            log.debug("쿠키 요청 수신했지만 내 쿠키가 없음: requester={}", requesterId);
        }
    }

    /**
     * 쿠키 응답 이벤트 처리
     */
    public void handleCookieResponse(KafkaServerEvent event) {
        try {
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
    public String getServerForRoom(String roomId, RoomRoutingInfo roomRoutingInfo) {
        if (hashRing.isEmpty()) {
            log.warn("No servers available for room: {}", roomId);
            return null;
        }

        long hash = computeHash(roomId);
        SortedMap<Long, String> tailMap = hashRing.tailMap(hash);
        long targetHash = tailMap.isEmpty() ? hashRing.firstKey() : tailMap.firstKey();

        String selectedServer = hashRing.get(targetHash);
        log.debug("===== Room {} mapped to server {}", roomId, selectedServer);

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

        log.debug("===== Added server: {} with {} virtual nodes", instanceId, hashes.size());
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

        log.debug("Removed server: {} with {} virtual nodes", instanceId, hashes != null ? hashes.size() : 0);
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
     * 현재 해시 링과 활성 서버 집합만 기준으로 라우팅 상태를 빠르게 점검한다.
     * 추후 auto scale 시점에는 failoverService 기준의 상세 상태 점검으로 확장 필요
     *
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
            try {
                sendHeartbeat();
                checkInactiveServers();
            } catch (Exception e) {
                log.error("Heartbeat task failed", e);
            }
        }, 30, 30, TimeUnit.SECONDS); // 30초마다 실행

        log.info("Heartbeat started :: {}", instanceId);
    }

    /**
     * Heartbeat 전송
     */
    private void sendHeartbeat() {
        String key = RedisKeyPrefix.INSTANCE_HEARTBEAT_PREFIX.getPrefix() + instanceId;
        redisService.setObject(key, System.currentTimeMillis(), 90, TimeUnit.SECONDS); // 90초 TTL
        log.debug("===== Sent heartbeat to server {} =====", instanceId);
    }

    /**
     * 비활성 서버 체크
     */
    private void checkInactiveServers() {
        Set<String> serversToRemove = new HashSet<>();

        for (String serverId : getActiveServers()) {
            if (!serverId.equals(instanceId)) { // 자신은 제외
                String key = RedisKeyPrefix.INSTANCE_HEARTBEAT_PREFIX.getPrefix() + serverId;
                Long lastHeartbeat = (Long) redisService.getObject(key, Object.class);

                if (lastHeartbeat == null) {
                    // Redis TTL로 인해 키가 없어짐 -> 서버 비활성
                    serversToRemove.add(serverId);
                    log.debug("비활성 서버 감지됨: {}", serverId);
                }
            }
        }

        // 비활성 서버들 제거
        for (String serverId : serversToRemove) {
            redisService.delInstanceInfo(serverId);
            removeServer(serverId);
        }

        log.debug("===== {} inactive servers removed =====", serversToRemove.size());
        log.debug("===== Active servers: {} =====", getActiveServers().size());
    }
}
