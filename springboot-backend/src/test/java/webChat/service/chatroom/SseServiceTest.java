package webChat.service.chatroom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import webChat.model.room.ChatRoom;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SseServiceTest {

    @Test
    @DisplayName("SSE 전송 실패 emitter 는 목록에서 제거한다")
    void sendRoomUserCntEvent_whenEmitterSendFails_removesDeadEmitter() throws Exception {
        // given
        SseService sseService = new SseService();
        SseEmitter failingEmitter = new SseEmitter() {
            @Override
            public synchronized void send(SseEventBuilder builder) throws IOException {
                throw new IOException("Broken pipe");
            }
        };
        SseEmitter healthyEmitter = new SseEmitter();

        List<SseEmitter> emitters = getEmitters(sseService);
        emitters.add(failingEmitter);
        emitters.add(healthyEmitter);

        // when
        sseService.sendRoomUserCntEvent(new ChatRoom());

        // then
        assertThat(emitters).containsExactly(healthyEmitter);
    }

    @SuppressWarnings("unchecked")
    private List<SseEmitter> getEmitters(SseService sseService) throws Exception {
        Field field = SseService.class.getDeclaredField("emitters");
        field.setAccessible(true);
        return (List<SseEmitter>) field.get(sseService);
    }
}
