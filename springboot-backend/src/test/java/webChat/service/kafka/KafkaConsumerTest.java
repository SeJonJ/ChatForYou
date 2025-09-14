package webChat.service.kafka;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.CountDownLatch;

@Component
@Slf4j
@Getter
@Setter
public class KafkaConsumerTest {
    private CountDownLatch latch = new CountDownLatch(1);
    private String receivedMessage;

    @KafkaListener(topics = "test-topic", groupId = "test-consumer")
    public void listen(String message) {
        this.receivedMessage = message;
        latch.countDown();
        log.info("#################### 받은 메시지: {}", message);
    }

    public void resetLatch() {
        this.latch = new CountDownLatch(1);
    }
}