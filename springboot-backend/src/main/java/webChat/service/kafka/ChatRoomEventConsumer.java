package webChat.service.kafka;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.coyote.BadRequestException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import webChat.model.redis.DataType;
import webChat.model.room.ChatRoom;
import webChat.service.chatroom.SseService;
import webChat.service.redis.impl.RedisServiceImpl;

import java.util.Map;

@Component
@Slf4j
@AllArgsConstructor
public class ChatRoomEventConsumer {
    private final SseService sseService;
    private final RedisServiceImpl redisService;

    @KafkaListener(
            topics = "room-events",
            containerFactory = "kafkaRoomEventListenerContainerFactory",
            groupId = "room-event-group-#{T(java.util.UUID).randomUUID().toString().split(\"-\")[0]}" // 인스턴스별 고유 groupId
    )
    public void listen(ConsumerRecord<String, Map<String, Object>> record) throws BadRequestException {
        Map<String, Object> message = record.value();
        String eventKey = (String) message.get("event");
        String roomId = (String) message.get("roomId");
        ChatRoom chatRoom = redisService.getRedisDataByDataType(roomId, DataType.CHATROOM, ChatRoom.class);

        switch (eventKey) {
            // 새로운 방 생성 시 모든 클라이언트에 이벤트 전송
            case "createRoom" -> {
                log.info("===========create==========");
                sseService.sendRoomCreatedEvent(chatRoom);
            }
            // 방 삭제 시 모든 클라이언트에 이벤트 전송
            case "deleteRoom" -> {
                log.info("===========delete==========");
                sseService.sendRoomDeletedEvent(chatRoom);
            }
            // 방 인원 수 변경 시에 모든 클라이언트에 이벤트 전송
            case "roomUserCnt" -> {
                log.info("===========roomUserCnt==========");
                sseService.sendRoomUserCntEvent(chatRoom);
            }
        }
    }
}
