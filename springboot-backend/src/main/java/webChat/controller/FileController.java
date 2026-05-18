package webChat.controller;

import com.google.firebase.auth.FirebaseToken;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import webChat.entity.DownloadLog;
import webChat.exception.ChatForYouException;
import webChat.exception.ErrorCode;
import webChat.model.file.FileDto;
import webChat.model.login.OauthRedis;
import webChat.service.file.impl.MinioFileService;
import webChat.service.file.impl.RecordingFileService;
import webChat.service.monitoring.DownloadLogService;
import webChat.service.user.UserService;
import webChat.utils.ClientUtils;
import webChat.utils.TokenUtils;

@RestController
@RequestMapping("/chatforyou/api/file")
@RequiredArgsConstructor
@Slf4j
public class FileController {
    private final MinioFileService minioFileService;
    private final RecordingFileService recordingFileService;
    private final UserService userService;
    private final DownloadLogService downloadLogService;

    /**
     * 채팅방 파일을 업로드한다.
     *
     * @param file 업로드 대상 파일
     * @param roomId 채팅방 ID
     * @param authorization Firebase 인증 토큰
     * @return 업로드된 파일 메타데이터
     */
    @PostMapping("/upload")
    public FileDto uploadFile(
            @RequestPart("file") MultipartFile file,
            @RequestParam("roomId") String roomId,
            @RequestHeader("Authorization") String authorization) {

        // token 확인
        FirebaseToken token = TokenUtils.checkGoogleOAuthToken(authorization);
        // 유저 검증 및 로그인(레디스 저장) 정보 확인
        userService.getValidatedOauthUser(token.getEmail());

        FileDto uploadFile = minioFileService.uploadFile(file, roomId);
        log.debug("최종 upload Data {}", uploadFile);

        return uploadFile;
    }

    /**
     * 일반 파일 또는 녹화 파일을 다운로드한다.
     *
     * @param request 클라이언트 요청
     * @param roomId 채팅방 ID
     * @param bucket 다운로드 대상 버킷 유형
     * @param fileName 파일명
     * @param filePath 파일 경로
     * @param authorization Firebase 인증 토큰
     * @return 다운로드 응답
     */
    @PostMapping("/download")
    public ResponseEntity<byte[]> download(
            HttpServletRequest request,
            @RequestParam("roomId") String roomId,
            @RequestParam("bucket") String bucket,
            @RequestParam("fileName") String fileName,
            @RequestParam("filePath") String filePath,
            @RequestHeader("Authorization") String authorization) {
        log.debug("fileDir : fileName [{} : {}]", filePath, fileName);

        // token 확인
        FirebaseToken token = TokenUtils.checkGoogleOAuthToken(authorization);

        // 유저 검증 및 로그인(레디스 저장) 정보 확인
        OauthRedis oauthRedis = userService.getValidatedOauthUser(token.getEmail());

        String ipAddress = ClientUtils.getRemoteAddr(request);
        String userAgent = ClientUtils.getUserAgent(request);

        DownloadLog.DownloadType downloadType = null;
        try{
            downloadType = DownloadLog.DownloadType.valueOf(bucket.toUpperCase());
        } catch (IllegalArgumentException e){
            throw new ChatForYouException(ErrorCode.UNAUTHORIZED);
        }

        ResponseEntity<byte[]> fileData = null;
        try {
            if(DownloadLog.DownloadType.FILE.equals(downloadType)){
                fileData = minioFileService.getObject(fileName, filePath);
            } else if(DownloadLog.DownloadType.RECORDING.equals(downloadType)) {
                fileData = recordingFileService.getObject(roomId, fileName, filePath);
            }

            downloadLogService.saveDownloadLog(
                    DownloadLog.of(
                            oauthRedis.getIdx(), oauthRedis.getEmail(), roomId,
                            downloadType,
                            filePath, fileName, ipAddress, userAgent, DownloadLog.DownloadStatus.SUCCESS
                    )
            );

        } catch (ChatForYouException e) {
            throw e;
        } catch (Exception e) {
            downloadLogService.saveDownloadLog(
                    DownloadLog.of(
                            oauthRedis.getIdx(), oauthRedis.getEmail(), roomId,
                            downloadType,
                            filePath, fileName, ipAddress, userAgent, DownloadLog.DownloadStatus.FAIL
                    )
            );
            throw new ChatForYouException(ErrorCode.INTERNAL_SERVER_ERROR);
        }

        return fileData;
    }

}
