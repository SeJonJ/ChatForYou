package webChat.service.redis.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dengliming.redismodule.redisearch.client.RediSearchClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import webChat.model.room.recovery.RecoveryStatus;
import webChat.model.room.recovery.RoomRecoveryMetadata;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RedisServiceImplRoomRecoveryTest {

    private static final String ROOM_ID = "room-1";
    private static final String INSTANCE_ID = "instance-1";

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

    @Test
    @DisplayName("tryAcquireRoomClaimLock_whenRedisSetIfAbsentSucceeds_returnsTrue")
    void tryAcquireRoomClaimLock_whenRedisSetIfAbsentSucceeds_returnsTrue() {
        // given
        given(valueOperations.setIfAbsent("room:claim-lock:" + ROOM_ID, INSTANCE_ID, 30_000L, TimeUnit.MILLISECONDS))
                .willReturn(true);

        // when
        boolean acquired = sut.tryAcquireRoomClaimLock(ROOM_ID, INSTANCE_ID, 30_000L);

        // then
        assertThat(acquired).isTrue();
    }

    @Test
    @DisplayName("releaseRoomClaimLock_whenValueMatches_usesCompareAndDeleteScript")
    void releaseRoomClaimLock_whenValueMatches_usesCompareAndDeleteScript() {
        // given
        given(masterTemplate.execute(any(), eq(Collections.singletonList("room:claim-lock:" + ROOM_ID)), eq(INSTANCE_ID)))
                .willReturn(1L);

        // when
        boolean released = sut.releaseRoomClaimLock(ROOM_ID, INSTANCE_ID);

        // then
        assertThat(released).isTrue();
        verify(masterTemplate).execute(any(), eq(Collections.singletonList("room:claim-lock:" + ROOM_ID)), eq(INSTANCE_ID));
    }

    @Test
    @DisplayName("releaseRoomClaimLock_whenScriptReturnsZero_returnsFalse")
    void releaseRoomClaimLock_whenScriptReturnsZero_returnsFalse() {
        // given
        given(masterTemplate.execute(any(), eq(Collections.singletonList("room:claim-lock:" + ROOM_ID)), eq(INSTANCE_ID)))
                .willReturn(0L);

        // when
        boolean released = sut.releaseRoomClaimLock(ROOM_ID, INSTANCE_ID);

        // then
        assertThat(released).isFalse();
    }

    @Test
    @DisplayName("releaseRoomClaimLock_whenScriptReturnsNull_returnsFalse")
    void releaseRoomClaimLock_whenScriptReturnsNull_returnsFalse() {
        // given
        given(masterTemplate.execute(any(), eq(Collections.singletonList("room:claim-lock:" + ROOM_ID)), eq(INSTANCE_ID)))
                .willReturn(null);

        // when
        boolean released = sut.releaseRoomClaimLock(ROOM_ID, INSTANCE_ID);

        // then
        assertThat(released).isFalse();
    }

    @Test
    @DisplayName("saveGetDeleteRoomRecoveryMetadata_usesRecoveryPrefix")
    void saveGetDeleteRoomRecoveryMetadata_usesRecoveryPrefix() {
        // given
        RoomRecoveryMetadata metadata = RoomRecoveryMetadata.builder()
                .roomId(ROOM_ID)
                .previousInstanceId(INSTANCE_ID)
                .createdAt(1L)
                .expiresAt(2L)
                .reason("PRE_SHUTDOWN")
                .status(RecoveryStatus.CANDIDATE)
                .build();
        given(valueOperations.get("room:recovery:" + ROOM_ID)).willReturn(metadata);

        // when
        sut.saveRoomRecoveryMetadata(metadata, 180L);
        RoomRecoveryMetadata found = sut.getRoomRecoveryMetadata(ROOM_ID);
        sut.deleteRoomRecoveryMetadata(ROOM_ID);

        // then
        assertThat(found).isSameAs(metadata);
        verify(valueOperations).set("room:recovery:" + ROOM_ID, metadata, 180L, TimeUnit.SECONDS);
        verify(valueOperations).get("room:recovery:" + ROOM_ID);
        verify(masterTemplate).delete("room:recovery:" + ROOM_ID);
    }
}
