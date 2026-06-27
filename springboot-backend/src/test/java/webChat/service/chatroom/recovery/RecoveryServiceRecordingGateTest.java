package webChat.service.chatroom.recovery;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import webChat.model.chat.ChatType;
import webChat.model.record.RecordingInfo;
import webChat.model.record.RecordingPartialMarker;
import webChat.model.room.ChatRoom;
import webChat.model.room.KurentoRoom;
import webChat.model.room.recovery.RecoveryStatus;
import webChat.model.room.recovery.RoomRecoveryMetadata;
import webChat.model.routing.RoomRoutingInfo;
import webChat.service.chatroom.recovery.impl.ChatRoomRecoveryServiceImpl;
import webChat.service.redis.RedisService;
import webChat.service.routing.RoutingInstanceProvider;
import webChat.service.routing.RoutingService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * ChatRoomRecoveryServiceImpl — 녹화 게이트 및 idempotency QA 테스트.
 *
 * 시나리오 1(graceful→claim 순서): claim이 isRecordingInProgress==false를 보고
 * marker 재기록을 건너뛰어 cleanup의 notified 값을 보존하는지 검증한다.
 * 시나리오 2(ungraceful): cleanup 미실행 시 claim이 marker를 기록하는지 검증한다.
 * 게이트 정확성: 정상 완료 방(isRecordingInProgress=false)은 claim 경로를 지나도
 * hasRecordedOnce/recordingInfo가 변하지 않아야 한다.
 */
@ExtendWith(MockitoExtension.class)
class RecoveryServiceRecordingGateTest {

