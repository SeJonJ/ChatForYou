package webChat.service.chatroom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import webChat.model.room.KurentoRoom;
import webChat.security.jwt.JwtRoomProvider;
import webChat.service.analysis.AnalysisService;
import webChat.service.file.impl.MinioFileService;
import webChat.service.file.impl.RecordingFileService;
import webChat.service.kafka.ChatKafkaProducer;
import webChat.service.kurento.KurentoRoomManager;
import webChat.service.redis.RedisService;
import webChat.service.routing.RoutingInstanceProvider;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * ChatRoomService.delChatRoom(KurentoRoom) 멤버십 정리 검증.
 *
 * FR-4: 방 영구 삭제 시 deleteRoomMembers → deleteAllChatRoomData 순서로 호출.
 * ledger 누수 방지를 위해 명시 DEL 이 deleteAllChatRoomData(SCAN) 보다 먼저 실행되어야 한다.
 */
@ExtendWith(MockitoExtension.class)
class ChatRoomServiceDelChatRoomTest {

    private static final String ROOM_ID = "room-delete-001";

    @Mock private RedisService redisService;
    @Mock private JwtRoomProvider jwtRoomProvider;
    @Mock private KurentoRoomManager kurentoRoomManager;
    @Mock private RoutingInstanceProvider instanceProvider;
    @Mock private ChatKafkaProducer chatKafkaProducer;
    @Mock private SseService sseService;
    @Mock private MinioFileService minioFileService;
    @Mock private RecordingFileService recordingFileService;
    @Mock private AnalysisService analysisService;

    @InjectMocks
    private ChatRoomService chatRoomService;

    @Test
    @DisplayName("delChatRoom(KurentoRoom) 은 deleteRoomMembers 를 deleteAllChatRoomData 보다 먼저 호출한다 (FR-4)")
    void delChatRoom_멤버십정리() {
        // given
        KurentoRoom room = mock(KurentoRoom.class);
        given(room.getRoomId()).willReturn(ROOM_ID);
        given(redisService.deleteAllChatRoomData(ROOM_ID)).willReturn(true);

        // when
        chatRoomService.delChatRoom(room);

        // then — 순서 검증: deleteRoomMembers(명시 DEL) 가 deleteAllChatRoomData(SCAN) 보다 먼저
        InOrder inOrder = inOrder(redisService);
        inOrder.verify(redisService).deleteRoomMembers(ROOM_ID);
        inOrder.verify(redisService).deleteAllChatRoomData(ROOM_ID);
    }

    @Test
    @DisplayName("delChatRoom(KurentoRoom) 은 deleteRoomMembers 를 반드시 1회 호출한다 (FR-4 회귀)")
    void delChatRoom_deleteRoomMembers_1회호출() {
        // given
        KurentoRoom room = mock(KurentoRoom.class);
        given(room.getRoomId()).willReturn(ROOM_ID);
        given(redisService.deleteAllChatRoomData(ROOM_ID)).willReturn(true);

        // when
        chatRoomService.delChatRoom(room);

        // then
        verify(redisService, times(1)).deleteRoomMembers(ROOM_ID);
    }
}
