package webChat.model.record;

import lombok.Getter;

// 녹화 상태
@Getter
public enum RecordingStatus {
    READY, // 녹화 시작전(대기)
    RECORDING, // 녹화중
    PAUSED, // 일지 정지
    STOPPED, // 중지
    AUTO_STOPPED, // 자동중지(10분)
    UPLOADING, // 업로드 중
    COMPLETED, // 완료(업로드 완료)
    EXPIRED, // 파일 다운로드 시간 만료
    FAILED, // 실패
    ERROR, // 에러
    ;
}
