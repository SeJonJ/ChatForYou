package webChat.repository.kurento.participant;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 인메모리 기반 참가자 Repository 구현체.
 *
 * ConcurrentHashMap 이중 구조(roomId - userId - participant)로 방별 참가자를 관리한다.
 * 스레드 안전한 읽기를 위해 방어적 복사를 반환하고, 쓰기 작업은 synchronized 블록으로 직렬화한다.
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

    /**
     * 참가자를 방에 추가하거나 기존 세션을 교체한다.
     *
     * synchronized 블록 내 computeIfAbsent로 방 맵을 초기화한다.
     * 이미 동일 userId가 존재하면 warn 로그를 남긴 후 새 세션으로 교체한다.
     *
     * @param roomId      대상 방 ID
     * @param userId      추가할 사용자 ID
     * @param participant 추가할 참가자 세션
     */
    @Override
    public void addParticipant(K roomId, String userId, V participant) {
        int currentSize;
        synchronized (this) {
            Map<String, V> participants = roomParticipants.computeIfAbsent(roomId, k -> new ConcurrentHashMap<>());
            V previous = participants.put(userId, participant);
            currentSize = participants.size();
            if (previous != null && previous != participant) {
                log.warn("사용자 {} 가 이미 방 {} 에 존재합니다. 기존 세션을 새 세션으로 교체합니다.", userId, roomId);
            }
        }
        log.info("사용자 {}가 방 {}에 입장했습니다. 현재 인원: {}", userId, roomId, currentSize);
    }

    /**
     * 방에서 참가자를 제거한다.
     *
     * 제거 후 방이 비면 roomParticipants에서 방 자체도 제거하여 불필요한 빈 맵이 남지 않도록 한다.
     *
     * @param roomId 대상 방 ID
     * @param userId 제거할 사용자 ID
     * @return 제거된 참가자 세션, 없으면 null
     */
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

    /**
     * 특정 방의 참가자 맵을 방어적 복사로 반환한다.
     *
     * ConcurrentHashMap으로 복사하여 반환하므로 호출자가 반환값을 수정해도 내부 상태에 영향이 없다.
     * 방이 없으면 빈 ConcurrentHashMap을 반환한다.
     *
     * @param roomId 대상 방 ID
     * @return userId를 키, 참가자 세션을 값으로 하는 방어적 복사 맵
     */
    @Override
    public Map<String, V> getParticipants(K roomId) {
        Map<String, V> participants = roomParticipants.get(roomId);
        // 방어적 복사로 안전성 보장
        return participants != null ? new ConcurrentHashMap<>(participants) : new ConcurrentHashMap<>();
    }

    /**
     * 특정 방에서 userId로 참가자 세션을 조회한다.
     *
     * @param roomId 대상 방 ID
     * @param userId 조회할 사용자 ID
     * @return 해당 참가자 세션, 방이 없거나 참가자가 없으면 null
     */
    @Override
    public V getParticipant(K roomId, String userId) {
        Map<String, V> participants = roomParticipants.get(roomId);
        return participants != null ? participants.get(userId) : null;
    }

    /**
     * 방과 해당 방의 모든 참가자 데이터를 제거한다.
     *
     * @param roomId 제거할 방 ID
     */
    @Override
    public synchronized void removeRoom(K roomId) {
        Map<String, V> removedParticipants = roomParticipants.remove(roomId);
        if (removedParticipants != null) {
            log.info("방 {}가 제거되었습니다. 제거된 참가자 수: {}", roomId, removedParticipants.size());
        } else {
            log.debug("제거할 방 {}가 존재하지 않습니다.", roomId);
        }
    }

    /**
     * 특정 방에 해당 userId의 참가자가 존재하는지 확인한다.
     *
     * @param roomId 대상 방 ID
     * @param userId 확인할 사용자 ID
     * @return 참가자가 존재하면 true, 방이 없거나 참가자가 없으면 false
     */
    @Override
    public boolean containsParticipant(K roomId, String userId) {
        Map<String, V> participants = roomParticipants.get(roomId);
        return participants != null && participants.containsKey(userId);
    }

    /**
     * 특정 방의 현재 참가자 수를 반환한다.
     *
     * @param roomId 대상 방 ID
     * @return 참가자 수, 방이 없으면 0
     */
    @Override
    public int getParticipantCount(K roomId) {
        Map<String, V> participants = roomParticipants.get(roomId);
        return participants != null ? participants.size() : 0;
    }

    /**
     * 전체 방 스냅샷을 방어적 복사로 반환한다.
     *
     * ConcurrentHashMap으로 복사하여 반환하므로 호출자가 반환값을 수정해도 내부 상태에 영향이 없다.
     *
     * @return roomId를 키, 참가자 맵을 값으로 하는 방어적 복사 맵
     */
    @Override
    public Map<K, Map<String, V>> getAllRooms() {
        return new ConcurrentHashMap<>(roomParticipants);
    }
}
