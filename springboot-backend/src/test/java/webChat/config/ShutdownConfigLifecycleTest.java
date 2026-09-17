package webChat.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kurento.client.KurentoClient;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.event.ContextClosedEvent;
import webChat.model.chat.ChatType;
import webChat.model.redis.DataType;
import webChat.model.room.KurentoRoom;
import webChat.model.room.recovery.PreShutdownResult;
import webChat.service.chatroom.recovery.ChatRoomRecoveryService;
import webChat.service.kurento.KurentoRoomManager;
import webChat.service.redis.RedisService;
import webChat.service.routing.InstanceProvider;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 종료 정리 실행 경로와 실패 격리 단위 테스트.
 *
 * 정리 도중 한 방이 실패하거나 복구 후보 기록이 실패해도, 클러스터에 종료를 알리고 인스턴스 정보를
 * 지우는 마지막 단계는 반드시 수행되어야 다른 인스턴스가 이미 없는 인스턴스로 라우팅하지 않는다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShutdownConfigLifecycleTest {

    private static final String CURRENT_INSTANCE = "instance-A";

    @Mock
    private KurentoRoomManager kurentoRoomManager;
    @Mock
    private KurentoClient kurentoClient;
    @Mock
    private RedisService redisService;
    @Mock
    private InstanceProvider instanceProvider;
    @Mock
    private ChatRoomRecoveryService chatRoomRecoveryService;

    @InjectMocks
    private ShutdownConfig shutdownConfig;

    @BeforeEach
    void setUp() {
        given(instanceProvider.getInstanceId()).willReturn(CURRENT_INSTANCE);
        markedRooms(List.of());
    }

    private void markedRooms(List<String> roomIds) {
        given(chatRoomRecoveryService.markOwnedRoomsRecoverable()).willReturn(PreShutdownResult.builder()
                .instanceId(CURRENT_INSTANCE)
                .markedRoomCount(roomIds.size())
                .roomIds(roomIds)
                .build());
    }

    @Test
    @DisplayName("방 하나의 정리가 실패해도 나머지 방을 정리하고 종료 알림·Kurento 해제를 수행한다")
    void cleanup_방하나실패해도_나머지정리와_종료알림수행() {
        // given
        KurentoRoom brokenRoom = room("room-broken");
        KurentoRoom healthyRoom = room("room-ok");
        markedRooms(List.of("room-broken", "room-ok"));
        given(redisService.getChatRoomFromMaster(anyString())).willAnswer(call -> room(call.getArgument(0)));
        given(redisService.getAllChatRoomData("room-broken"))
                .willReturn(Map.of(DataType.CHATROOM.getType(), brokenRoom));
        given(redisService.getAllChatRoomData("room-ok"))
                .willReturn(Map.of(DataType.CHATROOM.getType(), healthyRoom));
        willThrow(new IllegalStateException("redis down")).given(redisService).updateChatRoom(brokenRoom);

        // when
        shutdownConfig.onApplicationEvent(new ContextClosedEvent(new TestApplicationContext()));

        // then
        verify(redisService).updateChatRoom(healthyRoom);
        verify(kurentoRoomManager).deleteKurentoRoom(healthyRoom);
        verify(instanceProvider).shutdown();
        verify(kurentoClient).destroy();
    }

    @Test
    @DisplayName("복구 후보 기록이 실패하면 방 상태를 되돌리지 않고 종료 알림·Kurento 해제만 수행한다")
    void cleanup_복구후보기록실패시_방정리는건너뛴다() {
        // given
        willThrow(new IllegalStateException("redis down"))
                .given(chatRoomRecoveryService).markOwnedRoomsRecoverable();

        // when
        shutdownConfig.onApplicationEvent(new ContextClosedEvent(new TestApplicationContext()));

        // then
        verify(redisService, never()).getAllChatRoomData(anyString());
        verify(redisService, never()).updateChatRoom(any());
        verify(instanceProvider).shutdown();
        verify(kurentoClient).destroy();
    }

    @Test
    @DisplayName("종료 알림 실패에도 Kurento 자원을 해제한다")
    void cleanup_종료알림실패해도_Kurento해제수행() {
        // given
        willThrow(new IllegalStateException("kafka down")).given(instanceProvider).shutdown();

        // when
        shutdownConfig.onApplicationEvent(new ContextClosedEvent(new TestApplicationContext()));

        // then
        verify(kurentoClient).destroy();
    }

    @Test
    @DisplayName("방 정리를 모두 끝낸 뒤에 종료를 알린다")
    void cleanup_방정리후_종료알림순서() {
        // given
        KurentoRoom ownedRoom = room("room-A");
        markedRooms(List.of("room-A"));
        given(redisService.getChatRoomFromMaster("room-A")).willReturn(room("room-A"));
        given(redisService.getAllChatRoomData("room-A"))
                .willReturn(Map.of(DataType.CHATROOM.getType(), ownedRoom));

        // when
        shutdownConfig.onApplicationEvent(new ContextClosedEvent(new TestApplicationContext()));

        // then — 먼저 알리면 다른 인스턴스가 방을 가져간 뒤 이 인스턴스의 저장이 소유자를 덮어쓴다
        InOrder ordered = inOrder(redisService, kurentoRoomManager, instanceProvider);
        ordered.verify(redisService).updateChatRoom(ownedRoom);
        ordered.verify(kurentoRoomManager).deleteKurentoRoom(ownedRoom);
        ordered.verify(instanceProvider).shutdown();
    }

    @Test
    @DisplayName("복구 후보로 기록되지 않은 방은 초기화하지 않는다")
    void cleanup_기록되지않은방은_초기화하지않는다() {
        // given — 기록된 방이 없는데 다른 경로로 관측된 방이 있는 상황
        KurentoRoom unmarkedRoom = room("room-A");
        markedRooms(List.of());
        given(redisService.getChatRoomFromMaster("room-A")).willReturn(room("room-A"));
        given(redisService.getAllChatRoomData("room-A"))
                .willReturn(Map.of(DataType.CHATROOM.getType(), unmarkedRoom));

        // when
        shutdownConfig.onApplicationEvent(new ContextClosedEvent(new TestApplicationContext()));

        // then — metadata 없는 방을 CREATED 로 되돌리면 재입장 시 복구 불가로 삭제된다
        verify(redisService, never()).getAllChatRoomData(anyString());
        verify(redisService, never()).updateChatRoom(any());
        verify(kurentoRoomManager, never()).deleteKurentoRoom(any());
    }

    @Test
    @DisplayName("기록 이후 소유자가 바뀐 방은 master 확인으로 건너뛴다")
    void cleanup_소유자가바뀐방은_건너뛴다() {
        // given
        KurentoRoom staleRoom = room("room-A");
        markedRooms(List.of("room-A"));
        given(redisService.getAllChatRoomData("room-A"))
                .willReturn(Map.of(DataType.CHATROOM.getType(), staleRoom));
        given(redisService.getChatRoomFromMaster("room-A")).willReturn(
                new KurentoRoom("room-A", "room", "creator", null, false, 1, 8, ChatType.MSG, "instance-B"));

        // when
        shutdownConfig.onApplicationEvent(new ContextClosedEvent(new TestApplicationContext()));

        // then
        verify(redisService, never()).updateChatRoom(any());
        verify(kurentoRoomManager, never()).deleteKurentoRoom(any());
    }

    @Test
    @DisplayName("정리는 컨텍스트 종료 이벤트에서만 실행되고 별도 JVM 종료 훅을 등록하지 않는다")
    void cleanup_별도JVM종료훅을_등록하지않는다() {
        // when
        boolean hasInitializer = Arrays.stream(ShutdownConfig.class.getDeclaredMethods())
                .anyMatch(ShutdownConfigLifecycleTest::isPostConstruct);

        // then — 컨텍스트 종료와 병렬로 도는 훅은 Redis·Kafka 빈 파괴와 경쟁한다
        assertThat(hasInitializer).isFalse();
    }

    private static boolean isPostConstruct(Method method) {
        return Arrays.stream(method.getAnnotations())
                .anyMatch(annotation -> annotation.annotationType().getName().endsWith("PostConstruct"));
    }

    private KurentoRoom room(String roomId) {
        return new KurentoRoom(roomId, "room", "creator", null, false, 1, 8, ChatType.MSG, CURRENT_INSTANCE);
    }

    /**
     * ContextClosedEvent 생성을 위한 최소 ApplicationContext 스텁.
     */
    private static class TestApplicationContext
            extends org.springframework.context.support.StaticApplicationContext {
    }
}
