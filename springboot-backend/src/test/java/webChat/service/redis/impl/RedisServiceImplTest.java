package webChat.service.redis.impl;

import lombok.extern.slf4j.Slf4j;
import org.apache.coyote.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import webChat.model.login.OauthRedis;
import webChat.model.redis.DataType;
import webChat.service.redis.RedisService;

import java.util.Map;

@SpringBootTest
@Slf4j
class RedisServiceImplTest {

    @MockBean
    private ServletServerContainerFactoryBean webSocketContainer;

    @Autowired
    private RedisService redisService;

    @Test
    @DisplayName("전체 쿠키 확인")
    void getCookieMapping() throws BadRequestException {
        Map<String, String> allInstanceCookies = redisService.getAllInstanceCookies();
        for(Map.Entry<String, String> entry : allInstanceCookies.entrySet()){
            log.info("===================================");
            log.info("instanceId: {}, cookie: {}", entry.getKey(), entry.getValue());
        }
    }
}