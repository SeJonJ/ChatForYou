package webChat.repository.kurento.participant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.WebSocketSession;
import webChat.service.kurento.KurentoUserSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * KurentoParticipantRepository 중복 세션 교체 시나리오 테스트
 */
@ExtendWith(MockitoExtension.class)
class KurentoParticipantRepositoryTest {

    private static final String ROOM_ID = "room-1";
    private static final String USER_ID = "user-1";

    @Mock private KurentoUserSession previousParticipant;
    @Mock private KurentoUserSession newParticipant;
    @Mock private WebSocketSession previousSession;
    @Mock private WebSocketSession newSession;

    @Test
    @DisplayName("addParticipant 에서 기존 세션이 있으면 이전 sessionId 매핑을 제거하고 새 세션 매핑으로 교체한다")
    void addParticipant_기존세션존재시_sessionId매핑이새세션으로교체된다() {
        // given
        given(previousParticipant.getSession()).willReturn(previousSession);
        given(previousSession.getId()).willReturn("session-prev");
        given(newParticipant.getSession()).willReturn(newSession);
        given(newSession.getId()).willReturn("session-new");

        KurentoParticipantRepository repository = new KurentoParticipantRepository();
        repository.addParticipant(ROOM_ID, USER_ID, previousParticipant);

        // when: 동일 userId 로 새 참가자 등록
        repository.addParticipant(ROOM_ID, USER_ID, newParticipant);

        // then: 새 세션으로 조회 가능
        assertThat(repository.getParticipantBySessionId("session-new")).isSameAs(newParticipant);
        // 이전 세션 ID 로는 조회 불가 (매핑 제거됨)
        assertThat(repository.getParticipantBySessionId("session-prev")).isNull();
    }

    @Test
    @DisplayName("addParticipant 에서 participant 가 null 이면 IllegalArgumentException 을 던진다")
    void addParticipant_participant가null이면_IllegalArgumentException을던진다() {
        // given
        KurentoParticipantRepository repository = new KurentoParticipantRepository();

        // when & then
        assertThatThrownBy(() -> repository.addParticipant(ROOM_ID, USER_ID, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    @Test
    @DisplayName("addParticipant 에서 WebSocket 세션이 null 이면 IllegalArgumentException 을 던진다")
    void addParticipant_WebSocket세션이null이면_IllegalArgumentException을던진다() {
        // given
        given(newParticipant.getSession()).willReturn(null);
        KurentoParticipantRepository repository = new KurentoParticipantRepository();

        // when & then
        assertThatThrownBy(() -> repository.addParticipant(ROOM_ID, USER_ID, newParticipant))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    @Test
    @DisplayName("removeParticipant 는 제거된 참가자의 sessionId 매핑도 함께 제거한다")
    void removeParticipant_제거시_sessionId매핑도함께제거된다() {
        // given
        given(previousParticipant.getSession()).willReturn(previousSession);
        given(previousSession.getId()).willReturn("session-prev");

        KurentoParticipantRepository repository = new KurentoParticipantRepository();
        repository.addParticipant(ROOM_ID, USER_ID, previousParticipant);

        // when
        repository.removeParticipant(ROOM_ID, USER_ID);

        // then
        assertThat(repository.getParticipantBySessionId("session-prev")).isNull();
        assertThat(repository.getParticipant(ROOM_ID, USER_ID)).isNull();
    }

    @Test
    @DisplayName("removeRoom 은 방 참가자들의 sessionId 역색인까지 함께 정리한다")
    void removeRoom_방제거시_sessionId역색인까지함께정리된다() {
        // given
        WebSocketSession anotherSession = org.mockito.Mockito.mock(WebSocketSession.class);
        KurentoUserSession anotherParticipant = org.mockito.Mockito.mock(KurentoUserSession.class);
        given(previousParticipant.getSession()).willReturn(previousSession);
        given(previousSession.getId()).willReturn("session-prev");
        given(anotherParticipant.getSession()).willReturn(anotherSession);
        given(anotherSession.getId()).willReturn("session-another");

        KurentoParticipantRepository repository = new KurentoParticipantRepository();
        repository.addParticipant(ROOM_ID, USER_ID, previousParticipant);
        repository.addParticipant(ROOM_ID, "user-2", anotherParticipant);

        // when
        repository.removeRoom(ROOM_ID);

        // then
        assertThat(repository.getParticipantBySessionId("session-prev")).isNull();
        assertThat(repository.getParticipantBySessionId("session-another")).isNull();
        assertThat(repository.getParticipants(ROOM_ID)).isEmpty();
    }

    @Test
    @DisplayName("removeParticipantSessionMapping 은 값이 일치하는 경우에만 제거한다 (ConcurrentHashMap.remove 의미론)")
    void removeParticipantSessionMapping_값불일치시_제거하지않는다() {
        // given
        given(previousParticipant.getSession()).willReturn(previousSession);
        given(previousSession.getId()).willReturn("session-abc");
        given(newParticipant.getSession()).willReturn(newSession);
        given(newSession.getId()).willReturn("session-new");

        KurentoParticipantRepository repository = new KurentoParticipantRepository();
        repository.addParticipant(ROOM_ID, USER_ID, previousParticipant);

        // 새 참가자를 등록하면 session-abc 매핑이 previousParticipant 에서 newParticipant 로 교체됨
        repository.addParticipant(ROOM_ID, USER_ID, newParticipant);

        // when: 이미 교체된 이전 세션 매핑을 다시 제거 시도 (stale close 시뮬레이션)
        repository.removeParticipantSessionMapping("session-abc", previousParticipant);

        // then: 새 세션 매핑은 여전히 살아있어야 함
        assertThat(repository.getParticipantBySessionId("session-new")).isSameAs(newParticipant);
    }
}
