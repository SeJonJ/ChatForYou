package webChat.model.friend;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FriendDto {
    private String userId;
    private String friendId;
    private String nickname;
    private String status;
}
