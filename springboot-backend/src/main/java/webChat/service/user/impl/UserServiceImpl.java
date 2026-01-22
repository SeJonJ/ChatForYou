package webChat.service.user.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.coyote.BadRequestException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import webChat.entity.SocialUser;
import webChat.model.login.OauthRedis;
import webChat.model.redis.DataType;
import webChat.model.user.UserDto;
import webChat.repository.SocialUserRepository;
import webChat.service.user.UserService;

import java.util.Optional;

import static webChat.model.redis.RedisKeyPrefix.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
    private final SocialUserRepository socialUserRepository;
    private final RedisTemplate<String, Object> slaveTemplate;

    @Override
    public UserDto getUserInfo(OauthRedis oauthRedis) throws Exception {
        Optional<SocialUser> socialUser = Optional.ofNullable(socialUserRepository.findByEmail(oauthRedis.getEmail()));

        if(socialUser == null || socialUser.isEmpty()){
            throw new BadRequestException("Not exist user !!!");
        }

        return UserDto.of(socialUser.get(), oauthRedis);

    }

    @Override
    public <T> T getFriendRedisInfo(String userId, Class<T> clazz) throws Exception {
        SocialUser user = socialUserRepository.findByEmail(userId);
        if (user == null) {
            throw new RuntimeException("Not exist user !!!");
        }

        String redisKey = OAUTH_PREFIX.getPrefix() + user.getIdx();
        return clazz.cast(slaveTemplate.opsForHash().get(redisKey, DataType.SOCIAL_USER.getType()));
    }
}
