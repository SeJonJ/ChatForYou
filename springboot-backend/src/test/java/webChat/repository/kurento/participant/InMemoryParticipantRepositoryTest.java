package webChat.repository.kurento.participant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class InMemoryParticipantRepositoryTest {

    @Test
    @DisplayName("동일 사용자 재입장 시 repository 는 새 참가자만 유지하고 close 는 상위 orchestration 에 맡긴다")
    void addParticipant_동일사용자재입장시_새참가자만유지하고기존참가자는자동close하지않는다() {
        // given
        InMemoryParticipantRepository<String, CloseTrackingParticipant> repository = new InMemoryParticipantRepository<>();
        CloseTrackingParticipant previousParticipant = new CloseTrackingParticipant("previous");
        CloseTrackingParticipant currentParticipant = new CloseTrackingParticipant("current");

        repository.addParticipant("room-1", "user-1", previousParticipant);

        // when
        repository.addParticipant("room-1", "user-1", currentParticipant);

        // then
        assertThat(repository.getParticipant("room-1", "user-1")).isSameAs(currentParticipant);
        assertThat(repository.getParticipantCount("room-1")).isEqualTo(1);
        assertThat(previousParticipant.getCloseCount()).isZero();
        assertThat(currentParticipant.getCloseCount()).isZero();
    }

    private static final class CloseTrackingParticipant implements AutoCloseable {
        private final String id;
        private int closeCount;

        private CloseTrackingParticipant(String id) {
            this.id = id;
        }

        @Override
        public void close() {
            closeCount++;
        }

        private int getCloseCount() {
            return closeCount;
        }

        @Override
        public String toString() {
            return id;
        }
    }
}
