package webChat.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import webChat.entity.Friend;

public interface FriendsRepository extends JpaRepository<Friend, Long> {
    Friend findByIdx(int idx);
}
