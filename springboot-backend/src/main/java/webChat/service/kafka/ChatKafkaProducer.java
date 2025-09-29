package webChat.service.kafka;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import webChat.model.kafka.KafkaEvent;
import webChat.model.kafka.KafkaRoomEvent;
import webChat.model.kafka.RoomEvent;
import webChat.model.room.ChatRoom;
@Service
public class ChatKafkaProducer {
    private final KafkaTemplate<String, KafkaEvent> kafkaTemplate;

    public ChatKafkaProducer(KafkaTemplate<String, KafkaEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void sendCreateRoomEvent(ChatRoom chatRoom) {
        KafkaRoomEvent kafkaRoomEvent = KafkaRoomEvent.of(chatRoom.getRoomId(), chatRoom.getInstanceId(), RoomEvent.ROOM_CREATE);
        kafkaTemplate.send("room-events", chatRoom.getRoomId(), kafkaRoomEvent);
    }

    public void sendDeleteRoomEvent(ChatRoom chatRoom) {
        KafkaRoomEvent kafkaRoomEvent = KafkaRoomEvent.of(chatRoom.getRoomId(), chatRoom.getInstanceId(), RoomEvent.ROOM_DELETE);
        kafkaTemplate.send("room-events", chatRoom.getRoomId(), kafkaRoomEvent);
    }

    public void sendRoomUserCntEvent(ChatRoom chatRoom) {
        KafkaRoomEvent kafkaRoomEvent = KafkaRoomEvent.of(chatRoom.getRoomId(), chatRoom.getInstanceId(), RoomEvent.ROOM_USER_CNT);
        kafkaTemplate.send("room-events", chatRoom.getRoomId(), kafkaRoomEvent);
    }
}
