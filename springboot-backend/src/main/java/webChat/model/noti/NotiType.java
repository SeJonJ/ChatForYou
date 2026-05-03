package webChat.model.noti;

import lombok.Getter;

@Getter
public enum NotiType {
	FRIEND_REQUEST("friend_request"),
	FRIEND_ACCEPT("friend_accept"),
	FRIEND_REJECT("friend_reject")
	;

	private final String msg;

	NotiType(String msg) {
		this.msg = msg;
	}
}
