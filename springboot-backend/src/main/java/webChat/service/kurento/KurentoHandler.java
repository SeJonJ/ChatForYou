package webChat.service.kurento;

import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import org.apache.coyote.BadRequestException;
import org.kurento.client.IceCandidate;
import org.kurento.client.KurentoClient;
import org.kurento.client.MediaPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import webChat.model.kurento.KurentoMessage;
import webChat.model.kurento.KurentoOverlayMessage;
import webChat.model.kurento.KurentoRTCMessage;
import webChat.model.kurento.KurentoRecordingMessage;
import webChat.model.record.RecordingInfo;
import webChat.model.redis.DataType;
import webChat.model.room.KurentoRoom;
import webChat.repository.KurentoPipelineMap;
import webChat.repository.KurentoRecorderMap;
import webChat.service.chatroom.participant.KurentoParticipantService;
import webChat.service.kafka.ChatKafkaProducer;
import webChat.service.recording.RecordingService;
import webChat.service.redis.RedisService;
import webChat.utils.JsonUtils;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * kurento 동작 handler
 */
@Service
@RequiredArgsConstructor
public class KurentoHandler extends TextWebSocketHandler {

    // 로깅을 위한 객체 생성
    private static final Logger log = LoggerFactory.getLogger(KurentoHandler.class);

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

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        final KurentoMessage kurentoMessage = JsonUtils.jsonToObj(message.getPayload(), KurentoMessage.class);
        final KurentoUserSession user = participantService.getBySessionId(session);

