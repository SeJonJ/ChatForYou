package webChat.service.file.impl;

import io.minio.*;
import io.minio.http.Method;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.utils.IOUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import webChat.config.MinioConfig;
import webChat.controller.ExceptionController;
import webChat.model.file.FileDto;
import webChat.service.file.FileService;
import webChat.utils.StringUtil;

import javax.annotation.PostConstruct;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.TimeUnit;


@Service
@RequiredArgsConstructor
@Qualifier("minioFileService")
@Slf4j
public class MinioFileServiceImpl implements FileService {

    private final MinioConfig minioConfig;
    private MinioClient minioClient;

    @Value("${allowed.file_extension}")
    ArrayList<String> allowedFileExtensions;

    @PostConstruct
    private void initMinioClient() {
        minioClient = minioConfig.getMinioClient();
    }

    // MultipartFile 과 transcation, roomId 를 전달받는다.
    // 이때 transcation 는 파일 이름 중복 방지를 위한 UUID 를 의미한다.
    @Override
    public FileDto uploadFile(MultipartFile file, String roomId) {
        String originFileName = file.getOriginalFilename();
        String path = UUID.randomUUID().toString().split("-")[0];
        String fullPath = roomId + "/" + path + "/" + originFileName;

        this.uploadFileSizeCheck(file);

        try {
            PutObjectArgs args = PutObjectArgs.builder()
                    .bucket(minioConfig.getBucketName())
                    .object(fullPath)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build();

            minioClient.putObject(args);

            String url = minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(minioConfig.getBucketName())
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

    // path 아래있는 모든 파일을 삭제한다.
    // 이때 path 는 roomId 가 된다 => minIO 에 roomId/변경된 파일명(uuid)/원본 파일명 으로 되어있기 때문에
    // roomId 를 적어주면 기준이 되는 roomId 아래의 모든 파일이 삭제된다.
    @Override
    public void deleteFileDir(String roomId) {

        try {
            Iterable<Result<Item>> results = minioClient.listObjects(
                    ListObjectsArgs.builder()
                            .bucket(minioConfig.getBucketName())
                            .prefix(roomId) // roomId 로 시작하는 모든 객체들을 가져옴
                            .recursive(true) // prefix 로 시작하는 하위 모든 디렉토리/파일을 가져옴
                            .build());

            for (Result<Item> result : results) {
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(minioConfig.getBucketName())
                        .object(result.get().objectName())
                        .build());
            }
        } catch (Exception e) {
            log.error("error Message ::: {}", e.getCause());
            e.printStackTrace();
        }
    }

    // byte 배열 타입을 return 한다.
    @Override
    public ResponseEntity<byte[]> getObject(String fileName, String fileDir) throws Exception {
        // bucket 와 fileDir 을 사용해서 minIO 에 있는 객체 - object - 를 가져온다.
        InputStream fileData = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(minioConfig.getBucketName())
                        .object(fileDir)
                        .build()
        );

        // 이후 다시 byte 배열 형태로 변환한다.
        // 파일 전송을 위해서는 다시 byte[] 즉, binary 로 변환해서 전달해야햐기 때문
        byte[] bytes = IOUtils.toByteArray(fileData);

        // 여기는 httpHeader 에 파일 다운로드 요청을 하기 위한내용
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);

        // 지정된 fileName 으로 파일이 다운로드 된다.
        httpHeaders.setContentDispositionFormData("attachment", fileName);

        log.info("HttpHeader : [{}]", httpHeaders);

        // 최종적으로 ResponseEntity 객체를 리턴하는데
        // --> ResponseEntity 란?
        // ResponseEntity 는 사용자의 httpRequest 에 대한 응답 테이터를 포함하는 클래스이다.
        // 단순히 body 에 데이터를 포함하는 것이 아니라, header 와 httpStatus 까지 넣어 줄 수 있다.
        // 이를 통해서 header 에 따라서 다른 동작을 가능하게 할 수 있다 => 파일 다운로드!!

        // 나는 object가 변환된 byte 데이터, httpHeader 와 HttpStatus 가 포함된다.
        return new ResponseEntity<>(bytes, httpHeaders, HttpStatus.OK);
    }

    @Override
    public void uploadFileSizeCheck(MultipartFile file) {
        String extension = StringUtil.getExtension(file);
        if (!allowedFileExtensions.contains(extension)) {
            throw new ExceptionController.FileExtensionException("file extension exception");
        }
    }

}
