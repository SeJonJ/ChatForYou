package webChat.config;

import io.github.dengliming.redismodule.redisearch.client.RediSearchClient;
import io.github.dengliming.redismodule.redisearch.index.schema.Field;
import io.github.dengliming.redismodule.redisearch.index.schema.FieldType;
import io.github.dengliming.redismodule.redisearch.index.schema.Schema;
import io.github.dengliming.redismodule.redisearch.index.schema.TextField;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import webChat.model.redis.RedisIndex;
import webChat.utils.StringUtil;

/**
 * redis 를 master - slave 구조로 사용
 * master : 쓰기 작업 수행
 * slave : 읽기 작업 수행
 */
@Configuration
@EnableCaching
@Slf4j
public class RedisConfig {
    @Value("${spring.data.redis.host}")
    private String host;

    @Value("${spring.data.redis.master.port}")
    private String masterPort;

    @Value("${spring.data.redis.slave.port}")
    private String slavePort;

    @Value("${spring.data.redis.password}")
    private String password;

    private RediSearchClient redisSearchClient;

    // redis 의 설정값을 세팅하기 위한 postConstruct
    // 환경변수로 url 과 port 가 들어오면 해당 값을 을 사용하고, 아니면 properties 에 정의 된 값을 사용
    @PostConstruct
    private void initRedis(){
        String envRedisUrl = System.getenv("REDIS_URL");
        if(!StringUtil.isNullOrEmpty(envRedisUrl)){
            host = envRedisUrl;
        }

        String envRedisMasterPort = System.getenv("REDIS_MASTER_PORT");
        if(!StringUtil.isNullOrEmpty(envRedisMasterPort)){
            masterPort = envRedisMasterPort;
        }

        String envRedisSlavePort = System.getenv("REDIS_SLAVE_PORT");
        if(!StringUtil.isNullOrEmpty(envRedisSlavePort)){
            slavePort = envRedisSlavePort;
        }

        String envRedisPassword = System.getenv("REDIS_PASSWORD");
        if(!StringUtil.isNullOrEmpty(envRedisPassword)){
            password = envRedisPassword;
        }
    }

    @Primary
    @Bean("masterRedisConnectionFactory")
    public RedisConnectionFactory masterRedisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, Integer.parseInt(masterPort));
        return new LettuceConnectionFactory(config);
    }

    @Bean("slaveRedisConnectionFactory")
    public RedisConnectionFactory slaveRedisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, Integer.parseInt(slavePort));
        return new LettuceConnectionFactory(config);
    }

    @Primary
    @Bean(name = "masterRedisTemplate")
    public RedisTemplate<?, ?> masterRedisTemplate(@Qualifier("masterRedisConnectionFactory") RedisConnectionFactory redisConnectionFactory) {
        RedisTemplate<byte[], byte[]> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);
        // 직렬화 및 역직렬화 설정
        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer();
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer); // 이 부분이 데이터를 JSON 형식으로 직렬화
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(serializer);

        template.afterPropertiesSet();
        return template;
    }

    @Bean(name = "slaveRedisTemplate")
    public RedisTemplate<?, ?> slaveRedisTemplate(@Qualifier("slaveRedisConnectionFactory") RedisConnectionFactory redisConnectionFactory) {
        RedisTemplate<byte[], byte[]> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);
        // 직렬화 및 역직렬화 설정
        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer();
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer); // 이 부분이 데이터를 JSON 형식으로 직렬화
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(serializer);

        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public RediSearchClient redisSearchClient() {
//        // Redis 서버에 연결 (JedisPooled 사용)
//        JedisPooled jedisPooled = new JedisPooled(host, Integer.parseInt(masterPort));

        Config config = new Config();
        config.useMasterSlaveServers()
                .setMasterAddress("redis://" + host + ":" + masterPort)
                .addSlaveAddress("redis://" + host + ":" + slavePort);

        // RediSearchClient 생성
        redisSearchClient = new RediSearchClient(config);

        // chatRoom 인덱스 스키마 정의
        Schema chatRoomSchema = new Schema()
                .addField(new TextField("roomId"))
                .addField(new TextField("creator").noStem())
                .addField(new TextField("roomName").noStem())
                .addField(new Field("createDate", FieldType.NUMERIC))
                .addField(new TextField("state").noStem());

        // TODO 추후 member 관리를 위한 User 인덱스 스키마
//        // User 인덱스 스키마 정의
//        Schema userSchema = new Schema()
//                .addField(new TextField("userId").noStem())
//                .addField(new TextField("nickName").noStem());

        Schema notiSchema = new Schema()
                .addField(new Field("userIdx", FieldType.NUMERIC));

        // 인덱스 생성 (존재하지 않을 경우에만 생성)
        try {
            redisSearchClient.getRediSearch(RedisIndex.CHATROOM.getType()).createIndex(chatRoomSchema);
            redisSearchClient.getRediSearch(RedisIndex.NOTI.getType()).createIndex(notiSchema);
//            redisSearchClient.getRediSearch(RedisIndex.LOGIN_USER.getType()).createIndex(userSchema);
            log.info("##### Successfully create Index #####");
        } catch (Exception e) {
            log.error("#### Fail to create Index, Maybe already Exist #####");
        }

        return redisSearchClient;
    }

}