package webChat.friend;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import webChat.entity.Friend;
import webChat.repository.FriendsRepository;

@SpringBootTest
@Slf4j
public class FriendTest {

//    @MockBean
//    private ServletServerContainerFactoryBean webSocketContainer;
    @Autowired
    FriendsRepository friendsRepository;
    @Test
    @DisplayName("frind delete test")
    public void deleteFriend() {
        Friend friend = friendsRepository.findByUserIdAndFriendId("dlwhsktm@gmail.com", "test@gmail.com");
        log.info("--------------- Friend log --------------");
        log.info("userid : {}, friendId : {}, nickname : {}", friend.getUserId(), friend.getFriendId(), friend.getNickname());
    }
}