        if (user != null) {
            log.debug("Incoming message from user '{}': {}", user.getUserId(), kurentoMessage.toString());
        } else {
            log.debug("Incoming message from new user: {}", kurentoMessage.toString());
        }

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
    }

    private void processRecordingStart(KurentoUserSession user) throws BadRequestException {
        String roomId = user.getRoomId();
        String userId = user.getUserId();
        KurentoRoom room = redisService.getRedisDataByDataType(roomId, DataType.CHATROOM, KurentoRoom.class);

        try {
            // 방에 이미 녹화 파일이 존재하는지 확인
            if (room.isRoomRecording()) {
                kurentoMessageSender.broadcastErrorAndThrow(
                        roomId,
                        KurentoMessageBuilder.recordingFileExistsError(),
                        "Recording file already exists for room : " + roomId);
            }

            // 방에서 이미 녹화중인지 확인
            if (room.isRecordingInProgress()) {
                kurentoMessageSender.broadcastErrorAndThrow(
                        roomId,
                        KurentoMessageBuilder.alreadyRecordingError(),
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
            e.printStackTrace();
            log.error("Error starting recording for user {}: {}", userId, e.getMessage());
//            sendErrorResponse(user, "startRecordingResponse", "녹화 시작 중 오류가 발생했습니다.");
        }
    }

    private void processRecordingStop(KurentoRecordingMessage message, KurentoUserSession user) throws BadRequestException {
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
                        "No active room recording to stop for room : " + roomId);
            }

            // Map에서 실제 RecorderEndpoint 가 있는지 확인
            if (!KurentoRecorderMap.hasRecorder(roomId)) {
                kurentoMessageSender.broadcastErrorAndThrow(
                        roomId,
                        KurentoMessageBuilder.recordingEndpointNotFoundError(),
                        "Recording endpoint not found for room: " + roomId);
            }

            // 녹화 중지 권한이 있는지 확인
            if(!room.getRecordingInfo().getRecordingUserId().equals(user.getUserId())){
                kurentoMessageSender.broadcastErrorAndThrow(
                        roomId,
                        KurentoMessageBuilder.permissionDeniedError(),
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
            e.printStackTrace();
            log.error("Error stopping recording for user {}: {}", userId, e.getMessage());
//            sendErrorResponse(user, "stopRecordingResponse", "녹화 정지 중 오류가 발생했습니다.");
        }
    }

    // 유저의 연결이 끊어진 경우
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        // TODO user 가 null 인 경우 예외처리
        KurentoUserSession user = participantService.getBySessionId(session);
        this.leaveRoom(user);
    }

    // 유저가 Room 에 입장했을 때
    private void joinRoom(KurentoRTCMessage message, WebSocketSession session) throws IOException {
        // kurento RTC 객체에서 각 값을 가져온다
        final String roomId = message.getRoomId();
        final String userId = message.getSenderId();
        final String nickName = message.getSenderNickName();

        log.info("PARTICIPANT {}: trying to join room {}", userId, roomId);

        // roomId 를 기준으로 room 을 가져온다
        KurentoRoom kurentoRoom = redisService.getRedisDataByDataType(roomId, DataType.CHATROOM, KurentoRoom.class);
        if (kurentoRoom == null) {
            // TODO 예외처리
            return;
        }

        // room 을 active 상태로 전환
        if (kurentoRoom.getKurento() == null) {
            kurentoRoom.setKurento(kurentoClient);
        }

        if (!kurentoPiplineMap.containsKey(roomId)) {
            kurentoPiplineMap.put(roomId, kurentoRoom.getKurento().createMediaPipeline());
            // userHub 초기화
            kurentoRoom.initUserHubPort();
        }

        kurentoRoom.activate();
        kurentoRoomManager.join(kurentoRoom, userId, nickName, session);
        redisService.incrementUserCount(kurentoRoom);
        chatKafkaProducer.sendRoomUserCntEvent(kurentoRoom);

        // 방 참가 시 녹화 중 확인
        if (kurentoRoom.isRoomRecording()) {
            RecordingInfo recordingInfo = kurentoRoom.getRecordingInfo();
            KurentoUserSession user = participantService.getBySessionId(session);
            kurentoMessageSender.sendToUser(user,
                    KurentoMessageBuilder.recordingInProgress()
                            .recordingId(recordingInfo.getRecordingId())
                            .message(String.format("%s 님이 녹화 중입니다. 녹화 및 자막 기능이 비활성화됩니다.",
                                    recordingInfo.getRecordingNickName())));
        }
    }

    private void leaveRoom(KurentoUserSession user) throws IOException {
        // user 가 null 이면 return
        if (Objects.isNull(user)) {
            return;
        }

        // redis 에서 방 삭제
        KurentoRoom kurentoRoom = redisService.getRedisDataByDataType(user.getRoomId(), DataType.CHATROOM, KurentoRoom.class);

        // 유저가 room 의 participants 에 없다면 return
        if (!participantService.getParticipantMap(kurentoRoom.getRoomId()).containsKey(user.getUserId())) {
            return;
        }

        kurentoRoomManager.leave(kurentoRoom, user);
        redisService.decrementUserCount(kurentoRoom);
        chatKafkaProducer.sendRoomUserCntEvent(kurentoRoom);
    }

    private void processReceiveVideo(KurentoRTCMessage message, KurentoUserSession user) {
        try {
            // sender 명 - 사용자명 - 과
            final String senderUserId = message.getSenderId();
            // 유저명을 통해 session 값을 가져온다
            final KurentoUserSession sender = participantService.getParticipant(message.getRoomId(), senderUserId);
            // TODO sender 예외처리
            // jsonMessage 에서 sdpOffer 값을 가져온다
            final String sdpOffer = message.getSdpOffer();
            // 이후 receiveVideoFrom 실행 => 아마도 특정 유저로부터 받은 비디오를 다른 유저에게 넘겨주는게 아닌가...?
            user.receiveVideoFrom(sender, sdpOffer);
        } catch (Exception e){
            connectException(user, e);
        }
    }

    private void processIceCandidate(KurentoRTCMessage message, KurentoUserSession user) throws IOException {
        JsonObject candidate = message.getCandidate();

        if (user != null) {
            IceCandidate cand = new IceCandidate(candidate.get("candidate").getAsString(),
                    candidate.get("sdpMid").getAsString(), candidate.get("sdpMLineIndex").getAsInt());
            user.addCandidate(cand, message.getSenderId());
        }
    }

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

    private void connectException(KurentoUserSession user, Exception e) {
        log.error("Connection failed for user {}: {}", user.getUserId(), e.getMessage());
        kurentoMessageSender.sendErrorToUser(
            user,
            KurentoMessageBuilder.connectionFailed()
        );
    }
}
