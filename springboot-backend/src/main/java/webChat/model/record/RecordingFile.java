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
    private String downloadUrl;
    private long fileSize;
    private long createdAt;
    private long expiresAt;

//    public static RecordingFile from(RecordingInfo recordingInfo) {
//        return RecordingFile.builder()
//                .recordingId(recordingInfo.getRecordingId())
//                .roomId(recordingInfo.getRoomId())
//                .fileName(recordingInfo.getFileName())
//                .createdAt(System.currentTimeMillis())
//                .status(recordingInfo.getStatus())
//                .build();
//    }

    public static RecordingFile of(String filePath, String downloadUrl, long fileSize, long createdAt, long expiresAt) {
        return RecordingFile.builder()
                .filePath(filePath)
                .downloadUrl(downloadUrl)
                .fileSize(fileSize)
                .createdAt(createdAt)
                .expiresAt(expiresAt)
                .build();
    }

    public static RecordingFile ofCreate(String filePath, String fileFullPath, long createdAt) {
        return RecordingFile.builder()
                .filePath(filePath)
                .fileFullPath(fileFullPath)
                .createdAt(createdAt)
                .build();
    }
}
