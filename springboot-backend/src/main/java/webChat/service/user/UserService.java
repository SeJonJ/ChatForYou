package webChat.service.user;

import org.apache.coyote.BadRequestException;
import webChat.model.login.OauthRedis;
import webChat.model.redis.DataType;
import webChat.model.user.UserDto;

public interface UserService {
    UserDto getUserInfo(OauthRedis oauthRedis) throws Exception;
    OauthRedis getValidatedOauthUser(String userId) throws BadRequestException;
}
