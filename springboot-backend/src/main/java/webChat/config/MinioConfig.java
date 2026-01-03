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

    @Value("${minio.internal-url}")
    private String internalUrl;


    private MinioClient minioClient;

    // minio 의 url 을 세팅하기 위한 postConstruct
    // 환경변수로 url 이 들어오면 해당 url 을 사용하고, 아니면 properties 에 정의 된 값을 사용
    @PostConstruct
    private void initMinioClient(){

        String envMinioInternalUrl = System.getenv("MINIO_INTERNAL_URL");
        if(!StringUtil.isNullOrEmpty(envMinioInternalUrl)){
            internalUrl = envMinioInternalUrl;
        }

        minioClient = MinioClient.builder()
                .endpoint(this.getInternalUrl())
                .credentials(this.getAccessKey(), this.getSecretKey())
                .build();

        log.info("============================================");
        log.info("MinIO Client Configuration");
        log.info("  Internal URL (upload): {}", internalUrl);
        log.info("  Bucket: {}", bucketName);
        log.info("  Recording Bucket: {}", recordingBucketName);
        log.info("============================================");

        try {
            minioClient.ignoreCertCheck(); // ssl 인증 연결 무시
            log.info("MinIO client initialized successfully");
        } catch (KeyManagementException | NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
