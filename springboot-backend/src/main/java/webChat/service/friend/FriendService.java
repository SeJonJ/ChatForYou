package webChat.service.friend;

import webChat.model.friend.FriendDto;
import webChat.model.friend.FriendInVo;

import java.util.*;

public interface FriendService {
    List<FriendDto> getFriendList(String userId);
    void addFriend(FriendInVo friendInVo);
    void deleteFriend(String userId, String friendId);
}
