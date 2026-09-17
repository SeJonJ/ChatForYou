/* global module */
/**
 * 재연결·복구 흐름의 판단 로직 모음.
 * DOM·WebSocket·jQuery 에 의존하지 않아 브라우저와 Node 테스트에서 같은 코드를 검증한다.
 */
const ReconnectPolicy = {
    // 서버가 "잠시 후 다시 시도"로 응답하는 사유. 백엔드 RecoveryReason 이름과 문자열이 같아야 한다.
    RETRYABLE_RECOVERY_REASONS: [
        'CLAIM_IN_PROGRESS',
        'CURRENT_COOKIE_UNAVAILABLE',
        'OWNER_COOKIE_UNAVAILABLE',
        'INSTANCE_SHUTTING_DOWN'
    ],

    /**
     * 복구 응답 사유가 재시도 대상인지 판정한다.
     */
    isRetryableRecoveryReason: function (reason) {
        return ReconnectPolicy.RETRYABLE_RECOVERY_REASONS.indexOf(reason) !== -1;
    },

    /**
     * 빠른 재시도를 더 할지, 재연결 루프로 넘길지 결정한다.
     * 빠른 재시도는 초 단위 지연 상황을 위한 것이라 상한을 넘으면 방 목록으로 보내지 않고
     * 분 단위 백오프를 가진 재연결 루프가 이어받는다.
     */
    decideRecoveryRetry: function (retryCount, maxRetry) {
        return (retryCount < maxRetry) ? 'RETRY' : 'HAND_OFF';
    },

    /**
     * 다른 서버로 이동하라는 응답을 어떻게 처리할지 결정한다.
     * 통화 중 재연결에서 새로고침하면 대기 흐름이 초기화되므로 재연결로 수렴시킨다.
     */
    decideRedirectRoomAction: function (isReconnecting) {
        return isReconnecting ? 'RECONNECT' : 'RELOAD';
    },

    /**
     * 방 정보 조회 실패를 어떻게 처리할지 결정한다.
     * 재연결 중 실패를 무시하면 입장 요청이 나가지 않은 채 화면이 멈춘다.
     */
    decideRegisterFailureAction: function (isReconnecting) {
        return isReconnecting ? 'RECONNECT' : 'KEEP';
    },

    /**
     * 토글 버튼 상태를 켬/끔으로 해석한다. 상태가 없으면 켜짐으로 본다.
     */
    resolveMediaFlag: function (flag) {
        return flag !== false;
    },

    /**
     * 로컬 스트림 트랙에 켬/끔 상태를 적용한다.
     * 새로 연 트랙은 항상 켜진 상태라, 재연결 뒤 음소거가 풀린 채 소리가 나가는 것을 막는다.
     */
    applyLocalMediaStates: function (stream, isAudioOn, isVideoOn) {
        if (!stream) {
            return;
        }

        if (typeof stream.getAudioTracks === 'function') {
            stream.getAudioTracks().forEach(function (track) {
                track.enabled = isAudioOn;
            });
        }

        if (typeof stream.getVideoTracks === 'function') {
            stream.getVideoTracks().forEach(function (track) {
                track.enabled = isVideoOn;
            });
        }
    }
};

if (typeof window !== 'undefined') {
    window.ReconnectPolicy = ReconnectPolicy;
}

if (typeof module !== 'undefined' && module.exports) {
    module.exports = ReconnectPolicy;
}
