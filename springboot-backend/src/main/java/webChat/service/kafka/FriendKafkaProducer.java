package webChat.service.kafka;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import webChat.model.friend.FriendInVo;
import webChat.model.kafka.FriendEvent;
import webChat.model.kafka.KafkaEvent;
import webChat.model.kafka.KafkaFriendEvent;
import webChat.service.routing.InstanceProvider;

@Service
public class FriendKafkaProducer {
	private final KafkaTemplate<String, KafkaEvent> kafkaTemplate;

	@Autowired
	private InstanceProvider instanceProvider;

	public FriendKafkaProducer(KafkaTemplate<String, KafkaEvent> kafkaTemplate) {
		this.kafkaTemplate = kafkaTemplate;
	}

	public void sendFriendRequest(FriendInVo friendRequest) {
		// instanceId 가 뭔지??
		instanceProvider.initInstanceId();
		String instanceId = instanceProvider.getInstanceId();
		KafkaFriendEvent kafkaFriendEvent = KafkaFriendEvent.of(friendRequest.getUserId(), friendRequest.getFriendId(), instanceId, FriendEvent.FRIEND_REQUEST);
		kafkaTemplate.send("friend-events", kafkaFriendEvent);
	}
}
