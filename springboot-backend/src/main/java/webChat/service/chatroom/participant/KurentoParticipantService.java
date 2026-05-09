package webChat.service.chatroom.participant;

import org.springframework.web.socket.WebSocketSession;
import webChat.service.kurento.KurentoUserSession;

import java.util.Collection;
import java.util.Map;

/**
 * Kurento 화상채팅 방 참가자 관리 서비스 인터페이스.
 *
 * 방별 참가자 CRUD와 WebSocket 세션 기반 역색인 조회를 제공한다.
 * stale disconnect 방지를 위한 세션 동일성 검사 및 조건부 세션 매핑 제거 기능도 포함한다.
 */
public interface KurentoParticipantService {

    /**
     * 특정 방의 참가자 맵(userId → 세션)을 반환한다.
     *
     * @param roomId 대상 방 ID
     * @return userId를 키, KurentoUserSession을 값으로 하는 방어적 복사 맵
     */
    Map<String, KurentoUserSession> getParticipantMap(String roomId);

    /**
     * 특정 방의 참가자 세션 컬렉션을 반환한다.
     *
     * @param roomId 대상 방 ID
     * @return 참가자 세션 컬렉션 (방이 없으면 빈 컬렉션)
     */
    Collection<KurentoUserSession> getParticipantList(String roomId);

    /**
     * userId로 특정 방의 참가자 세션을 조회한다.
     *
     * @param roomId 대상 방 ID
     * @param userId 조회할 사용자 ID
     * @return 해당 참가자 세션, 없으면 null
     */
    KurentoUserSession getParticipant(String roomId, String userId);

    /**
     * 참가자를 방에 추가하거나 기존 세션을 새 세션으로 교체한다.
     * 이미 동일 userId가 존재하면 sessionId 역색인을 갱신하여 새 세션으로 대체한다.
     *
     * @param roomId      대상 방 ID
     * @param participant 추가할 참가자 세션
     */
    void addParticipant(String roomId, KurentoUserSession participant);

    /**
     * 방에서 참가자를 제거하고 sessionId 역색인도 함께 정리한다.
     *
     * @param roomId 대상 방 ID
     * @param userId 제거할 사용자 ID
     * @return 제거된 KurentoUserSession, 없으면 null
     */
    KurentoUserSession removeParticipant(String roomId, String userId);

    /**
     * 방을 인메모리 저장소에서 완전히 제거한다.
     *
     * @param roomId 제거할 방 ID
     */
    void removeRoom(String roomId);

    /**
     * 특정 방의 현재 참가자 수를 반환한다.
     *
     * @param roomId 대상 방 ID
     * @return 참가자 수
     */
    int getParticipantCount(String roomId);

    /**
     * 특정 방의 참가자 ID(userId) 집합을 반환한다.
     *
     * @param roomId 대상 방 ID
     * @return userId 컬렉션
     */
    Collection<String> getParticipantIds(String roomId);

    /**
     * WebSocket 세션 ID를 기반으로 참가자 세션을 조회한다.
     * WebSocket 이벤트 핸들러에서 세션으로부터 참가자를 식별할 때 사용한다.
     *
     * @param session 조회에 사용할 WebSocket 세션
     * @return 해당 세션에 매핑된 KurentoUserSession, 없으면 null
     */
    KurentoUserSession getBySessionId(WebSocketSession session);

    /**
     * sessionId 역색인에서 지정된 세션 항목을 조건부로 제거한다.
     *
     * 세션 교체(replaceParticipant) 후 구세션의 WebSocket 종료 이벤트가 도달했을 때,
     * 이미 신규 세션으로 교체된 역색인 항목을 실수로 제거하지 않도록 보호한다.
     * ConcurrentHashMap.remove(key, value) 의미론을 따라 지정된 participant 값이
     * 현재 저장된 값과 동일할 때만 제거한다.
     *
     * @param session     제거할 WebSocket 세션 (sessionId 키로 사용)
     * @param participant 비교 기준이 될 참가자 세션 객체
     */
    void removeSessionMappingIfMatched(WebSocketSession session, KurentoUserSession participant);

    /**
     * 요청 세션이 방에 등록된 최신 세션과 동일한지 검사한다.
     *
     * stale disconnect 방지에 사용된다. 탭 새로고침이나 중복 접속으로 인해
     * 구세션의 afterConnectionClosed가 신규 세션을 잘못 제거하는 것을 막는다.
     * 방에 등록된 참가자의 sessionId와 파라미터로 받은 session의 ID가 일치하는지 확인한다.
     *
     * @param roomId  대상 방 ID
     * @param userId  검사할 사용자 ID
     * @param session 현재 요청의 WebSocket 세션
     * @return 세션이 최신 등록 세션과 동일하면 true, 아니면 false
     */
    boolean isCurrentParticipantSession(String roomId, String userId, WebSocketSession session);
}
