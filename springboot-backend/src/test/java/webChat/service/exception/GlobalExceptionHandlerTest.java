package webChat.service.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import webChat.controller.GlobalExceptionHandler;
import webChat.exception.ChatForYouException;
import webChat.exception.ErrorCode;
import webChat.model.response.common.ErrorResponse;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    @DisplayName("ChatForYouException - ROOM_NOT_FOUND 시 404 응답 반환")
    void handleChatForYouException_roomNotFound() {
        // given
        ChatForYouException ex = new ChatForYouException(ErrorCode.ROOM_NOT_FOUND);

        // when
        ResponseEntity<ErrorResponse> response = handler.handleChatForYouException(ex);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("R001");
        assertThat(response.getBody().getStatus()).isEqualTo(404);
        assertThat(response.getBody().getMessage()).isEqualTo(ErrorCode.ROOM_NOT_FOUND.getMessage());
        assertThat(response.getBody().getTimestamp()).isNotNull();
    }

    @Test
    @DisplayName("ChatForYouException - INTERNAL_SERVER_ERROR 시 500 응답에 스택 트레이스 미포함")
    void handleChatForYouException_internalServerError_noStackTrace() {
        // given
        ChatForYouException ex = new ChatForYouException(ErrorCode.INTERNAL_SERVER_ERROR);

        // when
        ResponseEntity<ErrorResponse> response = handler.handleChatForYouException(ex);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("C003");
        // 응답 본문에 스택 트레이스 필드 없음 확인
        assertThat(response.getBody().getErrors()).isNull();
    }

    @Test
    @DisplayName("ChatForYouException - detail 이 있으면 에러 응답에도 detail 이 유지된다")
    void handleChatForYouException_withDetail_preservesDetail() {
        // given
        ChatForYouException ex = new ChatForYouException(ErrorCode.ACCESS_DENIED, "1");

        // when
        ResponseEntity<ErrorResponse> response = handler.handleChatForYouException(ex);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("A001");
        assertThat(response.getBody().getDetail()).isEqualTo("1");
    }

    @Test
    @DisplayName("handled websocket sentinel detail 은 응답에 노출되지 않는다")
    void handleChatForYouException_withHandledWebSocketSentinel_hidesDetail() {
        // given
        ChatForYouException ex = new ChatForYouException(ErrorCode.INTERNAL_SERVER_ERROR, "__ws_error_already_sent__");

        // when
        ResponseEntity<ErrorResponse> response = handler.handleChatForYouException(ex);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).isNull();
    }

    @Test
    @DisplayName("MethodArgumentNotValidException 시 400 응답 + errors 배열 반환")
    void handleValidationException_returns400WithFieldErrors() {
        // given
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError fieldError = new FieldError("obj", "roomName", "방 이름은 필수입니다.");
        when(ex.getBindingResult()).thenReturn(bindingResult);
        when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));

        // when
        ResponseEntity<ErrorResponse> response = handler.handleValidationException(ex);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("C001");
        assertThat(response.getBody().getErrors()).hasSize(1);
        assertThat(response.getBody().getErrors().get(0).getField()).isEqualTo("roomName");
    }

    @Test
    @DisplayName("예상치 못한 Exception 발생 시 500 응답 반환 - 스택 트레이스 미포함")
    void handleException_unexpectedError_returns500() {
        // given
        Exception ex = new RuntimeException("unexpected error");

        // when
        ResponseEntity<ErrorResponse> response = handler.handleException(ex);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("C003");
        assertThat(response.getBody().getErrors()).isNull();
    }

    @Test
    @DisplayName("Broken pipe IOException 은 204로 무시한다")
    void handleIOException_brokenPipe_returnsNoContent() {
        // given
        IOException ex = new IOException("Broken pipe");

        // when
        ResponseEntity<ErrorResponse> response = handler.handleIOException(ex);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
    }

    @Test
    @DisplayName("connection reset by peer IOException 은 204로 무시한다")
    void handleIOException_connectionResetByPeer_returnsNoContent() {
        // given
        IOException ex = new IOException("Connection reset by peer");

        // when
        ResponseEntity<ErrorResponse> response = handler.handleIOException(ex);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
    }

    @Test
    @DisplayName("일반 IOException 은 500 표준 에러 응답을 반환한다")
    void handleIOException_generalIoException_returnsInternalServerError() {
        // given
        IOException ex = new IOException("stream closed unexpectedly");

        // when
        ResponseEntity<ErrorResponse> response = handler.handleIOException(ex);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("C003");
        assertThat(response.getBody().getDetail()).isNull();
    }

    @Test
    @DisplayName("AsyncRequestNotUsableException 래핑 예외는 204로 무시한다")
    void handleException_asyncRequestWrapped_returnsNoContent() {
        // given
        class AsyncRequestNotUsableException extends IOException {
            AsyncRequestNotUsableException(String message) {
                super(message);
            }
        }
        Exception ex = new RuntimeException("wrapper", new AsyncRequestNotUsableException("async failure"));

        // when
        ResponseEntity<ErrorResponse> response = handler.handleException(ex);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
    }
}
