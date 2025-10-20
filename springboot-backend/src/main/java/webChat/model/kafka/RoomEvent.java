package webChat.model.kafka;

import lombok.Getter;

@Getter
public enum RoomEvent {
    ROOM_CREATE,
    ROOM_UPDATE,
    ROOM_DELETE,
    ROOM_USER_CNT
}
