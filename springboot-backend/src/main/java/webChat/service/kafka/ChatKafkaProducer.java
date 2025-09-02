package webChat.service.kafka;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import webChat.model.room.ChatRoom;

import java.util.HashMap;
import java.util.Map;

@Service
public class ChatKafkaProducer {
    private final KafkaTemplate<String, Map<String, Object>> kafkaTemplate;

    public ChatKafkaProducer(KafkaTemplate<String, Map<String, Object>> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void sendCreateRoomEvent(ChatRoom chatRoom) {
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("event", "createRoom");
        eventData.put("roomId", chatRoom.getRoomId());

        kafkaTemplate.send("room-events", chatRoom.getRoomId(), eventData);
    }

    public void sendDeleteRoomEvent(ChatRoom chatRoom) {
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("event", "deleteRoom");
        eventData.put("roomId", chatRoom.getRoomId());

        kafkaTemplate.send("room-events", chatRoom.getRoomId(), eventData);
    }
}
