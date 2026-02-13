package webChat.repository.kurento.participant;

import java.util.Map;

/**
 * 확장 가능한 참가자 관리를 위한 공통 Repository 인터페이스
 *
 * @param <K> Room ID 타입 (일반적으로 String)
 * @param <V> Participant 타입 (KurentoUserSession, GeneralUserSession 등)
 * @author SeJon Jang (wkdtpwhs@gmail.com)
 */
public interface ParticipantRepository<K, V> {

        /**
         * 특정 방에 참가자 추가
         * @param roomId 방 ID
         * @param userId 사용자 ID
         * @param participant 참가자 세션 객체
         */
        void addParticipant(K roomId, String userId, V participant);

        /**
         * 특정 방에서 참가자 제거
         * @param roomId 방 ID
         * @param userId 사용자 ID
         * @return 제거된 참가자 세션 객체 (없으면 null)
         */
        V removeParticipant(K roomId, String userId);

        /**
         * 특정 방의 모든 참가자 조회
         * @param roomId 방 ID
         * @return 참가자 Map (userId -> participant)
         */
        Map<String, V> getParticipants(K roomId);

        /**
         * 특정 방의 특정 참가자 조회
         * @param roomId 방 ID
         * @param userId 사용자 ID
         * @return 참가자 세션 객체 (없으면 null)
         */
        V getParticipant(K roomId, String userId);

        /**
         * 특정 방 완전 제거 (모든 참가자 정리)
         * @param roomId 방 ID
         */
        void removeRoom(K roomId);

        /**
         * 특정 방에 특정 참가자가 존재하는지 확인
         * @param roomId 방 ID
         * @param userId 사용자 ID
         * @return 존재 여부
         */
        boolean containsParticipant(K roomId, String userId);

        /**
         * 특정 방의 참가자 수 조회 (Redis userCount와 동기화용)
         * @param roomId 방 ID
         * @return 참가자 수
         */
        int getParticipantCount(K roomId);

        /**
         * 모든 방의 참가자 정보 조회 (디버깅/모니터링용)
         * @return 전체 참가자 Map (roomId -> participants Map)
         */
        Map<K, Map<String, V>> getAllRooms();

}
