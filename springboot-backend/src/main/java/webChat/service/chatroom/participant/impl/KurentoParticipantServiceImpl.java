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
import java.util.Objects;

/**
 * KurentoParticipantService 구현체.
 *
 * 실제 저장소 연산은 KurentoParticipantRepository에 위임하며,
 * 서비스 레이어는 비즈니스 로직(세션 동일성 검사, 조건부 제거 등)을 담당한다.
 *
 * @author SeJon Jang (wkdtpwhs@gmail.com)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KurentoParticipantServiceImpl implements KurentoParticipantService {

    private final KurentoParticipantRepository participantRepository;


    /**
     * 특정 방의 참가자 맵(userId → 세션)을 반환한다.
     *
     * @param roomId 대상 방 ID
     * @return 참가자 맵 (방이 없으면 빈 맵)
     */
    @Override
    public Map<String, KurentoUserSession> getParticipantMap(String roomId) {
        return participantRepository.getParticipants(roomId);
    }

    /**
     * 특정 방의 참가자 세션 컬렉션을 반환한다.
     *
     * @param roomId 대상 방 ID
     * @return 참가자 세션 값 컬렉션
     */
    @Override
    public Collection<KurentoUserSession> getParticipantList(String roomId) {
        return participantRepository.getParticipants(roomId).values();
    }

    /**
     * userId로 특정 방의 참가자 세션을 조회한다.
     *
     * @param roomId 대상 방 ID
     * @param userId 조회할 사용자 ID
     * @return 해당 참가자 세션, 없으면 {@code null}
     */
    public KurentoUserSession getParticipant(String roomId, String userId) {
        return participantRepository.getParticipant(roomId, userId);
    }

    /**
     * 참가자를 방에 추가하거나 기존 세션을 새 세션으로 교체한다.
     *
     * @param roomId      대상 방 ID
     * @param participant 추가할 참가자 세션
     */
    @Override
    public void addParticipant(String roomId, KurentoUserSession participant) {
        participantRepository.addParticipant(roomId, participant.getUserId(), participant);
    }

    /**
     * 방에서 참가자를 제거하고 sessionId 역색인도 함께 정리한다.
     *
     * @param roomId 대상 방 ID
     * @param userId 제거할 사용자 ID
     * @return 제거된 참가자 세션, 없으면 {@code null}
     */
    @Override
    public KurentoUserSession removeParticipant(String roomId, String userId) {
        return participantRepository.removeParticipant(roomId, userId);
    }

    /**
     * 방을 인메모리 저장소에서 완전히 제거한다.
     *
     * @param roomId 제거할 방 ID
     */
    public void removeRoom(String roomId) {
        participantRepository.removeRoom(roomId);
    }

    /**
     * 특정 방의 현재 참가자 수를 반환한다.
     * Redis userCount와의 동기화 확인 등에 활용된다.
     *
     * @param roomId 대상 방 ID
     * @return 참가자 수
     */
    @Override
    public int getParticipantCount(String roomId) {
        return participantRepository.getParticipantCount(roomId);
    }

    /**
     * 특정 방의 참가자 ID(userId) 집합을 반환한다.
     *
     * @param roomId 대상 방 ID
     * @return userId 집합
     */
    @Override
    public Collection<String> getParticipantIds(String roomId) {
        return participantRepository.getParticipants(roomId).keySet();
    }

    /**
     * WebSocket 세션 ID를 기반으로 참가자 세션을 조회한다.
     *
     * @param session 조회에 사용할 WebSocket 세션
     * @return 해당 세션에 매핑된 {@link KurentoUserSession}, 없으면 {@code null}
     */
    @Override
    public KurentoUserSession getBySessionId(WebSocketSession session) {
        return participantRepository.getParticipantBySessionId(session.getId());
    }

    /**
     * sessionId 역색인에서 지정된 세션 항목을 조건부로 제거한다.
     *
     * session 또는 participant가 null이면 아무 작업도 수행하지 않는다.
     * 실제 제거는 KurentoParticipantRepository.removeParticipantSessionMapping에서
     * ConcurrentHashMap.remove(key, value)로 처리되므로,
     * 신규 세션이 이미 등록된 경우에는 구세션 항목만 선택적으로 제거된다.
     *
     * @param session     제거할 WebSocket 세션
     * @param participant 비교 기준 참가자 세션
     */
    @Override
    public void removeSessionMappingIfMatched(WebSocketSession session, KurentoUserSession participant) {
        if (session == null || participant == null) {
            return;
        }

        participantRepository.removeParticipantSessionMapping(session.getId(), participant);
    }

    /**
     * 요청 세션이 방에 등록된 최신 세션과 동일한지 검사한다.
     *
     * 방에 등록된 참가자의 sessionId와 파라미터 session의 ID를 비교한다.
     * 세션이 null이거나 참가자가 존재하지 않으면 false를 반환한다.
     *
     * @param roomId  대상 방 ID
     * @param userId  검사할 사용자 ID
     * @param session 현재 요청의 WebSocket 세션
     * @return 세션이 최신 세션과 동일하면 true
     */
    @Override
    public boolean isCurrentParticipantSession(String roomId, String userId, WebSocketSession session) {
        if (session == null) {
            return false;
        }

        KurentoUserSession currentParticipant = participantRepository.getParticipant(roomId, userId);
        // 방에 등록된 세션 ID와 요청 세션 ID가 일치해야만 현재 세션으로 인정한다
        return currentParticipant != null
                && currentParticipant.getSession() != null
                && Objects.equals(currentParticipant.getSession().getId(), session.getId());
    }

}
