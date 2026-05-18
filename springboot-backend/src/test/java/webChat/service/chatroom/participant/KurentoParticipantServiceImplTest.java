package webChat.service.chatroom.participant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.WebSocketSession;
import webChat.repository.kurento.participant.KurentoParticipantRepository;
import webChat.service.chatroom.participant.impl.KurentoParticipantServiceImpl;
import webChat.service.kurento.KurentoUserSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class KurentoParticipantServiceImplTest {

    private static final String ROOM_ID = "room-1";
    private static final String USER_ID = "user-1";
    private static final String SESSION_ID = "session-abc";

    @Mock private KurentoParticipantRepository participantRepository;
    @Mock private KurentoUserSession participant;
    @Mock private WebSocketSession session;
    @Mock private WebSocketSession otherSession;

    @InjectMocks
    private KurentoParticipantServiceImpl participantService;

    @Test
    @DisplayName("isCurrentParticipantSession 은 저장된 세션과 일치하면 true 를 반환한다")
    void isCurrentParticipantSession_세션이일치하면_true를반환한다() {
        // given
        given(session.getId()).willReturn(SESSION_ID);
        given(participant.getSession()).willReturn(session);
        given(participantRepository.getParticipant(ROOM_ID, USER_ID)).willReturn(participant);

        // when & then
        assertThat(participantService.isCurrentParticipantSession(ROOM_ID, USER_ID, session)).isTrue();
    }

    @Test
    @DisplayName("isCurrentParticipantSession 은 저장된 세션과 불일치하면 false 를 반환한다")
    void isCurrentParticipantSession_세션이불일치하면_false를반환한다() {
        // given
        given(session.getId()).willReturn(SESSION_ID);
        given(otherSession.getId()).willReturn("other-session");
        given(participant.getSession()).willReturn(otherSession);
        given(participantRepository.getParticipant(ROOM_ID, USER_ID)).willReturn(participant);

        // when & then
        assertThat(participantService.isCurrentParticipantSession(ROOM_ID, USER_ID, session)).isFalse();
    }

    @Test
    @DisplayName("isCurrentParticipantSession 은 participant 가 null 이면 false 를 반환한다")
    void isCurrentParticipantSession_participant가null이면_false를반환한다() {
        // given
        given(participantRepository.getParticipant(ROOM_ID, USER_ID)).willReturn(null);

        // when & then
        assertThat(participantService.isCurrentParticipantSession(ROOM_ID, USER_ID, session)).isFalse();
    }

    @Test
    @DisplayName("removeSessionMappingIfMatched 는 session 이 null 이면 remove 를 호출하지 않는다")
    void removeSessionMappingIfMatched_session이null이면_remove를호출하지않는다() {
        // when
        participantService.removeSessionMappingIfMatched(null, participant);

        // then
        verify(participantRepository, Mockito.never())
                .removeParticipantSessionMapping(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    @DisplayName("removeSessionMappingIfMatched 는 session 과 participant 가 있으면 repository 제거를 위임한다")
    void removeSessionMappingIfMatched_whenSessionAndParticipantPresent_delegatesRepositoryRemoval() {
        // given
        given(session.getId()).willReturn(SESSION_ID);

        // when
        participantService.removeSessionMappingIfMatched(session, participant);

        // then
        verify(participantRepository).removeParticipantSessionMapping(SESSION_ID, participant);
    }
}
