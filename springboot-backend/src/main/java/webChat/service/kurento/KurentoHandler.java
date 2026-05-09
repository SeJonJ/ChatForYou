package webChat.service.kurento;

import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kurento.client.IceCandidate;
import org.kurento.client.KurentoClient;
import org.kurento.client.MediaPipeline;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import webChat.exception.ChatForYouException;
import webChat.exception.ErrorCode;
import webChat.model.kurento.*;
import webChat.model.record.RecordingInfo;
import webChat.model.redis.DataType;
import webChat.model.room.KurentoRoom;
import webChat.repository.kurento.KurentoPipelineMap;
import webChat.repository.kurento.KurentoRecorderMap;
import webChat.service.chatroom.participant.KurentoParticipantService;
import webChat.service.kafka.ChatKafkaProducer;
import webChat.service.recording.RecordingService;
import webChat.service.redis.RedisService;
import webChat.utils.JsonUtils;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * Kurento WebRTC WebSocket 메시지 핸들러.
 *
 * 클라이언트로부터 수신되는 WebSocket 텍스트 메시지를 이벤트 유형별로 분기하여
 * 방 입장·퇴장, 영상 수신, ICE 후보 교환, 텍스트 오버레이, 녹화 시작·중지 등의
 * Kurento 미디어 서버 연동 로직을 처리한다.
 *
 * 비즈니스 예외(ChatForYouException)는 클라이언트에게 표준 에러 메시지로 전달하고,
 * 시스템 예외는 서버 내부 에러로 응답한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KurentoHandler extends TextWebSocketHandler {
    // kurento room 기능
    private final KurentoRoomManager kurentoRoomManager;
    private final KurentoClient kurentoClient;
    private final RedisService redisService;
    private final KurentoParticipantService participantService;
    private final ChatKafkaProducer chatKafkaProducer;
    private final Map<String, MediaPipeline> kurentoPiplineMap = KurentoPipelineMap.getInstance();

    // 녹화 관련
    private final RecordingService recordingService;
    private final KurentoMessageSender kurentoMessageSender;

    /**
     * WebSocket 텍스트 메시지를 수신하고 이벤트 유형에 따라 적절한 처리 메서드로 분기한다.
     *
     * 메시지의 event 필드를 기준으로 JOIN_ROOM(방 입장), RECEIVE_VIDEO_FROM(SDP 교환),
     * ON_ICE_CANDIDATE(ICE 후보 추가), LEAVE_ROOM(퇴장), TEXT_OVERLAY(자막),
     * RECORDING_START(녹화 시작), RECORDING_STOP(녹화 중지) 중 하나로 분기한다.
     *
     * HANDLED_WS_ERROR_DETAIL sentinel을 가진 ChatForYouException은 이미 클라이언트에
     * 전달된 에러이므로 중복 응답 없이 조용히 종료한다.
     *
     * @param session WebSocket 세션
     * @param message 수신된 텍스트 메시지
     */
    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) {
        final KurentoMessage kurentoMessage = JsonUtils.jsonToObj(message.getPayload(), KurentoMessage.class);
        final KurentoUserSession user = participantService.getBySessionId(session);

        if (user != null) {
            log.debug("Incoming message from user '{}': {}", user.getUserId(), kurentoMessage.toString());
        } else {
            log.debug("Incoming message from new user: {}", kurentoMessage.toString());
        }

        try {
            // kurento event 가 어떤 내용인지 확인
            switch (kurentoMessage.getEvent()) {
                case JOIN_ROOM: // event 가 joinRoom 인 경우
                    joinRoom(KurentoRTCMessage.of(message.getPayload()), session); // joinRoom 메서드를 실행
                    break;
                case RECEIVE_VIDEO_FROM: // receiveVideoFrom 인 경우
                    processReceiveVideo(KurentoRTCMessage.of(message.getPayload()), user);
                    break;
                case ON_ICE_CANDIDATE: // 유저에 대해 IceCandidate 프로토콜을 실행할 때
                    processIceCandidate(KurentoRTCMessage.of(message.getPayload()), user);
                    break;
                case LEAVE_ROOM: // 유저가 나간 경우
                    leaveRoom(user);
                    break;
                case TEXT_OVERLAY: // 텍스트 오버레이 요청
                    processTextOverlay(KurentoOverlayMessage.of(message.getPayload()), user);
                    break;
                case RECORDING_START: // 녹화 시작
                    processRecordingStart(user);
                    break;
                case RECORDING_STOP: // 녹화 중지
                    processRecordingStop(KurentoRecordingMessage.of(message.getPayload()), user);
                default:
                    break;
            }
        } catch (ChatForYouException e) {
            if (KurentoMessageSender.HANDLED_WS_ERROR_DETAIL.equals(e.getDetail())) {
                log.warn("WebSocket 비즈니스 예외는 이미 클라이언트에 전달됨: sessionId={}", session.getId());
                return;
            }
            log.warn("WebSocket 비즈니스 예외: code={}, message={}", e.getErrorCode().getCode(), e.getMessage());
            kurentoMessageSender.sendStandardErrorToSession(session, e.getErrorCode(), e.getDetail());
        } catch (Exception e) {
            log.error("WebSocket 시스템 예외: sessionId={}", session.getId(), e);
            kurentoMessageSender.sendStandardErrorToSession(session, ErrorCode.INTERNAL_SERVER_ERROR, null);
        }
    }

    /**
     * 녹화 시작 사전 검증 후 녹화 세션을 연다.
     *
     * 검증 순서: 방에 이미 녹화 파일이 존재하는지 확인 후 이미 녹화 중인지 확인한다.
     * 검증 통과 시 녹화를 시작하고 Redis 방 정보를 업데이트한 뒤 성공 응답을 전송한다.
     * HANDLED_WS_ERROR_DETAIL sentinel 예외는 이미 클라이언트에 전달된 에러이므로
     * 추가 전송 없이 조용히 종료한다.
     *
     * @param user 녹화를 시작하는 참가자 세션
     */
    private void processRecordingStart(KurentoUserSession user) {
        String roomId = user.getRoomId();
        String userId = user.getUserId();
        KurentoRoom room = redisService.getRedisDataByDataType(roomId, DataType.CHATROOM, KurentoRoom.class);

        try {
            // 방에 이미 녹화 파일이 존재하는지 확인
            if (room.isHasRecordedOnce()) {
                kurentoMessageSender.broadcastErrorAndThrow(
                        roomId,
                        KurentoMessageBuilder.recordingFileExistsError(),
                        ErrorCode.RECORDING_FILE_EXISTS,
                        KurentoMessageType.RECORDING_FILE_EXISTS_ERROR.getDefaultMessage());
            }

            // 방에서 이미 녹화중인지 확인
            if (room.isRecordingInProgress()) {
                kurentoMessageSender.broadcastErrorAndThrow(
                        roomId,
                        KurentoMessageBuilder.alreadyRecordingError(),
                        ErrorCode.ALREADY_RECORDING,
                        "Already recording in room : " + roomId);
            }

            // 2. 녹화 시작
            String recordId = recordingService.startRecording(room, user);

            // 3. 방 정보 업데이트
            redisService.updateChatRoom(room);

            // 4. 성공 응답
            kurentoMessageSender.sendToUser(
                user,
                KurentoMessageBuilder.recordingStarted()
                    .recordingId(recordId)
            );

        } catch (Exception e) {
            if (e instanceof ChatForYouException chatForYouException
                    && KurentoMessageSender.HANDLED_WS_ERROR_DETAIL.equals(chatForYouException.getDetail())) {
                log.warn("녹화 시작 예외는 이미 클라이언트에 전달됨: roomId={}, userId={}", roomId, userId);
                return;
            }
            log.error("Error starting recording for user {}: {}", userId, e.getMessage(), e);
            kurentoMessageSender.sendStandardErrorToUser(user, ErrorCode.INTERNAL_SERVER_ERROR, null);
        }
    }

    /**
     * 녹화 중지 사전 검증 후 녹화를 종료한다.
     *
     * 검증 순서: 녹화 중 여부 확인, RecorderEndpoint 존재 확인, 녹화 권한(요청자 == 녹화 시작자) 확인.
     * 검증 통과 시 녹화를 중지하고 Redis 방 정보를 업데이트한 뒤 성공 응답을 전송한다.
     * HANDLED_WS_ERROR_DETAIL sentinel 예외는 이미 클라이언트에 전달된 에러이므로
     * 추가 전송 없이 조용히 종료한다.
     *
     * @param message 녹화 ID를 포함한 녹화 중지 요청 메시지
     * @param user    녹화를 중지하는 참가자 세션
     */
    private void processRecordingStop(KurentoRecordingMessage message, KurentoUserSession user) {
        String roomId = user.getRoomId();
        String userId = user.getUserId();
        String recordId = message.getRecordingId();
        KurentoRoom room = redisService.getRedisDataByDataType(roomId, DataType.CHATROOM, KurentoRoom.class);

        try {

            // 녹화 중 여부 확인
            if(!room.isRecordingInProgress()){
                kurentoMessageSender.broadcastErrorAndThrow(
                        roomId,
                        KurentoMessageBuilder.notRecordingError(),
                        ErrorCode.NOT_RECORDING,
                        "No active room recording to stop for room : " + roomId);
            }

            // Map에서 실제 RecorderEndpoint 가 있는지 확인
            if (!KurentoRecorderMap.hasRecorder(roomId)) {
                kurentoMessageSender.broadcastErrorAndThrow(
                        roomId,
                        KurentoMessageBuilder.recordingEndpointNotFoundError(),
                        ErrorCode.RECORDING_ENDPOINT_NOT_FOUND,
                        "Recording endpoint not found for room: " + roomId);
            }

            // 녹화 중지 권한이 있는지 확인
            if(!room.getRecordingInfo().getRecordingUserId().equals(user.getUserId())){
                kurentoMessageSender.broadcastErrorAndThrow(
                        roomId,
                        KurentoMessageBuilder.permissionDeniedError(),
                        ErrorCode.ACCESS_DENIED,
                        "No active room recording to stop for room: " + roomId);
            }

            // 녹화 정지
            recordingService.stopRecording(room, user);

            // 방 정보 업데이트
            redisService.updateChatRoom(room);

            kurentoMessageSender.sendToUser(
                user,
                KurentoMessageBuilder.recordingStopped()
                    .recordingId(recordId)
            );

        } catch (Exception e) {
            if (e instanceof ChatForYouException chatForYouException
                    && KurentoMessageSender.HANDLED_WS_ERROR_DETAIL.equals(chatForYouException.getDetail())) {
                log.warn("녹화 중지 예외는 이미 클라이언트에 전달됨: roomId={}, userId={}", roomId, userId);
                return;
            }
            log.error("Error stopping recording for user {}: {}", userId, e.getMessage(), e);
            kurentoMessageSender.sendStandardErrorToUser(user, ErrorCode.INTERNAL_SERVER_ERROR, null);
        }
    }

    /**
     * WebSocket 연결이 종료되었을 때 호출되며, leaveRoom에 처리를 위임한다.
     *
     * 정상/비정상 종료 모두 처리한다. 세션에 매핑된 참가자가 없으면 조용히 반환한다.
     * 실제 방 퇴장 로직(미디어 자원 해제, Redis userCount 감소 등)은 leaveRoom에서 처리된다.
     *
     * @param session 종료된 WebSocket 세션
     * @param status  종료 상태 코드
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        KurentoUserSession user = participantService.getBySessionId(session);
        if (user == null) {
            log.debug("Disconnected session had no participant mapping: sessionId={}", session.getId());
            return;
        }
        try {
            this.leaveRoom(user);
        } catch (IOException e) {
            log.error("연결 종료 처리 실패: sessionId={}, userId={}", session.getId(), user.getUserId(), e);
        }
    }

    /**
     * 사용자를 Kurento 방에 입장시킨다.
     *
     * 처리 순서: Redis에서 방 정보 조회(없으면 예외) 후 비활성 상태이면 KurentoClient 및
     * MediaPipeline을 초기화하여 활성화한다. 이후 KurentoRoomManager.join을 호출하여
     * 참가자를 등록한다.
     *
     * Redis userCount 증감 판단은 KurentoRoomManager.join 결과만 authoritative source 로 사용한다.
     * 동일 userId의 재접속(탭 새로고침, 중복 접속)은 기존 세션 교체이므로 인원 수는 변하지 않는다.
     *
     * join 완료 후 session 매핑이 없으면 방어적으로 종료한다.
     * 녹화 진행 중이면 신규 참가자에게 recordingInProgress 메시지를 전송한다.
     *
     * @param message 입장 요청 메시지 (roomId, senderId, senderNickName 포함)
     * @param session 신규 WebSocket 세션
     * @throws IOException WebSocket 메시지 전송 실패 시
     */
    private void joinRoom(KurentoRTCMessage message, WebSocketSession session) throws IOException {
        // kurento RTC 객체에서 각 값을 가져온다
        final String roomId = message.getRoomId();
        final String userId = message.getSenderId();
        final String nickName = message.getSenderNickName();
        log.info("PARTICIPANT {}: trying to join room {}", userId, roomId);

        // roomId 를 기준으로 room 을 가져온다
        KurentoRoom kurentoRoom = redisService.getRedisDataByDataType(roomId, DataType.CHATROOM, KurentoRoom.class);
        if (kurentoRoom == null) {
            throw new ChatForYouException(ErrorCode.ROOM_NOT_FOUND);
        }

        // room 을 active 상태로 전환
        if (kurentoRoom.getKurento() == null) {
            kurentoRoom.setKurento(kurentoClient);
        }

        if (!kurentoPiplineMap.containsKey(roomId)) {
            kurentoPiplineMap.put(roomId, kurentoRoom.getKurento().createMediaPipeline());
        }

        kurentoRoom.activate();
        KurentoJoinResult joinResult = kurentoRoomManager.join(kurentoRoom, userId, nickName, session);
        if (!joinResult.replacedExistingParticipant()) {
            redisService.incrementUserCount(kurentoRoom);
            chatKafkaProducer.sendRoomUserCntEvent(kurentoRoom);
        }
        KurentoUserSession user = participantService.getBySessionId(session);
        if (user == null) {
            // join 직후 session 매핑이 없는 경우 — 정상적으로 발생하지 않지만 방어적으로 처리
            log.warn("join 완료 후 session 매핑 없음: sessionId={}, userId={}", session.getId(), userId);
            return;
        }

        // 방 참가 시 녹화 중 확인
        if (kurentoRoom.isRecordingInProgress()) {
            RecordingInfo recordingInfo = kurentoRoom.getRecordingInfo();
            kurentoMessageSender.sendToUser(user,
                    KurentoMessageBuilder.recordingInProgress()
                            .recordingId(recordingInfo.getRecordingId())
                            .message(String.format("%s 님이 녹화 중입니다. 녹화 및 자막 기능이 비활성화됩니다.",
                                    recordingInfo.getRecordingNickName())));
        } else if (kurentoRoom.isHasRecordedOnce()) {  // 방 새로고침 시 이미 녹화 파일이 있는지 확인
            kurentoMessageSender.sendToUser(user,
                    KurentoMessageBuilder.recordingFileExistsError());
        }
    }

    /**
     * 사용자를 방에서 퇴장시키고 관련 자원을 정리한다.
     *
     * isCurrentParticipantSession으로 현재 세션이 방에 등록된 최신 세션과 동일한지 먼저 확인한다.
     * 이 검사는 탭 새로고침 시 구세션의 afterConnectionClosed가 신규 세션을 잘못 제거하는
     * stale disconnect 문제와, replaceParticipant 완료 직후 구세션 종료 이벤트가 지연 도달하는
     * 경쟁 조건을 방지한다.
     *
     * 세션이 불일치하면 sessionId 역색인만 조건부로 제거하고 조용히 반환한다.
     *
     * @param user 퇴장할 참가자 세션
     * @throws IOException 미디어 자원 해제 실패 시
     */
    private void leaveRoom(KurentoUserSession user) throws IOException {
        // user 가 null 이면 return
        if (Objects.isNull(user)) {
            return;
        }

        // 현재 세션이 방의 최신 세션인지 확인 — stale disconnect 방지
        if (!participantService.isCurrentParticipantSession(user.getRoomId(), user.getUserId(), user.getSession())) {
            // 역색인에서 구세션 항목만 제거하여 신규 세션의 매핑을 보호한다
            participantService.removeSessionMappingIfMatched(user.getSession(), user);
            log.debug("Ignoring stale leaveRoom request: roomId={}, userId={}, sessionId={}",
                    user.getRoomId(), user.getUserId(), user.getSession() != null ? user.getSession().getId() : null);
            return;
        }

        // redis 에서 방 삭제
        KurentoRoom kurentoRoom = redisService.getRedisDataByDataType(user.getRoomId(), DataType.CHATROOM, KurentoRoom.class);
        if (kurentoRoom == null) {
            participantService.removeSessionMappingIfMatched(user.getSession(), user);
            log.debug("Room already missing during leaveRoom: roomId={}, userId={}", user.getRoomId(), user.getUserId());
            return;
        }

        // 유저가 room 의 participants 에 없다면 return
        if (!participantService.getParticipantMap(kurentoRoom.getRoomId()).containsKey(user.getUserId())) {
            return;
        }

        kurentoRoomManager.leave(kurentoRoom, user);
        redisService.decrementUserCount(kurentoRoom);
        chatKafkaProducer.sendRoomUserCntEvent(kurentoRoom);
    }

    /**
     * 특정 발신자로부터 비디오 수신을 시작한다 (SDP offer/answer 교환).
     *
     * 수신 측 참가자(user)가 발신자(sender)의 WebRtcEndpoint에 SDP offer를 제출하고
     * SDP answer를 받아 P2P 미디어 연결을 확립한다.
     * user 또는 sender가 null이면 예외를 발생시키고 connectException으로 분기한다.
     *
     * @param message SDP offer 및 발신자 정보가 담긴 RTC 메시지
     * @param user    수신 측 참가자 세션
     */
    private void processReceiveVideo(KurentoRTCMessage message, KurentoUserSession user) {
        try {
            if (user == null) {
                throw new ChatForYouException(ErrorCode.UNAUTHORIZED);
            }
            // 발신자 userId로 방에 등록된 참가자 세션을 조회한다
            final String senderUserId = message.getSenderId();
            // 유저명을 통해 session 값을 가져온다
            final KurentoUserSession sender = participantService.getParticipant(message.getRoomId(), senderUserId);
            if (sender == null) {
                throw new ChatForYouException(ErrorCode.USER_NOT_FOUND, senderUserId);
            }
            // jsonMessage 에서 sdpOffer 값을 가져온다
            final String sdpOffer = message.getSdpOffer();
            // sender의 WebRtcEndpoint에 SDP offer를 제출하여 P2P 영상 연결을 시작한다
            user.receiveVideoFrom(sender, sdpOffer);
        } catch (Exception e) {
            connectException(user, e);
        }
    }

    /**
     * 클라이언트에서 수집된 ICE 후보를 해당 WebRtcEndpoint에 추가한다.
     *
     * ICE 후보는 P2P 연결 경로 협상에 사용된다. 발신자(senderId)가 자기 자신이면
     * outgoingMedia에, 다른 참가자이면 해당 incomingMedia endpoint에 추가된다.
     *
     * @param message senderId와 ICE 후보 정보가 담긴 RTC 메시지
     * @param user    ICE 후보를 추가할 참가자 세션
     * @throws IOException WebSocket 메시지 전송 실패 시
     */
    private void processIceCandidate(KurentoRTCMessage message, KurentoUserSession user) throws IOException {
        JsonObject candidate = message.getCandidate();

        if (user != null) {
            // JSON에서 ICE 후보 객체를 생성하여 적합한 WebRtcEndpoint에 등록한다
            IceCandidate cand = new IceCandidate(candidate.get("candidate").getAsString(),
                    candidate.get("sdpMid").getAsString(), candidate.get("sdpMLineIndex").getAsInt());
            user.addCandidate(cand, message.getSenderId());
        }
    }

    /**
     * 텍스트 오버레이 요청을 user 세션에 적용하고 성공 응답을 전송한다.
     *
     * GStreamer textoverlay 필터를 통해 발신자 비디오 위에 텍스트를 3초간 표시한다.
     * 처리 성공 시 요청한 사용자에게 성공 응답을 전송한다.
     *
     * @param message 오버레이 텍스트 및 요청자 정보가 담긴 메시지
     * @param user    오버레이를 적용할 참가자 세션
     * @throws IOException WebSocket 메시지 전송 실패 시
     */
    private void processTextOverlay(KurentoOverlayMessage message, KurentoUserSession user) throws IOException {
        String overlayText = message.getText();
        log.debug("Received text overlay request from user {}: {}", user.getUserId(), overlayText);

        // 텍스트 오버레이 적용
        user.showTextOverlay(overlayText);

        // 성공 응답 전송
        kurentoMessageSender.sendToUser(
            user,
            KurentoMessageBuilder.textOverlaySuccess()
        );
    }

    /**
     * 영상 연결 처리 중 발생한 예외를 ChatForYouException 여부에 따라 분기 처리한다.
     *
     * ChatForYouException이면 에러 코드와 상세 메시지를 포함한 표준 에러 응답을 전송한다.
     * 그 외 시스템 예외는 connectionFailed 메시지로 응답한다.
     * user가 null이면 connectionFailed 전송을 생략하고 로그만 남긴다.
     *
     * @param user 예외가 발생한 참가자 세션 (null 가능)
     * @param e    발생한 예외
     */
    private void connectException(KurentoUserSession user, Exception e) {
        if (e instanceof ChatForYouException chatForYouException) {
            log.warn("영상 연결 비즈니스 예외: userId={}, code={}, detail={}",
                    user != null ? user.getUserId() : "unknown",
                    chatForYouException.getErrorCode().getCode(),
                    chatForYouException.getDetail());
            kurentoMessageSender.sendStandardErrorToUser(user, chatForYouException.getErrorCode(), chatForYouException.getDetail());
            return;
        }
        log.error("영상 연결 실패: userId={}", user != null ? user.getUserId() : "unknown", e);
        if (user == null) {
            return;
        }
        kurentoMessageSender.sendErrorToUser(
                user,
                KurentoMessageBuilder.connectionFailed()
        );
    }
}
