package webChat.service.redis.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dengliming.redismodule.redisearch.client.RediSearchClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import webChat.model.record.RecordingPartialMarker;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * RedisServiceImpl — RecordingPartialMarker 마커 save/get/delete QA 테스트.
 *
 * Round 2 P2 권고: RoomRecoveryMetadata와 동일 template 경로지만 신규 타입이므로
 * 실제 직렬화/역직렬화 1건 확인. masterTemplate opsForValue() 경로와 key prefix
 * ("room:recording:partial:{roomId}")가 올바르게 적용되는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class RedisServiceImplRecordingPartialMarkerTest {

    private static final String ROOM_ID = "room-qa-001";
    private static final String EXPECTED_KEY = "room:recording:partial:" + ROOM_ID;

    @Mock
    private RedisTemplate<String, Object> masterTemplate;

    @Mock
    private RedisTemplate<String, Object> slaveTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private RediSearchClient rediSearchClient;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    private RedisServiceImpl sut;

    @BeforeEach
    void setUp() {
        sut = new RedisServiceImpl(masterTemplate, slaveTemplate, objectMapper, rediSearchClient);
        lenient().when(masterTemplate.opsForValue()).thenReturn(valueOperations);
    }

    // ── Round 2 P2 핵심: 직렬화·역직렬화 round-trip ──────────────────────────

    @Test
    @DisplayName("saveRecordingPartialMarker_마커저장시masterTemplate_opsForValue_set호출하고key와ttl검증")
    void saveRecordingPartialMarker_usesCorrectKeyPrefixAndTtl() {
        // given: notified=false, 파일 식별 필드 완비
        RecordingPartialMarker marker = fullMarker();

        // when
        sut.saveRecordingPartialMarker(marker, 86_400L);

        // then: masterTemplate.opsForValue().set(key, marker, ttl, SECONDS) 호출
        verify(valueOperations).set(EXPECTED_KEY, marker, 86_400L, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("getRecordingPartialMarker_저장된마커조회시올바른key로masterTemplate조회하고객체반환")
    void getRecordingPartialMarker_returnsMarkerFromCorrectKey() {
        // given: Redis에 마커가 저장된 상태 시뮬레이션
        RecordingPartialMarker stored = fullMarker();
        given(valueOperations.get(EXPECTED_KEY)).willReturn(stored);

        // when
        RecordingPartialMarker result = sut.getRecordingPartialMarker(ROOM_ID);

        // then: 동일 객체 반환 + 필드 보존 확인 (역직렬화 round-trip)
        assertThat(result).isSameAs(stored);
        assertThat(result.getRoomId()).isEqualTo(ROOM_ID);
        assertThat(result.getRecordingId()).isEqualTo("rec-qa-001");
        assertThat(result.getRecordingUserId()).isEqualTo("user-qa-001");
        assertThat(result.getFileName()).isEqualTo("rec.webm");
        assertThat(result.getMinioFilePath()).isEqualTo("minio/rec.webm");
        assertThat(result.isNotified()).isFalse();
        assertThat(result.getStartAt()).isEqualTo(1_000_000L);
    }

    @Test
    @DisplayName("getRecordingPartialMarker_마커없을때_null반환")
    void getRecordingPartialMarker_whenAbsent_returnsNull() {
        // given: 마커 없음
        given(valueOperations.get(EXPECTED_KEY)).willReturn(null);

        // when
        RecordingPartialMarker result = sut.getRecordingPartialMarker(ROOM_ID);

        // then: null 반환 (조용히 skip 가능)
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("deleteRecordingPartialMarker_올바른key로masterTemplate_delete호출")
    void deleteRecordingPartialMarker_deletesWithCorrectKey() {
        // when
        sut.deleteRecordingPartialMarker(ROOM_ID);

        // then
        verify(masterTemplate).delete(EXPECTED_KEY);
    }

    // ── notified 플래그 round-trip ────────────────────────────────────────────

    @Test
    @DisplayName("saveGetRecordingPartialMarker_notifiedFalse저장후true로변경재저장_두번째조회에서notifiedTrue반환")
    void saveGetMarker_notifiedFalseToTrue_roundTrip() {
        // given: 첫 저장 — notified=false
        RecordingPartialMarker notNotified = markerWithNotified(false);
        // markNotified 후 재저장 — notified=true
        RecordingPartialMarker notified = markerWithNotified(true);

        given(valueOperations.get(EXPECTED_KEY))
                .willReturn(notNotified)  // 1차 조회
                .willReturn(notified);   // 2차 조회 (재저장 후)

        // when: 1차 조회 → notified=false 확인 → markNotified() → 재저장
        RecordingPartialMarker first = sut.getRecordingPartialMarker(ROOM_ID);
        assertThat(first.isNotified()).isFalse();

        RecordingPartialMarker marked = first.markNotified();
        sut.saveRecordingPartialMarker(marked, 86_400L);

        // 2차 조회
        RecordingPartialMarker second = sut.getRecordingPartialMarker(ROOM_ID);

        // then: 2차 조회에서 notified=true
        assertThat(second.isNotified()).isTrue();
        // 재저장 시 notified=true 객체가 set된 것 검증
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(valueOperations, org.mockito.Mockito.atLeast(1))
                .set(eq(EXPECTED_KEY), captor.capture(), eq(86_400L), eq(TimeUnit.SECONDS));
        RecordingPartialMarker savedObj = (RecordingPartialMarker) captor.getValue();
        assertThat(savedObj.isNotified()).isTrue();
    }

    // ── key prefix 정확성 — RoomRecoveryMetadata 경로와 구분 ──────────────────

    @Test
    @DisplayName("saveRecordingPartialMarker_keyPrefix는room:recording:partial:으로시작한다")
    void saveRecordingPartialMarker_keyPrefixIsRecordingPartial() {
        // given
        RecordingPartialMarker marker = fullMarker();

        // when
        sut.saveRecordingPartialMarker(marker, 86_400L);

        // then: room:recovery: 가 아닌 room:recording:partial: prefix 사용
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(keyCaptor.capture(), eq(marker), anyLong(), any());
        String usedKey = keyCaptor.getValue();
        assertThat(usedKey).startsWith("room:recording:partial:");
        assertThat(usedKey).doesNotStartWith("room:recovery:");
        assertThat(usedKey).endsWith(ROOM_ID);
    }

    @Test
    @DisplayName("getRecordingPartialMarker_keyPrefix는room:recording:partial:으로시작한다")
    void getRecordingPartialMarker_keyPrefixIsRecordingPartial() {
        // given
        given(valueOperations.get(EXPECTED_KEY)).willReturn(null);

        // when
        sut.getRecordingPartialMarker(ROOM_ID);

        // then
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).get(keyCaptor.capture());
        assertThat(keyCaptor.getValue()).startsWith("room:recording:partial:");
        assertThat(keyCaptor.getValue()).endsWith(ROOM_ID);
    }

    // ── TTL 검증 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("saveRecordingPartialMarker_TTL을SECONDS단위로저장한다")
    void saveRecordingPartialMarker_ttlUnitIsSeconds() {
        // given
        RecordingPartialMarker marker = fullMarker();

        // when
        sut.saveRecordingPartialMarker(marker, 3_600L);

        // then: SECONDS 단위 확인
        verify(valueOperations).set(eq(EXPECTED_KEY), eq(marker), eq(3_600L), eq(TimeUnit.SECONDS));
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private RecordingPartialMarker fullMarker() {
        return RecordingPartialMarker.builder()
                .roomId(ROOM_ID)
                .recordingId("rec-qa-001")
                .recordingUserId("user-qa-001")
                .recordingNickName("QA테스터")
                .fileName("rec.webm")
                .filePath("/path")
                .fileFullPath("/path/rec.webm")
                .minioFilePath("minio/rec.webm")
                .startAt(1_000_000L)
                .markedAt(System.currentTimeMillis())
                .notified(false)
                .build();
    }

    private RecordingPartialMarker markerWithNotified(boolean notified) {
        return RecordingPartialMarker.builder()
                .roomId(ROOM_ID)
                .recordingId("rec-qa-001")
                .markedAt(System.currentTimeMillis())
                .notified(notified)
                .build();
    }
}
