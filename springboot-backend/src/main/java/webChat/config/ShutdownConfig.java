package webChat.config;

import com.google.common.collect.Lists;
import io.github.dengliming.redismodule.redisearch.index.Document;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kurento.client.KurentoClient;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
import webChat.model.redis.DataType;
import webChat.model.redis.RedisIndex;
import webChat.model.redis.RoomSearchCriteria;
import webChat.model.room.KurentoRoom;
import webChat.model.room.RoomState;
import webChat.service.kurento.KurentoRoomManager;
import webChat.service.redis.RedisService;
import webChat.service.routing.InstanceProvider;

import java.util.List;
import java.util.Map;
import java.util.Objects;


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
    private final List<RoomState> ALL_ROOM_STATES = Lists.newArrayList(RoomState.ACTIVE, RoomState.CREATED, RoomState.INACTIVE);
    private final InstanceProvider instanceProvider;

    @PostConstruct
    public void init() {
        // JVM Shutdown Hook도 함께 등록 (이중 보장)
        Runtime.getRuntime().addShutdownHook(new Thread(this::cleanup));
    }

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
        String currentInstanceId = instanceProvider.getInstanceId();
        RoomSearchCriteria searchCriteria = RoomSearchCriteria.builder()
                .redisIndex(RedisIndex.CHATROOM)
                .keyword("")
                .roomStates(ALL_ROOM_STATES)
                .build();
        for (Document document : redisService.searchRoomListByOptions(searchCriteria)) {
            if (document.getFields().get("roomId") == null) {
                continue;
            }
            String roomId = document.getFields().get("roomId").toString().replace("\"", "");
            Map<Object, Object> allChatRoomData = redisService.getAllChatRoomData(roomId);
            if (allChatRoomData.isEmpty() || allChatRoomData.get(DataType.CHATROOM.getType()) == null) {
                continue;
            }
            KurentoRoom kurentoRoom = (KurentoRoom) allChatRoomData.get("chatroom");

            // owner-scope 가드 — 이 인스턴스 소유 방만 정리하여 타 인스턴스 authoritative count/자원 오염 차단
            if (!Objects.equals(currentInstanceId, kurentoRoom.getInstanceId())) {
                log.debug("Skip cleanup for room {} owned by another instance {} (current {})",
                        kurentoRoom.getRoomId(), kurentoRoom.getInstanceId(), currentInstanceId);
                continue;
            }

            // redis 에서 해당 방의 유저수 및 방 상태 변경
            kurentoRoom.setUserCount(0); // 유저 count 초기화
            kurentoRoom.setRoomState(RoomState.CREATED); // 방 상태 초기화
            redisService.updateChatRoom(kurentoRoom);
            log.info("KurentoRoom {} data updated", kurentoRoom.getRoomId());

            kurentoRoomManager.deleteKurentoRoom(kurentoRoom);
        }

        if (!instanceProvider.isShutdown()) {
            instanceProvider.shutdown();
        }

        kurentoClient.destroy();
        // 재배포 시 필요한 정리 작업
        log.info("All Kurento Data destroyed - Clean up completed");
    }
}
