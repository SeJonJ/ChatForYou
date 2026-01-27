package webChat.service.kurento;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import webChat.model.kurento.KurentoMessageType;

/**
 * Kurento WebRTC 메시지 빌더
 * kurento 에서 사용하는 메시지 빌더
 * 1. 정의된 messageType 사용
 * 2. 추가해야하는 파라미터가 있는 경우 아래처럼 생성자 만들어서 사용
 - public KurentoMessageBuilder minioFilePath(String minioFilePath)
 */
@Slf4j
public class KurentoMessageBuilder {
    private final JsonObject message;
    private final KurentoMessageType messageType;

    private KurentoMessageBuilder(KurentoMessageType messageType) {
        this.messageType = messageType;
        this.message = new JsonObject();
        this.message.addProperty("id", messageType.getMessageId());

        // status가 있는 경우만 추가
        if (messageType.getStatus() != null) {
            this.message.addProperty("status", messageType.getStatus().name());
        }

        // 기본 메시지가 있는 경우 추가
        if (messageType.getDefaultMessage() != null) {
            this.message.addProperty("message", messageType.getDefaultMessage());
        }
    }

    // 녹화 관련

    /**
     * 녹화 업로드 완료 메시지
     */
    public static KurentoMessageBuilder uploadCompleted() {
        return new KurentoMessageBuilder(KurentoMessageType.UPLOAD_COMPLETED);
    }

    /**
     * 녹화 업로드 실패 메시지
     */
    public static KurentoMessageBuilder uploadFailed() {
        return new KurentoMessageBuilder(KurentoMessageType.UPLOAD_FAILED);
    }

    /**
     * 녹화 자동 중지 메시지
     */
    public static KurentoMessageBuilder autoStopped() {
        return new KurentoMessageBuilder(KurentoMessageType.AUTO_STOPPED);
    }

    /**
     * 녹화 자동 중지 실패 메시지
     */
    public static KurentoMessageBuilder autoStopFailed() {
        return new KurentoMessageBuilder(KurentoMessageType.AUTO_STOP_FAILED);
    }

    /**
     * 녹화 시작 성공 응답
     */
    public static KurentoMessageBuilder recordingStarted() {
        return new KurentoMessageBuilder(KurentoMessageType.RECORDING_STARTED);
    }

    /**
     * 녹화 중지 성공 응답
     */
    public static KurentoMessageBuilder recordingStopped() {
        return new KurentoMessageBuilder(KurentoMessageType.RECORDING_STOPPED);
    }

    /**
     * 이미 녹화 중 에러 메시지
     */
    public static KurentoMessageBuilder alreadyRecordingError() {
        return new KurentoMessageBuilder(KurentoMessageType.ALREADY_RECORDING_ERROR);
    }

    /**
     * 녹화 중이 아님 에러 메시지
     */
    public static KurentoMessageBuilder notRecordingError() {
        return new KurentoMessageBuilder(KurentoMessageType.NOT_RECORDING_ERROR);
    }

    /**
     * 녹화 Endpoint 없음 에러 메시지
     */
    public static KurentoMessageBuilder recordingEndpointNotFoundError() {
        return new KurentoMessageBuilder(KurentoMessageType.RECORDING_ENDPOINT_NOT_FOUND_ERROR);
    }

    /**
     * 권한 없음 에러 메시지
     */
    public static KurentoMessageBuilder permissionDeniedError() {
        return new KurentoMessageBuilder(KurentoMessageType.PERMISSION_DENIED_ERROR);
    }

    /**
     * 녹화 파일 존재 에러 메시지
     */
    public static KurentoMessageBuilder recordingFileExistsError() {
        return new KurentoMessageBuilder(KurentoMessageType.RECORDING_FILE_EXISTS_ERROR);
    }

    /**
     * 이미 녹화가 진행중인 방에 참여자 입장
     */
    public static KurentoMessageBuilder recordingInProgress(){
        return new KurentoMessageBuilder(KurentoMessageType.RECORDING_INPROGRESS);
    }

    // 참가자 관련

    /**
     * 새 참가자 입장 알림
     */
    public static KurentoMessageBuilder newParticipantArrived() {
        return new KurentoMessageBuilder(KurentoMessageType.NEW_PARTICIPANT_ARRIVED);
    }

