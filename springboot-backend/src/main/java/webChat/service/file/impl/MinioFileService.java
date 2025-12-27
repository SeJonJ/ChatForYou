package webChat.service.file.impl;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import webChat.config.MinioConfig;
import webChat.model.file.FileDto;
import webChat.service.file.AbstractFileService;

import java.util.UUID;
import java.util.concurrent.TimeUnit;


@Service
@Slf4j
public class MinioFileService extends AbstractFileService {

    public MinioFileService(MinioConfig minioConfig) {
        super(minioConfig);
    }

    @Override
    protected String getBucketName() {
        return minioConfig.getBucketName();
    }

    // MultipartFile 과 transcation, roomId 를 전달받는다.
    // 이때 transcation 는 파일 이름 중복 방지를 위한 UUID 를 의미한다.
    public FileDto uploadFile(MultipartFile file, String roomId) {
        String originFileName = file.getOriginalFilename();
        String path = UUID.randomUUID().toString().split("-")[0];
        String fullPath = roomId + "/" + path + "/" + originFileName;

        this.uploadFileSizeCheck(file);

        try {
            PutObjectArgs args = PutObjectArgs.builder()
                    .bucket(getBucketName())
                    .object(fullPath)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build();

            minioClient.putObject(args);

            String url = minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(getBucketName())
                            .object(fullPath)
                            .expiry(10, TimeUnit.MINUTES) // 다운로드 시간 제한
                            .build());

            // uploadDTO 객체 리턴
            return new FileDto().builder()
                    .fileName(originFileName)
                    .roomId(roomId)
                    .filePath(fullPath)
                    .minioDataUrl(url)
                    .contentType(file.getContentType())
                    .status(FileDto.Status.UPLOADED)
                    .build();

        } catch (Exception e) {
            log.error("fileUploadException {}", e.getMessage());
            e.printStackTrace();

            return new FileDto().builder()
                    .status(FileDto.Status.FAIL)
                    .build();
        }
    }
}
