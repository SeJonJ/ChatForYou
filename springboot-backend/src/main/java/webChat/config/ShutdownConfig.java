package webChat.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kurento.client.KurentoClient;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
import webChat.model.record.RecordingPartialMarker;
import webChat.model.redis.DataType;
import webChat.model.room.ChatRoom;
import webChat.model.room.KurentoRoom;
import webChat.model.room.RoomState;
import webChat.model.room.recovery.PreShutdownResult;
import webChat.service.chatroom.recovery.ChatRoomRecoveryService;
import webChat.service.kurento.KurentoRoomManager;
import webChat.service.redis.RedisService;
import webChat.service.routing.InstanceProvider;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * 서버 종료 시 데이터를 정리하기 위해서 사용
 */
@Configuration
@Slf4j
@RequiredArgsConstructor
public class ShutdownConfig implements ApplicationListener<ContextClosedEvent> {

    private final KurentoRoomManager kurentoRoomManager;
    private final KurentoClient kurentoClient;
    private final RedisService redisService;
    private final InstanceProvider instanceProvider;
    private final ChatRoomRecoveryService chatRoomRecoveryService;
    private final AtomicBoolean cleanupStarted = new AtomicBoolean(false);

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        cleanup();
    }

    /**
     * kurento 연결 객체 및 방 사용자 session 객체 제거 및 close
     * 단 redis 에서 방 직접 삭제는 X
     *
     * 방별 정리(userCount 0 초기화·상태 변경·deleteKurentoRoom)는 이 인스턴스가 소유한 방에만 수행한다.
     * 다중 인스턴스 무중단 rolling 배포에서 내려가는 인스턴스가 타 인스턴스 소유 방의 userCount 를 0 으로
     * 덮어쓰면 syncUserCount 가 만든 authoritative count 가 파괴되고, 타 인스턴스에만 존재하는 Kurento
     * 자원을 해제하려는 무의미한 동작이 발생하므로 instanceId 가 다른 방은 건너뛴다.
     */
    private void cleanup() {
        if (!cleanupStarted.compareAndSet(false, true)) {
            log.info("Shutdown cleanup already started. Skip duplicate cleanup invocation.");
            return;
        }

        String currentInstanceId = instanceProvider.getInstanceId();
        try {
            instanceProvider.beginShutdown();
            List<String> recoverableRoomIds = markOwnedRoomsRecoverable();
            if (recoverableRoomIds != null) {
                resetOwnedRooms(recoverableRoomIds, currentInstanceId);
            }
        } finally {
            // 종료 알림과 인스턴스 정보 삭제가 빠지면 다른 인스턴스는 이 인스턴스를 최대 heartbeat TTL 동안
            // 살아있다고 보고 이미 없는 인스턴스로 사용자를 계속 라우팅한다. 방 정리가 실패해도 반드시 수행한다.
            announceShutdown();
            releaseKurentoClient();
        }
    }

    /**
     * 복구 후보로 기록한 방 목록을 반환하고, 실패하면 null 을 반환한다.
     * 기록에 실패한 채 방 상태를 되돌리면 사용자가 조용히 복구 불가 상태가 된다.
     */
    private List<String> markOwnedRoomsRecoverable() {
        try {
            PreShutdownResult result = chatRoomRecoveryService.markOwnedRoomsRecoverable();
            log.info("Pre-shutdown room recovery metadata marked: instanceId={}, roomCount={}",
                    result.getInstanceId(), result.getMarkedRoomCount());
            return result.getRoomIds() != null ? result.getRoomIds() : List.of();
        } catch (Exception e) {
            log.error("Pre-shutdown room recovery metadata marking failed. Skip owned room reset.", e);
            return null;
        }
    }

    /**
     * 복구 후보로 기록된 방만 초기화한다. 방 하나의 실패가 나머지 방 정리를 막지 않도록 방 단위로 격리한다.
     * 목록을 다시 조회하면 기록 시점과 집합이 달라져, metadata 없는 방을 초기화해 복구 불가 상태로 만들 수 있다.
     */
    private void resetOwnedRooms(List<String> roomIds, String currentInstanceId) {
        for (String roomId : roomIds) {
            try {
                resetOwnedRoom(roomId, currentInstanceId);
            } catch (Exception e) {
                log.error("Shutdown cleanup failed for room {}", roomId, e);
            }
        }
    }

    /**
     * 방 하나를 복구 가능한 상태로 되돌린다.
     */
    private void resetOwnedRoom(String roomId, String currentInstanceId) {
        Map<Object, Object> allChatRoomData = redisService.getAllChatRoomData(roomId);
        if (allChatRoomData.isEmpty() || allChatRoomData.get(DataType.CHATROOM.getType()) == null) {
            return;
        }
        KurentoRoom kurentoRoom = (KurentoRoom) allChatRoomData.get("chatroom");

        // owner-scope 가드 — 이 인스턴스 소유 방만 정리하여 타 인스턴스 authoritative count/자원 오염 차단.
        // 복제 지연으로 소유자가 바뀐 사실을 놓칠 수 있어 master 값으로 다시 확인한다.
        ChatRoom masterRoom = redisService.getChatRoomFromMaster(roomId);
        String ownerInstanceId = masterRoom != null ? masterRoom.getInstanceId() : kurentoRoom.getInstanceId();
        if (!Objects.equals(currentInstanceId, ownerInstanceId)) {
            log.debug("Skip cleanup for room {} owned by another instance {} (current {})",
                    roomId, ownerInstanceId, currentInstanceId);
            return;
        }

        // owner 방을 Redis 에 다시 쓰기 전, 진행 중이던 녹화만 정합 stopped 로 정리한다.
        // updateChatRoom 이 isRecordingInProgress=true 를 그대로 영속하면 복구 후 stale 시그널이 남는다.
        // 정상 완료된 녹화 방(isRecordingInProgress=false, 파일은 보존)까지 reset 하면 hasRecordedOnce 가
        // 풀려 동일 방 재녹화 차단이 사라지므로, marker 기록과 reset 을 같은 in-progress 게이트 안에 함께 둔다.
        // 순서 불변: marker 를 먼저 기록해야 reset 으로 사라지는 파일 식별 정보를 보존한다.
        if (kurentoRoom.isRecordingInProgress()) {
            redisService.saveRecordingPartialMarker(RecordingPartialMarker.fromRoom(kurentoRoom));
            kurentoRoom.resetRecordingState();
        }

        // redis 에서 해당 방의 유저수 및 방 상태 변경
        kurentoRoom.setUserCount(0); // 유저 count 초기화
        kurentoRoom.setRoomState(RoomState.CREATED); // 방 상태 초기화
        redisService.updateChatRoom(kurentoRoom);
        log.info("KurentoRoom {} data updated", kurentoRoom.getRoomId());

        kurentoRoomManager.deleteKurentoRoom(kurentoRoom);
    }

    /**
     * 클러스터에 종료 사실을 알리고 인스턴스 정보를 제거한다.
     */
    private void announceShutdown() {
        try {
            if (!instanceProvider.isShutdown()) {
                instanceProvider.shutdown();
            }
        } catch (Exception e) {
            log.error("Instance shutdown announcement failed", e);
        }
    }

    private void releaseKurentoClient() {
        try {
            kurentoClient.destroy();
            log.info("All Kurento Data destroyed - Clean up completed");
        } catch (Exception e) {
            log.error("Kurento client release failed", e);
        }
    }
}
