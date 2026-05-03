package webChat.service.kafka;

import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import webChat.model.kafka.FriendEvent;
import webChat.model.kafka.KafkaEvent;
import webChat.model.kafka.KafkaFriendEvent;

@Service
@RequiredArgsConstructor
@Slf4j
public class FriendKafkaProducerTest {
	@Autowired
	private KafkaTemplate<String, String> kafkaTemplate;

	public void sendMessage(String topic, String message) {
		log.info("Friend producer sending message : {}", message);
		KafkaFriendEvent kafkaFriendEvent = KafkaFriendEvent.of("dlwhsktm@gmail.com", "dlwhsktm2@gamil.com", "", FriendEvent.FRIEND_REQUEST);
		kafkaTemplate.send(topic, "kafkaFriendEvent");
	}
}
