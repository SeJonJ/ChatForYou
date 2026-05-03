package webChat.model.kafka;

import lombok.Getter;

@Getter
public enum FriendEvent {
	FRIEND_REQUEST,
	FRIEND_ACCEPT,
	FRIEND_REJECT
}
