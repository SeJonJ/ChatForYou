package webChat.service.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReadinessStateTest {

    private final ReadinessState sut = new ReadinessState();

    @Test
    @DisplayName("초기 상태는 ready 가 아니다")
    void 초기상태는ready가아니다() {
        assertThat(sut.isReady()).isFalse();
    }

    @Test
    @DisplayName("ApplicationReadyEvent 처리 후 ready 가 된다")
    void applicationReady이후ready가된다() {
        // when
        sut.onApplicationReady();

        // then
        assertThat(sut.isReady()).isTrue();
    }

    @Test
    @DisplayName("ApplicationReadyEvent 가 중복 발생해도 ready 상태가 유지된다(멱등)")
    void applicationReady중복발생시멱등이다() {
        // when
        sut.onApplicationReady();
        sut.onApplicationReady();

        // then
        assertThat(sut.isReady()).isTrue();
    }
}
