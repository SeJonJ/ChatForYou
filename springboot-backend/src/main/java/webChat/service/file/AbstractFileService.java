package webChat.service.file;

import io.minio.*;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.utils.IOUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import webChat.config.MinioConfig;
import webChat.controller.ExceptionController;
import webChat.utils.StringUtil;

import javax.annotation.PostConstruct;
import java.io.InputStream;
import java.util.ArrayList;

@Slf4j
@RequiredArgsConstructor
public abstract class AbstractFileService {
    protected final MinioConfig minioConfig;
    protected MinioClient minioClient;

    @Value("${allowed.file_extension}")
    ArrayList<String> allowedFileExtensions;

    @PostConstruct
    protected void initMinioClient() {
        minioClient = minioConfig.getMinioClient();
    }

    protected abstract String getBucketName();

    /**
     * presignedURL 을 외부 접근 가능한 URL로 변환
     */
    protected String convertToExternalUrl(String presignedUrl) {
        return minioConfig.convertToExternalUrl(presignedUrl);
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
            log.error("error Message ::: {}", e.getCause());
            e.printStackTrace();
        }
    }

    public ResponseEntity<byte[]> getObject(String fileName, String fileDir) throws Exception {
        // bucket 와 fileDir 을 사용해서 minIO 에 있는 객체 - object - 를 가져온다.
        InputStream fileData = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(getBucketName())
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

    public void uploadFileSizeCheck(MultipartFile file) {
        String extension = StringUtil.getExtension(file);
        if (!allowedFileExtensions.contains(extension)) {
            throw new ExceptionController.FileExtensionException("file extension exception");
        }
    }
}
