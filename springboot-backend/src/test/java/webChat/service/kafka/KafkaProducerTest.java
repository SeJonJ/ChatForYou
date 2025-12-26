package webChat.service.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import webChat.model.kafka.KafkaEvent;
import webChat.model.kafka.KafkaServerEvent;
import webChat.model.kafka.ServerEvent;

@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaProducerTest {
    private final KafkaTemplate<String, KafkaEvent> kafkaTemplate;

    public void sendMessage(String topic, String message) {
        log.info("Sending message: {}", message);
        KafkaEvent event = KafkaServerEvent.builder()
                .eventType(ServerEvent.SERVER_COOKIE_REQUEST)
                .instanceId("test-instance-id")
                .build();

        kafkaTemplate.send(topic, event);
    }
}
