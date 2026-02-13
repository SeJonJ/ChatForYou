package webChat.service.chatroom.participant.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;
import webChat.repository.kurento.participant.KurentoParticipantRepository;
import webChat.service.chatroom.participant.KurentoParticipantService;
import webChat.service.kurento.KurentoUserSession;

import java.util.Collection;
import java.util.Map;

/**
 * kurento 화상채팅 방 참여자 관리용 service
 * @author SeJon Jang (wkdtpwhs@gmail.com)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KurentoParticipantServiceImpl implements KurentoParticipantService {

    private final KurentoParticipantRepository participantRepository;


    /**
     * 기존 KurentoRoom.getParticipants
     */
    @Override
    public Map<String, KurentoUserSession> getParticipantMap(String roomId) {
        return participantRepository.getParticipants(roomId);
    }

    /**
     * kurentoUserSession list 가져오기
     */
    @Override
    public Collection<KurentoUserSession> getParticipantList(String roomId) {
        return participantRepository.getParticipants(roomId).values();
    }

    /**
     * kurentoUserSession 가져오기
     */
    public KurentoUserSession getParticipant(String roomId, String userId) {
        return participantRepository.getParticipant(roomId, userId);
    }

    /**
     * 참가자 추가
     */
    @Override
    public void addParticipant(String roomId, KurentoUserSession participant) {
        participantRepository.addParticipant(roomId, participant.getUserId(), participant);
    }

    /**
     * 참가자 제거
     */
    @Override
    public KurentoUserSession removeParticipant(String roomId, String userId) {
        return participantRepository.removeParticipant(roomId, userId);
    }

    /**
     * 방 완전 제거
     */
    public void removeRoom(String roomId) {
        participantRepository.removeRoom(roomId);
    }

    /**
     * 참가자 수 조회 (Redis userCount와 동기화용)
     */
    @Override
    public int getParticipantCount(String roomId) {
        return participantRepository.getParticipantCount(roomId);
    }

    @Override
    public Collection<String> getParticipantIds(String roomId) {
        return participantRepository.getParticipants(roomId).keySet();
    }

    /**
     * @Desc 파라미터로 받은 webSocketSession 로 userSession 을 가져옴
     * @Param WebSocketSession
     * @Return userSession
     * */
    @Override
    public KurentoUserSession getBySessionId(WebSocketSession session) {
        return participantRepository.getParticipantBySessionId(session.getId());
    }

}
