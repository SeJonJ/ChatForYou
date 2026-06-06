package webChat.service.chatroom;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import webChat.model.room.ChatRoom;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@Slf4j
public class SseService {

    // 동시성 관련하여 CopyOnWriteArrayList
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    // 클라이언트가 연결 요청할 때 Emitter 생성
    public SseEmitter createEmitter() {
        SseEmitter emitter = new SseEmitter(60_000L * 15);

        registerEmitter(emitter);

        return emitter;
    }

    // 방 생성 시, 연결된 모든 클라이언트에게 이벤트 전송
    public void sendRoomCreatedEvent(ChatRoom room) {
        sendEventToAll("roomCreated", room);
    }

    // 방 삭제 시 이벤트 전송
    public void sendRoomDeletedEvent(ChatRoom room) {
        sendEventToAll("roomDeleted", room);
    }


    // 핑 관련 이벤트 전송
    @Scheduled(fixedDelay = 10_000L * 7) // 70초 keep-alive ping (10_000ms × 7 = 70,000ms)
    public void sendPingToClients() {
        sendEventToAll("ping", "keep-alive");
    }

    // 방 인원수 변경 시 이벤트 전송
    public void sendRoomUserCntEvent(ChatRoom room) {
        sendEventToAll("changeUserCnt", room);
    }

    public void sendChangeRoomSettingEvent(ChatRoom room) {
        sendEventToAll("changeRoomSetting", room);
    }

    private void sendEventToAll(String eventName, Object data) {
        List<SseEmitter> deadEmitters = new ArrayList<>();

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(data));
            } catch (Exception e) {
                handleEmitterSendFailure(emitter, eventName, e, deadEmitters);
            }
        }
        emitters.removeAll(deadEmitters);
    }

    private void registerEmitter(SseEmitter emitter) {
        emitters.add(emitter);
        emitter.onCompletion(() -> cleanupEmitter(emitter, "completion", null));
        emitter.onTimeout(() -> cleanupEmitter(emitter, "timeout", null));
        emitter.onError(error -> cleanupEmitter(emitter, "error", error));
    }

    private void handleEmitterSendFailure(SseEmitter emitter, String eventName, Exception e, List<SseEmitter> deadEmitters) {
        deadEmitters.add(emitter);
        log.warn("SSE emitter send failed and will be removed: eventName={}, message={}", eventName, e.getMessage());
        safeCompleteWithError(emitter, e);
    }

    private void cleanupEmitter(SseEmitter emitter, String reason, Throwable error) {
        emitters.remove(emitter);
        if (error == null) {
            log.debug("SSE emitter removed: reason={}", reason);
            return;
        }
        log.warn("SSE emitter removed: reason={}, message={}", reason, error.getMessage());
    }

    private void safeCompleteWithError(SseEmitter emitter, Exception e) {
        try {
            emitter.completeWithError(e);
        } catch (Exception completeError) {
            log.debug("SSE emitter completeWithError ignored: message={}", completeError.getMessage());
        }
    }
}
