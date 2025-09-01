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
