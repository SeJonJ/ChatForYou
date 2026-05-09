package webChat.repository.kurento.participant;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import webChat.service.kurento.KurentoUserSession;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kurento WebRTC 전용 참가자 Repository.
 *
 * InMemoryParticipantRepository를 상속하며, Kurento 특화 기능으로
 * WebSocket sessionId → 참가자 역색인(usersBySessionId)을 추가 관리한다.
 * 역색인을 통해 WebSocket 이벤트 핸들러에서 sessionId만으로 참가자를 O(1)로 조회할 수 있으며,
 * 세션 교체 시 구세션 항목을 ConcurrentHashMap.remove(key, value)로 조건부 제거하여
 * 신규 세션 매핑을 보호한다.
 *
 * @author SeJon Jang (wkdtpwhs@gmail.com)
 */
@Repository
@Slf4j
public class KurentoParticipantRepository extends InMemoryParticipantRepository<String, KurentoUserSession> {

    /**
     * WebSocket sessionId → KurentoUserSession 역색인 맵.
     * 세션 교체 시 구세션과 신규 세션이 동시에 존재할 수 있으므로
     * ConcurrentHashMap.remove(key, value) 방식으로 조건부 제거한다.
     */
    private final ConcurrentHashMap<String, KurentoUserSession> usersBySessionId = new ConcurrentHashMap<>();

    /**
     * Kurento 참가자를 방에 추가하고 sessionId 역색인을 갱신한다.
     *
     * 처리 순서:
     * 1. 사전 조건 검증(validateParticipant): participant 또는 session이 null이면 예외
     * 2. 기존 동일 userId 참가자가 있으면 구세션의 sessionId 역색인 항목을 먼저 제거
     * 3. 부모 클래스의 참가자 맵 갱신 (computeIfAbsent + put)
     * 4. 신규 세션의 sessionId를 역색인에 등록
     *
     * @param roomId      대상 방 ID
     * @param userId      추가할 사용자 ID
     * @param participant 추가할 참가자 세션
     * @throws IllegalArgumentException participant 또는 session이 null인 경우
     */
    @Override
    public synchronized void addParticipant(String roomId, String userId, KurentoUserSession participant) {
        validateParticipant(roomId, userId, participant);

        // 동일 userId의 구세션이 있으면 역색인에서 먼저 정리하여 누락 항목이 남지 않도록 한다
        KurentoUserSession existingParticipant = getParticipant(roomId, userId);

        super.addParticipant(roomId, userId, participant);

        if (existingParticipant != null && existingParticipant.getSession() != null) {
            removeParticipantSessionMapping(existingParticipant.getSession().getId(), existingParticipant);
        }

        // 신규 세션의 sessionId를 역색인에 등록
        this.addParticipantBySessionId(participant.getSession().getId(), participant);

        log.debug("Kurento 세션 추가 완료. 방: {}, 사용자: {}, 세션ID: {}",
                roomId, userId, participant.getSession().getId());
    }

    /**
     * 방에서 참가자를 제거하고 sessionId 역색인도 함께 정리한다.
     * userId 맵과 sessionId 역색인을 동시에 정리하여 두 맵이 항상 일관된 상태를 유지한다.
     * 제거 대상이 없으면 아무 작업도 수행하지 않고 null을 반환한다.
     *
     * @param roomId 대상 방 ID
     * @param userId 제거할 사용자 ID
     * @return 제거된 KurentoUserSession, 없으면 null
     */
    @Override
    public synchronized KurentoUserSession removeParticipant(String roomId, String userId) {
        // 부모에서 userId 맵 제거
        KurentoUserSession removedParticipant = super.removeParticipant(roomId, userId);

        if (removedParticipant != null) {
            // sessionId 역색인도 함께 정리하여 고아 항목 방지
            if (removedParticipant.getSession() != null) {
                removeParticipantSessionMapping(removedParticipant.getSession().getId(), removedParticipant);
            }
            log.debug("Kurento 세션 제거 완료. 방: {}, 사용자: {}", roomId, userId);
        }

        return removedParticipant;
    }

    /**
     * 방 제거 전에 해당 방 참가자들의 sessionId 역색인을 먼저 정리한다.
     *
     * deleteKurentoRoom, shutdown 등 방 단위 teardown 경로에서는 room map 과 sessionId 역색인이
     * 함께 제거되어야 stale sessionId 조회가 남지 않는다.
     *
     * @param roomId 제거할 방 ID
     */
    @Override
    public synchronized void removeRoom(String roomId) {
        Map<String, KurentoUserSession> participants = getParticipants(roomId);
        for (KurentoUserSession participant : participants.values()) {
            if (participant == null || participant.getSession() == null) {
                continue;
            }
            removeParticipantSessionMapping(participant.getSession().getId(), participant);
        }

        super.removeRoom(roomId);
    }

    /**
     * sessionId를 키로 participant를 역색인에 추가한다.
     *
     * @param sessionId  WebSocket 세션 ID
     * @param participant 등록할 참가자 세션
     */
    private synchronized void addParticipantBySessionId(String sessionId, KurentoUserSession participant) {
        this.usersBySessionId.put(sessionId, participant);
    }

    /**
     * sessionId 기반으로 참가자 세션을 조회한다.
     *
     * @param sessionId 조회할 WebSocket 세션 ID
     * @return 해당 sessionId에 매핑된 KurentoUserSession, 없으면 null
     */
    public KurentoUserSession getParticipantBySessionId(String sessionId) {
        return this.usersBySessionId.get(sessionId);
    }

    /**
     * sessionId 역색인에서 지정된 세션 항목을 조건부로 제거한다.
     *
     * ConcurrentHashMap.remove(key, value)를 사용하여 값이 일치할 때만 제거한다.
     * 세션 교체 후 신규 세션의 매핑이 이미 등록된 경우 구세션 항목만 선택적으로 제거하여
     * 새 세션의 역색인 보호한다.
     *
     * @param sessionId   제거할 WebSocket 세션 ID (키)
     * @param participant 비교 기준이 될 참가자 세션 (값)
     */
    public synchronized void removeParticipantSessionMapping(String sessionId, KurentoUserSession participant) {
        this.usersBySessionId.remove(sessionId, participant);
    }

    /**
     * participant와 session이 null이 아닌지 사전 검증한다.
     *
     * @param roomId      대상 방 ID (로그 출력용)
     * @param userId      대상 사용자 ID (로그 출력용)
     * @param participant 검증할 참가자 세션
     * @throws IllegalArgumentException participant 또는 세션이 null인 경우
     */
    private void validateParticipant(String roomId, String userId, KurentoUserSession participant) {
        if (participant == null) {
            log.error("KurentoUserSession이 null입니다. 방: {}, 사용자: {}", roomId, userId);
            throw new IllegalArgumentException("KurentoUserSession cannot be null");
        }

        if (participant.getSession() == null) {
            log.error("WebSocket 세션이 null입니다. 방: {}, 사용자: {}", roomId, userId);
            throw new IllegalArgumentException("WebSocket session cannot be null");
        }
    }
}
