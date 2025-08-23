package webChat.model.kafka;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class KafkaServerEvent {
    private ServerEvent eventType;  // SERVER_STARTED, SERVER_STOPPED
    private String instanceId;
    private long timestamp;

    public static KafkaServerEvent of(ServerEvent eventType, String instanceId, long timestamp){
        return KafkaServerEvent.builder()
                .eventType(eventType)
                .instanceId(instanceId)
                .timestamp(timestamp)
                .build();
    }
}
