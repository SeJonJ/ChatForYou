package webChat.model.kurento;

import webChat.model.record.RecordingStatus;

/**
 * Kurento WebRTC 메시지 타입 정의
 */
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

    /**
     * duplicate session 으로 현재 세션이 교체됨
     */
    SESSION_REPLACED(
        "sessionReplaced",
        null,
        "동일한 계정으로 새 세션이 연결되어 현재 세션이 종료되었습니다."
    ),

    /**
     * 같은 사용자의 peer 세션 교체 알림
     */
    PARTICIPANT_SESSION_REPLACED(
        "participantSessionReplaced",
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
        "connectionFailed",
        null,
        "connection error"
    ),

    /**
     * 표준 WebSocket 에러 응답
     */
    GENERIC_ERROR(
        "error",
        null,
        null
    ),

    /**
     * 텍스트 오버레이 성공 응답
     */
    TEXT_OVERLAY_SUCCESS(
        "textOverlaySuccess",
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
            "uploadCompleted",
            RecordingStatus.COMPLETED,
            "녹화 파일 업로드가 완료되었습니다."
    ),

    /**
     * 녹화 자동 중지 (N분 경과)
     */
    RECORDING_AUTO_STOPPED(
            "recordingAutoStopped",
            RecordingStatus.AUTO_STOPPED,
            null  // 동적 메시지 (minutes 포함)
    ),

    /**
     * 녹화 파일 업로드 실패
     */
    UPLOAD_FAILED(
            "uploadFailed",
            RecordingStatus.FAILED,
            "녹화 파일 업로드에 실패했습니다. 자세한 사항은 관리자에게 문의부탁드립니다."
    ),

    /**
     * 녹화 자동 중지 실패
     */
    RECORDING_AUTO_STOP_FAILED(
            "recordingAutoStopFailed",
            RecordingStatus.FAILED,
            "녹화 자동 중지에 실패했습니다. 자세한 사항은 관리자에게 문의부탁드립니다."  // 동적 메시지
    ),

    // 3-2. 녹화 응답 (개인)

    /**
     * 녹화 시작 성공 응답
     */
    RECORDING_STARTED(
            "recordingStarted",
            RecordingStatus.RECORDING,
            "녹화가 시작되었습니다."
    ),

    /**
     * 녹화 중지 성공 응답
     */
    RECORDING_STOPPED(
            "recordingStopped",
            RecordingStatus.STOPPED,
            "녹화가 완료되었습니다."
    ),

    /**
     * 방 입장 시 녹화 중 여부 알림
     */
    RECORDING_STATUS(
            "recordingStatus",
            RecordingStatus.RECORDING,
            null
    ),

    // 3-3. 녹화 에러

    /**
     * 이미 녹화 중인 방에서 녹화 시작 시도
     */
    ALREADY_RECORDING_ERROR(
            "alreadyRecordingError",
            RecordingStatus.ERROR,
            "현재 방에서 이미 녹화가 진행중입니다."
    ),

    /**
     * 녹화 중이 아닌 방에서 중지 시도
     */
    NOT_RECORDING_ERROR(
            "notRecordingError",
            RecordingStatus.ERROR,
            "녹화 중인 방이 아닙니다."
    ),

    /**
     * 녹화 Endpoint를 찾을 수 없음
     */
    RECORDING_ENDPOINT_NOT_FOUND_ERROR(
            "recordingEndpointNotFoundError",
            RecordingStatus.ERROR,
            "녹화 정보를 확인할 수 없습니다. 관리자에게 문의해주세요."
    ),

    /**
     * 녹화 중지 권한 없음
     */
    PERMISSION_DENIED_ERROR(
            "permissionDeniedError",
            RecordingStatus.ERROR,
            "녹화를 중지할 권한이 없습니다."
    ),

    /**
     * 이미 녹화 파일 존재
     */
    RECORDING_FILE_EXISTS_ERROR(
            "recordingFileExistsError",
            RecordingStatus.ERROR,
            "해당 방에는 이미 녹화 파일이 있습니다. 녹화를 시작할 수 없습니다."
    ),

    /**
     * 특정 유저의 녹화 에러
     */
    PARTICIPANT_RECORDING_ERROR(
            "participantRecordingError",
            RecordingStatus.ERROR,
            ""
    ),

    RECORDING_IN_PROGRESS(
            "recordingInProgress",
            RecordingStatus.RECORDING,
        ""
    ),

    /**
     * 배포/종료로 녹화가 비정상 중단된 방에 재입장한 사용자 안내 (개인)
     */
    RECORDING_INTERRUPTED(
            "recordingInterrupted",
            null,
            "서버와의 연결 종료로 녹화가 중지됩니다. 서버 재연결 후 녹화를 재시작해주세요."
    ),

    // ==========================================
    // 4. 서버 연결 관련 메시지
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

    KurentoMessageType(String messageId, RecordingStatus status, String defaultMessage) {
        this.messageId = messageId;
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public String getMessageId() {
        return messageId;
    }

    public RecordingStatus getStatus() {
        return status;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
