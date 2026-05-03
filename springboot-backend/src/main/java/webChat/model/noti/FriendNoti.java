package webChat.model.noti;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@SuperBuilder
public class FriendNoti {
	private long requestor;
	private long responder;
	private String message;
	private String type;
}
