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
public class KafkaFriendEvent extends KafkaEvent {
	private String userId;
	private String friendId;
	private String instanceId;
	private FriendEvent friendEvent;

	public static KafkaFriendEvent of(String userId, String friendId, String instanceId, FriendEvent friendEvent) {
		return KafkaFriendEvent.builder()
				.userId(userId)
				.friendId(friendId)
				.instanceId(instanceId)
				.friendEvent(friendEvent)
				.build();
	}
}
