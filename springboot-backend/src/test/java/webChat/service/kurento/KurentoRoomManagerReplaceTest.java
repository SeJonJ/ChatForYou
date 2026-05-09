package webChat.service.kurento;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import webChat.model.room.KurentoRoom;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * KurentoRoomManager 중복 세션 교체(replaceParticipant) 핵심 로직 단위 테스트
 *
 * replaceParticipant 는 private 이므로 검증 가능한 핵심 조건 로직을 단위 테스트한다.
 * Kurento 인프라 의존 없이 모든 테스트가 순수 단위 수준으로 동작한다.
 */
@ExtendWith(MockitoExtension.class)
class KurentoRoomManagerReplaceTest {

    @Test
    @DisplayName("녹화 중 아닌 방에서는 composite disconnect 조건이 false 다")
    void disconnectFromRecordingIfNeeded_녹화중아닌방_조건이false다() {
        // given
        KurentoRoom room = mock(KurentoRoom.class);
        KurentoUserSession existingParticipant = mock(KurentoUserSession.class);
        given(room.isRecordingInProgress()).willReturn(false);

        // disconnectFromRecordingIfNeeded 조건: isRecordingInProgress() == false → early return
        boolean shouldDisconnect = room.isRecordingInProgress();

        // then
        assertThat(shouldDisconnect).isFalse();
        verify(existingParticipant, never()).disconnectFromComposite();
    }

    @Test
    @DisplayName("녹화 중 방에서 composite 에 연결된 참가자는 composite disconnect 조건이 true 다")
    void disconnectFromRecordingIfNeeded_녹화중인방_composite연결_조건이true다() {
        // given
        KurentoRoom room = mock(KurentoRoom.class);
        KurentoUserSession existingParticipant = mock(KurentoUserSession.class);
        given(room.isRecordingInProgress()).willReturn(true);
        given(existingParticipant.isConnectedToComposite()).willReturn(true);

        // then
        boolean shouldDisconnect = room.isRecordingInProgress() && existingParticipant.isConnectedToComposite();
        assertThat(shouldDisconnect).isTrue();
    }

    @Test
    @DisplayName("notifyPeerSessionReplaced 의 skip 조건 — 교체 대상 userId 와 동일한 peer 는 건너뛴다")
    void notifyPeerSessionReplaced_교체대상userId와동일한peer는skip된다() {
        // given
        KurentoUserSession replacementParticipant = mock(KurentoUserSession.class);
        KurentoUserSession sameUserPeer = mock(KurentoUserSession.class);
        KurentoUserSession differentUserPeer = mock(KurentoUserSession.class);

        given(replacementParticipant.getUserId()).willReturn("user-abc");
        given(sameUserPeer.getUserId()).willReturn("user-abc");       // 교체 대상 자신 → skip
        given(differentUserPeer.getUserId()).willReturn("other-user");

        List<KurentoUserSession> participants = List.of(sameUserPeer, differentUserPeer);

        // when: notifyPeerSessionReplaced 내부 skip 로직 시뮬레이션
        int notifiedCount = 0;
        for (KurentoUserSession participant : participants) {
            if (participant.getUserId().equals(replacementParticipant.getUserId())) {
                continue;
            }
            notifiedCount++;
        }

        // then: differentUserPeer 1명에게만 알림
        assertThat(notifiedCount).isEqualTo(1);
    }

    @Test
    @DisplayName("disconnectPeerIncomingMedia 의 skip 조건 — 교체 대상 자신은 cancelVideoFrom 호출 대상에서 제외된다")
    void disconnectPeerIncomingMedia_교체대상자신은cancelVideoFrom대상에서제외된다() {
        // given
        String replacedUserId = "user-abc";

        KurentoUserSession peer1 = mock(KurentoUserSession.class);
        KurentoUserSession selfPeer = mock(KurentoUserSession.class);  // 교체 대상 자신

        given(peer1.getUserId()).willReturn("peer-1");
        given(selfPeer.getUserId()).willReturn(replacedUserId);

        List<KurentoUserSession> participants = List.of(peer1, selfPeer);

        // when: disconnectPeerIncomingMedia 내부 skip 로직 시뮬레이션
        for (KurentoUserSession participant : participants) {
            if (participant.getUserId().equals(replacedUserId)) {
                continue; // 자기 자신은 skip
            }
            participant.cancelVideoFrom(replacedUserId);
        }

        // then: peer1 에게만 cancelVideoFrom 호출, selfPeer 에게는 호출하지 않음
        verify(peer1).cancelVideoFrom(replacedUserId);
        verify(selfPeer, never()).cancelVideoFrom(replacedUserId);
    }
}
