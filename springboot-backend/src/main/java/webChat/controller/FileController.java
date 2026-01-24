package webChat.controller;

import com.google.firebase.auth.FirebaseToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import webChat.model.file.FileDto;
import webChat.service.file.impl.MinioFileService;
import webChat.service.file.impl.RecordingFileService;
import webChat.service.user.UserService;
import webChat.utils.TokenUtils;

@RestController
@RequestMapping("/chatforyou/api/file")
@RequiredArgsConstructor
@Slf4j
public class FileController {
    private final MinioFileService minioFileService;
    private final RecordingFileService recordingFileService;
    private final UserService userService;

    // 프론트에서 ajax 를 통해 /upload 로 MultipartFile 형태로 파일과 roomId 를 전달받는다.
    // 전달받은 file 를 uploadFile 메서드를 통해 업로드한다.
    @PostMapping("/upload")
    public FileDto uploadFile(
            @RequestPart("file") MultipartFile file,
            @RequestParam("roomId") String roomId,
            @RequestHeader("Authorization") String authorization) throws Exception {

        // token 확인
        FirebaseToken token = TokenUtils.checkGoogleOAuthToken(authorization);
        // 유저 검증 및 로그인(레디스 저장) 정보 확인
        userService.getValidatedOauthUser(token.getEmail());

        FileDto uploadFile = minioFileService.uploadFile(file, roomId);
        log.info("최종 upload Data {}", uploadFile);

        // fileReq 객체 리턴
        return uploadFile;
    }

    // get 으로 요청이 오면 아래 download 메서드를 실행한다.
    // fileName 과 파라미터로 넘어온 fileDir 을 getObject 메서드에 매개변수로 넣는다.
    @PostMapping("/download")
    public ResponseEntity<byte[]> download(
            @RequestParam("roomId") String roomId,
            @RequestParam("bucket") String bucket,
            @RequestParam("fileName") String fileName,
            @RequestParam("filePath") String filePath,
            @RequestHeader("Authorization") String authorization) throws Exception {
        log.debug("fileDir : fileName [{} : {}]", filePath, fileName);

        // token 확인
        FirebaseToken token = TokenUtils.checkGoogleOAuthToken(authorization);
        // 유저 검증 및 로그인(레디스 저장) 정보 확인
        userService.getValidatedOauthUser(token.getEmail());

        // 변환된 byte, httpHeader 와 HttpStatus 가 포함된 ResponseEntity 객체를 return 한다.
        ResponseEntity<byte[]> fileData = null;
        try {
            if("file".equals(bucket)){
                fileData = minioFileService.getObject(fileName, filePath);
            } else if("recording".equals(bucket)) {
                fileData = recordingFileService.getObject(roomId, fileName, filePath);
            }
        } catch (Exception e) {
            throw new ExceptionController.InternalServerError(e.getMessage());
        }

        return fileData;
    }

}
