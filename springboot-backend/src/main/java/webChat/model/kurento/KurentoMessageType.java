package webChat.model.kurento;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import webChat.model.record.RecordingStatus;

/**
 * Kurento WebRTC 메시지 타입 정의
 */
@Getter
@RequiredArgsConstructor
public enum KurentoMessageType {


    // ==========================================
    // 1. 참가자 관련 메시지
    // ==========================================

    /**
     * 새 참가자 입장 알림 (브로드캐스트)
     */
    NEW_PARTICIPANT_ARRIVED(
        "newParticipantArrived",
        null,
        null
    ),

    /**
     * 참가자 퇴장 알림 (브로드캐스트)
     */
    PARTICIPANT_LEFT(
        "participantLeft",
        null,
        null
    ),

    /**
     * 기존 참가자 목록 응답 (개인)
     */
    EXISTING_PARTICIPANTS(
        "existingParticipants",
        null,
        null
    ),

    // ==========================================
    // 2. 연결 및 기타 메시지
    // ==========================================

    /**
     * 연결 실패
     */
    CONNECTION_FAILED(
        "ConnectionFail",
        null,
        "connection error"
    ),

    /**
     * 텍스트 오버레이 성공 응답
     */
    TEXT_OVERLAY_SUCCESS(
        "textOverlayResponse",
        null,
        "Text overlay applied successfully"
    ),


    // ==========================================
    // 3. 녹화 관련 메시지
    // ==========================================

    // 녹화 이벤트(브로드 캐스트)
    /**
     * 녹화 파일 업로드 완료
     */
    UPLOAD_COMPLETED(
            "recordingUploadCompleted",
            RecordingStatus.COMPLETED,
            "녹화 파일 업로드가 완료되었습니다."
    ),

    /**
     * 녹화 자동 중지 (N분 경과)
     */
    AUTO_STOPPED(
            "recordingAutoStopped",
            RecordingStatus.AUTO_STOPPED,
            null  // 동적 메시지 (minutes 포함)
    ),

    /**
     * 녹화 파일 업로드 실패
     */
    UPLOAD_FAILED(
            "recordingUploadFailed",
            RecordingStatus.FAILED,
            "녹화 파일 업로드에 실패했습니다. 자세한 사항은 관리자에게 문의부탁드립니다."
    ),

    /**
     * 녹화 자동 중지 실패
     */
    AUTO_STOP_FAILED(
            "recordingAutoStopFailed",
            RecordingStatus.FAILED,
            "녹화 자동 중지에 실패했습니다. 자세한 사항은 관리자에게 문의부탁드립니다."  // 동적 메시지
    ),

    // 3-2. 녹화 응답 (개인)

    /**
     * 녹화 시작 성공 응답
     */
    RECORDING_STARTED(
            "startRecording",
            RecordingStatus.RECORDING,
            "녹화가 시작되었습니다."
    ),

    /**
     * 녹화 중지 성공 응답
     */
    RECORDING_STOPPED(
            "stopRecording",
            RecordingStatus.STOPPED,
            "녹화가 완료되었습니다."
    ),

    /**
     * 방 입장 시 녹화 중 여부 알림
     */
    ROOM_RECORDING_STATUS(
            "roomRecordingStatus",
            null,
            null
    ),

    // 3-3. 녹화 에러

    /**
     * 이미 녹화 중인 방에서 녹화 시작 시도
     */
    ALREADY_RECORDING_ERROR(
            "alreadyRecording",
            RecordingStatus.ERROR,
            "현재 방에서 이미 녹화가 진행중입니다."
    ),

    /**
     * 녹화 중이 아닌 방에서 중지 시도
     */
    NOT_RECORDING_ERROR(
            "notRecording",
            RecordingStatus.ERROR,
            "녹화 중인 방이 아닙니다."
    ),

    /**
     * 녹화 Endpoint를 찾을 수 없음
     */
    RECORDING_ENDPOINT_NOT_FOUND_ERROR(
            "RecordingEndpointNotFound",
            RecordingStatus.ERROR,
            "녹화 정보를 확인할 수 없습니다. 관리자에게 문의해주세요."
    ),

    /**
     * 녹화 중지 권한 없음
     */
    PERMISSION_DENIED_ERROR(
            "permissionDenied",
            RecordingStatus.ERROR,
            "녹화를 중지할 권한이 없습니다."
    ),

    /**
     * 이미 녹화 파일 존재
     */
    RECORDING_FILE_EXISTS_ERROR(
            "recordingFileExists",
            null,
            "해당 방에는 이미 녹화 파일이 있습니다. 녹화를 시작할 수 없습니다."
    ),

    /**
     * 이미 녹화가 진행중인 방(새로운 참여자에게 알림)
     */
    RECORDING_INPROGRESS(
            "recordingAlreadyProcessing",
            null,
            ""
    ),


    // ==========================================
    // TODO 서버 연결 관련(개발 예정)
    // ==========================================

    /**
     * 서버 재연결 성공
     */
    RECONNECTION_SUCCESS(
        "reconnectionSuccess",
        null,
        "방에 재연결되었습니다."
    ),

    /**
     * 서버 재연결 실패
     */
    RECONNECTION_FAILED(
        "reconnectionFailed",
        null,
        "재연결에 실패했습니다."
    );

    private final String messageId;
    private final RecordingStatus status;
    private final String defaultMessage;
}