package webChat.service.kurento;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
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
import webChat.model.redis.DataType;
import webChat.model.room.KurentoRoom;
import webChat.repository.KurentoPiplineMap;
import webChat.service.chatroom.ChatRoomService;
import webChat.service.chatroom.participant.KurentoParticipantService;
import webChat.service.kafka.ChatKafkaProducer;
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

    // 데이터를 json 으로 넘겨 받고, 넘기기 때문에 관련 라이브러리로 GSON 을 사용함
    // gson은 json구조를 띄는 직렬화된 데이터를 JAVA의 객체로 역직렬화, 직렬화 해주는 자바 라이브러리 입니다.
    // 즉, JSON Object -> JAVA Object 또는 그 반대의 행위를 돕는 라이브러리 입니다.
    private static final Gson gson = new GsonBuilder().create();

    // kurento room 기능
    private final KurentoRoomManager kurentoRoomManager;
    private final KurentoClient kurentoClient;

    private final RedisService redisService;
    private final KurentoParticipantService participantService;
    private final ChatKafkaProducer chatKafkaProducer;
    private final Map<String, MediaPipeline> kurentoPiplineMap = KurentoPiplineMap.getInstance();

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        final JsonObject jsonMessage = gson.fromJson(message.getPayload(), JsonObject.class);

        String roomId = JsonUtils.getStrOrEmpty(jsonMessage, "roomId");
        final KurentoUserSession user = participantService.getBySessionId(session);

        if (user != null) {
            log.debug("Incoming message from user '{}': {}", user.getUserId(), jsonMessage);
        } else {
            log.debug("Incoming message from new user: {}", jsonMessage);
        }

        // 일전에 내가 만들었던 시그널링 서버와 동일하게 handleTextMessage 파라미터 message 로 값이 들어오면
        // swtich 문으로 해당 message 를 잘라서 사용한다.
        // 이때 message 는 json 형태로 들어온다
        // key : id 에 대하여
        switch (jsonMessage.get("id").getAsString()) {
            case "joinRoom": // value : joinRoom 인 경우
                joinRoom(jsonMessage, session); // joinRoom 메서드를 실행
                break;

            case "receiveVideoFrom": // receiveVideoFrom 인 경우
                try {
                    // sender 명 - 사용자명 - 과
                    final String senderUserId = jsonMessage.get("sender").getAsString();
                    // 유저명을 통해 session 값을 가져온다
                    final KurentoUserSession sender = participantService.getParticipant(roomId, senderUserId);
                    // TODO sender 예외처리
                    // jsonMessage 에서 sdpOffer 값을 가져온다
                    final String sdpOffer = jsonMessage.get("sdpOffer").getAsString();
                    // 이후 receiveVideoFrom 실행 => 아마도 특정 유저로부터 받은 비디오를 다른 유저에게 넘겨주는게 아닌가...?
                    user.receiveVideoFrom(sender, sdpOffer);
                } catch (Exception e){
                    e.printStackTrace();
                    connectException(user, e);
                }
                break;

            case "leaveRoom": // 유저가 나간 경우
                leaveRoom(user);
                break;

            case "onIceCandidate": // 유저에 대해 IceCandidate 프로토콜을 실행할 때
                JsonObject candidate = jsonMessage.get("candidate").getAsJsonObject();

                if (user != null) {
                    IceCandidate cand = new IceCandidate(candidate.get("candidate").getAsString(),
                            candidate.get("sdpMid").getAsString(), candidate.get("sdpMLineIndex").getAsInt());
                    user.addCandidate(cand, jsonMessage.get("name").getAsString());
                }
                break;

            case "textOverlay": // 텍스트 오버레이 요청
                if (user != null) {
                    String overlayText = JsonUtils.getStrOrEmpty(jsonMessage, "text");
                    log.debug("Received text overlay request from user {}: {}", user.getUserId(), overlayText);

                    // 텍스트 오버레이 적용
                    user.showTextOverlay(overlayText);

                    // 성공 응답 전송
                    JsonObject response = new JsonObject();
                    response.addProperty("id", "textOverlayResponse");
                    response.addProperty("status", "success");
                    response.addProperty("message", "Text overlay applied successfully");
                    user.sendMessage(response);
                }
                break;

            default:
                break;
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
    private void joinRoom(JsonObject params, WebSocketSession session) throws IOException {
        // json 형태의 params 에서 room 과 userId, nickName 을 분리해온다
        final String roomId = params.get("roomId").getAsString();
        final String userId = params.get("userId").getAsString();
        final String nickName = params.get("nickName").getAsString();

        log.info("PARTICIPANT {}: trying to join room {}", userId, roomId);

        // roomId 를 기준으로 room 을 가져온다
        KurentoRoom kurentoRoom = redisService.getRedisDataByDataType(roomId, DataType.CHATROOM, KurentoRoom.class);
        if (kurentoRoom == null) {
            // TODO 예외처리
            return;
        }

        // room 을 active 상태로 전환
        if(kurentoRoom.getKurento() == null){
            kurentoRoom.setKurento(kurentoClient);
        }

        if (!kurentoPiplineMap.containsKey(roomId)) {
            kurentoPiplineMap.put(roomId, kurentoRoom.getKurento().createMediaPipeline());
        }
        kurentoRoom.activate();
        kurentoRoomManager.join(kurentoRoom, userId, nickName, session);
        redisService.incrementUserCount(kurentoRoom);
        chatKafkaProducer.sendRoomUserCntEvent(kurentoRoom);
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

    private void connectException(KurentoUserSession user, Exception e) throws IOException {
        JsonObject message = new JsonObject();
        message.addProperty("id", "ConnectionFail");
        message.addProperty("data", "connection error");

        user.sendMessage(message);

    }
}
