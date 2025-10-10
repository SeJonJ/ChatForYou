package webChat.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import webChat.entity.SocialUser;

public interface SocialUserRepository extends JpaRepository<SocialUser, Long> {
    SocialUser findByEmail(String email);
    @Query("SELECT CASE WHEN COUNT(u) > 0 THEN true ELSE false END FROM SocialUser u WHERE u.nickname = ?1")
    boolean existsByNickname(String nickName);
}
