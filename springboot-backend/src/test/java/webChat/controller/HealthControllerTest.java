package webChat.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import webChat.service.routing.ReadinessState;
import webChat.service.routing.RoutingInstanceProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class HealthControllerTest {

    @Mock
    private RoutingInstanceProvider instanceProvider;

    @Mock
    private ReadinessState readinessState;

    @InjectMocks
    private HealthController sut;

    @Test
    @DisplayName("readiness 는 shutdown drain 상태면 기동 완료 여부와 무관하게 503 을 반환한다")
    void readiness_shuttingDown이면503을반환한다() {
        // given
        given(instanceProvider.isShuttingDown()).willReturn(true);

        // when
        ResponseEntity<String> result = sut.readiness();

        // then
        assertThat(result.getStatusCode().value()).isEqualTo(503);
        assertThat(result.getBody()).isEqualTo("Server is shutting down");
        // drain 은 기동 완료 신호와 무관하게 우선한다(short-circuit) — readiness 상태를 조회하지 않는다.
        verifyNoInteractions(readinessState);
    }

    @Test
    @DisplayName("readiness 는 종료 중이 아니어도 앱 기동이 미완료면 503 Starting 을 반환한다")
    void readiness_기동미완료면503Starting을반환한다() {
        // given
        given(instanceProvider.isShuttingDown()).willReturn(false);
        given(readinessState.isReady()).willReturn(false);

        // when
        ResponseEntity<String> result = sut.readiness();

        // then
        assertThat(result.getStatusCode().value()).isEqualTo(503);
        assertThat(result.getBody()).isEqualTo("Starting");
    }

    @Test
    @DisplayName("readiness 는 앱 기동 완료 + 비종료면 쿠키 수집 여부와 무관하게 READY 를 반환한다")
    void readiness_기동완료이고비종료면Ready를반환한다() {
        // given
        given(instanceProvider.isShuttingDown()).willReturn(false);
        given(readinessState.isReady()).willReturn(true);

        // when
        ResponseEntity<String> result = sut.readiness();

        // then
        assertThat(result.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(result.getBody()).isEqualTo("READY");
    }
}
