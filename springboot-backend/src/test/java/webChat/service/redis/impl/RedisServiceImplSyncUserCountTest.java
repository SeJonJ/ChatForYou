package webChat.service.redis.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dengliming.redismodule.redisearch.client.RediSearchClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import webChat.model.chat.ChatType;
import webChat.model.room.KurentoRoom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

/**
 * RedisServiceImpl.syncUserCount authoritative 동기화 단위 테스트.
 *
 * ±1 산술(이전 increment/decrement)을 대체하여, 전달받은 실제 참가자 수를 그대로 userCount 에
 * 기록하는지 검증한다. 권위 소스가 참가자 맵 size(0 이상)이므로 baseline 값과 무관하게 덮어쓴다.
 * Redis 연결 없이 POJO write 경로만 단위로 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class RedisServiceImplSyncUserCountTest {

    @Mock
    private RedisTemplate<String, Object> masterTemplate;

    @Mock
    private RedisTemplate<String, Object> slaveTemplate;

    @Mock
    private RediSearchClient rediSearchClient;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private RedisServiceImpl redisService;

    @BeforeEach
    void setUp() {
        redisService = new RedisServiceImpl(masterTemplate, slaveTemplate, new ObjectMapper(), rediSearchClient);
        // syncUserCount 는 updateChatRoom 으로 Redis Hash 에 writeback 한다 — 연결 없이 통과시킨다.
        lenient().when(masterTemplate.opsForHash()).thenReturn(hashOperations);
    }

    @Test
    @DisplayName("실제 참가자 수 0 을 전달하면 userCount 를 0 으로 동기화한다")
    void syncUserCount_actualCount0이면_0으로기록한다() {
        // given
        KurentoRoom room = newRoom(1);

        // when
        redisService.syncUserCount(room, 0);

        // then
        assertThat(room.getUserCount()).isZero();
    }

    @Test
    @DisplayName("baseline 과 무관하게 전달받은 실제 참가자 수로 userCount 를 덮어쓴다")
    void syncUserCount_baseline무관하게_실제수로덮어쓴다() {
        // given — baseline 이 어긋나 있어도(예: 0) 실제 수로 교정한다
        KurentoRoom room = newRoom(0);

        // when
        redisService.syncUserCount(room, 2);

        // then
        assertThat(room.getUserCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("재연결로 baseline 이 과대(3)여도 실제 참가자 수(1)로 교정한다")
    void syncUserCount_baseline과대여도_실제수로교정한다() {
        // given
        KurentoRoom room = newRoom(3);

        // when
        redisService.syncUserCount(room, 1);

        // then
        assertThat(room.getUserCount()).isEqualTo(1);
    }

    private KurentoRoom newRoom(int userCount) {
        return new KurentoRoom("room-1", "room", "creator", null, false, userCount, 4, ChatType.MSG, "instance-1");
    }
}
