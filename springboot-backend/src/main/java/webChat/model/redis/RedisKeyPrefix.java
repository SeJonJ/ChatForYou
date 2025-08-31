package webChat.model.redis;

import lombok.Getter;

@Getter
public enum RedisKeyPrefix {

    ROOM_ID_PREFIX("roomId:"),
    USER_ID_PREFIX("userId:"),
    USER_PREFIX("user:"),
    ROOM_ROUTING_PREFIX("room:mapping:"),
    ROOM_COUNT_PREFIX("instanceRoomCount:"),
    SERVER_HEARTBEAT_PREFIX("server:heartbeat:")
    ;


    private final String prefix;

    RedisKeyPrefix(String prefix){
        this.prefix = prefix;
    }
}
