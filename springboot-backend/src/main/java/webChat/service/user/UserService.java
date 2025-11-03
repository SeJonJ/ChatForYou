package webChat.service.user;

import webChat.model.login.OauthRedis;
import webChat.model.user.UserDto;

public interface UserService {
    UserDto getUserInfo(OauthRedis oauthRedis) throws Exception;
}
