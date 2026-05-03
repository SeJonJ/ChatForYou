package webChat.controller;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import webChat.exception.ChatForYouException;
import webChat.exception.ErrorCode;
import webChat.model.response.common.ErrorResponse;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final String HANDLED_WS_ERROR_DETAIL = "__ws_error_already_sent__";

    @ExceptionHandler(ChatForYouException.class)
    public ResponseEntity<ErrorResponse> handleChatForYouException(ChatForYouException e) {
        final ErrorCode errorCode = e.getErrorCode();
        String detail = HANDLED_WS_ERROR_DETAIL.equals(e.getDetail()) ? null : e.getDetail();
        log.warn("비즈니스 예외 발생: code={}, message={}", errorCode.getCode(), e.getMessage());
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(buildErrorResponse(errorCode.getStatus().value(), errorCode.getCode(), errorCode.getMessage(), detail, null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException e) {
        List<ErrorResponse.FieldError> fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> ErrorResponse.FieldError.builder()
                        .field(fe.getField())
                        .value(String.valueOf(fe.getRejectedValue()))
                        .reason(fe.getDefaultMessage())
                        .build())
                .collect(Collectors.toList());
        log.warn("입력값 검증 실패: errors={}", fieldErrors.size());
        final ErrorCode errorCode = ErrorCode.INVALID_INPUT_VALUE;
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(buildErrorResponse(errorCode.getStatus().value(), errorCode.getCode(), errorCode.getMessage(), null, fieldErrors));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingRequestHeader(MissingRequestHeaderException e) {
        log.warn("필수 헤더 누락: headerName={}", e.getHeaderName());
        final ErrorCode errorCode = ErrorCode.UNAUTHORIZED;
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(buildErrorResponse(errorCode.getStatus().value(), errorCode.getCode(), errorCode.getMessage(), null, null));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        log.warn("허용되지 않는 메서드: {}", e.getMessage());
        final ErrorCode errorCode = ErrorCode.METHOD_NOT_ALLOWED;
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(buildErrorResponse(errorCode.getStatus().value(), errorCode.getCode(), errorCode.getMessage(), null, null));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException e) {
        log.warn("파일 크기 초과: {}", e.getMessage());
        final ErrorCode errorCode = ErrorCode.FILE_SIZE_EXCEEDED;
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(buildErrorResponse(errorCode.getStatus().value(), errorCode.getCode(), errorCode.getMessage(), null, null));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<ErrorResponse> handleIOException(IOException e) {
        if (isClientConnectionException(e)) {
            log.warn("클라이언트 연결 종료 예외 무시: {}", e.getMessage());
            return ResponseEntity.noContent().build();
        }

        log.error("I/O 예외 발생: {}", e.getMessage(), e);
        final ErrorCode errorCode = ErrorCode.INTERNAL_SERVER_ERROR;
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(buildErrorResponse(errorCode.getStatus().value(), errorCode.getCode(), errorCode.getMessage(), null, null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        if (isClientConnectionException(e)) {
            log.warn("클라이언트 연결 종료 예외 무시: {}", e.getMessage());
            return ResponseEntity.noContent().build();
        }
        // 스택 트레이스는 로그에만 기록, 응답에 노출 금지
        log.error("시스템 예외 발생: {}", e.getMessage(), e);
        final ErrorCode errorCode = ErrorCode.INTERNAL_SERVER_ERROR;
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(buildErrorResponse(errorCode.getStatus().value(), errorCode.getCode(), errorCode.getMessage(), null, null));
    }

    private boolean isClientConnectionException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("broken pipe") || normalized.contains("connection reset by peer")) {
                    return true;
                }
            }
            String className = current.getClass().getName();
            if (className.contains("AsyncRequestNotUsableException")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private ErrorResponse buildErrorResponse(int status, String code, String message, String detail, List<ErrorResponse.FieldError> errors) {
        return ErrorResponse.builder()
                .status(status)
                .code(code)
                .message(message)
                .detail(detail)
                .traceId(MDC.get("traceId"))
                .timestamp(Instant.now().toString())
                .errors(errors)
                .build();
    }
}
