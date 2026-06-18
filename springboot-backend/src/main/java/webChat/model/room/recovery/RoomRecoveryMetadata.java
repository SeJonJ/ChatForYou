package webChat.model.room.recovery;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomRecoveryMetadata {
    private String roomId;
    private String previousInstanceId;
    private long createdAt;
    private long expiresAt;
    private String reason;
    private RecoveryStatus status;
}
