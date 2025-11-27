package webChat.model.friend;

import lombok.*;
import webChat.entity.Friend;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(access = AccessLevel.PRIVATE)
public class FriendDto {
    private String userId;
    private String friendId;
    private String nickname;
    private String status;

    public static FriendDto of (Friend friend){
        return FriendDto.builder()
                .userId(friend.getUserId())
                .friendId(friend.getFriendId())
                .nickname(friend.getNickname())
                .status(friend.getStatus())
                .build();
    }
}
