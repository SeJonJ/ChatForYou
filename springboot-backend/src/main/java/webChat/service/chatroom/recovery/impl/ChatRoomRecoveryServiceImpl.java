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
    @Value("${recovery.room.ttl-seconds:180}")
    private long recoveryTtlSeconds;

    // 중단 부분 녹화 마커(room:recording:partial:{roomId})의 TTL(초).
    @Value("${recording.partial.marker.ttl-seconds:21600}")
    private long partialMarkerTtlSeconds;

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

        if (StringUtil.isNullOrEmpty(chatRoom.getInstanceId()) || instanceProvider.isHealthy(chatRoom.getInstanceId())) {
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

        if (!redisService.tryAcquireRoomClaimLock(roomId, currentInstanceId, CLAIM_LOCK_TTL_MS)) {
            // 다른 인스턴스가 소유권 이전 중이면 브라우저가 짧게 재시도해야 중복 owner를 피할 수 있다.
            return RecoveryResult.redirectRecover(
                    ChatRoomRecoveryOutVo.retry(roomId, RecoveryReason.CLAIM_IN_PROGRESS, CLAIM_RETRY_AFTER_MS)
            );
        }

        try {
            // lock 획득 뒤 master 값을 다시 읽어 slave lag나 오래된 join 응답으로 인한 오판을 막는다.
            ChatRoom masterRoom = redisService.getChatRoomFromMaster(roomId);
            if (masterRoom == null) {
                redisService.deleteRoomRecoveryMetadata(roomId);
                return RecoveryResult.redirectDashboard(
                        ChatRoomRecoveryOutVo.redirectDashboard(roomId, RecoveryReason.ROOM_NOT_FOUND)
                );
            }

            if (currentInstanceId.equals(masterRoom.getInstanceId()) && instanceProvider.isHealthy(currentInstanceId)) {
                String existingCookie = redisService.getInstanceCookieFromMaster(currentInstanceId);
                if (StringUtil.isNullOrEmpty(existingCookie)) {
                    return RecoveryResult.redirectRecover(
                            ChatRoomRecoveryOutVo.retry(roomId, RecoveryReason.CURRENT_COOKIE_UNAVAILABLE, CLAIM_RETRY_AFTER_MS)
                    );
                }

                routingService.setRecoveryRoutingInfo(response, roomId, existingCookie);
                return RecoveryResult.success(ChatRoomRecoveryOutVo.success(masterRoom, currentInstanceId));
            }

            if (StringUtil.isNullOrEmpty(masterRoom.getInstanceId()) || instanceProvider.isHealthy(masterRoom.getInstanceId())) {
                return RecoveryResult.redirectDashboard(
                        ChatRoomRecoveryOutVo.redirectDashboard(roomId, RecoveryReason.NOT_RECOVERABLE)
                );
            }

            String currentCookie = redisService.getInstanceCookieFromMaster(currentInstanceId);
            if (StringUtil.isNullOrEmpty(currentCookie)) {
                // current instance cookie 없이는 성공 Set-Cookie 계약을 지킬 수 없다. old cookie/raw instanceId fallback은 dead pod 재고정을 만든다.
                return RecoveryResult.redirectRecover(
                        ChatRoomRecoveryOutVo.retry(roomId, RecoveryReason.CURRENT_COOKIE_UNAVAILABLE, CLAIM_RETRY_AFTER_MS)
                );
            }

            masterRoom.setInstanceId(currentInstanceId);

            // 새 owner 는 RecorderEndpoint 를 갖지 않으므로 중단된 녹화 상태를 정합 stopped 로 정리한다.
            // graceful cleanup 이 이미 정리했다면 isRecordingInProgress 가 false 라 marker 중복 기록을 건너뛴다.
            // 순서 불변: marker 를 먼저 기록해야 reset 으로 사라지는 파일 식별 정보를 보존한다.
            // 정리된 masterRoom 은 아래 updateRecoveredRoomRoutingAndMetadata 가 Redis 에 영속한다.
            if (masterRoom instanceof KurentoRoom recoveredRoom && recoveredRoom.isRecordingInProgress()) {
                redisService.saveRecordingPartialMarker(
                        RecordingPartialMarker.fromRoom(recoveredRoom), partialMarkerTtlSeconds);
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

    private boolean isExpired(RoomRecoveryMetadata metadata) {
        return metadata.getExpiresAt() <= System.currentTimeMillis()
                || RecoveryStatus.EXPIRED.equals(metadata.getStatus());
    }
}
