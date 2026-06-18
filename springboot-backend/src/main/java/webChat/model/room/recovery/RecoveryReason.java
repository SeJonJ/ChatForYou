package webChat.model.room.recovery;

public enum RecoveryReason {
    RECOVERABLE,
    CLAIM_IN_PROGRESS,
    CURRENT_COOKIE_UNAVAILABLE,
    RECOVERY_EXPIRED,
    NOT_RECOVERABLE,
    ROOM_NOT_FOUND
}
