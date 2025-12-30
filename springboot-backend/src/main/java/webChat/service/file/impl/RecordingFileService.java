package webChat.service.file.impl;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import webChat.config.MinioConfig;
import webChat.model.record.RecordingInfo;
import webChat.service.file.AbstractFileService;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class RecordingFileService extends AbstractFileService {
    public RecordingFileService(MinioConfig minioConfig) {
        super(minioConfig);
    }

    @Override
    protected String getBucketName() {
        return minioConfig.getRecordingBucketName();
    }

    /**
     * 녹화 파일을 MinIO에 업로드
     * @param localFilePath KMS가 생성한 로컬 파일 경로 (file:// 포함)
     * @param recordingInfo 녹화 정보
     * @return MinIO 다운로드 URL (24시간 유효)
     * @throws Exception 업로드 실패 시
     */
    public String uploadRecording(RecordingInfo recordingInfo, String localFilePath, int expiredTime) throws Exception {
        // file:// 프리픽스 제거
        String cleanPath = localFilePath.replace("file://", "");
        File localFile = new File(cleanPath);

        // 파일 존재 여부 확인
        if (!localFile.exists()) {
            throw new FileNotFoundException("Recording file not found: " + localFilePath);
        }

        log.info("Starting upload to MinIO - File: {}, Size: {} bytes",
                 localFile.getName(), localFile.length());

        // MinIO 경로 생성: roomId/recordingId/filename
        String minioPath = String.format("%s/%s/%s",
                recordingInfo.getRoomId(),
                recordingInfo.getRecordingId(),
                localFile.getName()
        );

        try {
            // MinIO 업로드
            try (InputStream fileStream = new FileInputStream(localFile)) {
                // 파일 확장자에 따른 ContentType 결정
                String contentType = localFile.getName().endsWith(".mp4") ?
                                   "video/mp4" : "video/webm";

                PutObjectArgs args = PutObjectArgs.builder()
                        .bucket(getBucketName()) // 현재 extends 된 클래스에 따라서 bucketName 을 가져옴
                        .object(minioPath)
                        .stream(fileStream, localFile.length(), -1)
                        .contentType(contentType)
                        .build();

                minioClient.putObject(args);
                log.info("Recording uploaded to MinIO successfully: {}", minioPath);
            }

            // Presigned URL 생성 (1시간 유효)
            String internalUrl = minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(getBucketName())
                            .object(minioPath)
                            .expiry(expiredTime, TimeUnit.HOURS)
                            .build()
            );

            // 외부 접근 가능 url 로 변환
            String downloadUrl = convertToExternalUrl(internalUrl);
            log.info("Download URL generated: {} (expires in {} hours)", downloadUrl, expiredTime);

            // 로컬 파일 삭제
            if (localFile.delete()) {
                log.info("Local recording file deleted: {}", cleanPath);
            } else {
                log.warn("Failed to delete local recording file: {}", cleanPath);
            }

            return downloadUrl;

        } catch (Exception e) {
            log.error("Failed to upload recording to MinIO: {}", e.getMessage());
            throw new Exception("Recording upload failed: " + e.getMessage(), e);
        }
    }
}
