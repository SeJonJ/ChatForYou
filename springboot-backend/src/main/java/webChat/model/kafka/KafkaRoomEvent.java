package webChat.model.kafka;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
public class KafkaRoomEvent extends KafkaEvent {
    private String roomId;
    private String instanceId;
    private RoomEvent roomEvent;

    public static KafkaRoomEvent of(String roomId, String instanceId, RoomEvent roomEvent) {
        return KafkaRoomEvent.builder()
                .roomId(roomId)
                .instanceId(instanceId)
                .roomEvent(roomEvent)
                .build();
    }
}
