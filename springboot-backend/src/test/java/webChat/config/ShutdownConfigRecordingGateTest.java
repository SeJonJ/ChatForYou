package webChat.config;

import io.github.dengliming.redismodule.redisearch.index.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kurento.client.KurentoClient;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.test.util.ReflectionTestUtils;
import webChat.model.record.RecordingPartialMarker;
import webChat.model.redis.DataType;
import webChat.model.room.KurentoRoom;
import webChat.model.room.recovery.PreShutdownResult;
import webChat.service.chatroom.recovery.ChatRoomRecoveryService;
import webChat.service.kurento.KurentoRoomManager;
import webChat.service.redis.RedisService;
import webChat.service.routing.InstanceProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * ShutdownConfig.cleanup() — 녹화 상태 게이트 QA 테스트.
 *
 * Round 1 P0 회귀 가드: isRecordingInProgress 게이트가 정상 완료 방을 보호하는지,
 * in-progress 방만 marker+reset을 실행하는지, marker 기록 순서가 불변인지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShutdownConfigRecordingGateTest {

    private static final String INSTANCE_ID = "instance-A";

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
        given(instanceProvider.getInstanceId()).willReturn(INSTANCE_ID);
        given(chatRoomRecoveryService.markOwnedRoomsRecoverable()).willReturn(
                PreShutdownResult.builder()
                        .instanceId(INSTANCE_ID)
                        .markedRoomCount(0)
                        .roomIds(List.of())
                        .build());
        ReflectionTestUtils.setField(shutdownConfig, "partialMarkerTtlSeconds", 86_400L);
    }

    // ── Round 1 P0 회귀 가드 ──────────────────────────────────────────────────

    @Test
    @DisplayName("cleanup_isRecordingInProgress가true인방_marker기록후resetRecordingState호출한다")
    void cleanup_whenRecordingInProgress_savesMarkerThenResets() {
        // given
        KurentoRoom room = inProgressRoom("room-1");
        stubRoomList("room-1", room);

        // when
        shutdownConfig.onApplicationEvent(closeEvent());

        // then
        ArgumentCaptor<RecordingPartialMarker> markerCaptor = ArgumentCaptor.forClass(RecordingPartialMarker.class);
        verify(redisService).saveRecordingPartialMarker(markerCaptor.capture(), anyLong());
        RecordingPartialMarker saved = markerCaptor.getValue();
        assertThat(saved.getRoomId()).isEqualTo("room-1");
        assertThat(saved.isNotified()).isFalse();

        // reset 결과 확인 — updateChatRoom 에 isRecordingInProgress=false 상태가 넘어가야 함
        assertThat(room.isRecordingInProgress()).isFalse();
        assertThat(room.isHasRecordedOnce()).isFalse();
        assertThat(room.getRecordingInfo()).isNull();
    }

    @Test
    @DisplayName("cleanup_정상완료방(isRecordingInProgress=false)_marker기록과resetRecordingState를호출하지않는다")
    void cleanup_whenRecordingCompleted_doesNotSaveMarkerOrReset() {
        // given: isRecordingInProgress=false, hasRecordedOnce=true — 정상 완료 방
        KurentoRoom room = completedRoom("room-1");
        boolean originalHasRecordedOnce = room.isHasRecordedOnce();
        stubRoomList("room-1", room);

        // when
        shutdownConfig.onApplicationEvent(closeEvent());

        // then: marker 기록·reset 미호출 — hasRecordedOnce 보존으로 재녹화 차단 유지
        verify(redisService, never()).saveRecordingPartialMarker(any(), anyLong());
        assertThat(room.isHasRecordedOnce())
                .as("정상 완료 방의 hasRecordedOnce 는 reset 되지 않아야 한다")
                .isEqualTo(originalHasRecordedOnce);
        assertThat(room.isRecordingInProgress()).isFalse();
    }

    @Test
    @DisplayName("cleanup_녹화이력없는방(isRecordingInProgress=false_hasRecordedOnce=false)_marker기록하지않는다")
    void cleanup_whenNeverRecorded_doesNotSaveMarker() {
        // given: 녹화 이력 없는 방
        KurentoRoom room = freshRoom("room-1");
        stubRoomList("room-1", room);

        // when
        shutdownConfig.onApplicationEvent(closeEvent());

        // then
        verify(redisService, never()).saveRecordingPartialMarker(any(), anyLong());
        assertThat(room.isHasRecordedOnce()).isFalse();
    }

    @Test
    @DisplayName("cleanup_recordingInfo가null인inProgress방_NPE없이marker기록하고reset한다")
    void cleanup_whenRecordingInfoNull_noNpeAndMarkerSaved() {
        // given: recordingInfo=null + isRecordingInProgress=true (시작 실패 직후 케이스)
        KurentoRoom room = inProgressRoomNullInfo("room-1");
        stubRoomList("room-1", room);

        // when
        shutdownConfig.onApplicationEvent(closeEvent());

        // then: NPE 없이 marker 기록 + reset
        verify(redisService).saveRecordingPartialMarker(any(RecordingPartialMarker.class), anyLong());
        assertThat(room.isRecordingInProgress()).isFalse();
    }

    @Test
    @DisplayName("cleanup_marker기록이reset보다먼저실행된다(순서불변)")
    void cleanup_markerSavedBeforeReset_orderInvariant() {
        // given
        KurentoRoom room = inProgressRoom("room-1");
        stubRoomList("room-1", room);

        // room.resetRecordingState()가 호출되면 이미 recordingInfo가 null이 된다.
        // marker는 reset 전에 fromRoom()으로 채워져야 recordingId 등이 존재해야 한다.
        // saveRecordingPartialMarker 캡처 시점에 roomId가 있으면 순서 불변 증명.
        ArgumentCaptor<RecordingPartialMarker> captor = ArgumentCaptor.forClass(RecordingPartialMarker.class);

        // when
        shutdownConfig.onApplicationEvent(closeEvent());

        // then
        verify(redisService).saveRecordingPartialMarker(captor.capture(), anyLong());
        // marker가 reset 전에 생성되었으므로 recordingId 같은 파일 식별 정보가 남아있다
        assertThat(captor.getValue().getRecordingId()).isNotNull();
    }

    @Test
    @DisplayName("cleanup_혼재방(inProgress+completed+fresh)_inProgress방만marker기록하고reset한다")
    void cleanup_mixedRooms_onlyInProgressRoomGetsMarkerAndReset() {
        // given
        KurentoRoom inProgressRoom = inProgressRoom("room-in");
        KurentoRoom completedRoom = completedRoom("room-done");
        KurentoRoom freshRoom = freshRoom("room-new");

        given(redisService.searchRoomListByOptions(any()))
                .willReturn(List.of(doc("room-in"), doc("room-done"), doc("room-new")));
        given(redisService.getAllChatRoomData("room-in"))
                .willReturn(Map.of(DataType.CHATROOM.getType(), inProgressRoom));
        given(redisService.getAllChatRoomData("room-done"))
                .willReturn(Map.of(DataType.CHATROOM.getType(), completedRoom));
        given(redisService.getAllChatRoomData("room-new"))
                .willReturn(Map.of(DataType.CHATROOM.getType(), freshRoom));

        // when
        shutdownConfig.onApplicationEvent(closeEvent());

        // then: in-progress 방만 marker 기록·reset
        verify(redisService).saveRecordingPartialMarker(any(RecordingPartialMarker.class), anyLong());
        assertThat(inProgressRoom.isRecordingInProgress()).isFalse();

        // 정상 완료 방: hasRecordedOnce 보존
        assertThat(completedRoom.isHasRecordedOnce())
                .as("정상 완료 방의 hasRecordedOnce 는 보존되어야 한다")
                .isTrue();

        // 녹화 이력 없는 방: 그대로
        assertThat(freshRoom.isHasRecordedOnce()).isFalse();
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private KurentoRoom inProgressRoom(String roomId) {
        KurentoRoom room = new KurentoRoom(roomId, "방", "creator", null, false, 1, 8, webChat.model.chat.ChatType.RTC, INSTANCE_ID);
        room.setRecordingInProgress(true);
        room.setHasRecordedOnce(false);
        room.setRecordingInfo(webChat.model.record.RecordingInfo.builder()
                .recordingId("rec-" + roomId)
                .roomId(roomId)
                .recordingUserId("user-1")
                .recordingNickName("닉네임")
                .startAt(1_000_000L)
                .build());
        return room;
    }

    private KurentoRoom inProgressRoomNullInfo(String roomId) {
        KurentoRoom room = new KurentoRoom(roomId, "방", "creator", null, false, 1, 8, webChat.model.chat.ChatType.RTC, INSTANCE_ID);
        room.setRecordingInProgress(true);
        room.setHasRecordedOnce(false);
        room.setRecordingInfo(null);
        return room;
    }

    private KurentoRoom completedRoom(String roomId) {
        KurentoRoom room = new KurentoRoom(roomId, "방", "creator", null, false, 1, 8, webChat.model.chat.ChatType.RTC, INSTANCE_ID);
        room.setRecordingInProgress(false);
        room.setHasRecordedOnce(true);
        room.setRecordingInfo(webChat.model.record.RecordingInfo.builder()
                .recordingId("rec-" + roomId)
                .roomId(roomId)
                .recordingUserId("user-1")
                .recordingNickName("닉네임")
                .startAt(1_000_000L)
                .build());
        return room;
    }

    private KurentoRoom freshRoom(String roomId) {
        KurentoRoom room = new KurentoRoom(roomId, "방", "creator", null, false, 0, 8, webChat.model.chat.ChatType.RTC, INSTANCE_ID);
        room.setRecordingInProgress(false);
        room.setHasRecordedOnce(false);
        return room;
    }

    private void stubRoomList(String roomId, KurentoRoom room) {
        given(redisService.searchRoomListByOptions(any())).willReturn(List.of(doc(roomId)));
        given(redisService.getAllChatRoomData(roomId))
                .willReturn(Map.of(DataType.CHATROOM.getType(), room));
    }

    private Document doc(String roomId) {
        return new Document(roomId, 1.0, Map.of("roomId", roomId));
    }

    private ContextClosedEvent closeEvent() {
        return new ContextClosedEvent(new org.springframework.context.support.StaticApplicationContext());
    }
}
