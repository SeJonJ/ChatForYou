package webChat.service.kafka;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import webChat.model.kafka.KafkaServerEvent;
import webChat.model.kafka.ServerEvent;
import webChat.support.ExternalTest;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Slf4j
@ExternalTest
public class KafkaTest {
    @MockBean
    private ServletServerContainerFactoryBean webSocketContainer;

    @Autowired
    private KafkaProducerTest kafkaProducerTest;
    @Autowired
    private KafkaConsumerTest kafkaConsumerTest;

    @Test
    @DisplayName("kafka server event test")
    void sendAndReceive() throws Exception {
        kafkaConsumerTest.resetLatch();            // Latch 초기화
        kafkaProducerTest.sendMessage("test-topic", "hello"); // 메시지 전송
        boolean ok = kafkaConsumerTest.getLatch()
                .await(10, TimeUnit.SECONDS); // 최대 5초 대기
        assertTrue(ok, "메시지가 소비되지 않았습니다");

        KafkaServerEvent event = assertInstanceOf(KafkaServerEvent.class, kafkaConsumerTest.getReceivedMessage());
        assertEquals(ServerEvent.SERVER_COOKIE_REQUEST, event.getEventType());
        assertEquals("test-instance-id", event.getInstanceId());
    }

}
