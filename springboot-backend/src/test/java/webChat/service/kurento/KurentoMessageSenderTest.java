package webChat.service.kurento;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import webChat.exception.ErrorCode;
import webChat.service.chatroom.participant.KurentoParticipantService;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KurentoMessageSenderTest {

    @Test
    @DisplayName("broadcastErrorAndThrow 는 전달받은 ErrorCode 를 유지한 ChatForYouException 을 던진다")
    void broadcastErrorAndThrow_preservesProvidedErrorCode() {
        // given
        KurentoMessageSender sender = new KurentoMessageSender(participantService);
        when(participantService.getParticipantList("room-1")).thenReturn(java.util.List.of());

        // when
        org.assertj.core.api.ThrowableAssert.ThrowingCallable action = () -> sender.broadcastErrorAndThrow(
                "room-1",
                KurentoMessageBuilder.recordingFileExistsError(),
                ErrorCode.RECORDING_FILE_EXISTS,
                "recording file already exists");

        // then
        assertThatThrownBy(action)
                .isInstanceOf(webChat.exception.ChatForYouException.class)
                .extracting(throwable -> ((webChat.exception.ChatForYouException) throwable).getErrorCode())
                .isEqualTo(ErrorCode.RECORDING_FILE_EXISTS);
    }

    @Mock
    private KurentoParticipantService participantService;

    @Mock
    private WebSocketSession webSocketSession;

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("MDC traceId 가 없으면 WebSocket 표준 에러 응답에 ws- prefix traceId 를 넣는다")
    void sendStandardErrorToSession_whenMdcMissing_usesWsTraceIdFallback() throws Exception {
        // given
        KurentoMessageSender sender = new KurentoMessageSender(participantService);
        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        HashMap<String, Object> sessionAttributes = new HashMap<>();
        when(webSocketSession.getAttributes()).thenReturn(sessionAttributes);

        // when
        sender.sendStandardErrorToSession(webSocketSession, ErrorCode.INTERNAL_SERVER_ERROR, "detail");

        // then
        verify(webSocketSession).sendMessage(messageCaptor.capture());
        String payload = messageCaptor.getValue().getPayload();

        assertThat(payload).contains("\"id\":\"error\"");
        assertThat(payload).contains("\"code\":\"C003\"");
        assertThat(payload).contains("\"detail\":\"detail\"");
        assertThat(payload).contains("\"traceId\":\"ws-");
        assertThat(sessionAttributes).containsKey("wsTraceId");
    }

    @Test
    @DisplayName("MDC traceId 가 있으면 WebSocket 표준 에러 응답이 해당 traceId 를 그대로 사용한다")
    void sendStandardErrorToSession_whenMdcExists_usesCurrentTraceId() throws Exception {
        // given
        MDC.put("traceId", "req-test-trace");
        KurentoMessageSender sender = new KurentoMessageSender(participantService);
        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        HashMap<String, Object> sessionAttributes = new HashMap<>();
        when(webSocketSession.getAttributes()).thenReturn(sessionAttributes);

        // when
        sender.sendStandardErrorToSession(webSocketSession, ErrorCode.ROOM_NOT_FOUND, null);

        // then
        verify(webSocketSession).sendMessage(messageCaptor.capture());
        String payload = messageCaptor.getValue().getPayload();

        assertThat(payload).contains("\"id\":\"error\"");
        assertThat(payload).contains("\"code\":\"R001\"");
        assertThat(payload).contains("\"traceId\":\"req-test-trace\"");
        assertThat(sessionAttributes).containsEntry("wsTraceId", "req-test-trace");
    }

    @Test
    @DisplayName("세션에 저장된 WebSocket traceId 가 있으면 이후 표준 에러 응답에서도 동일 값을 재사용한다")
    void sendStandardErrorToSession_whenSessionTraceIdExists_reusesStoredTraceId() throws Exception {
        KurentoMessageSender sender = new KurentoMessageSender(participantService);
        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        HashMap<String, Object> sessionAttributes = new HashMap<>();
        sessionAttributes.put("wsTraceId", "ws-stable-trace");
        when(webSocketSession.getAttributes()).thenReturn(sessionAttributes);

        sender.sendStandardErrorToSession(webSocketSession, ErrorCode.INTERNAL_SERVER_ERROR, null);

        verify(webSocketSession).sendMessage(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getPayload()).contains("\"traceId\":\"ws-stable-trace\"");
    }
}
