package webChat.service.chatroom;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import webChat.model.room.ChatRoom;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class SseService {

    // 동시성 관련하여 CopyOnWriteArrayList
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    // 클라이언트가 연결 요청할 때 Emitter 생성
    public SseEmitter createEmitter() {
        SseEmitter emitter = new SseEmitter(60_000L * 15);

        // 리스트에 추가
        emitters.add(emitter);

        // 연결 종료 시 자동 제거
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));

        return emitter;
    }

    // 방 생성 시, 연결된 모든 클라이언트에게 이벤트 전송
    public void sendRoomCreatedEvent(ChatRoom room) {
        List<SseEmitter> deadEmitters = new CopyOnWriteArrayList<>();

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("roomCreated")
                        .data(room));
            } catch (IOException e) {
                deadEmitters.add(emitter);
            }
        }

        emitters.removeAll(deadEmitters);
    }

    // 방 삭제 시 이벤트 전송
    public void sendRoomDeletedEvent(ChatRoom room) {
        List<SseEmitter> deadEmitters = new CopyOnWriteArrayList<>();

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("roomDeleted")
                        .data(room));
            } catch (IOException e) {
                deadEmitters.add(emitter);
            }
        }

        emitters.removeAll(deadEmitters);
    }


    // 핑 관련 이벤트 전송
    @Scheduled(fixedDelay = 10_000L * 7) // 7분
    public void sendPingToClients() {
        List<SseEmitter> deadEmitters = new CopyOnWriteArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("ping")
                        .data("keep-alive"));
            } catch (IOException e) {
                deadEmitters.add(emitter);
            }
        }
        emitters.removeAll(deadEmitters);
    }

    // 방 인원수 변경 시 이벤트 전송
    public void sendRoomUserCntEvent(ChatRoom room) {
        List<SseEmitter> deadEmitters = new CopyOnWriteArrayList<>();

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("changeUserCnt")
                        .data(room));
            } catch (IOException e) {
                deadEmitters.add(emitter);
            }
        }
        emitters.removeAll(deadEmitters);
    }

    public void sendChangeRoomSettingEvent(ChatRoom room) {
        List<SseEmitter> deadEmitters = new CopyOnWriteArrayList<>();

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("changeRoomSetting")
                        .data(room));
            } catch (IOException e) {
                deadEmitters.add(emitter);
            }
        }
        emitters.removeAll(deadEmitters);
    }
}
