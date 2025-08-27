package webChat.model.routing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomRoutingInfo {
    private String roomId;
    private String instanceId;
    private String nginxCookie;
    private long createdTime;

    public static RoomRoutingInfo of(@NotNull String roomId, @NotNull String instanceId, @NotNull String nginxCookie, long createdTime){
        return RoomRoutingInfo.builder()
                .roomId(roomId)
                .instanceId(instanceId)
                .nginxCookie(nginxCookie)
                .createdTime(createdTime)
                .build();
    }
}