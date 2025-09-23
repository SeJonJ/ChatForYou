package webChat.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import webChat.entity.SocialUser;

public interface SocialUserRepository extends JpaRepository<SocialUser, Long> {
    SocialUser findByEmail(String email);
}
