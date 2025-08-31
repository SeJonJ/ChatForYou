package webChat.model.redis;

import lombok.Getter;

@Getter
public enum DataType {
    CHATROOM("chatroom", 1),
//    KURENTO("kurento", 2),
    USER_COUNT("user_count", 4),
    USER_LIST("user_list", 5),
    LOGIN_USER("login_user", 9),
    USER_REFRESH_TOKEN("user_refresh_token", 10),
    USER_LAST_LOGIN_DATE("user_last_login_date", 11),
    ROOM_ROUTING("room_routing", 12),
    INSTANCE_COOKIE("instance_cookie", 13)
    ;

    private final int code;
    private final String type;

    DataType(String type, int code){
        this.code = code;
        this.type = type;
    }

    public static String redisDataTypeConnection(String key, DataType dataType){
        return dataType.type + "_" + key;
    }
}
