package webChat.service.chatroom.recovery;

import io.github.dengliming.redismodule.redisearch.index.Document;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import webChat.model.chat.ChatType;
import webChat.model.redis.DataType;
import webChat.model.room.ChatRoom;
import webChat.model.room.recovery.PreShutdownResult;
import webChat.model.room.recovery.RecoveryDecision;
import webChat.model.room.recovery.RecoveryReason;
import webChat.model.room.recovery.RecoveryResult;
import webChat.model.room.recovery.RecoveryStatus;
import webChat.model.room.recovery.RoomRecoveryMetadata;
import webChat.model.routing.RoomRoutingInfo;
import webChat.service.chatroom.recovery.impl.ChatRoomRecoveryServiceImpl;
import webChat.service.redis.RedisService;
import webChat.service.routing.RoutingInstanceProvider;
import webChat.service.routing.RoutingService;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatRoomRecoveryServiceImplTest {

    private static final String ROOM_ID = "room-1";
    private static final String OLD_INSTANCE = "instance-old";
    private static final String NEW_INSTANCE = "instance-new";
    private static final String CURRENT_COOKIE = "srv|cookie-new";

    @Mock
    private RedisService redisService;

    @Mock
    private RoutingInstanceProvider instanceProvider;

    @Mock
    private RoutingService routingService;

    @Mock
    private HttpServletResponse response;

    @InjectMocks
    private ChatRoomRecoveryServiceImpl sut;

    @Test
    @DisplayName("evaluateJoinRecovery_whenOwnerUnhealthyAndMetadataExists_returnsRecoverable")
    void evaluateJoinRecovery_whenOwnerUnhealthyAndMetadataExists_returnsRecoverable() {
        // given
        ChatRoom chatRoom = rtcRoom(OLD_INSTANCE);
        given(instanceProvider.isHealthy(OLD_INSTANCE)).willReturn(false);
        given(redisService.getRoomRecoveryMetadata(ROOM_ID)).willReturn(validMetadata());

        // when
        RecoveryDecision result = sut.evaluateJoinRecovery(chatRoom);

        // then
        assertThat(result.isRecoverable()).isTrue();
        assertThat(result.getReason()).isEqualTo(RecoveryReason.RECOVERABLE);
    }

    @Test
    @DisplayName("evaluateJoinRecovery_whenMetadataExpired_deletesMetadataAndReturnsExpired")
    void evaluateJoinRecovery_whenMetadataExpired_deletesMetadataAndReturnsExpired() {
        // given
        ChatRoom chatRoom = rtcRoom(OLD_INSTANCE);
        given(instanceProvider.isHealthy(OLD_INSTANCE)).willReturn(false);
        given(redisService.getRoomRecoveryMetadata(ROOM_ID)).willReturn(expiredMetadata());

        // when
        RecoveryDecision result = sut.evaluateJoinRecovery(chatRoom);

        // then
        assertThat(result.isRecoverable()).isFalse();
        assertThat(result.getReason()).isEqualTo(RecoveryReason.RECOVERY_EXPIRED);
        verify(redisService).deleteRoomRecoveryMetadata(ROOM_ID);
    }

    @Test
    @DisplayName("evaluateJoinRecovery_whenMetadataMissing_returnsNotRecoverable")
    void evaluateJoinRecovery_whenMetadataMissing_returnsNotRecoverable() {
        // given
        ChatRoom chatRoom = rtcRoom(OLD_INSTANCE);
        given(instanceProvider.isHealthy(OLD_INSTANCE)).willReturn(false);
        given(redisService.getRoomRecoveryMetadata(ROOM_ID)).willReturn(null);

        // when
        RecoveryDecision result = sut.evaluateJoinRecovery(chatRoom);

        // then
        assertThat(result.isRecoverable()).isFalse();
        assertThat(result.getReason()).isEqualTo(RecoveryReason.NOT_RECOVERABLE);
    }

    @Test
    @DisplayName("recoverRoom_whenClaimLockBusy_returnsRedirectRecover")
    void recoverRoom_whenClaimLockBusy_returnsRedirectRecover() {
        // given
        ChatRoom chatRoom = rtcRoom(OLD_INSTANCE);
        given(instanceProvider.getInstanceId()).willReturn(NEW_INSTANCE);
        given(redisService.getRoomRecoveryMetadata(ROOM_ID)).willReturn(validMetadata());
        given(redisService.tryAcquireRoomClaimLock(eq(ROOM_ID), eq(NEW_INSTANCE), anyLong())).willReturn(false);

        // when
        RecoveryResult result = sut.recoverRoom(chatRoom, response);

        // then
        assertThat(result.getResult().name()).isEqualTo("REDIRECT_RECOVER");
        assertThat(result.getData().getReason()).isEqualTo(RecoveryReason.CLAIM_IN_PROGRESS.name());
        assertThat(result.getData().getRetryAfterMs()).isEqualTo(500);
        verify(redisService, never()).releaseRoomClaimLock(anyString(), anyString());
        verify(routingService, never()).setRecoveryRoutingInfo(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("recoverRoom_whenRecoverySucceeds_updatesOwnerRoutingAndReturnsSuccess")
    void recoverRoom_whenRecoverySucceeds_updatesOwnerRoutingAndReturnsSuccess() {
        // given
        ChatRoom requestRoom = rtcRoom(OLD_INSTANCE);
        ChatRoom masterRoom = rtcRoom(OLD_INSTANCE);
        RoomRecoveryMetadata metadata = validMetadata();

        given(instanceProvider.getInstanceId()).willReturn(NEW_INSTANCE);
        given(redisService.getRoomRecoveryMetadata(ROOM_ID)).willReturn(metadata);
        given(redisService.tryAcquireRoomClaimLock(eq(ROOM_ID), eq(NEW_INSTANCE), anyLong())).willReturn(true);
        given(redisService.getChatRoomFromMaster(ROOM_ID)).willReturn(masterRoom);
        given(instanceProvider.isHealthy(OLD_INSTANCE)).willReturn(false);
        given(redisService.getInstanceCookieFromMaster(NEW_INSTANCE)).willReturn(CURRENT_COOKIE);

        // when
        RecoveryResult result = sut.recoverRoom(requestRoom, response);

        // then
        assertThat(result.getResult().name()).isEqualTo("SUCCESS");
        assertThat(masterRoom.getInstanceId()).isEqualTo(NEW_INSTANCE);
        verify(redisService).updateRecoveredRoomRoutingAndMetadata(
                eq(masterRoom),
                any(RoomRoutingInfo.class),
                argThat(saved -> saved.getStatus() == RecoveryStatus.CLAIMED
                        && ROOM_ID.equals(saved.getRoomId())),
                anyLong()
        );
        verify(instanceProvider).incrementInstanceRoomCount();
        verify(routingService).setRecoveryRoutingInfo(response, ROOM_ID, CURRENT_COOKIE);
        verify(redisService).releaseRoomClaimLock(ROOM_ID, NEW_INSTANCE);
    }

    @Test
    @DisplayName("recoverRoom_whenMasterRoomMissing_deletesMetadataAndReturnsDashboard")
    void recoverRoom_whenMasterRoomMissing_deletesMetadataAndReturnsDashboard() {
        // given
        ChatRoom requestRoom = rtcRoom(OLD_INSTANCE);
        given(instanceProvider.getInstanceId()).willReturn(NEW_INSTANCE);
        given(redisService.getRoomRecoveryMetadata(ROOM_ID)).willReturn(validMetadata());
        given(redisService.tryAcquireRoomClaimLock(eq(ROOM_ID), eq(NEW_INSTANCE), anyLong())).willReturn(true);
        given(redisService.getChatRoomFromMaster(ROOM_ID)).willReturn(null);

        // when
        RecoveryResult result = sut.recoverRoom(requestRoom, response);

        // then
        assertThat(result.getResult().name()).isEqualTo("REDIRECT_DASHBOARD");
        assertThat(result.getData().getReason()).isEqualTo(RecoveryReason.ROOM_NOT_FOUND.name());
        verify(redisService).deleteRoomRecoveryMetadata(ROOM_ID);
        verify(redisService).releaseRoomClaimLock(ROOM_ID, NEW_INSTANCE);
    }

    @Test
    @DisplayName("recoverRoom_whenAlreadyOwnedByCurrentHealthyInstance_setsExistingCookieAndReturnsSuccess")
    void recoverRoom_whenAlreadyOwnedByCurrentHealthyInstance_setsExistingCookieAndReturnsSuccess() {
        // given
        ChatRoom requestRoom = rtcRoom(NEW_INSTANCE);
        ChatRoom masterRoom = rtcRoom(NEW_INSTANCE);
        given(instanceProvider.getInstanceId()).willReturn(NEW_INSTANCE);
        given(redisService.getRoomRecoveryMetadata(ROOM_ID)).willReturn(validMetadata());
        given(redisService.tryAcquireRoomClaimLock(eq(ROOM_ID), eq(NEW_INSTANCE), anyLong())).willReturn(true);
        given(redisService.getChatRoomFromMaster(ROOM_ID)).willReturn(masterRoom);
        given(instanceProvider.isHealthy(NEW_INSTANCE)).willReturn(true);
        given(redisService.getInstanceCookieFromMaster(NEW_INSTANCE)).willReturn(CURRENT_COOKIE);

        // when
        RecoveryResult result = sut.recoverRoom(requestRoom, response);

        // then
        assertThat(result.getResult().name()).isEqualTo("SUCCESS");
        verify(routingService).setRecoveryRoutingInfo(response, ROOM_ID, CURRENT_COOKIE);
        verify(redisService, never()).updateChatRoom(any());
        verify(redisService).releaseRoomClaimLock(ROOM_ID, NEW_INSTANCE);
    }

    @Test
    @DisplayName("recoverRoom_whenCurrentCookieMissing_returnsRetryWithoutSuccess")
    void recoverRoom_whenCurrentCookieMissing_returnsRetryWithoutSuccess() {
        // given
        ChatRoom requestRoom = rtcRoom(OLD_INSTANCE);
        ChatRoom masterRoom = rtcRoom(OLD_INSTANCE);
        given(instanceProvider.getInstanceId()).willReturn(NEW_INSTANCE);
        given(redisService.getRoomRecoveryMetadata(ROOM_ID)).willReturn(validMetadata());
        given(redisService.tryAcquireRoomClaimLock(eq(ROOM_ID), eq(NEW_INSTANCE), anyLong())).willReturn(true);
        given(redisService.getChatRoomFromMaster(ROOM_ID)).willReturn(masterRoom);
        given(instanceProvider.isHealthy(OLD_INSTANCE)).willReturn(false);
        given(redisService.getInstanceCookieFromMaster(NEW_INSTANCE)).willReturn(null);

        // when
        RecoveryResult result = sut.recoverRoom(requestRoom, response);

        // then
        assertThat(result.getResult().name()).isEqualTo("REDIRECT_RECOVER");
        assertThat(result.getData().getReason()).isEqualTo(RecoveryReason.CURRENT_COOKIE_UNAVAILABLE.name());
        verify(redisService, never()).updateRecoveredRoomRoutingAndMetadata(any(), any(), any(), anyLong());
        verify(routingService, never()).setRecoveryRoutingInfo(any(), anyString(), anyString());
        verify(redisService).releaseRoomClaimLock(ROOM_ID, NEW_INSTANCE);
    }

    @Test
    @DisplayName("recoverRoom_whenAlreadyOwnedButCurrentCookieMissing_returnsRetryWithoutSuccess")
    void recoverRoom_whenAlreadyOwnedButCurrentCookieMissing_returnsRetryWithoutSuccess() {
        // given
        ChatRoom requestRoom = rtcRoom(NEW_INSTANCE);
        ChatRoom masterRoom = rtcRoom(NEW_INSTANCE);
        given(instanceProvider.getInstanceId()).willReturn(NEW_INSTANCE);
        given(redisService.getRoomRecoveryMetadata(ROOM_ID)).willReturn(validMetadata());
        given(redisService.tryAcquireRoomClaimLock(eq(ROOM_ID), eq(NEW_INSTANCE), anyLong())).willReturn(true);
        given(redisService.getChatRoomFromMaster(ROOM_ID)).willReturn(masterRoom);
        given(instanceProvider.isHealthy(NEW_INSTANCE)).willReturn(true);
        given(redisService.getInstanceCookieFromMaster(NEW_INSTANCE)).willReturn(null);

        // when
        RecoveryResult result = sut.recoverRoom(requestRoom, response);

        // then
        assertThat(result.getResult().name()).isEqualTo("REDIRECT_RECOVER");
        assertThat(result.getData().getReason()).isEqualTo(RecoveryReason.CURRENT_COOKIE_UNAVAILABLE.name());
        verify(routingService, never()).setRecoveryRoutingInfo(any(), anyString(), anyString());
        verify(redisService).releaseRoomClaimLock(ROOM_ID, NEW_INSTANCE);
    }

    @Test
    @DisplayName("recoverRoom_whenUpdateFails_releasesClaimLock")
    void recoverRoom_whenUpdateFails_releasesClaimLock() {
        // given
        ChatRoom requestRoom = rtcRoom(OLD_INSTANCE);
        ChatRoom masterRoom = rtcRoom(OLD_INSTANCE);
        given(instanceProvider.getInstanceId()).willReturn(NEW_INSTANCE);
        given(redisService.getRoomRecoveryMetadata(ROOM_ID)).willReturn(validMetadata());
        given(redisService.tryAcquireRoomClaimLock(eq(ROOM_ID), eq(NEW_INSTANCE), anyLong())).willReturn(true);
        given(redisService.getChatRoomFromMaster(ROOM_ID)).willReturn(masterRoom);
        given(instanceProvider.isHealthy(OLD_INSTANCE)).willReturn(false);
        given(redisService.getInstanceCookieFromMaster(NEW_INSTANCE)).willReturn(CURRENT_COOKIE);
        willThrow(new IllegalStateException("update failed"))
                .given(redisService)
                .updateRecoveredRoomRoutingAndMetadata(eq(masterRoom), any(RoomRoutingInfo.class), any(RoomRecoveryMetadata.class), anyLong());

        // when / then
        assertThatThrownBy(() -> sut.recoverRoom(requestRoom, response))
                .isInstanceOf(IllegalStateException.class);
        verify(redisService).releaseRoomClaimLock(ROOM_ID, NEW_INSTANCE);
    }

    @Test
    @DisplayName("markOwnedRoomsRecoverable_whenCalled_marksOnlyCurrentInstanceOwnedRooms")
    void markOwnedRoomsRecoverable_whenCalled_marksOnlyCurrentInstanceOwnedRooms() {
        // given
        ChatRoom ownedRoom = rtcRoom(NEW_INSTANCE);
        ChatRoom foreignRoom = rtcRoom(OLD_INSTANCE);
        ChatRoom messageRoom = messageRoom(NEW_INSTANCE);
        given(instanceProvider.getInstanceId()).willReturn(NEW_INSTANCE);
        given(redisService.searchRoomListByOptions(any()))
                .willReturn(List.of(document("room-owned"), document("room-foreign"), document("room-message")));
        given(redisService.getAllChatRoomData("room-owned"))
                .willReturn(Map.of(DataType.CHATROOM.getType(), ownedRoom));
        given(redisService.getAllChatRoomData("room-foreign"))
                .willReturn(Map.of(DataType.CHATROOM.getType(), foreignRoom));
        given(redisService.getAllChatRoomData("room-message"))
                .willReturn(Map.of(DataType.CHATROOM.getType(), messageRoom));

        // when
        PreShutdownResult result = sut.markOwnedRoomsRecoverable();

        // then
        assertThat(result.getInstanceId()).isEqualTo(NEW_INSTANCE);
        assertThat(result.getMarkedRoomCount()).isEqualTo(1);
        assertThat(result.getRoomIds()).containsExactly("room-owned");
        verify(redisService, times(1)).saveRoomRecoveryMetadata(any(RoomRecoveryMetadata.class), anyLong());
    }

    private ChatRoom rtcRoom(String instanceId) {
        return ChatRoom.builder()
                .roomId(ROOM_ID)
                .roomName("Recovery Room")
                .chatType(ChatType.RTC)
                .instanceId(instanceId)
                .build();
    }

    private ChatRoom messageRoom(String instanceId) {
        return ChatRoom.builder()
                .roomId(ROOM_ID)
                .roomName("Message Room")
                .chatType(ChatType.MSG)
                .instanceId(instanceId)
                .build();
    }

    private RoomRecoveryMetadata validMetadata() {
        long now = System.currentTimeMillis();
        return RoomRecoveryMetadata.builder()
                .roomId(ROOM_ID)
                .previousInstanceId(OLD_INSTANCE)
                .createdAt(now)
                .expiresAt(now + 60_000)
                .reason("PRE_SHUTDOWN")
                .status(RecoveryStatus.CANDIDATE)
                .build();
    }

    private RoomRecoveryMetadata expiredMetadata() {
        long now = System.currentTimeMillis();
        return RoomRecoveryMetadata.builder()
                .roomId(ROOM_ID)
                .previousInstanceId(OLD_INSTANCE)
                .createdAt(now - 120_000)
                .expiresAt(now - 1_000)
                .reason("PRE_SHUTDOWN")
                .status(RecoveryStatus.CANDIDATE)
                .build();
    }

    private Document document(String roomId) {
        return new Document(roomId, 1.0, Map.of("roomId", roomId));
    }
}
