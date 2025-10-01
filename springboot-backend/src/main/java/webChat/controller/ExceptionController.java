package webChat.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.coyote.BadRequestException;
import org.apache.tomcat.util.http.fileupload.impl.FileSizeLimitExceededException;
import org.apache.tomcat.util.http.fileupload.impl.SizeLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@ControllerAdvice
public class ExceptionController {

    private static final Logger log = LoggerFactory.getLogger(ExceptionController.class);

    // 권한이 없는 경우 발생하는 예외 핸들러
    @ResponseStatus(HttpStatus.UNAUTHORIZED)  // 401 status code :: 권한없음
    @ExceptionHandler(UnauthorizedException.class)
    @ResponseBody
    public String unauthorizedException(Exception e) {
        this.printErrorLog(e);

        Map<String, String> result = new HashMap<>();
        result.put("code", "401");
        result.put("message", "You Have No Authentication");
        return "error/403"; // 403 에러 페이지로 리디렉션
    }

    // 권한이 없을 때 발생하는 커스텀 예외
    public static class UnauthorizedException extends RuntimeException {
        public UnauthorizedException(String message) {
            super(message);
        }
    }

    // 잘못된 요청을 받았을 경우 발생하는 예외 핸들러
    @ResponseStatus(HttpStatus.BAD_REQUEST) // 400 status code
    @ExceptionHandler(MissingRequestHeaderException.class)
    @ResponseBody
    public String missingHeaderException(Exception e) {
        this.printErrorLog(e);

        Map<String, String> result = new HashMap<>();
        result.put("code", "400");
        result.put("message", "Required header is missing :: " + e.getMessage());

        return "error/403"; // 403 에러 페이지로 리디렉션
    }

    // 잘못된 요청을 받았을 때 발생하는 커스텀 예외
    public static class AlreadyExistRoomNameException extends BadRequestException {
        public AlreadyExistRoomNameException(String message) {
            super(message);
        }
    }

    // 500 서버에러
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(HttpServerErrorException.InternalServerError.class)
    public @ResponseBody String InternalServerError(Exception e){
        this.printErrorLog(e);
        return "error/500"; // 500 에러 페이지로 리디렉션
    }

    // 서버 에러 발생 시 예외 핸들러
    public static class InternalServerError extends RuntimeException {
        public InternalServerError(String message) {
            super(message);
        }
    }

    // 요청한 자원이 없는 경우 발생하는 예외 핸들러
    @ResponseStatus(HttpStatus.NOT_FOUND) // 404 status code
    @ExceptionHandler(ResourceNotFoundException.class)
    public @ResponseBody Map<String, String> resourceNotFoundException(Exception e) {
        this.printErrorLog(e);
        Map<String, String> result = new HashMap<>();
        result.put("code", "404");
        result.put("message", "there is no resource");
        return result;
    }

    // 요청한 자원이 없을 때 발생하는 커스텀 예외
    public static class ResourceNotFoundException extends RuntimeException {
        public ResourceNotFoundException(String message) {
            super(message);
        }
    }

    /**
     * 403 forbidden 발생 시 예외처리
     * @param ex
     * @param request
     * @return
     */
    @ExceptionHandler({AccessForbiddenException.class, AccessDeniedException.class, UnknownHostException.class})
    public String handleAccessException(Exception ex, HttpServletRequest request) {
        this.printErrorLog(ex);
        request.setAttribute("error_message", ex.getMessage());
        return "error/403"; // 403 에러 페이지로 리디렉션
    }

    public static class AccessForbiddenException extends RuntimeException {
        public AccessForbiddenException(String message) {
            super(message);
        }
    }

    //
    public static class AccessDeniedException extends RuntimeException {
        public AccessDeniedException(String message) {
            super(message);
        }
    }

    /**
     * application.properties 에 정의된 파일사이즈 기준으로 체크 후 예외처리
     * @param e
     * @return
     */
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public @ResponseBody Map<String, String> fileSizeException(Exception e){
        this.printErrorLog(e);
        Map<String, String> result = new HashMap<>();
        result.put("code", "40013");
        result.put("message", "File Extension Error");
        return result;
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(FileExtensionException.class)
    public @ResponseBody Map<String, String> fileExtensionException(Exception e){
        this.printErrorLog(e);
        Map<String, String> result = new HashMap<>();
        result.put("code", "40022");
        result.put("message", "File Extension Error");
        return result;
    }

    public static class FileExtensionException extends RuntimeException {
        public FileExtensionException(String message) {
            super(message);
        }
    }

    public static class AlreadyPlayedGameException extends BadRequestException {

        public AlreadyPlayedGameException(String message) {
            super(message);
        }
    }
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(AlreadyPlayedGameException.class)
    public @ResponseBody Map<String, String> alreadyPlayedGameException(Exception e){
        Map<String, String> result = new HashMap<>();
        result.put("code", "40040");
        result.put("message", "이미 게임을 플레이하셨기에 더 이상 게임을 실행할 수 없습니다.");
        return result;
    }

    public static class SyncGameRound extends BadRequestException {

        public SyncGameRound(String message) {
            super(message);
        }
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(SyncGameRound.class)
    public @ResponseBody Map<String, Object> syncGameRound(String gameRound){
        Map<String, Object> result = new HashMap<>();
        result.put("code", "40040");
        result.put("message", "Syncing game round info.");
        result.put("data", Integer.parseInt(gameRound));
        return result;
    }

    public static class DelRoomException extends BadRequestException {

        public DelRoomException(String message) {
            super(message);
        }
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(DelRoomException.class)
    public @ResponseBody Map<String, Object> delRoomException(){
        Map<String, Object> result = new HashMap<>();
        result.put("code", "40041");
        result.put("message", "Can't Delete Room! Somebody use the  room XD");
        return result;
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(AlreadyExistRoomNameException.class)
    public @ResponseBody Map<String, Object> existRoomName(){
        Map<String, Object> result = new HashMap<>();
        result.put("code", "40042");
        result.put("message", "RoomName Already Exists. Please try another room name.");
        return result;
    }

    private void printErrorLog(Exception e){
        log.error(">>>>>>> "+e.getMessage());
        if (Objects.nonNull(e.getCause())) {
            log.error(">>>>>>> "+ e.getCause().toString());
        }
        e.printStackTrace();
    }

    public static class NotExistUserException extends BadRequestException {
        public NotExistUserException(String message) {
            super(message);
        }
    }
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(NotExistUserException.class)
    public @ResponseBody Map<String, Object> notExistUser(){
        Map<String, Object> result = new HashMap<>();
        result.put("code", "40050");
        result.put("message", "Not Exist Account");
        return result;
    }
}
