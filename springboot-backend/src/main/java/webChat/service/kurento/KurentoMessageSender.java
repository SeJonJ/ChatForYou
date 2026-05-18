package webChat.service.kurento;

import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import webChat.exception.ChatForYouException;
import webChat.exception.ErrorCode;
import webChat.service.chatroom.participant.KurentoParticipantService;

import java.io.IOException;
import java.util.Collection;
import java.util.UUID;

/**
 * Kurento WebRTC 메시지를 전송한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KurentoMessageSender {
    public static final String HANDLED_WS_ERROR_DETAIL = "__ws_error_already_sent__";
    private static final String WS_TRACE_ID_ATTRIBUTE = "wsTraceId";

    private final KurentoParticipantService participantService;

    /**
     * 에러 메시지를 브로드캐스트한 뒤 표준 비즈니스 예외를 발생시킨다.
     *
     * @param roomId 방 ID
     * @param builder 메시지 빌더
     * @param errorCode 표준 에러 코드
     * @param exceptionMessage 예외 메시지 (영문)
     * @throws ChatForYouException 항상 던짐
     */
    public void broadcastErrorAndThrow(String roomId,
                                       KurentoMessageBuilder builder,
                                       ErrorCode errorCode,
                                       String exceptionMessage) {
        JsonObject message = builder.build();
        this.broadcastToRoom(roomId, message);
        log.warn("Broadcasting error to room {}: code={}, message={}",
                roomId, errorCode.getCode(), exceptionMessage);
        throw new ChatForYouException(errorCode, HANDLED_WS_ERROR_DETAIL);
    }

    /**
     * 방 전체에 성공 메시지를 브로드캐스트한다.
     *
     * @param roomId 방 ID
     * @param builder 메시지 빌더
     */
    public void broadcastSuccess(String roomId, KurentoMessageBuilder builder) {
        JsonObject message = builder.build();
        this.broadcastToRoom(roomId, message);
        log.info("Broadcasting success message to room {}: messageId={}",
                roomId, message.get("id").getAsString());
    }

    /**
     * 방 전체에 에러 메시지를 브로드캐스트한다.
     *
     * @param roomId 방 ID
     * @param builder 메시지 빌더
     */
    public void broadcastError(String roomId, KurentoMessageBuilder builder) {
        JsonObject message = builder.build();
        this.broadcastToRoom(roomId, message);
        log.error("Broadcasting error to room {}: {}", roomId, message);
    }

    /**
     * 특정 유저에게 성공 응답 메시지를 전송한다.
     *
     * @param user 대상 유저
     * @param builder 메시지 빌더
     */
    public void sendToUser(KurentoUserSession user, KurentoMessageBuilder builder) {
        try {
            JsonObject message = builder.build();
            user.sendMessage(message);
            log.info("Sending response to user {}: messageId={}",
                    user.getUserId(), message.get("id").getAsString());
        } catch (Exception e) {
            log.error("Failed to send message to user {}: {}", user.getUserId(), e.getMessage());
        }
    }

    /**
     * 특정 유저에게 에러 메시지를 전송한다.
     *
     * @param user 대상 유저
     * @param builder 메시지 빌더
     */
    public void sendErrorToUser(KurentoUserSession user, KurentoMessageBuilder builder) {
        try {
            JsonObject message = builder.build();
            user.sendMessage(message);
            log.error("Sending error to user {}: {}", user.getUserId(), message);
        } catch (Exception e) {
            log.error("Failed to send error message to user {}: {}", user.getUserId(), e.getMessage());
        }
    }

    /**
     * 특정 유저에게 표준 에러 응답을 전송한다.
     */
    public void sendStandardErrorToUser(KurentoUserSession user, ErrorCode errorCode, String detail) {
        if (user == null) {
            return;
        }
        sendErrorToUser(user, buildStandardErrorBuilder(errorCode, detail, resolveTraceId(user.getSession())));
    }

    /**
     * WebSocket 세션에 표준 에러 응답을 전송한다.
     */
    public void sendStandardErrorToSession(WebSocketSession session, ErrorCode errorCode, String detail) {
        if (session == null) {
            return;
        }
        try {
            JsonObject message = buildStandardErrorBuilder(errorCode, detail, resolveTraceId(session)).build();
            synchronized (session) {
                session.sendMessage(new TextMessage(message.toString()));
            }
            log.error("Sending standard error to session {}: {}", session.getId(), message);
        } catch (IOException e) {
            log.error("Failed to send standard error to session {}: {}", session.getId(), e.getMessage());
        }
    }

    /**
     * 방의 모든 참가자에게 메시지를 브로드캐스트한다.
     *
     * @param roomId 방 ID
     * @param message 전송할 메시지
     */
    public void broadcastToRoom(String roomId, JsonObject message) {
        try {
            Collection<KurentoUserSession> participants = participantService.getParticipantList(roomId);
            log.debug("Broadcasting message to {} participants in room {}", participants.size(), roomId);

            for (KurentoUserSession participant : participants) {
                try {
                    participant.sendMessage(message);
                } catch (IOException e) {
                    log.warn("Failed to send message to participant {}: {}",
                            participant.getUserId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to broadcast message to room {}: {}", roomId, e.getMessage());
        }
    }

    /**
     * WebSocket 표준 에러 payload 를 만드는 빌더를 구성한다.
     */
    private KurentoMessageBuilder buildStandardErrorBuilder(ErrorCode errorCode, String detail, String traceId) {
        return KurentoMessageBuilder.websocketError()
                .code(errorCode.getCode())
                .error(errorCode.name())
                .message(errorCode.getMessage())
                .detail(detail)
                .traceId(traceId);
    }

    /**
     * 세션 attribute 나 MDC 에서 traceId 를 찾고, 없으면 새 값을 만든다.
     */
    private String resolveTraceId(WebSocketSession session) {
        if (session != null) {
            Object traceId = session.getAttributes().get(WS_TRACE_ID_ATTRIBUTE);
            if (traceId instanceof String existingTraceId && !existingTraceId.isBlank()) {
                return existingTraceId;
            }

            String mdcTraceId = MDC.get("traceId");
            if (mdcTraceId != null && !mdcTraceId.isBlank()) {
                session.getAttributes().put(WS_TRACE_ID_ATTRIBUTE, mdcTraceId);
                return mdcTraceId;
            }

            String generatedTraceId = "ws-" + UUID.randomUUID();
            session.getAttributes().put(WS_TRACE_ID_ATTRIBUTE, generatedTraceId);
            return generatedTraceId;
        }

        String traceId = MDC.get("traceId");
        return traceId != null ? traceId : "ws-" + UUID.randomUUID();
    }
}
