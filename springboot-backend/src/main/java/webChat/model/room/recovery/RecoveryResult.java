package webChat.model.room.recovery;

import lombok.Builder;
import lombok.Getter;
import webChat.model.response.ChatForYouResponseResult;

@Getter
@Builder
public class RecoveryResult {
    private ChatForYouResponseResult result;
    private ChatRoomRecoveryOutVo data;

    public static RecoveryResult success(ChatRoomRecoveryOutVo data) {
        return RecoveryResult.builder()
                .result(ChatForYouResponseResult.SUCCESS)
                .data(data)
                .build();
    }

    public static RecoveryResult redirectRecover(ChatRoomRecoveryOutVo data) {
        return RecoveryResult.builder()
                .result(ChatForYouResponseResult.REDIRECT_RECOVER)
                .data(data)
                .build();
    }

    public static RecoveryResult redirectDashboard(ChatRoomRecoveryOutVo data) {
        return RecoveryResult.builder()
                .result(ChatForYouResponseResult.REDIRECT_DASHBOARD)
                .data(data)
                .build();
    }
}
