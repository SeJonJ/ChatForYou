package webChat.service.kurento;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kurento.client.Continuation;
import org.kurento.client.GStreamerFilter;
import org.kurento.client.HubPort;
import org.kurento.client.MediaPipeline;
import org.kurento.client.WebRtcEndpoint;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KurentoUserSessionTest {

    @Test
    @DisplayName("close 는 WebSocket close 가 실패해도 Kurento 자원을 먼저 해제한다")
    void close_WebSocketClose실패해도_Kurento자원을먼저해제한다() throws Exception {
        // given
        WebSocketSession session = mock(WebSocketSession.class);
        MediaPipeline pipeline = mock(MediaPipeline.class);
        WebRtcEndpoint outgoingMedia = mock(WebRtcEndpoint.class);
        WebRtcEndpoint incomingMedia = mock(WebRtcEndpoint.class);
        GStreamerFilter textOverlayFilter = mock(GStreamerFilter.class);
        GStreamerFilter compositeScaler = mock(GStreamerFilter.class);
        HubPort compositeHubPort = mock(HubPort.class);

        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("session-1");
        org.mockito.Mockito.doThrow(new IOException("ws close failed")).when(session).close();

        KurentoUserSession userSession = new KurentoUserSession("user-1", "tester", "room-1");
        ReflectionTestUtils.setField(userSession, "session", session);
        ReflectionTestUtils.setField(userSession, "pipeline", pipeline);
        ReflectionTestUtils.setField(userSession, "outgoingMedia", outgoingMedia);
        ReflectionTestUtils.setField(userSession, "textOverlayFilter", textOverlayFilter);
        ReflectionTestUtils.setField(userSession, "compositeScaler", compositeScaler);
        ReflectionTestUtils.setField(userSession, "compositeHubPort", compositeHubPort);
        userSession.getIncomingMedia().put("peer-1", incomingMedia);

        // when & then
        assertThatThrownBy(userSession::close)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("ws close failed");

        InOrder inOrder = inOrder(incomingMedia, textOverlayFilter, compositeScaler, compositeHubPort, outgoingMedia, session);
        inOrder.verify(incomingMedia).release(org.mockito.ArgumentMatchers.<Continuation<Void>>any());
        inOrder.verify(textOverlayFilter).release(org.mockito.ArgumentMatchers.<Continuation<Void>>any());
        inOrder.verify(compositeScaler).release(org.mockito.ArgumentMatchers.<Continuation<Void>>any());
        inOrder.verify(compositeHubPort).release(org.mockito.ArgumentMatchers.<Continuation<Void>>any());
        inOrder.verify(outgoingMedia).release(org.mockito.ArgumentMatchers.<Continuation<Void>>any());
        inOrder.verify(session).close();
    }
}
