package webChat.model.kafka;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
public class KafkaServerEvent extends KafkaEvent {
    private ServerEvent eventType;  // SERVER_STARTED, SERVER_STOPPED
    private String instanceId;

    public static KafkaServerEvent of(ServerEvent eventType, String instanceId, long publishedAt){
        return KafkaServerEvent.builder()
                .eventType(eventType)
                .instanceId(instanceId)
                .publishedAt(publishedAt)
                .build();
    }
}
