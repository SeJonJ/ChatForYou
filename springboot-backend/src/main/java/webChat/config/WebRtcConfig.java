package webChat.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import webChat.service.kurento.KurentoHandler;

@Configuration
@EnableWebSocket // 웹 소켓에 대해 자동 설정
@RequiredArgsConstructor
public class WebRtcConfig implements WebSocketConfigurer {

    @Value("${server.rtc.async-timeout:0}")
    private long rtcAsyncTimeOut;

    @Value("${server.rtc.session-idle-timeout:0}")
    private long rtcSessionIdleTimeout;

    // kurento 를 다루기 위한 핸들러
    private final KurentoHandler kurentoHandler;

    // signal 로 요청이 왔을 때 아래의 WebSockerHandler 가 동작하도록 registry 에 설정
    // 요청은 클라이언트 접속, close, 메시지 발송 등에 대해 특정 메서드를 호출한다
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(kurentoHandler, "/chatforyou/api/signal")
                .setAllowedOrigins("*");
    }

    // 웹 소켓에서 rtc 통신을 위한 최대 텍스트 버퍼와 바이너리 버퍼 사이즈를 설정한다?
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(65536);
        container.setMaxBinaryMessageBufferSize(65536);
        container.setAsyncSendTimeout(rtcAsyncTimeOut); // 비동기 전송 타임아웃 설정
        container.setMaxSessionIdleTimeout(rtcSessionIdleTimeout); // 유휴 세션 타임아웃 설정
        return container;
    }

}