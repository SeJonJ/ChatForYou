package webChat.model.room.recovery;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class RecoveryDecision {
    private boolean recoverable;
    private RecoveryReason reason;

    public static RecoveryDecision recoverable() {
        return RecoveryDecision.builder()
                .recoverable(true)
                .reason(RecoveryReason.RECOVERABLE)
                .build();
    }

    public static RecoveryDecision notRecoverable(RecoveryReason reason) {
        return RecoveryDecision.builder()
                .recoverable(false)
                .reason(reason)
                .build();
    }
}
