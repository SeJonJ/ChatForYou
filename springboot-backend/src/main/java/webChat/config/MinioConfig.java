package webChat.config;

import io.minio.MinioClient;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import webChat.utils.StringUtil;

import javax.annotation.PostConstruct;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;

@Configuration
@Getter
public class MinioConfig {

    @Value("${minio.access.key}")
    private String accessKey;

    @Value("${minio.access.secret}")
    private String secretKey;

    @Value("${minio.bucket.name}")
    private String bucketName;

    @Value("${minio.recording_bucket.name:chatforyou-recording-storage}")
    private String recordingBucketName;

    @Value("${minio.url}")
    private String url;

    private MinioClient minioClient;

    // minio 의 url 을 세팅하기 위한 postConstruct
    // 환경변수로 url 이 들어오면 해당 url 을 사용하고, 아니면 properties 에 정의 된 값을 사용
    @PostConstruct
    private void initMinioClient(){
        String envMinioUrl = System.getenv("MINIO_URL");
        if(!StringUtil.isNullOrEmpty(envMinioUrl)){
            url = envMinioUrl;
        }

        minioClient = MinioClient.builder()
                .endpoint(this.getUrl())
                .credentials(this.getAccessKey(), this.getSecretKey())
                .build();
        try {
            minioClient.ignoreCertCheck(); // ssl 인증 연결 무시
        } catch (KeyManagementException | NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
