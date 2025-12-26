package webChat.model.redis;

import lombok.Getter;

@Getter
public enum RedisKeyPrefix {

    ROOM_ID_PREFIX("roomId:"),
    USER_ID_PREFIX("userId:"),
    USER_PREFIX("user:"),
    SOCIAL_USER_PREFIX("oauth:"),
    ROOM_ROUTING_PREFIX("room:mapping:"),
    ROOM_COUNT_PREFIX("instance:roomcount:"),
    INSTANCE_HEARTBEAT_PREFIX("instance:heartbeat:"),
    INSTANCE_COOKIE_PREFIX("instance:cookie:"),
    INSTANCE_INFO_PREFIX("instance:info:"),
    OAUTH_PREFIX("oauth:"),
    QR_SESSION_PREFIX("qr:session:")
    ;


    private final String prefix;

    RedisKeyPrefix(String prefix){
        this.prefix = prefix;
    }
}
