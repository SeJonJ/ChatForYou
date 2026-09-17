package webChat.service.chatroom.recovery.impl;

import io.github.dengliming.redismodule.redisearch.index.Document;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import webChat.model.chat.ChatType;
import webChat.model.record.RecordingPartialMarker;
import webChat.model.redis.DataType;
import webChat.model.redis.RedisIndex;
import webChat.model.redis.RoomSearchCriteria;
import webChat.model.room.ChatRoom;
import webChat.model.room.KurentoRoom;
import webChat.model.room.recovery.ChatRoomRecoveryOutVo;
import webChat.model.room.recovery.PreShutdownResult;
import webChat.model.room.recovery.RecoveryDecision;
import webChat.model.room.recovery.RecoveryReason;
import webChat.model.room.recovery.RecoveryResult;
import webChat.model.room.recovery.RecoveryStatus;
import webChat.model.room.recovery.RoomRecoveryMetadata;
import webChat.model.routing.RoomRoutingInfo;
import webChat.service.chatroom.recovery.ChatRoomRecoveryService;
import webChat.service.redis.RedisService;
import webChat.service.routing.RoutingInstanceProvider;
import webChat.service.routing.RoutingService;
import webChat.utils.StringUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ChatRoomRecoveryServiceImpl implements ChatRoomRecoveryService {

    private static final long CLAIM_LOCK_TTL_MS = 30_000L;
    private static final int CLAIM_RETRY_AFTER_MS = 500;

    // 배포 복구 후보 metadata(room:recovery:{roomId})의 TTL(초). 이 시간 안에 재입장해야 복구된다.
    // 기본값은 브라우저 자동 재연결 대기(3분)에 서버 종료 대기 시간을 더한 값이다. TTL 이 브라우저 대기보다
    // 먼저 끝나면 아직 재연결을 시도 중인 참가자가 복구 불가로 판정된다.
    @Value("${recovery.room.ttl-seconds:240}")
    private long recoveryTtlSeconds;

    private final RedisService redisService;
    private final RoutingInstanceProvider instanceProvider;
    private final RoutingService routingService;

    /**
     * 입장 요청에서 owner-unhealthy 방이 HTTP recovery 대상으로 전환 가능한지 판정한다.
     */
    @Override
    public RecoveryDecision evaluateJoinRecovery(ChatRoom chatRoom) {
        if (chatRoom == null || !ChatType.RTC.equals(chatRoom.getChatType())) {
            return RecoveryDecision.notRecoverable(RecoveryReason.NOT_RECOVERABLE);
        }

        if (StringUtil.isNullOrEmpty(chatRoom.getInstanceId()) || instanceProvider.isInstanceAlive(chatRoom.getInstanceId())) {
            return RecoveryDecision.notRecoverable(RecoveryReason.NOT_RECOVERABLE);
        }

        RoomRecoveryMetadata metadata = redisService.getRoomRecoveryMetadata(chatRoom.getRoomId());
        if (metadata == null) {
            return RecoveryDecision.notRecoverable(RecoveryReason.NOT_RECOVERABLE);
        }

        if (isExpired(metadata)) {
            redisService.deleteRoomRecoveryMetadata(chatRoom.getRoomId());
            return RecoveryDecision.notRecoverable(RecoveryReason.RECOVERY_EXPIRED);
        }

        return RecoveryDecision.recoverable();
    }

    /**
     * Redis claim lock으로 단일 인스턴스만 방 소유권을 이전하고 라우팅 쿠키를 재발급한다.
     */
    @Override
    @Transactional
    public RecoveryResult recoverRoom(ChatRoom chatRoom, HttpServletResponse response) {
        String roomId = chatRoom.getRoomId();
        String currentInstanceId = instanceProvider.getInstanceId();

        RoomRecoveryMetadata metadata = redisService.getRoomRecoveryMetadata(roomId);
        if (metadata == null) {
            return RecoveryResult.redirectDashboard(
                    ChatRoomRecoveryOutVo.redirectDashboard(roomId, RecoveryReason.NOT_RECOVERABLE)
            );
        }

        if (isExpired(metadata)) {
            redisService.deleteRoomRecoveryMetadata(roomId);
            return RecoveryResult.redirectDashboard(
                    ChatRoomRecoveryOutVo.redirectDashboard(roomId, RecoveryReason.RECOVERY_EXPIRED)
            );
        }

        if (instanceProvider.isShuttingDown()) {
            // 종료 중인 인스턴스가 방을 가져가면 곧바로 다시 주인을 잃는다. claim lock 도 잡지 않아야
            // 살아있는 인스턴스의 복구가 lock TTL 만큼 지연되지 않는다.
            return retryResult(roomId, RecoveryReason.INSTANCE_SHUTTING_DOWN, currentInstanceId, currentInstanceId);
        }

        if (!redisService.tryAcquireRoomClaimLock(roomId, currentInstanceId, CLAIM_LOCK_TTL_MS)) {
            // 다른 인스턴스가 소유권 이전 중이면 브라우저가 짧게 재시도해야 중복 owner를 피할 수 있다.
            return retryResult(roomId, RecoveryReason.CLAIM_IN_PROGRESS, currentInstanceId, null);
        }

        try {
            // lock 을 잡는 사이에 종료가 시작될 수 있어 한 번 더 확인한다.
            if (instanceProvider.isShuttingDown()) {
                return retryResult(roomId, RecoveryReason.INSTANCE_SHUTTING_DOWN, currentInstanceId, currentInstanceId);
            }

            // lock 획득 뒤 master 값을 다시 읽어 slave lag나 오래된 join 응답으로 인한 오판을 막는다.
            ChatRoom masterRoom = redisService.getChatRoomFromMaster(roomId);
            if (masterRoom == null) {
                redisService.deleteRoomRecoveryMetadata(roomId);
                return RecoveryResult.redirectDashboard(
                        ChatRoomRecoveryOutVo.redirectDashboard(roomId, RecoveryReason.ROOM_NOT_FOUND)
                );
            }

            String ownerInstanceId = masterRoom.getInstanceId();

            if (currentInstanceId.equals(ownerInstanceId) && instanceProvider.isInstanceAlive(currentInstanceId)) {
                String existingCookie = redisService.getInstanceCookieFromMaster(currentInstanceId);
                if (StringUtil.isNullOrEmpty(existingCookie)) {
                    return retryResult(roomId, RecoveryReason.CURRENT_COOKIE_UNAVAILABLE, currentInstanceId, ownerInstanceId);
                }

                routingService.setRecoveryRoutingInfo(response, roomId, existingCookie);
                return RecoveryResult.success(ChatRoomRecoveryOutVo.success(masterRoom, currentInstanceId));
            }

            if (StringUtil.isNullOrEmpty(ownerInstanceId)) {
                return RecoveryResult.redirectDashboard(
                        ChatRoomRecoveryOutVo.redirectDashboard(roomId, RecoveryReason.NOT_RECOVERABLE)
                );
            }

            if (instanceProvider.isInstanceAlive(ownerInstanceId)) {
                // 다른 참가자가 먼저 복구해 살아있는 owner 가 이미 있는 상태다. 소유권은 그대로 두고 owner 로
                // 가는 라우팅 쿠키만 내려줘야 같은 방으로 합류한다.
                String ownerCookie = redisService.getInstanceCookieFromMaster(ownerInstanceId);
                if (StringUtil.isNullOrEmpty(ownerCookie)) {
                    return retryResult(roomId, RecoveryReason.OWNER_COOKIE_UNAVAILABLE, currentInstanceId, ownerInstanceId);
                }

                routingService.setRecoveryRoutingInfo(response, roomId, ownerCookie);
                return RecoveryResult.success(ChatRoomRecoveryOutVo.success(masterRoom, ownerInstanceId));
            }

            String currentCookie = redisService.getInstanceCookieFromMaster(currentInstanceId);
            if (StringUtil.isNullOrEmpty(currentCookie)) {
                // current instance cookie 없이는 성공 Set-Cookie 계약을 지킬 수 없다. old cookie/raw instanceId fallback은 dead pod 재고정을 만든다.
                return retryResult(roomId, RecoveryReason.CURRENT_COOKIE_UNAVAILABLE, currentInstanceId, ownerInstanceId);
            }

            masterRoom.setInstanceId(currentInstanceId);

            // 새 owner 는 RecorderEndpoint 를 갖지 않으므로 중단된 녹화 상태를 정합 stopped 로 정리한다.
            // graceful cleanup 이 이미 정리했다면 isRecordingInProgress 가 false 라 marker 중복 기록을 건너뛴다.
            // 순서 불변: marker 를 먼저 기록해야 reset 으로 사라지는 파일 식별 정보를 보존한다.
            // 정리된 masterRoom 은 아래 updateRecoveredRoomRoutingAndMetadata 가 Redis 에 영속한다.
            if (masterRoom instanceof KurentoRoom recoveredRoom && recoveredRoom.isRecordingInProgress()) {
                redisService.saveRecordingPartialMarker(RecordingPartialMarker.fromRoom(recoveredRoom));
                recoveredRoom.resetRecordingState();
            }

            RoomRecoveryMetadata claimedMetadata = RoomRecoveryMetadata.builder()
                    .roomId(roomId)
                    .previousInstanceId(metadata.getPreviousInstanceId())
                    .createdAt(metadata.getCreatedAt())
                    .expiresAt(metadata.getExpiresAt())
                    .reason(metadata.getReason())
                    .status(RecoveryStatus.CLAIMED)
                    .build();
            redisService.updateRecoveredRoomRoutingAndMetadata(
                    masterRoom,
                    RoomRoutingInfo.of(roomId, currentInstanceId, currentCookie, System.currentTimeMillis()),
                    claimedMetadata,
                    recoveryTtlSeconds
            );

            instanceProvider.incrementInstanceRoomCount();
            routingService.setRecoveryRoutingInfo(response, roomId, currentCookie);

            log.info("Room recovery claimed: roomId={}, from={}, to={}",
                    roomId, metadata.getPreviousInstanceId(), currentInstanceId);

            return RecoveryResult.success(ChatRoomRecoveryOutVo.success(masterRoom, currentInstanceId));
        } finally {
            // 실패 경로에서도 compare-and-delete로 잠금을 풀어 다음 복구 시도를 막지 않는다.
            redisService.releaseRoomClaimLock(roomId, currentInstanceId);
        }
    }

    /**
     * Spring 종료 전에 현재 인스턴스가 소유한 방만 TTL 기반 복구 후보로 저장한다.
     */
    @Override
    @Transactional
    public PreShutdownResult markOwnedRoomsRecoverable() {
        String currentInstanceId = instanceProvider.getInstanceId();
        long now = System.currentTimeMillis();
        long expiresAt = now + (recoveryTtlSeconds * 1000);
        List<String> roomIds = new ArrayList<>();

        RoomSearchCriteria searchCriteria = RoomSearchCriteria.builder()
                .redisIndex(RedisIndex.CHATROOM)
                .keyword("")
                .roomStates(List.of(webChat.model.room.RoomState.ACTIVE, webChat.model.room.RoomState.CREATED))
                .build();

        for (Document document : redisService.searchRoomListByOptions(searchCriteria)) {
            Object roomIdValue = document.getFields().get("roomId");
            if (roomIdValue == null) {
                continue;
            }

            String roomId = roomIdValue.toString().replace("\"", "");
            Map<Object, Object> allChatRoomData = redisService.getAllChatRoomData(roomId);
            Object chatRoomValue = allChatRoomData.get(DataType.CHATROOM.getType());
            if (!(chatRoomValue instanceof ChatRoom chatRoom)) {
                continue;
            }

            if (!currentInstanceId.equals(chatRoom.getInstanceId())) {
                continue;
            }

            if (!ChatType.RTC.equals(chatRoom.getChatType())) {
                continue;
            }

            // 소유 인스턴스가 살아 있는 마지막 시점에 남겨야 종료 후 owner-unhealthy 방과 실제 삭제 방을 구분할 수 있다.
            redisService.saveRoomRecoveryMetadata(RoomRecoveryMetadata.builder()
                    .roomId(roomId)
                    .previousInstanceId(currentInstanceId)
                    .createdAt(now)
                    .expiresAt(expiresAt)
                    .reason("PRE_SHUTDOWN")
                    .status(RecoveryStatus.CANDIDATE)
                    .build(), recoveryTtlSeconds);
            roomIds.add(roomId);
        }

        log.info("Marked owned rooms recoverable before shutdown: instanceId={}, roomCount={}",
                currentInstanceId, roomIds.size());

        return PreShutdownResult.builder()
                .instanceId(currentInstanceId)
                .markedRoomCount(roomIds.size())
                .roomIds(roomIds)
                .build();
    }

    /**
     * 복구 대기 여부를 판정한다. 만료 metadata 는 삭제하지 않고 대기 아님으로만 응답한다.
     */
    @Override
    public boolean hasPendingRecovery(String roomId) {
        RoomRecoveryMetadata metadata = redisService.getRoomRecoveryMetadata(roomId);
        return metadata != null && !isExpired(metadata);
    }

    /**
     * 재시도 응답을 만들고 사유를 기록한다.
     * 재시도 응답은 브라우저에서만 보이고 서버 로그에는 남지 않아 운영에서 추적할 수 없었다.
     */
    private RecoveryResult retryResult(String roomId, RecoveryReason reason,
                                       String currentInstanceId, String ownerInstanceId) {
        log.info("Room recovery retry: roomId={}, reason={}, instanceId={}, ownerInstanceId={}",
                roomId, reason, currentInstanceId, ownerInstanceId);
        return RecoveryResult.redirectRecover(
                ChatRoomRecoveryOutVo.retry(roomId, reason, CLAIM_RETRY_AFTER_MS)
        );
    }

    private boolean isExpired(RoomRecoveryMetadata metadata) {
        return metadata.getExpiresAt() <= System.currentTimeMillis()
                || RecoveryStatus.EXPIRED.equals(metadata.getStatus());
    }
}
