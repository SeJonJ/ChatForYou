package webChat.model.routing;

import lombok.Getter;

@Getter
public enum RoutingCookie {
    CHATFORYOU_SERVER_COOKIE("chatforyou-server"),
    ROOM_ID_COOKIE("room-id"),
    ROOM_REDIRECT_COUNT("room-redirect-count")
    ;

    private final String name;

    RoutingCookie(String name){
        this.name = name;
    }
}
