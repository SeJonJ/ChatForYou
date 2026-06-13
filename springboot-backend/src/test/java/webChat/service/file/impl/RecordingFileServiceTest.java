package webChat.service.file.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import webChat.config.MinioConfig;
import webChat.exception.ChatForYouException;
import webChat.exception.ErrorCode;
import webChat.model.redis.DataType;
import webChat.model.room.KurentoRoom;
import webChat.service.redis.RedisService;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;
import static webChat.service.file.fixture.RecordingFileFixture.*;

/**
 * RecordingFileService.getObject() 단위 테스트.
 *
 * expiresAt 차단 제거 정책 변경(FR-6)의 핵심 회귀를 검증한다.
 * super.getObject()는 MinioClient 의존이므로 spy + doReturn 으로 실제 I/O 없이 위임 도달만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class RecordingFileServiceTest {

    @Mock
    private MinioConfig minioConfig;

    @Mock
    private RedisService redisService;

    private RecordingFileService sut;

    @BeforeEach
    void setUp() {
        // spy: super.getObject() 위임을 stub 처리하여 실제 MinIO I/O 없이 검증.
        // lenient: 예외 케이스(UT-3/4/4b)에서 super.getObject()가 호출되지 않으므로
        //          UnnecessaryStubbingException 방지를 위해 lenient() 적용
        sut = spy(new RecordingFileService(minioConfig, redisService));
        lenient().doReturn(ResponseEntity.ok(new byte[0]))
                .when(sut)
                .getObject(anyString(), anyString());
    }

    @Test
    @DisplayName("UT-1: expiresAt 이 과거여도 방 생존 + 녹화 메타 존재 시 예외 없이 super.getObject 위임")
    void getObject_expiresAtPast_withLiveRoom_delegatesToSuperGetObject() {
        // given — expiresAt < now (정책 변경 핵심: 과거 시각이어도 차단하지 않아야 함)
        KurentoRoom room = roomWithRecordingFile(EXPIRES_AT_PAST);
        given(redisService.getRedisDataByDataType(ROOM_ID, DataType.CHATROOM, KurentoRoom.class))
                .willReturn(room);

        // when & then — INTERNAL_SERVER_ERROR 미발생, super.getObject 위임 도달
        assertThatCode(() -> sut.getObject(ROOM_ID, FILE_NAME, FILE_DIR))
                .doesNotThrowAnyException();

        verify(sut).getObject(FILE_NAME, FILE_DIR);
    }

    @Test
    @DisplayName("UT-2: expiresAt 이 미래여도 방 생존 + 녹화 메타 존재 시 정상 위임")
    void getObject_expiresAtFuture_withLiveRoom_delegatesToSuperGetObject() {
        // given — expiresAt > now (기존 정상 케이스)
        KurentoRoom room = roomWithRecordingFile(EXPIRES_AT_FUTURE);
        given(redisService.getRedisDataByDataType(ROOM_ID, DataType.CHATROOM, KurentoRoom.class))
                .willReturn(room);

        // when & then
        assertThatCode(() -> sut.getObject(ROOM_ID, FILE_NAME, FILE_DIR))
                .doesNotThrowAnyException();

        verify(sut).getObject(FILE_NAME, FILE_DIR);
    }

    @Test
    @DisplayName("UT-3: Redis 방 없음(null) 시 ROOM_NOT_FOUND(R001) 예외 유지")
    void getObject_roomNotFound_throwsRoomNotFoundException() {
        // given — Redis 에 방이 없는 상태 (방 종료 후 자연 차단 시나리오)
        given(redisService.getRedisDataByDataType(ROOM_ID, DataType.CHATROOM, KurentoRoom.class))
                .willReturn(null);

        // when & then
        assertThatThrownBy(() -> sut.getObject(ROOM_ID, FILE_NAME, FILE_DIR))
                .isInstanceOf(ChatForYouException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ROOM_NOT_FOUND);

        verify(sut, never()).getObject(FILE_NAME, FILE_DIR);
    }

    @Test
    @DisplayName("UT-4: recordingInfo 가 null 이면 INTERNAL_SERVER_ERROR(C003) 예외 유지")
    void getObject_recordingInfoNull_throwsInternalServerError() {
        // given — 방은 존재하나 녹화 메타 없음
        KurentoRoom room = roomWithNullRecordingInfo();
        given(redisService.getRedisDataByDataType(ROOM_ID, DataType.CHATROOM, KurentoRoom.class))
                .willReturn(room);

        // when & then
        assertThatThrownBy(() -> sut.getObject(ROOM_ID, FILE_NAME, FILE_DIR))
                .isInstanceOf(ChatForYouException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR);

        verify(sut, never()).getObject(FILE_NAME, FILE_DIR);
    }

    @Test
    @DisplayName("UT-4b: recordingFile 이 null 이면 INTERNAL_SERVER_ERROR(C003) 예외 유지")
    void getObject_recordingFileNull_throwsInternalServerError() {
        // given — recordingInfo 는 있으나 recordingFile 이 null
        KurentoRoom room = roomWithNullRecordingFile();
        given(redisService.getRedisDataByDataType(ROOM_ID, DataType.CHATROOM, KurentoRoom.class))
                .willReturn(room);

        // when & then
        assertThatThrownBy(() -> sut.getObject(ROOM_ID, FILE_NAME, FILE_DIR))
                .isInstanceOf(ChatForYouException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR);

        verify(sut, never()).getObject(FILE_NAME, FILE_DIR);
    }
}