    /**
     * 참가자 퇴장 알림
     */
    public static KurentoMessageBuilder participantLeft() {
        return new KurentoMessageBuilder(KurentoMessageType.PARTICIPANT_LEFT);
    }

    /**
     * 기존 참가자 목록
     */
    public static KurentoMessageBuilder existingParticipants() {
        return new KurentoMessageBuilder(KurentoMessageType.EXISTING_PARTICIPANTS);
    }

    // 연결 및 기타

    /**
     * 연결 실패
     */
    public static KurentoMessageBuilder connectionFailed() {
        return new KurentoMessageBuilder(KurentoMessageType.CONNECTION_FAILED);
    }

    /**
     * 텍스트 오버레이 성공
     */
    public static KurentoMessageBuilder textOverlaySuccess() {
        return new KurentoMessageBuilder(KurentoMessageType.TEXT_OVERLAY_SUCCESS);
    }

    /**
     * 방 녹화 상태
     */
    public static KurentoMessageBuilder roomRecordingStatus() {
        return new KurentoMessageBuilder(KurentoMessageType.ROOM_RECORDING_STATUS);
    }

    /**
     * 재연결 성공
     */
    public static KurentoMessageBuilder reconnectionSuccess() {
        return new KurentoMessageBuilder(KurentoMessageType.RECONNECTION_SUCCESS);
    }

    /**
     * 재연결 실패
     */
    public static KurentoMessageBuilder reconnectionFailed() {
        return new KurentoMessageBuilder(KurentoMessageType.RECONNECTION_FAILED);
    }

    // 녹화 관련 필드

    /**
     * 녹화 ID 추가
     */
    public KurentoMessageBuilder recordingId(String recordingId) {
        message.addProperty("recordingId", recordingId);
        return this;
    }

    /**
     * recordId 별칭
     */
    public KurentoMessageBuilder recordId(String recordId) {
        return recordingId(recordId);
    }

    /**
     * 녹화 파일 minioPath
     * @param minioFilePath
     */
    public KurentoMessageBuilder minioFilePath(String minioFilePath) {
        message.addProperty("minioFilePath", minioFilePath);
        return this;
    }

    /**
     * 녹화 파일명
     * @param fileName
     * @return
     */
    public KurentoMessageBuilder fileName(String fileName) {
        message.addProperty("fileName", fileName);
        return this;
    }

    /**
     * 파일 용량 추가 및 사이즈 계산
     */
    public KurentoMessageBuilder fileSize(long fileSize) {
        message.addProperty("fileSize", fileSize);
        message.addProperty("fileSizeMB", fileSize / 1024 / 1024);
        return this;
    }

    /**
     * 분(minutes) 추가
     */
    public KurentoMessageBuilder minutes(int minutes) {
        message.addProperty("minutes", minutes);
        return this;
    }

    /**
     * 에러 메시지 추가
     */
    public KurentoMessageBuilder error(String error) {
        message.addProperty("error", error);
        return this;
    }

    // 참가자 관련 필드

    /**
     * 참가자 정보 추가
     */
    public KurentoMessageBuilder participantData(String userId, String nickName) {
        JsonObject data = new JsonObject();
        data.addProperty("userId", userId);
        data.addProperty("nickName", nickName);
        message.add("data", data);
        return this;
    }

    /**
     * 참가자 목록 추가
     */
    public KurentoMessageBuilder participantsArray(JsonArray participants) {
        message.add("data", participants);
        return this;
    }

    /**
     * name 필드 추가
     */
    public KurentoMessageBuilder name(String name) {
        message.addProperty("name", name);
        return this;
    }

    /**
     * data 필드 추가
     */
    public KurentoMessageBuilder data(String data) {
        message.addProperty("data", data);
        return this;
    }

    // 공통 필드

    /**
     * 기본 메시지를 오버라이드
     */
    public KurentoMessageBuilder message(String msg) {
        message.addProperty("message", msg);
        return this;
    }

    /**
     * 동적 메시지 생성
     */
    public KurentoMessageBuilder formatMessage(String format, Object... args) {
        message.addProperty("message", String.format(format, args));
        return this;
    }

    /**
     * status를 문자열로 설정
     */
    public KurentoMessageBuilder statusString(String status) {
        message.addProperty("status", status);
        return this;
    }

    /**
     * JsonObject 빌드
     */
    public JsonObject build() {
        log.debug("Building message: type={}, content={}", messageType, message);
        return message;
    }
}