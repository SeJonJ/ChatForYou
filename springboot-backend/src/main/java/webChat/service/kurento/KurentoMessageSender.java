package webChat.service.kurento;

import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.coyote.BadRequestException;
import org.springframework.stereotype.Component;
import webChat.service.chatroom.participant.KurentoParticipantService;

import java.io.IOException;
import java.util.Collection;

/**
 * Kurento WebRTC 메시지 전송을 위한 클래스
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class KurentoMessageSender {
    private final KurentoParticipantService participantService;

    // 에러 메시지 (브로드캐스트)

    /**
     * 에러 메시지 브로드캐스트 후 BadRequestException 던지기
     *
     * @param roomId 방 ID
     * @param builder 메시지 빌더
     * @param exceptionMessage 예외 메시지 (영문)
     * @throws BadRequestException 항상 던짐
     */
    public void broadcastErrorAndThrow(String roomId,
                                       KurentoMessageBuilder builder,
                                       String exceptionMessage) throws BadRequestException {
        JsonObject message = builder.build();
        this.broadcastToRoom(roomId, message);
        log.warn("Broadcasting error to room {}: {}", roomId, exceptionMessage);
        throw new BadRequestException(exceptionMessage);
    }

    // 성공 메시지 (브로드캐스트)

    /**
     * 성공 메시지 브로드캐스트 (방 전체)
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
     * 에러 메시지 브로드캐스트 (방 전체, 예외 없음)
     *
     * @param roomId 방 ID
     * @param builder 메시지 빌더
     */
    public void broadcastError(String roomId, KurentoMessageBuilder builder) {
        JsonObject message = builder.build();
        this.broadcastToRoom(roomId, message);
        log.error("Broadcasting error to room {}: {}", roomId, message);
    }

    // 개별 메시지 (특정 유저)

    /**
     * 특정 유저에게 성공 응답 메시지 전송
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
     * 특정 유저에게 에러 메시지 전송
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
     * 방의 모든 참가자에게 메시지 브로드캐스트
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
                    log.error("Failed to send message to participant {}: {}",
                            participant.getUserId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to broadcast message to room {}: {}", roomId, e.getMessage());
        }
    }
}
