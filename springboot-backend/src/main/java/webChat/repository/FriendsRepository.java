package webChat.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import webChat.entity.Friend;

import java.util.List;
public interface FriendsRepository extends JpaRepository<Friend, Long> {
    Friend findByIdx(int idx);
    List<Friend> findByUserId(String userId);
    Friend findByUserIdAndFriendId(String userId, String friendId);
}
