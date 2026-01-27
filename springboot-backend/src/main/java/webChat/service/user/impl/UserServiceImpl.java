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
import webChat.service.redis.RedisService;
import webChat.service.user.UserService;
import webChat.utils.StringUtil;

import java.util.Optional;

import static webChat.model.redis.RedisKeyPrefix.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
    private final SocialUserRepository socialUserRepository;
    private final RedisService redisService;

    @Override
    public UserDto getUserInfo(OauthRedis oauthRedis) throws Exception {
        Optional<SocialUser> socialUser = Optional.ofNullable(socialUserRepository.findByEmail(oauthRedis.getEmail()));

        if(socialUser.isEmpty()){
            throw new BadRequestException("Not exist user !!!");
        }

        return UserDto.of(socialUser.get(), oauthRedis);

    }

    // TODO 전반적인 예외 처리 수정 필요
    @Override
    public OauthRedis getValidatedOauthUser(String userId) throws BadRequestException {
        SocialUser user = socialUserRepository.findByEmail(userId);
        if (user == null) {
            throw new BadRequestException("Not exist user !!!");
        }

        OauthRedis oauthRedis = redisService.getRedisDataByDataType(String.valueOf(user.getIdx()), DataType.SOCIAL_USER, OauthRedis.class);
        if (oauthRedis == null) {
            throw new BadRequestException("Not exist oauth redis !!!");
        }

        return oauthRedis;
    }
}
