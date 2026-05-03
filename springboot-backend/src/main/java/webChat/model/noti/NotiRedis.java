package webChat.model.noti;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@SuperBuilder
public class NotiRedis<T> {
	private String notiId;
	private String type;
	private long userIdx; // user의 idx 저장
	private long sendTime;
	private boolean isRead;
	T noti;
}
