package webChat.service.friend.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import webChat.entity.Friend;
import webChat.model.friend.FriendDto;
import webChat.model.friend.FriendInVo;
import webChat.repository.FriendsRepository;
import webChat.service.friend.FriendService;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class FriendServiceImpl implements FriendService {

    private final FriendsRepository friendsRepository;

    @Override
    public List<FriendDto> getFriendList(String userId) {
        List<FriendDto> result = new ArrayList<>();

        List<Friend> friendList = friendsRepository.findByUserId(userId);
        for (Friend friend : friendList) {
            result.add(FriendDto.of(friend));
        }
        return result;
    }

    @Override
    public void addFriend(FriendInVo friendRequest) {

    }

    @Override
    public void deleteFriend(String userId, String friendId) {
        Friend friend = friendsRepository.findByUserIdAndFriendId(userId, friendId);
        if (friend == null) {
            throw new RuntimeException("Not exist friend");
        }
        friendsRepository.delete(friend);
    }

    @Override
    public void updateFriend(FriendInVo friendRequest, String userId) {
        Friend friend = friendsRepository.findByUserIdAndFriendId(userId, friendRequest.getFriendId());
        if (friend == null) {
            throw new RuntimeException("Not exist friend");
        }
        friend.setNickname(friendRequest.getNickname());
        friendsRepository.save(friend);
    }
}
