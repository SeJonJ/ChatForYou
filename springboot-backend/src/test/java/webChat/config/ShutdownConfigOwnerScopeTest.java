package webChat.config;

import io.github.dengliming.redismodule.redisearch.index.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kurento.client.KurentoClient;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.event.ContextClosedEvent;
import webChat.model.chat.ChatType;
import webChat.model.redis.DataType;
import webChat.model.room.KurentoRoom;
import webChat.service.kurento.KurentoRoomManager;
import webChat.service.redis.RedisService;
import webChat.service.routing.InstanceProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * ShutdownConfig.cleanup() owner-scope 가드 단위 테스트.
 *
 * 다중 인스턴스 무중단 rolling 배포에서, 내려가는 인스턴스가 타 인스턴스 소유 방의
 * userCount 를 0 으로 덮어쓰거나(authoritative count 파괴) deleteKurentoRoom 으로 자원을
 * 해제하지 않고, 자기 소유 방만 정리하는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShutdownConfigOwnerScopeTest {

    private static final String CURRENT_INSTANCE = "instance-A";
    private static final String OTHER_INSTANCE = "instance-B";

    @Mock
    private KurentoRoomManager kurentoRoomManager;
    @Mock
    private KurentoClient kurentoClient;
    @Mock
    private RedisService redisService;
    @Mock
    private InstanceProvider instanceProvider;

    @InjectMocks
    private ShutdownConfig shutdownConfig;

    @BeforeEach
    void setUp() {
        given(instanceProvider.getInstanceId()).willReturn(CURRENT_INSTANCE);
    }

    private Document roomDocument(String roomId) {
        return new Document(roomId, 1.0, Map.of("roomId", roomId));
    }

    private KurentoRoom room(String roomId, String instanceId, int userCount) {
        // baseline userCount 가 살아있는(예: 1명) 방을 가정한다.
        return new KurentoRoom(roomId, "room", "creator", null, false, userCount, 8, ChatType.MSG, instanceId);
    }

    @Test
    @DisplayName("타 인스턴스(B) 소유 방은 userCount 변경·updateChatRoom·deleteKurentoRoom 을 호출하지 않는다")
    void cleanup_타인스턴스소유방은_정리하지않는다() {
        // given: B 소유 방(userCount=1) 한 개
        KurentoRoom bRoom = room("room-B", OTHER_INSTANCE, 1);
        given(redisService.searchRoomListByOptions(any())).willReturn(List.of(roomDocument("room-B")));
        given(redisService.getAllChatRoomData("room-B"))
                .willReturn(Map.of(DataType.CHATROOM.getType(), bRoom));

        // when
        shutdownConfig.onApplicationEvent(new ContextClosedEvent(new TestApplicationContext()));

        // then: B 소유 방은 손대지 않는다 — authoritative count 보존, 자원 해제 미시도
        assertThat(bRoom.getUserCount())
                .as("타 인스턴스 소유 방의 userCount 는 0 으로 덮어써지지 않아야 한다")
                .isEqualTo(1);
        verify(redisService, never()).updateChatRoom(bRoom);
        verify(kurentoRoomManager, never()).deleteKurentoRoom(bRoom);
    }

    @Test
    @DisplayName("자기 인스턴스(A) 소유 방은 userCount 0 초기화·updateChatRoom·deleteKurentoRoom 으로 정상 정리한다")
    void cleanup_자기인스턴스소유방은_정상정리한다() {
        // given: A 소유 방(userCount=2) 한 개
        KurentoRoom aRoom = room("room-A", CURRENT_INSTANCE, 2);
        given(redisService.searchRoomListByOptions(any())).willReturn(List.of(roomDocument("room-A")));
        given(redisService.getAllChatRoomData("room-A"))
                .willReturn(Map.of(DataType.CHATROOM.getType(), aRoom));

        // when
        shutdownConfig.onApplicationEvent(new ContextClosedEvent(new TestApplicationContext()));

        // then: A 소유 방은 정상 정리
        assertThat(aRoom.getUserCount()).isZero();
        verify(redisService).updateChatRoom(aRoom);
        verify(kurentoRoomManager).deleteKurentoRoom(aRoom);
    }

    @Test
    @DisplayName("A·B 혼재 시 A 소유 방만 정리하고 B 소유 방은 건너뛴다")
    void cleanup_혼재시_자기소유방만정리한다() {
        // given: A 소유 방, B 소유 방 혼재
        KurentoRoom aRoom = room("room-A", CURRENT_INSTANCE, 1);
        KurentoRoom bRoom = room("room-B", OTHER_INSTANCE, 1);
        given(redisService.searchRoomListByOptions(any()))
                .willReturn(List.of(roomDocument("room-A"), roomDocument("room-B")));
        given(redisService.getAllChatRoomData("room-A"))
                .willReturn(Map.of(DataType.CHATROOM.getType(), aRoom));
        given(redisService.getAllChatRoomData("room-B"))
                .willReturn(Map.of(DataType.CHATROOM.getType(), bRoom));

        // when
        shutdownConfig.onApplicationEvent(new ContextClosedEvent(new TestApplicationContext()));

        // then: A 만 정리, B 는 보존
        assertThat(aRoom.getUserCount()).isZero();
        verify(redisService).updateChatRoom(aRoom);
        verify(kurentoRoomManager).deleteKurentoRoom(aRoom);

        assertThat(bRoom.getUserCount()).isEqualTo(1);
        verify(redisService, never()).updateChatRoom(bRoom);
        verify(kurentoRoomManager, never()).deleteKurentoRoom(bRoom);
    }

    /**
     * ContextClosedEvent 생성을 위한 최소 ApplicationContext 스텁.
     * 이벤트 소스만 필요하므로 동작 구현은 두지 않는다.
     */
    private static class TestApplicationContext
            extends org.springframework.context.support.StaticApplicationContext {
    }
}
