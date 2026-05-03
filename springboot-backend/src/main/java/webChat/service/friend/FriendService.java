package webChat.service.friend;

import webChat.model.friend.FriendDto;
import webChat.model.friend.FriendInVo;

import java.util.*;

public interface FriendService {
    List<FriendDto> getFriendList(String userId);
    void addFriend(FriendInVo friendInVo, String userId) throws Exception;
    void deleteFriend(String userId, String friendId);
    void updateFriend(FriendInVo friendInVo, String userId);
    void acceptFriend(FriendInVo friendInVo, String userId);
    void rejectFriend(FriendInVo friendInVo, String userId);
}
