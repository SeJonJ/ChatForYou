package webChat.service.kurento;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kurento.client.KurentoClient;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import webChat.model.chat.ChatType;
import webChat.model.record.RecordingInfo;
import webChat.model.record.RecordingPartialMarker;
import webChat.model.redis.DataType;
import webChat.model.room.KurentoRoom;
import webChat.service.chatroom.participant.KurentoParticipantService;
import webChat.service.kafka.ChatKafkaProducer;
import webChat.service.recording.RecordingService;
import webChat.service.redis.RedisService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * KurentoHandler.joinRoom() — else 분기 마커 알림 QA 테스트.
 *
 * 6가지 분기 조합을 검증한다:
 * 1. notified=false → 안내 1회 전송 후 notified=true 재저장
 * 2. notified=true → 미전송 (중복 방지)
 * 3. marker null → 조용히 skip
 * 4. isRecordingInProgress=true → else 분기 미진입 (안내 없음)
 * 5. isHasRecordedOnce=true → else 분기 미진입 (안내 없음)
 * 6. 동시 재입장 경합 (무해한 1회 중복) — 데이터 손상 아님 검증
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KurentoHandlerJoinRoomRecordingBranchTest {

    private static final String ROOM_ID = "room-1";
    private static final String USER_ID = "user-1";
    private static final String NICK = "tester";

    @Mock
    private KurentoRoomManager kurentoRoomManager;
    @Mock
    private KurentoClient kurentoClient;
    @Mock
    private org.kurento.client.MediaPipeline mediaPipeline;
    @Mock
    private RedisService redisService;
    @Mock
    private KurentoParticipantService participantService;
    @Mock
    private ChatKafkaProducer chatKafkaProducer;
    @Mock
    private RecordingService recordingService;
    @Mock
    private KurentoMessageSender kurentoMessageSender;
    @Mock
    private WebSocketSession session;
    @Mock
    private KurentoUserSession user;

    private static final String JOIN_PAYLOAD = """
            {
              "event":"JOIN_ROOM",
              "roomId":"room-1",
              "senderId":"user-1",
              "senderNickName":"tester"
            }
            """;

    // ── 분기 1: notified=false → 1회 전송 후 notified=true 재저장 ─────────────

    @Test
    @DisplayName("joinRoom_마커notifiedFalse_안내1회전송후notifiedTrue로재저장한다")
    void joinRoom_markerNotNotified_sendsInterruptedAndSavesNotifiedTrue() throws Exception {
        // given
        KurentoRoom room = freshRoom();
        KurentoHandler handler = handlerForRoom(room);

        RecordingPartialMarker marker = RecordingPartialMarker.builder()
                .roomId(ROOM_ID)
                .recordingId("rec-1")
                .notified(false)
                .markedAt(System.currentTimeMillis())
                .build();
        given(redisService.getRecordingPartialMarker(ROOM_ID)).willReturn(marker);
        given(participantService.getBySessionId(session)).willReturn(user);

        // when
        handler.handleTextMessage(session, new TextMessage(JOIN_PAYLOAD));

        // then: 안내 메시지 1회 전송
        verify(kurentoMessageSender).sendToUser(eq(user), any());

        // notified=true 로 마커 재저장
        ArgumentCaptor<RecordingPartialMarker> captor = ArgumentCaptor.forClass(RecordingPartialMarker.class);
        verify(redisService).saveRecordingPartialMarker(captor.capture(), anyLong());
        assertThat(captor.getValue().isNotified()).isTrue();
    }

    // ── 분기 2: notified=true → 미전송 ──────────────────────────────────────

    @Test
    @DisplayName("joinRoom_마커notifiedTrue_안내미전송하고마커재저장않는다")
    void joinRoom_markerAlreadyNotified_noSendAndNoResave() throws Exception {
        // given
        KurentoRoom room = freshRoom();
        KurentoHandler handler = handlerForRoom(room);

        RecordingPartialMarker marker = RecordingPartialMarker.builder()
                .roomId(ROOM_ID)
                .recordingId("rec-1")
                .notified(true)
                .markedAt(System.currentTimeMillis())
                .build();
        given(redisService.getRecordingPartialMarker(ROOM_ID)).willReturn(marker);
        given(participantService.getBySessionId(session)).willReturn(user);

        // when
        handler.handleTextMessage(session, new TextMessage(JOIN_PAYLOAD));

        // then: 안내 없음, 재저장 없음
        verify(kurentoMessageSender, never()).sendToUser(eq(user), any());
        verify(redisService, never()).saveRecordingPartialMarker(any(), anyLong());
    }

    // ── 분기 3: marker null → 조용히 skip ───────────────────────────────────

    @Test
    @DisplayName("joinRoom_마커null_안내없이조용히skip한다")
    void joinRoom_markerNull_silentSkip() throws Exception {
        // given
        KurentoRoom room = freshRoom();
        KurentoHandler handler = handlerForRoom(room);

        given(redisService.getRecordingPartialMarker(ROOM_ID)).willReturn(null);
        given(participantService.getBySessionId(session)).willReturn(user);

        // when
        handler.handleTextMessage(session, new TextMessage(JOIN_PAYLOAD));

        // then: 예외 없음, 안내 없음
        verify(kurentoMessageSender, never()).sendToUser(eq(user), any());
        verify(redisService, never()).saveRecordingPartialMarker(any(), anyLong());
    }

    // ── 분기 4: isRecordingInProgress=true → else 미진입 ────────────────────

    @Test
    @DisplayName("joinRoom_isRecordingInProgressTrue_else분기미진입으로마커조회안함")
    void joinRoom_recordingInProgress_doesNotEnterElseBranch() throws Exception {
        // given: 현재 녹화 중인 방 → 첫 번째 if 분기
        KurentoRoom room = inProgressRoom();
        KurentoHandler handler = handlerForRoom(room);
        given(participantService.getBySessionId(session)).willReturn(user);

        // when
        handler.handleTextMessage(session, new TextMessage(JOIN_PAYLOAD));

        // then: else 분기 미진입 → 마커 조회 없음
        verify(redisService, never()).getRecordingPartialMarker(any());
    }

    // ── 분기 5: isHasRecordedOnce=true → else 미진입 ────────────────────────

    @Test
    @DisplayName("joinRoom_hasRecordedOnceTrue_else분기미진입으로마커조회안함")
    void joinRoom_hasRecordedOnce_doesNotEnterElseBranch() throws Exception {
        // given: 정상 완료된 방 → else if 분기
        KurentoRoom room = completedRoom();
        KurentoHandler handler = handlerForRoom(room);
        given(participantService.getBySessionId(session)).willReturn(user);

        // when
        handler.handleTextMessage(session, new TextMessage(JOIN_PAYLOAD));

        // then: else 분기 미진입 → 마커 조회 없음
        verify(redisService, never()).getRecordingPartialMarker(any());
    }

    // ── 분기 6: TTL이 재저장 시에도 적용된다 ─────────────────────────────────

    @Test
    @DisplayName("joinRoom_notifiedTrue재저장시TTL이partialMarkerTtlSeconds로적용된다")
    void joinRoom_notifiedResave_usesTtlSeconds() throws Exception {
        // given
        KurentoRoom room = freshRoom();
        KurentoHandler handler = handlerForRoom(room);

        RecordingPartialMarker marker = RecordingPartialMarker.builder()
                .roomId(ROOM_ID)
                .notified(false)
                .build();
        given(redisService.getRecordingPartialMarker(ROOM_ID)).willReturn(marker);
        given(participantService.getBySessionId(session)).willReturn(user);

        // when
        handler.handleTextMessage(session, new TextMessage(JOIN_PAYLOAD));

        // then: TTL = 86400 (기본값) 으로 재저장
        verify(redisService).saveRecordingPartialMarker(any(), eq(86_400L));
    }

    // ── 동시 재입장 경합: 두 사용자가 동시에 notified=false 마커를 읽는 경우 ──

    @Test
    @DisplayName("joinRoom_동시재입장경합_두사용자모두notifiedFalse로읽으면안내가두번갈수있으나데이터손상없음")
    void joinRoom_concurrentJoin_bothReadNotifiedFalse_noDataCorruption() throws Exception {
        // given: 두 핸들러가 같은 마커를 동시에 읽는 상황 (lock 밖 read-modify-write 경합 시뮬레이션)
        KurentoRoom room = freshRoom();

        WebSocketSession sessionB = org.mockito.Mockito.mock(WebSocketSession.class);
        KurentoUserSession userB = org.mockito.Mockito.mock(KurentoUserSession.class);

        RecordingPartialMarker markerForA = RecordingPartialMarker.builder()
                .roomId(ROOM_ID).notified(false).build();
        RecordingPartialMarker markerForB = RecordingPartialMarker.builder()
                .roomId(ROOM_ID).notified(false).build();

        given(redisService.getRedisDataByDataType(eq(ROOM_ID), eq(DataType.CHATROOM), eq(KurentoRoom.class)))
                .willReturn(room);
        // ConcurrentHashMap null-value 방지 — pipeline 스텁
        lenient().when(kurentoClient.createMediaPipeline()).thenReturn(mediaPipeline);
        lenient().when(kurentoRoomManager.join(eq(room), eq(USER_ID), eq(NICK), eq(session)))
                .thenReturn(new KurentoJoinResult(user, false));
        lenient().when(kurentoRoomManager.join(eq(room), eq("user-2"), eq("tester2"), eq(sessionB)))
                .thenReturn(new KurentoJoinResult(userB, false));
        given(participantService.getParticipantCount(ROOM_ID)).willReturn(1);
        given(participantService.getBySessionId(session)).willReturn(user);
        given(participantService.getBySessionId(sessionB)).willReturn(userB);

        // A와 B가 각자의 읽기에서 notified=false 마커를 얻는다
        given(redisService.getRecordingPartialMarker(ROOM_ID))
                .willReturn(markerForA)
                .willReturn(markerForB);

        KurentoHandler handlerA = buildHandler();
        KurentoHandler handlerB = buildHandler();

        String payloadB = """
                {
                  "event":"JOIN_ROOM",
                  "roomId":"room-1",
                  "senderId":"user-2",
                  "senderNickName":"tester2"
                }
                """;

        // when: A, B 순서대로 join (lock 밖 동시 읽기 시뮬레이션)
        handlerA.handleTextMessage(session, new TextMessage(JOIN_PAYLOAD));
        handlerB.handleTextMessage(sessionB, new TextMessage(payloadB));

        // then: 최대 2회 안내 가능 (무해한 중복) — 저장된 마커의 roomId는 모두 room-1로 일치해야 한다
        ArgumentCaptor<RecordingPartialMarker> captor = ArgumentCaptor.forClass(RecordingPartialMarker.class);
        verify(redisService, org.mockito.Mockito.atLeast(1))
                .saveRecordingPartialMarker(captor.capture(), anyLong());
        captor.getAllValues().forEach(saved ->
                assertThat(saved.getRoomId()).isEqualTo(ROOM_ID));
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private KurentoRoom freshRoom() {
        KurentoRoom room = new KurentoRoom(ROOM_ID, "방", "creator", null, false, 0, 8, ChatType.RTC, "instance-1");
        room.setRecordingInProgress(false);
        room.setHasRecordedOnce(false);
        return room;
    }

    private KurentoRoom inProgressRoom() {
        KurentoRoom room = new KurentoRoom(ROOM_ID, "방", "creator", null, false, 1, 8, ChatType.RTC, "instance-1");
        room.setRecordingInProgress(true);
        room.setHasRecordedOnce(false);
        room.setRecordingInfo(RecordingInfo.builder()
                .recordingId("rec-1")
                .roomId(ROOM_ID)
                .recordingUserId(USER_ID)
                .recordingNickName(NICK)
                .startAt(1_000L)
                .build());
        return room;
    }

    private KurentoRoom completedRoom() {
        KurentoRoom room = new KurentoRoom(ROOM_ID, "방", "creator", null, false, 1, 8, ChatType.RTC, "instance-1");
        room.setRecordingInProgress(false);
        room.setHasRecordedOnce(true);
        return room;
    }

    /**
     * 단일 세션·사용자 기준 핸들러 준비 (개별 테스트 공통).
     * join()이 IOException을 선언하므로 호출 메서드는 throws Exception 필요.
     */
    private KurentoHandler handlerForRoom(KurentoRoom room) throws Exception {
        given(redisService.getRedisDataByDataType(eq(ROOM_ID), eq(DataType.CHATROOM), eq(KurentoRoom.class)))
                .willReturn(room);
        // kurentoPiplineMap.put(roomId, pipeline) 이 ConcurrentHashMap 이라 null value 불가 — pipeline 스텁 필요
        lenient().when(kurentoClient.createMediaPipeline()).thenReturn(mediaPipeline);
        lenient().when(kurentoRoomManager.join(eq(room), eq(USER_ID), eq(NICK), eq(session)))
                .thenReturn(new KurentoJoinResult(user, false));
        given(participantService.getParticipantCount(ROOM_ID)).willReturn(1);
        return buildHandler();
    }

    private KurentoHandler buildHandler() {
        KurentoHandler handler = new KurentoHandler(
                kurentoRoomManager,
                kurentoClient,
                redisService,
                participantService,
                chatKafkaProducer,
                recordingService,
                kurentoMessageSender
        );
        ReflectionTestUtils.setField(handler, "partialMarkerTtlSeconds", 86_400L);
        return handler;
    }
}
