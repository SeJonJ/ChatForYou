package webChat.model.friend;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class FriendInVo {
    private String userId;
    private String friendId;
    private String nickname;
}