    private static final String ROOM_ID = "room-1";
    private static final String OLD_INSTANCE = "instance-old";
    private static final String NEW_INSTANCE = "instance-new";
    private static final String COOKIE = "srv|cookie-new";

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

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(sut, "partialMarkerTtlSeconds", 86_400L);
        ReflectionTestUtils.setField(sut, "recoveryTtlSeconds", 180L);
        given(instanceProvider.getInstanceId()).willReturn(NEW_INSTANCE);
        given(redisService.getRoomRecoveryMetadata(ROOM_ID)).willReturn(validMetadata());
        given(redisService.tryAcquireRoomClaimLock(eq(ROOM_ID), eq(NEW_INSTANCE), anyLong())).willReturn(true);
        given(instanceProvider.isHealthy(OLD_INSTANCE)).willReturn(false);
        given(redisService.getInstanceCookieFromMaster(NEW_INSTANCE)).willReturn(COOKIE);
    }

    // ── 시나리오 1: graceful→claim idempotency ────────────────────────────────

    @Test
    @DisplayName("recoverRoom_graceful완료후claim_isRecordingInProgressFalse를보고marker재기록건너뜀")
    void recoverRoom_afterGracefulCleanup_skipsMarkerWhenNotInProgress() {
        // given: graceful cleanup이 이미 isRecordingInProgress=false로 정리한 상태
        KurentoRoom masterRoom = kurentoRoomAfterGracefulCleanup();
        given(redisService.getChatRoomFromMaster(ROOM_ID)).willReturn(masterRoom);

        // when
        sut.recoverRoom(chatRoom(OLD_INSTANCE), response);

        // then: marker 재기록 없음 — graceful cleanup의 notified 값 보존
        verify(redisService, never()).saveRecordingPartialMarker(any(), anyLong());
        // reset도 호출되지 않으므로 state 변화 없음
        assertThat(masterRoom.isRecordingInProgress()).isFalse();
        assertThat(masterRoom.isHasRecordedOnce()).isFalse();
        assertThat(masterRoom.getRecordingInfo()).isNull();
    }

    // ── 시나리오 2: ungraceful — cleanup 미실행 ───────────────────────────────

    @Test
    @DisplayName("recoverRoom_ungraceful(cleanup미실행)_claim이marker기록하고resetRecordingState호출한다")
    void recoverRoom_ungracefulShutdown_claimSavesMarkerAndResets() {
        // given: ungraceful — masterRoom은 여전히 isRecordingInProgress=true
        KurentoRoom masterRoom = kurentoRoomInProgress();
        given(redisService.getChatRoomFromMaster(ROOM_ID)).willReturn(masterRoom);

        // when
        sut.recoverRoom(chatRoom(OLD_INSTANCE), response);

        // then: claim이 marker 기록
        ArgumentCaptor<RecordingPartialMarker> captor = ArgumentCaptor.forClass(RecordingPartialMarker.class);
        verify(redisService).saveRecordingPartialMarker(captor.capture(), eq(86_400L));
        RecordingPartialMarker saved = captor.getValue();
        assertThat(saved.getRoomId()).isEqualTo(ROOM_ID);
        assertThat(saved.isNotified()).isFalse();
        assertThat(saved.getRecordingId()).isEqualTo("rec-1");

        // reset 확인
        assertThat(masterRoom.isRecordingInProgress()).isFalse();
        assertThat(masterRoom.isHasRecordedOnce()).isFalse();
        assertThat(masterRoom.getRecordingInfo()).isNull();
    }

    // ── 게이트 정확성: 정상 완료 방 보호 ─────────────────────────────────────

    @Test
    @DisplayName("recoverRoom_정상완료방(isRecordingInProgressFalse_hasRecordedOnceTrue)_hasRecordedOnce보존")
    void recoverRoom_completedRecordingRoom_preservesHasRecordedOnce() {
        // given: 정상적으로 녹화를 완료한 방
        KurentoRoom masterRoom = kurentoRoomCompleted();
        given(redisService.getChatRoomFromMaster(ROOM_ID)).willReturn(masterRoom);

        // when
        sut.recoverRoom(chatRoom(OLD_INSTANCE), response);

        // then: hasRecordedOnce 보존 — 재녹화 차단(RECORDING_FILE_EXISTS) 유지
        verify(redisService, never()).saveRecordingPartialMarker(any(), anyLong());
        assertThat(masterRoom.isHasRecordedOnce())
                .as("정상 완료 방의 hasRecordedOnce 는 claim 경로를 지나도 변하지 않아야 한다")
                .isTrue();
        assertThat(masterRoom.isRecordingInProgress()).isFalse();
    }

    @Test
    @DisplayName("recoverRoom_정상완료방_recordingInfo보존되어파일식별정보손실없음")
    void recoverRoom_completedRecordingRoom_preservesRecordingInfo() {
        // given
        KurentoRoom masterRoom = kurentoRoomCompleted();
        given(redisService.getChatRoomFromMaster(ROOM_ID)).willReturn(masterRoom);

        // when
        sut.recoverRoom(chatRoom(OLD_INSTANCE), response);

        // then: recordingInfo null 미발생 — MinIO 파일 경로 유지
        assertThat(masterRoom.getRecordingInfo())
                .as("정상 완료 방의 recordingInfo 는 null 로 초기화되지 않아야 한다")
                .isNotNull();
    }

    // ── NPE 경계값 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("recoverRoom_recordingInfoNull이고isRecordingInProgressTrue_NPE없이marker기록하고reset")
    void recoverRoom_recordingInfoNullInProgress_noNpeMarkerSavedAndReset() {
        // given: isRecordingInProgress=true, recordingInfo=null (시작 직후 실패 케이스)
        KurentoRoom masterRoom = kurentoRoomInProgressNullInfo();
        given(redisService.getChatRoomFromMaster(ROOM_ID)).willReturn(masterRoom);

        // when (NPE 발생 없이 완료되어야 함)
        sut.recoverRoom(chatRoom(OLD_INSTANCE), response);

        // then: marker 기록됨 (파일 식별 필드는 null 허용)
        ArgumentCaptor<RecordingPartialMarker> captor = ArgumentCaptor.forClass(RecordingPartialMarker.class);
        verify(redisService).saveRecordingPartialMarker(captor.capture(), anyLong());
        RecordingPartialMarker saved = captor.getValue();
        assertThat(saved.getRoomId()).isEqualTo(ROOM_ID);
        assertThat(saved.getRecordingId()).isNull();

        // reset 완료
        assertThat(masterRoom.isRecordingInProgress()).isFalse();
    }

    @Test
    @DisplayName("recoverRoom_marker기록이resetRecordingState보다먼저실행된다(순서불변)")
    void recoverRoom_markerSavedBeforeReset_orderInvariant() {
        // given
        KurentoRoom masterRoom = kurentoRoomInProgress();
        given(redisService.getChatRoomFromMaster(ROOM_ID)).willReturn(masterRoom);

        // saveRecordingPartialMarker 호출 시점에 recordingId가 있으면
        // marker 생성이 reset(recordingInfo=null)보다 먼저임을 증명한다.
        ArgumentCaptor<RecordingPartialMarker> captor = ArgumentCaptor.forClass(RecordingPartialMarker.class);

        // when
        sut.recoverRoom(chatRoom(OLD_INSTANCE), response);

        // then
        verify(redisService).saveRecordingPartialMarker(captor.capture(), anyLong());
        assertThat(captor.getValue().getRecordingId())
                .as("marker는 resetRecordingState 전에 fromRoom()으로 생성되어야 파일 식별 정보가 존재한다")
                .isNotNull();
    }

    // ── 비-KurentoRoom 방 instanceof 가드 ────────────────────────────────────

    @Test
    @DisplayName("recoverRoom_masterRoom이비KurentoRoom(ChatRoom)_marker기록하지않는다")
    void recoverRoom_masterRoomIsNotKurentoRoom_doesNotSaveMarker() {
        // given: masterRoom이 KurentoRoom이 아닌 ChatRoom (비-RTC 방)
        ChatRoom masterRoom = ChatRoom.builder()
                .roomId(ROOM_ID)
                .chatType(ChatType.MSG)
                .instanceId(OLD_INSTANCE)
                .build();
        given(redisService.getChatRoomFromMaster(ROOM_ID)).willReturn(masterRoom);

        // when
        sut.recoverRoom(chatRoom(OLD_INSTANCE), response);

        // then: instanceof 가드가 걸려 marker 기록 없음
        verify(redisService, never()).saveRecordingPartialMarker(any(), anyLong());
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private ChatRoom chatRoom(String instanceId) {
        return ChatRoom.builder()
                .roomId(ROOM_ID)
                .chatType(ChatType.RTC)
                .instanceId(instanceId)
                .build();
    }

    private KurentoRoom kurentoRoomInProgress() {
        KurentoRoom room = new KurentoRoom(ROOM_ID, "방", "creator", null, false, 1, 8, ChatType.RTC, OLD_INSTANCE);
        room.setRecordingInProgress(true);
        room.setHasRecordedOnce(false);
        room.setRecordingInfo(RecordingInfo.builder()
                .recordingId("rec-1")
                .roomId(ROOM_ID)
                .recordingUserId("user-1")
                .recordingNickName("닉네임")
                .startAt(1_000_000L)
                .build());
        return room;
    }

    private KurentoRoom kurentoRoomInProgressNullInfo() {
        KurentoRoom room = new KurentoRoom(ROOM_ID, "방", "creator", null, false, 1, 8, ChatType.RTC, OLD_INSTANCE);
        room.setRecordingInProgress(true);
        room.setHasRecordedOnce(false);
        room.setRecordingInfo(null);
        return room;
    }

    private KurentoRoom kurentoRoomAfterGracefulCleanup() {
        // graceful cleanup이 실행된 후 상태: reset 완료
        KurentoRoom room = new KurentoRoom(ROOM_ID, "방", "creator", null, false, 0, 8, ChatType.RTC, OLD_INSTANCE);
        room.setRecordingInProgress(false);
        room.setHasRecordedOnce(false);
        room.setRecordingInfo(null);
        return room;
    }

    private KurentoRoom kurentoRoomCompleted() {
        // 정상 완료 방: isRecordingInProgress=false, hasRecordedOnce=true
        KurentoRoom room = new KurentoRoom(ROOM_ID, "방", "creator", null, false, 1, 8, ChatType.RTC, OLD_INSTANCE);
        room.setRecordingInProgress(false);
        room.setHasRecordedOnce(true);
        room.setRecordingInfo(RecordingInfo.builder()
                .recordingId("rec-1")
                .roomId(ROOM_ID)
                .recordingUserId("user-1")
                .recordingNickName("닉네임")
                .startAt(1_000_000L)
                .build());
        return room;
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
}
