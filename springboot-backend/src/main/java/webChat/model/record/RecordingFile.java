package webChat.model.record;

import lombok.*;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(access = AccessLevel.PRIVATE)
public class RecordingFile {
    private String fileName;
    private String filePath;
    private String fileFullPath;
    private String minioFilePath;
    private long fileSize;
    private long createdAt;
    private long expiresAt;

    public static RecordingFile of(RecordingFile recordingFile, String minioFilePath, long fileSize, long expiresAt) {
        return RecordingFile.builder()
                .fileName(recordingFile.getFileName())
                .filePath(recordingFile.getFilePath())
                .fileFullPath(recordingFile.getFileFullPath())
                .createdAt(recordingFile.getCreatedAt())
                .minioFilePath(minioFilePath)
                .fileSize(fileSize)
                .expiresAt(expiresAt)
                .build();
    }

    public static RecordingFile ofCreate(String fileName, String filePath, String fileFullPath, long createdAt) {
        return RecordingFile.builder()
                .fileName(fileName)
                .filePath(filePath)
                .fileFullPath(fileFullPath)
                .createdAt(createdAt)
                .build();
    }
}
