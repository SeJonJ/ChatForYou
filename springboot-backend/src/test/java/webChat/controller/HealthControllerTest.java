package webChat.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import webChat.service.routing.CookieCheckEvent;
import webChat.service.routing.RoutingInstanceProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class HealthControllerTest {

    @Mock
    private RoutingInstanceProvider instanceProvider;

    @Mock
    private CookieCheckEvent cookieCheckEvent;

    @InjectMocks
    private HealthController sut;

    @Test
    @DisplayName("readiness 는 shutdown drain 상태면 쿠키 수집 여부와 무관하게 503 을 반환한다")
    void readiness_shuttingDown이면503을반환한다() {
        // given
        given(instanceProvider.isShuttingDown()).willReturn(true);

        // when
        ResponseEntity<String> result = sut.readiness();

        // then
        assertThat(result.getStatusCode().value()).isEqualTo(503);
        assertThat(result.getBody()).isEqualTo("Server is shutting down");
    }

    @Test
    @DisplayName("readiness 는 쿠키 수집이 완료된 정상 상태면 READY 를 반환한다")
    void readiness_쿠키수집완료시Ready를반환한다() {
        // given
        given(instanceProvider.isShuttingDown()).willReturn(false);
        given(cookieCheckEvent.isCookieCollected()).willReturn(true);

        // when
        ResponseEntity<String> result = sut.readiness();

        // then
        assertThat(result.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(result.getBody()).isEqualTo("READY");
    }
}
