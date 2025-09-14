package webChat.model.room;

import lombok.Getter;

@Getter
public enum RoomState {
    // 방 상태 추가
    CREATED(1, "created"),      // 생성됨 (Redis에만 존재)
    ACTIVE(2, "active"),       // 활성화됨 (Kurento 리소스 생성됨)
    INACTIVE(3, "inactive"),   // 비활성화됨 (사용자 없음)
    REDIRECT(4, "redirect"),  // instanceId 매칭을 위해 리다이렉트
    ;
    private final int code;
    private final String type;

    RoomState(int code, String type) {
        this.code = code;
        this.type = type;
    }
}
