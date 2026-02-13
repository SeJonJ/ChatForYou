package webChat.repository.kurento.participant;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import webChat.service.kurento.KurentoUserSession;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Kurento WebRTC 전용 참가자 Repository
 *
 * @author SeJon Jang (wkdtpwhs@gmail.com)
 */
@Repository
@Slf4j
public class KurentoParticipantRepository extends InMemoryParticipantRepository<String, KurentoUserSession> {

    /**
     * @Desc 세션아이디 - userSession 객체 저장 map
     * */
    private final ConcurrentHashMap<String, KurentoUserSession> usersBySessionId = new ConcurrentHashMap<>();

    /**
     * Kurento 전용 참가자 추가
     */
    @Override
    public synchronized void addParticipant(String roomId, String userId, KurentoUserSession participant) {
        if (participant == null) {
            log.error("KurentoUserSession이 null입니다. 방: {}, 사용자: {}", roomId, userId);
            throw new IllegalArgumentException("KurentoUserSession cannot be null");
        }

        if (participant.getSession() == null) {
            log.error("WebSocket 세션이 null입니다. 방: {}, 사용자: {}", roomId, userId);
            throw new IllegalArgumentException("WebSocket session cannot be null");
        }

        super.addParticipant(roomId, userId, participant);
        this.addParticipantBySessionId(participant.getSession().getId(), participant);

        // Kurento 특화 로깅
        log.debug("Kurento 세션 추가 완료. 방: {}, 사용자: {}, 세션ID: {}",
                roomId, userId, participant.getSession().getId());
    }

    /**
     * Kurento 전용 참가자 제거 (세션 정리 포함)
     */
    @Override
    public synchronized KurentoUserSession removeParticipant(String roomId, String userId) {
        KurentoUserSession removedParticipant = super.removeParticipant(roomId, userId);

        if (removedParticipant != null) {
            try {
                // Kurento 세션 정리
                 if(this.getParticipantBySessionId(removedParticipant.getSession().getId()) != null) {
                     this.removeParticipantBySessionId(removedParticipant.getSession().getId());
                 }
                removedParticipant.close();
                log.debug("Kurento 세션 제거 완료. 방: {}, 사용자: {}", roomId, userId);

            } catch (Exception e) {
                log.error("Kurento 세션 정리 중 오류 발생. 방: {}, 사용자: {}", roomId, userId, e);
            }
        }

        return removedParticipant;
    }

    private synchronized void addParticipantBySessionId(String sessionId, KurentoUserSession participant) {
        this.usersBySessionId.put(sessionId, participant);
    }

    public KurentoUserSession getParticipantBySessionId(String sessionId) {
        return this.usersBySessionId.get(sessionId);
    }

    private synchronized void removeParticipantBySessionId(String sessionId) {
        this.usersBySessionId.remove(sessionId);
    }
}

