package webChat.exception;

import lombok.Getter;

@Getter
public class ChatForYouException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String detail;

    public ChatForYouException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.detail = null;
    }

    public ChatForYouException(ErrorCode errorCode, String detail) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.detail = detail;
    }

    public ChatForYouException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
        this.detail = null;
    }

    public ChatForYouException(ErrorCode errorCode, String detail, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
        this.detail = detail;
    }
}
