package webChat.service.kafka;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Slf4j
public class KafkaTest {
//    @MockBean
//    private ServletServerContainerFactoryBean webSocketContainer;
    @Autowired
    private KafkaProducerTest kafkaProducerTest;
    @Autowired
    private KafkaConsumerTest kafkaConsumerTest;

    @Test
    @DisplayName("kafka test")
    void sendAndReceive() throws Exception {
        kafkaConsumerTest.resetLatch();            // Latch 초기화
        kafkaProducerTest.sendMessage("test-topic", "hello"); // 메시지 전송
        boolean ok = kafkaConsumerTest.getLatch()
                .await(10, TimeUnit.SECONDS); // 최대 5초 대기
        assertTrue(ok, "메시지가 소비되지 않았습니다");
    }

}
