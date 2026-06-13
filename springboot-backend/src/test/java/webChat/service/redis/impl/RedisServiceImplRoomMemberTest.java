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
import org.springframework.data.redis.core.SetOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * RedisServiceImpl 의 방 멤버십 ledger Set 연산 단위 테스트.
 *
 * 녹화 다운로드 권한 검증의 기준 데이터인 room:members:{roomId} Set 의
 * 키 정합과 master/slave 템플릿 라우팅을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class RedisServiceImplRoomMemberTest {

    private static final String ROOM_ID = "room-1234";
    private static final String EMAIL = "tester@chatforyou.io";
    private static final String EXPECTED_KEY = "room:members:" + ROOM_ID;

    @Mock
    private RedisTemplate<String, Object> masterTemplate;

    @Mock
    private RedisTemplate<String, Object> slaveTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private RediSearchClient rediSearchClient;

    @Mock
    private SetOperations<String, Object> setOperations;

    private RedisServiceImpl sut;

    @BeforeEach
    void setUp() {
        sut = new RedisServiceImpl(masterTemplate, slaveTemplate, objectMapper, rediSearchClient);
    }

    @Test
    @DisplayName("UT-1: addRoomMember 는 master 템플릿 Set 에 ROOM_MEMBERS_PREFIX 키로 email 을 추가한다")
    void addRoomMember_addsEmailToMasterSetWithPrefixedKey() {
        // given
        given(masterTemplate.opsForSet()).willReturn(setOperations);

        // when
        sut.addRoomMember(ROOM_ID, EMAIL);

        // then
        verify(setOperations).add(EXPECTED_KEY, EMAIL);
    }

    @Test
    @DisplayName("UT-2: isRoomMember 는 멤버 존재 시 slave 템플릿 조회 결과 true 를 반환한다")
    void isRoomMember_memberExists_returnsTrue() {
        // given
        given(slaveTemplate.opsForSet()).willReturn(setOperations);
        given(setOperations.isMember(EXPECTED_KEY, EMAIL)).willReturn(Boolean.TRUE);

        // when
        boolean result = sut.isRoomMember(ROOM_ID, EMAIL);

        // then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("UT-3: isRoomMember 는 비멤버일 때 false 를 반환한다")
    void isRoomMember_notMember_returnsFalse() {
        // given
        given(slaveTemplate.opsForSet()).willReturn(setOperations);
        given(setOperations.isMember(EXPECTED_KEY, EMAIL)).willReturn(Boolean.FALSE);

        // when
        boolean result = sut.isRoomMember(ROOM_ID, EMAIL);

        // then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("UT-4: isRoomMember 는 키 미존재로 null 반환 시 false 를 반환한다")
    void isRoomMember_nullResult_returnsFalse() {
        // given — 키가 없으면 SISMEMBER 결과가 null 일 수 있다
        given(slaveTemplate.opsForSet()).willReturn(setOperations);
        given(setOperations.isMember(EXPECTED_KEY, EMAIL)).willReturn(null);

        // when
        boolean result = sut.isRoomMember(ROOM_ID, EMAIL);

        // then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("UT-5: deleteRoomMembers 는 master 템플릿에서 ROOM_MEMBERS_PREFIX 키를 삭제한다")
    void deleteRoomMembers_deletesPrefixedKeyOnMaster() {
        // when
        sut.deleteRoomMembers(ROOM_ID);

        // then
        verify(masterTemplate).delete(EXPECTED_KEY);
    }
}
