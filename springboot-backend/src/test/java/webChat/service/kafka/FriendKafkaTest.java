package webChat.service.kafka;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Slf4j
public class FriendKafkaTest {
//	@MockBean
//	private ServletServerContainerFactoryBean webSocketContainner;
	@Autowired
	private FriendKafkaConsumerTest  friendKafkaConsumerTest;
	@Autowired
	private FriendKafkaProducerTest friendKafkaProducerTest;

	@Test
	void sendAndReceive() throws Exception {
		friendKafkaConsumerTest.resetLatch();
		friendKafkaProducerTest.sendMessage("friend-test-topic", "friend friend~");
		boolean ok = friendKafkaConsumerTest.getLatch()
				.await(10, TimeUnit.SECONDS);
		assertTrue(ok, "메시지 소비 안됨");
	}
}
