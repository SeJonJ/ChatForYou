package webChat.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Common
    INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "C001", "잘못된 입력값입니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "C002", "허용되지 않는 메서드입니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "C003", "현재 서버가 알 수 없는 이유로 동작하지 않습니다. 금방 고쳐놓을게요!"),

    // Room
    ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "R001", "해당 채팅방을 찾을 수 없습니다."),
    ROOM_ALREADY_EXISTS(HttpStatus.BAD_REQUEST, "R002", "이미 존재하는 방 이름입니다."),
    ROOM_DELETE_FAILED(HttpStatus.BAD_REQUEST, "R003", "방을 삭제할 수 없습니다. 사용 중인 방입니다."),
    INVALID_ROOM_ACCESS(HttpStatus.BAD_REQUEST, "R004", "유효하지 않은 방 접근 토큰입니다."),

    // Auth
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "A001", "접근 권한이 없습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "A002", "인증이 필요합니다."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "A003", "토큰이 만료되었습니다."),
    TOKEN_NOT_FOUND(HttpStatus.UNAUTHORIZED, "A004", "토큰이 존재하지 않습니다."),
    QR_SESSION_EXPIRED(HttpStatus.BAD_REQUEST, "A005", "QR 세션이 만료되었습니다."),

    // File
    FILE_EXTENSION_INVALID(HttpStatus.BAD_REQUEST, "F001", "허용되지 않는 파일 형식입니다."),
    FILE_SIZE_EXCEEDED(HttpStatus.PAYLOAD_TOO_LARGE, "F002", "파일 크기가 초과되었습니다."),

    // Game
    GAME_ALREADY_PLAYED(HttpStatus.BAD_REQUEST, "G001", "이미 게임을 플레이하셨기에 더 이상 게임을 실행할 수 없습니다."),

    // Kurento Recording
    ALREADY_RECORDING(HttpStatus.BAD_REQUEST, "K001", "현재 방에서 이미 녹화가 진행중입니다."),
    NOT_RECORDING(HttpStatus.BAD_REQUEST, "K002", "녹화 중인 방이 아닙니다."),
    RECORDING_ENDPOINT_NOT_FOUND(HttpStatus.NOT_FOUND, "K003", "녹화 정보를 확인할 수 없습니다. 관리자에게 문의해주세요."),
    RECORDING_FILE_EXISTS(HttpStatus.BAD_REQUEST, "K004", "해당 방에는 이미 녹화 파일이 있습니다. 녹화를 시작할 수 없습니다."),

    // User
    USER_NOT_FOUND(HttpStatus.BAD_REQUEST, "U001", "존재하지 않는 계정입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
