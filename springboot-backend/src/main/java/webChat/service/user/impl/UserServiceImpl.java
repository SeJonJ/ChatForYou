package webChat.service.user.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import webChat.entity.SocialUser;
import webChat.exception.ChatForYouException;
import webChat.exception.ErrorCode;
import webChat.model.login.OauthRedis;
import webChat.model.redis.DataType;
import webChat.model.user.UserDto;
import webChat.repository.SocialUserRepository;
import webChat.service.redis.RedisService;
import webChat.service.user.UserService;

import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserServiceImpl implements UserService {
    private final SocialUserRepository socialUserRepository;
    private final RedisService redisService;

    /**
     * 로그인 사용자 정보를 조회해 프론트 전달용 DTO로 변환한다.
     *
     * @param oauthRedis OAuth 로그인 세션 정보
     * @return 사용자 DTO
     */
    @Override
    public UserDto getUserInfo(OauthRedis oauthRedis) {
        Optional<SocialUser> socialUser = Optional.ofNullable(socialUserRepository.findByEmail(oauthRedis.getEmail()));

        if(socialUser.isEmpty()){
            throw new ChatForYouException(ErrorCode.USER_NOT_FOUND);
        }

        return UserDto.of(socialUser.get(), oauthRedis);

    }

    /**
     * 소셜 사용자와 Redis 세션이 모두 유효한지 확인한다.
     *
     * @param userId 사용자 이메일
     * @return 검증된 OAuth 세션 정보
     */
    @Override
    public OauthRedis getValidatedOauthUser(String userId) {
        SocialUser user = socialUserRepository.findByEmail(userId);
        if (user == null) {
            throw new ChatForYouException(ErrorCode.USER_NOT_FOUND);
        }

        OauthRedis oauthRedis = redisService.getRedisDataByDataType(String.valueOf(user.getIdx()), DataType.SOCIAL_USER, OauthRedis.class);
        if (oauthRedis == null) {
            throw new ChatForYouException(ErrorCode.USER_NOT_FOUND);
        }

        return oauthRedis;
    }
}
