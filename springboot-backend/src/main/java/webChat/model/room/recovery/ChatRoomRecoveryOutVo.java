package webChat.model.room.recovery;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;
import webChat.model.room.ChatRoom;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatRoomRecoveryOutVo {
    private String roomId;
    private String roomName;
    private String instanceId;
    private String reason;
    private Integer retryAfterMs;

    public static ChatRoomRecoveryOutVo success(ChatRoom chatRoom, String instanceId) {
        return ChatRoomRecoveryOutVo.builder()
                .roomId(chatRoom.getRoomId())
                .roomName(chatRoom.getRoomName())
                .instanceId(instanceId)
                .build();
    }

    public static ChatRoomRecoveryOutVo retry(String roomId, RecoveryReason reason, Integer retryAfterMs) {
        return ChatRoomRecoveryOutVo.builder()
                .roomId(roomId)
                .reason(reason.name())
                .retryAfterMs(retryAfterMs)
                .build();
    }

    public static ChatRoomRecoveryOutVo redirectDashboard(String roomId, RecoveryReason reason) {
        return ChatRoomRecoveryOutVo.builder()
                .roomId(roomId)
                .reason(reason.name())
                .build();
    }
}
