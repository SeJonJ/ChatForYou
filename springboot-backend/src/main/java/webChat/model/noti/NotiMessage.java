package webChat.model.noti;

import lombok.Getter;

@Getter
public enum NotiMessage {
	FRIEND_REQUEST("$$requestor$$님이 친구 요청을 보냈습니다."),
	FRIEND_ACCEPT("$$responder$$님이 친구를 수락하였습니다."),
	FRIEND_REJECT("$$responder$$님이 친구를 거절하였습니다.")
	;

	private final String msg;

	NotiMessage(String msg) {
		this.msg = msg;
	}
}
