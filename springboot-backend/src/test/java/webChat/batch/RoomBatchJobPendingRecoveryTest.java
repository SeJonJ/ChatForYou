package webChat.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import webChat.model.chat.ChatType;
import webChat.model.room.KurentoRoom;
import webChat.repository.DailyInfoRepository;
import webChat.service.analysis.AnalysisService;
import webChat.service.chatroom.ChatRoomService;
import webChat.service.chatroom.recovery.ChatRoomRecoveryService;
import webChat.service.redis.RedisService;
import webChat.service.routing.RoutingInstanceProvider;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 방 삭제 배치의 복구 대기 방 보호 단위 테스트.
 *
 * 서버 종료 정리는 방을 CREATED 로 되돌리는데, 이 상태는 배치의 삭제 후보라서 배포와 배치 실행이
 * 겹치면 아직 재연결 중인 방과 녹화 파일이 함께 사라진다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RoomBatchJobPendingRecoveryTest {

    private static final String INSTANCE_ID = "instance-a";
    private static final String PENDING_ROOM_ID = "room-pending";
    private static final String DELETABLE_ROOM_ID = "room-deletable";

    @Mock
    private AnalysisService analysisService;
    @Mock
    private ChatRoomService chatRoomService;
    @Mock
    private DailyInfoRepository dailyInfoRepository;
    @Mock
    private RedisService redisService;
    @Mock
    private ChatRoomRecoveryService chatRoomRecoveryService;
    @Mock
    private RoutingInstanceProvider instanceProvider;

    @InjectMocks
    private RoomBatchJob roomBatchJob;

    @Test
    @DisplayName("복구를 기다리는 방은 삭제하지 않고 나머지 방만 삭제한다")
    void checkDeleteRoom_복구대기방은_삭제하지않는다() {
        // given
        KurentoRoom pendingRoom = room(PENDING_ROOM_ID);
        KurentoRoom deletableRoom = room(DELETABLE_ROOM_ID);
        given(redisService.getChatRoomListForDelete(anyInt())).willReturn(List.of(pendingRoom, deletableRoom));
        given(chatRoomRecoveryService.hasPendingRecovery(PENDING_ROOM_ID)).willReturn(true);
        given(chatRoomRecoveryService.hasPendingRecovery(DELETABLE_ROOM_ID)).willReturn(false);
        given(instanceProvider.getInstanceId()).willReturn(INSTANCE_ID);

        // when
        roomBatchJob.checkDeleteRoom();

        // then
        verify(chatRoomService, never()).delChatRoom(pendingRoom);
        verify(chatRoomService).delChatRoom(deletableRoom);
    }

    private KurentoRoom room(String roomId) {
        return new KurentoRoom(roomId, "room", "creator", null, false, 0, 8, ChatType.RTC, INSTANCE_ID);
    }
}
