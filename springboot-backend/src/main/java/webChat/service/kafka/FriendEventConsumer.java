package webChat.service.kafka;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import webChat.entity.Friend;
import webChat.entity.SocialUser;
import webChat.model.kafka.FriendEvent;
import webChat.model.kafka.KafkaEvent;
import webChat.model.kafka.KafkaFriendEvent;
import webChat.model.login.OauthRedis;
import webChat.model.noti.FriendNoti;
import webChat.model.noti.NotiMessage;
import webChat.model.noti.NotiRedis;
import webChat.model.noti.NotiType;
import webChat.repository.SocialUserRepository;
import webChat.service.chatroom.SseService;
import webChat.service.friend.FriendService;
import webChat.service.redis.impl.RedisServiceImpl;

import java.util.Calendar;
import java.util.List;
import java.util.UUID;

@Component
@Slf4j
@AllArgsConstructor
public class FriendEventConsumer {
	private final SseService sseService;
	private final RedisServiceImpl redisService;
	private final SocialUserRepository socialUserRepository;

	@KafkaListener(
			topics = "friend-events",
			containerFactory = "kafkaFriendEventListenerContainerFactory",
			groupId = "room-event-group-#{T(java.util.UUID).randomUUID().toString().split(\"-\")[0]}" // 인스턴스별 고유 groupId
	)
	public void listen(ConsumerRecord<String, KafkaEvent> record) {
		log.info("[FriendEventConsumer] ======== Friend event listener start. ");
		KafkaFriendEvent kafkaFriendEvent = (KafkaFriendEvent) record.value();
		FriendEvent event = kafkaFriendEvent.getFriendEvent();
		String userId = kafkaFriendEvent.getUserId();
		String friendId = kafkaFriendEvent.getFriendId();
		SocialUser user = socialUserRepository.findByEmail(userId);
		SocialUser friend = socialUserRepository.findByEmail(friendId);

		switch (event) {
			// 요청
			case FRIEND_REQUEST -> {
				log.info("===========friend request==========");

				// 요청을 보낸 친구에게 sse 전송?
				redisService.getNotiList(friend.getIdx());

				List<NotiRedis> notiList = null;
				// 친구에게 알림 요청 (noti정보를 넘겨줘야함)
				sseService.sendFriendRequestEvent(friend, notiList);
			}
			// 거절
			case FRIEND_REJECT -> {
				log.info("===========friend reject==========");
			}
		}

	}
}
