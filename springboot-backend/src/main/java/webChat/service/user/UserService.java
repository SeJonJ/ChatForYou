package webChat.service.user;

import webChat.model.login.OauthRedis;
import webChat.model.redis.DataType;
import webChat.model.user.UserDto;

public interface UserService {
    UserDto getUserInfo(OauthRedis oauthRedis) throws Exception;
    <T> T getFriendRedisInfo(String userId, Class<T> clazz) throws Exception;
}
