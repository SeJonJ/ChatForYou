package webChat.service.friend.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.coyote.BadRequestException;
import org.springframework.stereotype.Service;
import webChat.entity.Friend;
import webChat.entity.SocialUser;
import webChat.model.friend.FriendDto;
import webChat.model.friend.FriendInVo;
import webChat.model.noti.FriendNoti;
import webChat.model.noti.NotiMessage;
import webChat.model.noti.NotiRedis;
import webChat.model.noti.NotiType;
import webChat.repository.FriendsRepository;
import webChat.repository.SocialUserRepository;
import webChat.service.friend.FriendService;
import webChat.service.kafka.FriendKafkaProducer;
import webChat.service.redis.RedisService;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class FriendServiceImpl implements FriendService {

    private final FriendsRepository friendsRepository;
    private final FriendKafkaProducer friendKafkaProducer;
    private final SocialUserRepository socialUserRepository;
    private final RedisService redisService;

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
    public void addFriend(FriendInVo friendRequest, String userId) throws Exception {
        // 친구 있는지 조회
        SocialUser user = socialUserRepository.findByEmail(userId);
        Friend friend = friendsRepository.findByUserIdAndFriendId(friendRequest.getUserId(), friendRequest.getFriendId());

        // 친구따라 있는지 없는지 분기
        if (friend == null) {
            // 친구 디비에 요청 상태로 저장
            String nickname = friendRequest.getFriendId().split("@")[0];
            long now = System.currentTimeMillis();
            Friend requestInfo = Friend.builder()
                    .userId(friendRequest.getUserId())
                    .friendId(friendRequest.getFriendId())
                    .nickname(nickname)
                    .createDate(now)
                    .updateDate(now)
                    .status("0")
                    .build();
           // friendsRepository.save(requestInfo);


            // 친구 요청 noti redis 저장
            String notiId = UUID.randomUUID().toString().split("-")[0];

            // (친구 요청이 최초인경우)친구 요청 시 알림을 위한 redis insert
            FriendNoti friendNoti = FriendNoti.builder()
                    .requestor(user.getIdx())
                    .responder(friend.getIdx())
                    .message(NotiMessage.FRIEND_REQUEST.getMsg())
                    .type(NotiType.FRIEND_REQUEST.getMsg())
                    .build();

            NotiRedis notiRedis = NotiRedis.<FriendNoti>builder()
                    .notiId(notiId)
                    .type(NotiType.FRIEND_REQUEST.getMsg())
                    .userIdx(friend.getIdx())
                    .sendTime(now)
                    .isRead(false)
                    .noti(friendNoti)
                    .build();

            redisService.insertFriendRequestInfo(notiRedis);
            // 친구 실시간 요청
            friendKafkaProducer.sendFriendRequest(friendRequest);



        } else {
            throw new BadRequestException("Exist friend.");
        }
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

    @Override
    public void acceptFriend(FriendInVo friendInVo, String userId) {

    }

    @Override
    public void rejectFriend(FriendInVo friendInVo, String userId) {

    }
}
