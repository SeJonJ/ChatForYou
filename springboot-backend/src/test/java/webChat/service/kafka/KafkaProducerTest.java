package webChat.service.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaProducerTest {
    private final KafkaTemplate<String, String> kafkaTemplate;

    public void sendMessage(String topic, String message) {
        log.info("Sending message: {}", message);
        kafkaTemplate.send(topic, message);
    }
    public void sendMessage(String topic, String message, String key) {
        log.info("Sending message: {}", message);
        kafkaTemplate.send(topic, key, message);
    }
    public void sendMessage(String topic, String message, String key, Integer partition) {
        log.info("Sending message: {}", message);
    }
}
