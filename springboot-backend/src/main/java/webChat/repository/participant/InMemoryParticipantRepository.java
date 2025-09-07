package webChat.repository.participant;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 인메모리 기반 참가자 Repository 구현체
 *
 * @param <K> Room ID 타입
 * @param <V> Participant 타입
 * @author SeJon Jang (wkdtpwhs@gmail.com)
 */
@Slf4j
public class InMemoryParticipantRepository<K, V> implements ParticipantRepository<K, V> {


    // 룸별 참가자 저장소: ConcurrentHashMap 으로 동시성 보장
    // Map<roomId, Map<userId, userSession>>
    private final Map<K, Map<String, V>> roomParticipants = new ConcurrentHashMap<>();

    @Override
    public synchronized void addParticipant(K roomId, String userId, V participant) {
        // computeIfAbsent로 원자적 연산 보장
        Map<String, V> participants = roomParticipants.computeIfAbsent(roomId, k -> new ConcurrentHashMap<>());

        // 이미 존재하는 참가자 처리 (동시 입장 시나리오)
        V existing = participants.putIfAbsent(userId, participant);
        if (existing != null) {
            log.warn("사용자 {}가 이미 방 {}에 존재합니다. 기존 세션을 새 세션으로 교체합니다.", userId, roomId);
            participants.put(userId, participant); // 새 세션으로 교체
        }

        log.info("사용자 {}가 방 {}에 입장했습니다. 현재 인원: {}", userId, roomId, participants.size());
    }

    @Override
    public synchronized V removeParticipant(K roomId, String userId) {
        Map<String, V> participants = roomParticipants.get(roomId);
        if (participants == null) {
            log.debug("방 {}가 존재하지 않습니다.", roomId);
            return null;
        }

        V removedParticipant = participants.remove(userId);
        if (removedParticipant != null) {
            log.info("사용자 {}가 방 {}에서 퇴장했습니다. 현재 인원: {}", userId, roomId, participants.size());

            // 방이 비어있으면 정리
            if (participants.isEmpty()) {
                roomParticipants.remove(roomId);
                log.debug("방 {} 가 비어있어 유저를 정리했습니다.", roomId);
            }
        } else {
            log.debug("방 {}에서 사용자 {}를 찾을 수 없습니다.", roomId, userId);
        }

        return removedParticipant;
    }

    @Override
    public Map<String, V> getParticipants(K roomId) {
        Map<String, V> participants = roomParticipants.get(roomId);
        // 방어적 복사로 안전성 보장
        return participants != null ? new ConcurrentHashMap<>(participants) : new ConcurrentHashMap<>();
    }

    @Override
    public V getParticipant(K roomId, String userId) {
        Map<String, V> participants = roomParticipants.get(roomId);
        return participants != null ? participants.get(userId) : null;
    }

    @Override
    public synchronized void removeRoom(K roomId) {
        Map<String, V> removedParticipants = roomParticipants.remove(roomId);
        if (removedParticipants != null) {
            log.info("방 {}가 제거되었습니다. 제거된 참가자 수: {}", roomId, removedParticipants.size());
        } else {
            log.debug("제거할 방 {}가 존재하지 않습니다.", roomId);
        }
    }

    @Override
    public boolean containsParticipant(K roomId, String userId) {
        Map<String, V> participants = roomParticipants.get(roomId);
        return participants != null && participants.containsKey(userId);
    }

    @Override
    public int getParticipantCount(K roomId) {
        Map<String, V> participants = roomParticipants.get(roomId);
        return participants != null ? participants.size() : 0;
    }

    @Override
    public Map<K, Map<String, V>> getAllRooms() {
        return new ConcurrentHashMap<>(roomParticipants);
    }
}
