package webChat.service.user.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import webChat.entity.SocialUser;
import webChat.model.login.OauthRedis;
import webChat.model.user.UserDto;
import webChat.repository.SocialUserRepository;
import webChat.service.user.UserService;

import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
    private final SocialUserRepository socialUserRepository;

    @Override
    public UserDto getUserInfo(OauthRedis oauthRedis) {
        Optional<SocialUser> socialUser = Optional.ofNullable(socialUserRepository.findByEmail(oauthRedis.getEmail()));

        if(socialUser.isEmpty()){
            // TODO 예외처리
        }

        return UserDto.of(socialUser.get(), oauthRedis);

    }
}
