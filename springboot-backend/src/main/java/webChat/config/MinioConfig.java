package webChat.config;

import io.minio.MinioClient;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import webChat.utils.StringUtil;

import javax.annotation.PostConstruct;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;

@Configuration
@Getter
@Slf4j
public class MinioConfig {

    @Value("${minio.access.key}")
    private String accessKey;

    @Value("${minio.access.secret}")
    private String secretKey;

    @Value("${minio.bucket-name:chatforyou-storage}")
    private String bucketName;

    @Value("${minio.recording-bucket-name:chatforyou-recording-storage}")
    private String recordingBucketName;

    @Value("${minio.internal-url:}")
    private String internalUrl;

    @Value("${minio.external-url:https://hjproject.kro.kr:32090}")
    private String externalUrl;

    private MinioClient internalMinioClient;  // 내부 업로드용
    private MinioClient externalMinioClient;  // 외부 다운로드용

    // minio 의 url 을 세팅하기 위한 postConstruct
    // 환경변수로 url 이 들어오면 해당 url 을 사용하고, 아니면 properties 에 정의 된 값을 사용
    @PostConstruct
    private void initMinioClient() {
        try {

            // 환경변수 처리
            String envMinioInternalUrl = System.getenv("MINIO_INTERNAL_URL");
            if (!StringUtil.isNullOrEmpty(envMinioInternalUrl)) {
                internalUrl = envMinioInternalUrl;
            }

            if (!StringUtil.isNullOrEmpty(internalUrl)) {
                // 내부 MinioClient 생성
                internalMinioClient = MinioClient.builder()
                        .endpoint(internalUrl)
                        .credentials(accessKey, secretKey)
                        .build();
                internalMinioClient.ignoreCertCheck();
            }

            String envMinioExternalUrl = System.getenv("MINIO_EXTERNAL_URL");
            if (!StringUtil.isNullOrEmpty(envMinioExternalUrl)) {
                externalUrl = envMinioExternalUrl;
            }

            if (!StringUtil.isNullOrEmpty(externalUrl)) {
                // 외부 MinioClient 생성
                externalMinioClient = MinioClient.builder()
                        .endpoint(externalUrl)
                        .credentials(accessKey, secretKey)
                        .build();
//                externalMinioClient.ignoreCertCheck();
            }

            log.info("============================================");
            log.info("MinIO Client Configuration");
            log.info("  Internal URL (upload): {}", internalUrl);
            log.info("  External URL (presigned): {}", externalUrl);
            log.info("  Bucket: {}", bucketName);
            log.info("  Recording Bucket: {}", recordingBucketName);
            log.info("MinIO clients (internal/external) initialized successfully");
            log.info("============================================");

        } catch (KeyManagementException | NoSuchAlgorithmException e) {
            log.error("Failed to initialize MinIO clients", e);
            throw new IllegalStateException("MinIO 초기화 실패", e);
        }
    }
}
