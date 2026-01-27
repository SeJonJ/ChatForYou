package webChat.service.file.impl;

import io.minio.PutObjectArgs;
import lombok.extern.slf4j.Slf4j;
import org.apache.coyote.BadRequestException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import webChat.config.MinioConfig;
import webChat.model.record.RecordingInfo;
import webChat.model.redis.DataType;
import webChat.model.room.KurentoRoom;
import webChat.service.file.AbstractFileService;
import webChat.service.redis.RedisService;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;

@Service
@Slf4j
public class RecordingFileService extends AbstractFileService {

    @Autowired
    private RedisService redisService;

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
     * @return 파일 경로 (roomId/recordingId/filename)
     * @throws Exception 업로드 실패 시
     */
    public String uploadRecording(RecordingInfo recordingInfo, String localFilePath) throws Exception {
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

                // 내부 클라이언트로 업로드
                minioClient.putObject(args);
                log.info("Recording uploaded to MinIO successfully: {}", minioPath);
            }

            // 로컬 파일 삭제
            if (localFile.delete()) {
                log.info("Local recording file deleted: {}", cleanPath);
            } else {
                log.warn("Failed to delete local recording file: {}", cleanPath);
            }

            return minioPath;

        } catch (Exception e) {
            log.error("Failed to upload recording to MinIO: {}", e.getMessage());
            throw new Exception("Recording upload failed: " + e.getMessage(), e);
        }
    }

    public ResponseEntity<byte[]> getObject(String roomId, String fileName, String fileDir) throws Exception {
        KurentoRoom room = redisService.getRedisDataByDataType(roomId, DataType.CHATROOM, KurentoRoom.class);

        // TODO 예외처리 수정 필요
        if(room.getRecordingInfo() == null || room.getRecordingInfo().getRecordingFile() == null) {
            throw new BadRequestException("there is no recording file in room: " + roomId);
        }

        // expiresAt 비교
        // TODO 예외처리 수정 필요
        if (System.currentTimeMillis() > room.getRecordingInfo().getRecordingFile().getExpiresAt()) {
            throw new BadRequestException("Expired recording file");
        }

        return super.getObject(fileName, fileDir);
    }
}
