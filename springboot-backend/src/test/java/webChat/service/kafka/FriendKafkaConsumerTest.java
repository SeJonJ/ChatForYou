package webChat.service.kafka;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import webChat.model.kafka.KafkaEvent;
import webChat.model.kafka.KafkaFriendEvent;

import java.util.concurrent.CountDownLatch;

@Component
@Slf4j
@Getter
@Setter
public class FriendKafkaConsumerTest {
	private CountDownLatch latch = new CountDownLatch(1);
	private String receivedMessage;

	@KafkaListener(topics = "friend-test-topic", groupId = "friend-test-consumer")
	public void listen(ConsumerRecord<String, KafkaEvent> record) {
		KafkaFriendEvent kafkaFriendEvent = (KafkaFriendEvent) record.value();

		this.receivedMessage = "message";
		latch.countDown();
		log.info("################ 친구 받은 메시지 : {}", "message");
	}

	public void resetLatch() {
		this.latch = new CountDownLatch(1);
	}
}
