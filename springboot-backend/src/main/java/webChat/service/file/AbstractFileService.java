package webChat.service.file;

import io.minio.*;
import io.minio.http.Method;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.utils.IOUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.multipart.MultipartFile;
import webChat.config.MinioConfig;
import webChat.exception.ChatForYouException;
import webChat.exception.ErrorCode;
import webChat.utils.StringUtil;

import javax.annotation.PostConstruct;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
public abstract class AbstractFileService {
    protected final MinioConfig minioConfig;
    protected MinioClient minioClient;

    @Value("${allowed.file_extension}")
    ArrayList<String> allowedFileExtensions;

    protected abstract String getBucketName();

    // minio 접속 정보에 따라 minioClient init
    @PostConstruct
    private void initMinioClient() {
        if (minioConfig.getInternalMinioClient() != null) {
            this.minioClient = minioConfig.getInternalMinioClient();
        } else {
            this.minioClient = minioConfig.getExternalMinioClient();
        }
    }

    /**
     * roomId 하위의 모든 디렉토리/파일 삭제
     * @param roomId 방 roomID
     */
    // path 아래있는 모든 파일을 삭제한다.
    // 이때 path 는 roomId 가 된다 => minIO 에 roomId/변경된 파일명(uuid)/원본 파일명 으로 되어있기 때문에
    // roomId 를 적어주면 기준이 되는 roomId 아래의 모든 파일이 삭제된다.
    public void deleteFileDir(String roomId) {
        try {
            Iterable<Result<Item>> results = minioClient.listObjects(
                    ListObjectsArgs.builder()
                            .bucket(getBucketName())
                            .prefix(roomId) // roomId 로 시작하는 모든 객체들을 가져옴
                            .recursive(true) // prefix 로 시작하는 하위 모든 디렉토리/파일을 가져옴
                            .build());

            for (Result<Item> result : results) {
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(getBucketName())
                        .object(result.get().objectName())
                        .build());
            }
        } catch (Exception e) {
            log.error("MinIO directory cleanup failed: bucket={}, roomId={}", getBucketName(), roomId, e);
        }
    }

    public ResponseEntity<byte[]> getObject(String fileName, String fileDir) {
        try (InputStream fileData = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(getBucketName())
                        .object(fileDir)
                        .build()
        )) {
            byte[] bytes = IOUtils.toByteArray(fileData);

            HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);

            ContentDisposition contentDisposition = ContentDisposition.attachment()
                    .filename(fileName, StandardCharsets.UTF_8)
                    .build();
            httpHeaders.setContentDisposition(contentDisposition);

            log.info("HttpHeader : [{}]", httpHeaders);
            return new ResponseEntity<>(bytes, httpHeaders, HttpStatus.OK);
        } catch (Exception e) {
            log.error("MinIO 파일 다운로드 실패: bucket={}, fileDir={}", getBucketName(), fileDir, e);
            throw new ChatForYouException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    public void uploadFileSizeCheck(MultipartFile file) {
        String extension = StringUtil.getExtension(file);
        if (!allowedFileExtensions.contains(extension)) {
            throw new ChatForYouException(ErrorCode.FILE_EXTENSION_INVALID);
        }
    }

    /**
     * presigned URL 생성
     * 외부 도메인 기준으로 서명이 생성되어 클라이언트가 다운로드 가능
     *
     * @param objectPath MinIO 객체 경로
     * @param expiry 만료 시간
     * @param timeUnit 시간 단위
     * @return presigned URL (외부 도메인 기준)
     */
    protected String generatePresignedUrl(String objectPath, int expiry, TimeUnit timeUnit) {
        try {
            return minioConfig.getExternalMinioClient().getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(getBucketName())
                            .object(objectPath)
                            .expiry(expiry, timeUnit)
                            .build()
            );
        } catch (Exception e) {
            log.error("Presigned URL 생성 실패: bucket={}, objectPath={}", getBucketName(), objectPath, e);
            throw new ChatForYouException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}
