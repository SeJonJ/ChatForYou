package webChat.service.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import webChat.exception.ChatForYouException;
import webChat.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ChatForYouExceptionTest {

    @Test
    @DisplayName("ErrorCode만으로 생성 시 detail은 null")
    void constructor_withErrorCodeOnly_detailIsNull() {
        ChatForYouException ex = new ChatForYouException(ErrorCode.ROOM_NOT_FOUND);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ROOM_NOT_FOUND);
        assertThat(ex.getMessage()).isEqualTo(ErrorCode.ROOM_NOT_FOUND.getMessage());
        assertThat(ex.getDetail()).isNull();
    }

    @Test
    @DisplayName("ErrorCode + detail로 생성 시 detail 값 보존")
    void constructor_withDetail_detailPreserved() {
        ChatForYouException ex = new ChatForYouException(ErrorCode.ACCESS_DENIED, "3");

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED);
        assertThat(ex.getDetail()).isEqualTo("3");
        assertThat(ex.getMessage()).isEqualTo(ErrorCode.ACCESS_DENIED.getMessage());
    }

    @Test
    @DisplayName("ChatForYouException은 RuntimeException 상속")
    void extendsRuntimeException() {
        ChatForYouException ex = new ChatForYouException(ErrorCode.UNAUTHORIZED);

        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("ErrorCode의 HTTP status 코드 정합성 확인")
    void errorCode_httpStatus_correct() {
        assertThat(ErrorCode.ROOM_NOT_FOUND.getStatus().value()).isEqualTo(404);
        assertThat(ErrorCode.ROOM_ALREADY_EXISTS.getStatus().value()).isEqualTo(400);
        assertThat(ErrorCode.ACCESS_DENIED.getStatus().value()).isEqualTo(403);
        assertThat(ErrorCode.ALREADY_RECORDING.getStatus().value()).isEqualTo(400);
        assertThat(ErrorCode.INTERNAL_SERVER_ERROR.getStatus().value()).isEqualTo(500);
    }
}
