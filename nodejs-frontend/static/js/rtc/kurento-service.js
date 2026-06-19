/*
 * Copyright 2023 SejonJang (wkdtpwhs@gmail.com)
 *
 * Licensed under the  GNU General Public License v3.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.gnu.org/licenses/gpl-3.0.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

let locationHost = window.__CONFIG__.API_BASE_URL.replace(/^https?:\/\//, '').replace(/:\d+$/, '');
let participants = {};
// 재연결 버튼 5분 카운터 타이머 — 방 퇴장 시 clearTimeout 처리 필요
const pendingReconnectTimers = new Map();
// reconnect 클릭 시 nickName 복원용 — peer 수신 실패 시 저장, reconnect 클릭 후 삭제
const reconnectMetaCache = new Map(); // participantId → { userId, nickName }

let userId = null;
let nickName = null;
let roomId = null;
let roomName = null;

// turn Config
let turnUrls = null;   // string[] — 백엔드 응답 urls 배열을 그대로 수용 (RTCIceServer.urls 는 string[] 허용)
let turnUser = null;
let turnPwd = null;
let peerReconnectTimeoutMs = 5 * 60 * 1000; // 기본 5분, initTurnServer에서 서버 설정값으로 덮어씀

let origGetUserMedia;

// ==========================================
// WebSocket 메시지 핸들러 맵
// TODO 추후 별도의 js 로 분리 필요
// ==========================================

/**
 * 경고 토스트 표시 유틸리티
 * @param {string} text - 표시할 메시지
 * @param {number} duration - 표시 시간 (ms)
 */
function showWarningToast(text, duration = 4000) {
    Toastify({
        text: text,
        duration: duration,
        newWindow: true,
        close: true,
        gravity: "top",
        position: "center",
        style: {
            background: "linear-gradient(to right, #FF6B6B, #FFE66D)",
        },
    }).showToast();
}

/**
 * 녹화 에러 핸들러 일괄 생성
 * @param {string[]} errorTypes - 에러 타입 배열
 * @returns {Object} 에러 타입별 핸들러 객체
 */
function createRecordingErrorHandlers(errorTypes) {
    return errorTypes.reduce((handlers, errorType) => {
        handlers[errorType] = (msg) => {
            showWarningToast(msg?.message);
            recording?.handleRecordingError?.(msg);
        };
        return handlers;
    }, {});
}

/**
 * WebSocket 메시지 핸들러 정의
 * - 각 메시지 타입별 처리 로직을 분리
 * - 새로운 메시지 타입 추가 시 이 객체에 핸들러만 추가하면 됨
 */
const wsMessageHandlers = {
    // ==========================================
    // 1. 참가자 관련
    // ==========================================
    existingParticipants: (msg) => {
        // 재연결 성공 판정은 existingParticipants 수신 시점
        // onopen만으로 리셋하면 JOIN_ROOM 실패 시 폭주 가능하므로 여기서 성공 확정
        resetRecoveryState();
        resetReconnectState();
        onExistingParticipants(msg);
    },
    newParticipantArrived: (msg) => onNewParticipant(msg),
    participantLeft: (msg) => onParticipantLeft(msg),
    participantSessionReplaced: (msg) => onParticipantSessionReplaced(msg),
    sessionReplaced: (msg) => onSessionReplaced(msg),

    // ==========================================
    // 2. WebRTC 연결 관련
    // ==========================================
    receiveVideoAnswer: (msg) => receiveVideoResponse(msg),
    iceCandidate: (msg) => {
        const participant = participants[msg.name];
        if (participant?.rtcPeer) {
            participant.rtcPeer.addIceCandidate(msg.candidate, (error) => {
                // error.message는 TURN credential 포함 가능 — name만 로깅
            if (error) console.warn('[WebRTC:ICE] addIceCandidate 실패:', error?.name);
            });
        }
    },
    connectionFailed: (msg) => {
        showConnectionFailModal({
            title: '연결 실패',
            message: '서버와의 연결 불안정으로 인해 연결이 종료되었습니다.\n방에 재입장하여 다시 연결해주시기 바랍니다.',
            dismissible: true,
            onConfirm: () => {
                leaveRoom('error');
                setTimeout(function () {
                    window.location.reload();
                }, 20);
            }
        });
    },

    // ==========================================
    // 3. 기타
    // ==========================================
    textOverlaySuccess: (msg) => console.debug('textOverlaySuccess', msg),

    // ==========================================
    // 4. 녹화 관련
    // ==========================================
    recordingStarted: (msg) => console.debug('recordingStarted', msg),
    recordingStopped: (msg) => console.debug('recordingStopped', msg),
    recordingAutoStopped: (msg) => {
        if (recording?.handleAutoStopRecording) {
            recording.handleAutoStopRecording(msg);
        } else {
            console.warn('recording.handleAutoStopRecording is not defined');
        }
    },
    uploadCompleted: (msg) => {
        console.log('uploadCompleted', msg);
        if (recording?.handleUploadCompleted) {
            recording.handleUploadCompleted(msg);
        } else {
            // 녹화 모듈이 초기화되지 않은 경우에도 최소한의 완료 피드백은 유지한다.
            showToast(msg.message || '녹화가 완료되었습니다.', 'success');
        }
    },
    uploadFailed: (msg) => {
        console.log('uploadFailed', msg);
        if (recording?.handleUploadFailed) {
            recording.handleUploadFailed(msg);
        } else {
            // 업로드 실패는 화면 전환 없이 즉시 재시도 판단이 가능해야 하므로 경고 토스트로 남긴다.
            showWarningToast(msg.message || '녹화 업로드에 실패했습니다.');
        }
    },
    recordingInProgress: (msg) => recording.recordingInProgress(msg),
    participantRecordingError: (msg) => recording.participantRecordingError(msg),

    // ==========================================
    // 5. 녹화 에러 그룹 (공통 처리)
    // ==========================================
    ...createRecordingErrorHandlers([
        'alreadyRecordingError',
        'notRecordingError',
        'recordingEndpointNotFoundError',
        'permissionDeniedError',
        'recordingFileExistsError',
        'recordingAutoStopFailed'
    ]),

    // ==========================================
    // 6. 서버 에러 (표준 에러 응답 처리)
    // ==========================================
    error: (msg) => {
        // K008(방에 없는 참가자) = peer 단위 실패이므로 leaveRoom 하지 않는다. U001은 K008 배포 전 전이 fallback.
        // detail 유무와 무관하게 여기서 종결한다 — handleApiError로 흘리면 U001이 AUTH_REQUIRED에 걸려 leaveRoom 루프가 재발한다.
        if (msg.code === 'K008' || msg.code === 'U001') {
            if (msg.detail) {
                handlePeerSetupError({
                    participantId: msg.detail,
                    role: 'remote',
                    phase: 'server-error',
                    error: new Error(msg.message || '영상 연결 실패')
                });
            } else {
                showWarningToast(msg.message || '상대방과의 연결에 실패했습니다.');
            }
            return;
        }
        // handleApiError는 ajaxUtil.js에 선언되어 있으므로 jqXHR 구조로 래핑
        handleApiError({ responseJSON: msg });
    }
};

// roomId에서 하이픈을 제거한 _connected 키 생성
function getConnectedKey() {
    const connectedRoomId = new URLSearchParams(window.location.search).get('roomId');
    return connectedRoomId.replaceAll('-', '') + '_connected';
}

// _connected 키 정리 헬퍼
function clearConnectedSession() {
    for (let i = sessionStorage.length - 1; i >= 0; i--) {
        const key = sessionStorage.key(i);
        if (key && key.endsWith('_connected')) {
            sessionStorage.removeItem(key);
        }
    }
}

// WebSocket 지연 생성: 새로고침 시 autoplay 정책 우회
let ws = null;
let roomTeardownStarted = false;
let forcedSessionExitInProgress = false;
let suppressWebSocketCloseWarning = false;

// ==========================================
// 자동 재연결 상태 변수
// ==========================================
let reconnectAttempt = 0;
let reconnectInProgress = false;
let reconnectTimerId = null;
let waitingForOnline = false;
let heartbeatTimerId = null;
let onlineDebounceTimerId = null;
let recoveryInProgress = false;
let recoveryRetryCount = 0;
let recoveryRetryTimerId = null;
let roomListRedirectInProgress = false;
let reconnectNoticeMessage = null;
// 배포 재연결 안내(DEPLOY_RECONNECT_NOTICE)의 남은 시간 카운트다운 표시용. 성공/취소/퇴장 시 정지해 누수 방지.
let reconnectCountdownTimerId = null;
let reconnectCountdownDeadline = null;

const RECONNECT_BASE_DELAY_MS = 1000;
const RECONNECT_MAX_DELAY_MS = 30000;
const RECONNECT_JITTER_MS = 500;
// 자동 재연결 대기 윈도우(ms). 이 시간 동안 DEPLOY_RECONNECT_NOTICE 오버레이를 유지하며 재연결을 시도하고,
// 초과 시 수동 재입장 모달로 수렴한다. window.__CONFIG__.RECONNECT_WINDOW_MS 로 조정(기본 180000=3분).
const RECONNECT_WINDOW_MS = (window.__CONFIG__ && Number(window.__CONFIG__.RECONNECT_WINDOW_MS)) || 180000;
const RECONNECT_MAX_ATTEMPTS = (function () {
    let elapsed = 0;
    let attempts = 0;
    while (elapsed < RECONNECT_WINDOW_MS && attempts < 60) {
        elapsed += Math.min(RECONNECT_BASE_DELAY_MS * Math.pow(2, attempts), RECONNECT_MAX_DELAY_MS);
        attempts++;
    }
    return Math.max(attempts, 1);
})();
const RECOVERY_MAX_RETRY = 3;
const RECOVERY_RETRY_DELAY_MS = 500;
const DEPLOY_RECONNECT_NOTICE = '서버 패치가 진행 중입니다. 잠시 후 자동으로 재연결됩니다.';
// heartbeat 타이머는 ws.readyState 폴링 주기로만 사용 (idle 능동 close 없음)
const HEARTBEAT_INTERVAL_MS = 20000;
// WiFi 토글 시 OS가 online 이벤트를 다발 발화 — 마지막 1회로 흡수해 중복 재연결 방지
const ONLINE_DEBOUNCE_MS = 400;

function formatModalMessage(message) {
    return (message || '').replace(/\n/g, '<br>');
}

function showConnectionFailModal(options) {
    const modal = $('#connectionFailModal');
    const title = options?.title || '연결 실패';
    const message = options?.message || '';
    const dismissible = options?.dismissible !== false;

    modal.attr('data-backdrop', dismissible ? true : 'static');
    modal.attr('data-keyboard', dismissible ? true : false);
    modal.find('.modal-title').text(title);
    modal.find('.modal-body p').html(formatModalMessage(message));
    modal.find('.modal-header .close').toggle(dismissible);
    $('#reconnectButton')
        .text(options?.confirmText || '확인')
        .off('click')
        .on('click', function () {
            if (typeof options?.onConfirm === 'function') {
                options.onConfirm();
            }
        });
    modal.modal('show');
}

function closeWebSocketConnection() {
    if (!ws) {
        return;
    }

    const currentSocket = ws;
    ws = null;
    if (currentSocket.readyState === WebSocket.OPEN || currentSocket.readyState === WebSocket.CONNECTING) {
        currentSocket.close();
    }
}

/**
 * 예약된 recovery claim 재시도 타이머를 취소한다.
 */
function cancelRecoveryRetry() {
    if (recoveryRetryTimerId !== null) {
        clearTimeout(recoveryRetryTimerId);
        recoveryRetryTimerId = null;
    }
}

/**
 * recovery 진행/재시도 상태를 초기화한다.
 */
function resetRecoveryState() {
    cancelRecoveryRetry();
    recoveryInProgress = false;
    recoveryRetryCount = 0;
}

/**
 * 자동 재연결 중 사용자에게 보여줄 안내 문구를 정한다.
 */
function setReconnectNoticeMessage(message) {
    reconnectNoticeMessage = message;
    // 배포 안내일 때는 남은 시간 카운트다운이 오버레이를 갱신한다(중복 표시 방지).
    if (message === DEPLOY_RECONNECT_NOTICE) {
        startReconnectCountdown();
    } else {
        updateReconnectOverlay(message);
    }
}

/**
 * 현재 통화 컨텍스트의 roomId를 런타임 상태 또는 URL에서 가져온다.
 */
function getTargetRoomId() {
    return roomId || new URLSearchParams(window.location.search).get('roomId');
}

/**
 * 복구 불가 상태를 단일 room list 이동으로 수렴시킨다.
 * WHY: recovery/register/onclose가 겹치더라도 redirect는 1회만 발생해야 한다.
 * @param {string} message
 */
function redirectToRoomListOnce(message) {
    if (roomListRedirectInProgress) {
        return;
    }

    roomListRedirectInProgress = true;
    reconnectNoticeMessage = null;
    cancelReconnect();
    resetRecoveryState();
    clearConnectedSession();
    if (ws) {
        ws.onclose = null;
        ws.onerror = null;
        ws.onmessage = null;
    }
    closeWebSocketConnection();

    if (message) {
        showWarningToast(message, 3000);
    }

    setTimeout(function() {
        window.location.replace(window.__CONFIG__.BASE_URL + '/roomlist.html');
    }, 2000);
}

/**
 * recovery 성공 후 기존 소켓을 정리하고 새 sticky cookie 기준으로 signal 채널을 다시 연다.
 * WHY: recovery 응답의 Set-Cookie는 이미 열린 WebSocket에는 반영되지 않으므로 새 연결이 필요하다.
 */
function reconnectWebSocketAfterRecovery() {
    reconnectInProgress = true;
    recoveryInProgress = false;
    stopHeartbeat();

    if (ws) {
        ws.onclose = null;
        ws.onerror = null;
        ws.onmessage = null;
    }

    closeWebSocketConnection();
    connectWebSocket();
}

/**
 * reconnect preflight 실패를 기존 WebSocket 재연결 백오프로 되돌린다.
 */
function retryReconnectAfterPreflightFailure(error) {
    if (isIntentionalClose()) {
        return;
    }

    const errorCode = error?.responseJSON?.code;
    if (isAuthRequiredErrorCode(errorCode)) {
        showWarningToast(getApiErrorMessage(error?.responseJSON, '로그인이 필요한 서비스입니다.'));
        if (reconnectInProgress) {
            // 기존 register()의 reconnect 중 auth 실패 정책과 맞춘다. 즉시 로그인 이동하면 통화 컨텍스트가 먼저 파괴된다.
            recoveryInProgress = false;
            reconnectInProgress = false;
            scheduleReconnect();
        } else {
            clearConnectedSession();
            redirectToLogin();
        }
        return;
    }

    if (isInvalidRoomAccessErrorCode(errorCode) || errorCode === 'R001') {
        redirectToRoomListOnce(getApiErrorMessage(error?.responseJSON, '입장 정보가 확인되지 않았습니다. 다시 시도해주세요.'));
        return;
    }

    console.warn('[WebSocket:Reconnect] room preflight failed, will retry:', error?.status || error?.statusText || error);
    if (!reconnectNoticeMessage && error?.status === 0) {
        setReconnectNoticeMessage(DEPLOY_RECONNECT_NOTICE);
    }
    recoveryInProgress = false;
    reconnectInProgress = false;
    scheduleReconnect();
}

/**
 * WebSocket 재오픈 전에 HTTP로 방 상태를 확인해 deploy recovery 필요 여부를 판정한다.
 */
function preflightRoomBeforeReconnect() {
    const targetRoomId = getTargetRoomId();
    const url = window.__CONFIG__.API_BASE_URL + '/chat/room/' + targetRoomId;

    tokenAjax(
        url,
        'GET',
        true,
        '',
        function(response) {
            const { result, data } = response || {};

            if (isIntentionalClose()) {
                return;
            }

            if (result === 'REDIRECT_RECOVER') {
                recoverRoomAndReconnect();
                return;
            }

            if (result === 'REDIRECT_DASHBOARD') {
                redirectToRoomListOnce('현재 방에 참여할 수 없습니다. 잠시 후 다시 시도해주세요.');
                return;
            }

            if (result === 'REDIRECT_ROOM') {
                resetRecoveryState();
                clearConnectedSession();
                console.log('room redirect to : ', data?.roomId);
                location.reload();
                return;
            }

            // WebSocket 연결 자체가 실패하는 배포/재시작 구간에서는 register()까지 도달하지 못한다.
            // 그래서 재연결 경로만 HTTP로 방 상태를 먼저 확인하고, 복구가 필요 없을 때에만 signal 채널을 연다.
            connectWebSocket();
        },
        retryReconnectAfterPreflightFailure
    );
}

/**
 * REDIRECT_RECOVER 응답 시 room owner recovery API를 선행 호출한 뒤 재연결한다.
 */
function recoverRoomAndReconnect() {
    if (isIntentionalClose()) {
        return;
    }

    if (recoveryInProgress) {
        return;
    }

    recoveryInProgress = true;
    cancelRecoveryRetry();

    const targetRoomId = getTargetRoomId();
    const url = window.__CONFIG__.API_BASE_URL + '/chat/room/' + targetRoomId + '/recover';

    tokenAjax(
        url,
        'POST',
        true,
        '',
        function(response) {
            const { result, data } = response || {};

            if (result === 'SUCCESS') {
                reconnectWebSocketAfterRecovery();
                return;
            }

            if (result === 'REDIRECT_RECOVER'
                    && (data?.reason === 'CLAIM_IN_PROGRESS' || data?.reason === 'CURRENT_COOKIE_UNAVAILABLE')) {
                if (recoveryRetryCount >= RECOVERY_MAX_RETRY) {
                    redirectToRoomListOnce('방 복구가 지연되고 있습니다. 잠시 후 방 목록에서 다시 입장해주세요.');
                    return;
                }

                recoveryRetryCount++;
                recoveryInProgress = false;
                // claim lock 경합은 짧게 양보해야 여러 브라우저가 동시에 새 WebSocket을 열지 않는다.
                recoveryRetryTimerId = setTimeout(function() {
                    recoveryRetryTimerId = null;
                    recoverRoomAndReconnect();
                }, data?.retryAfterMs || RECOVERY_RETRY_DELAY_MS);
                return;
            }

            if (result === 'REDIRECT_DASHBOARD') {
                // 서버가 복구 불가로 확정한 뒤에는 reconnect loop보다 room list 이동이 우선이다.
                redirectToRoomListOnce('현재 방을 복구할 수 없습니다. 방 목록으로 이동합니다.');
                return;
            }

            recoveryInProgress = false;
            if (reconnectInProgress) {
                reconnectInProgress = false;
                scheduleReconnect();
            }
        },
        function(error) {
            const errorCode = error?.responseJSON?.code;

            recoveryInProgress = false;

            if (isAuthRequiredErrorCode(errorCode)) {
                showWarningToast(getApiErrorMessage(error?.responseJSON, '로그인이 필요한 서비스입니다.'));
                if (!reconnectInProgress) {
                    clearConnectedSession();
                    redirectToLogin();
                } else if (ws) {
                    ws.close();
                } else {
                    reconnectInProgress = false;
                    scheduleReconnect();
                }
                return;
            }

            if (isInvalidRoomAccessErrorCode(errorCode) || errorCode === 'R001') {
                redirectToRoomListOnce(getApiErrorMessage(error?.responseJSON, '입장 정보가 확인되지 않았습니다. 다시 시도해주세요.'));
                return;
            }

            if (reconnectInProgress) {
                console.warn('[WebSocket:Reconnect] room recovery failed, will retry:', error?.status || error?.statusText || error);
                reconnectInProgress = false;
                scheduleReconnect();
                return;
            }

            handleApiError(error);
            if (ws) {
                ws.close();
            }
        },
        null,
        {
            roomId: targetRoomId
        }
    );
}

// ==========================================
// 자동 재연결 헬퍼 함수
// ==========================================

/**
 * 의도적 종료 여부 판정.
 * 세 가드 중 하나라도 true면 재연결 금지.
 * @returns {boolean}
 */
function isIntentionalClose() {
    return suppressWebSocketCloseWarning || roomTeardownStarted || forcedSessionExitInProgress;
}

function stopHeartbeat() {
    if (heartbeatTimerId !== null) {
        clearInterval(heartbeatTimerId);
        heartbeatTimerId = null;
    }
}

/**
 * 진행 중인 재연결 시도를 전부 취소한다.
 * teardownRoomSession/leaveRoom 진입 시 doReconnect와의 경쟁을 막기 위해 즉시 호출한다.
 */
function cancelReconnect() {
    if (reconnectTimerId !== null) {
        clearTimeout(reconnectTimerId);
        reconnectTimerId = null;
    }
    if (onlineDebounceTimerId !== null) {
        clearTimeout(onlineDebounceTimerId);
        onlineDebounceTimerId = null;
    }
    reconnectInProgress = false;
    waitingForOnline = false;
    reconnectNoticeMessage = null;
    stopReconnectCountdown();
    stopHeartbeat();
}

/**
 * WebSocket half-open 감지를 위한 경량 heartbeat를 시작한다.
 * ws.readyState 폴링으로 소켓이 OPEN이 아닐 때 재연결 경로(scheduleReconnect)에 수렴한다.
 * WHY: idle 기반 능동 close는 정상 통화 중 시그널링 무수신 구간에서 오발 루프를 유발할 수 있어 제거했다.
 */
function startHeartbeat() {
    stopHeartbeat();
    heartbeatTimerId = setInterval(function () {
        if (isIntentionalClose()) {
            stopHeartbeat();
            return;
        }
        if (!ws || ws.readyState !== WebSocket.OPEN) {
            stopHeartbeat();
            scheduleReconnect();
        }
    }, HEARTBEAT_INTERVAL_MS);
}

/**
 * 지수 백오프 + jitter로 재연결을 예약한다.
 * in-flight 중복 방지: reconnectInProgress가 true면 즉시 반환.
 * offline 상태면 타이머 없이 online 이벤트 대기로 전환.
 * MAX 도달 시 수동 재입장 모달(GIVE_UP).
 */
function scheduleReconnect() {
    if (isIntentionalClose()) {
        return;
    }
    if (reconnectInProgress) {
        return;
    }
    // 이미 예약된 재연결 타이머가 있으면 중복 예약 금지
    // RF-1로 reconnectInProgress=false가 풀린 구간에서 heartbeat와 onclose가 동시에 진입할 때 double-schedule 차단
    // 정상 타이머는 발화 시 reconnectTimerId=null 처리 후 doReconnect를 부르므로(아래 setTimeout 콜백) 단일 사이클은 안 깨진다
    if (reconnectTimerId !== null) {
        return;
    }
    if (reconnectAttempt >= RECONNECT_MAX_ATTEMPTS) {
        // heartbeat가 살아있으면 모달 중복 팝업 유발 가능 — GIVE_UP 전에 명시 정리
        stopHeartbeat();
        stopReconnectCountdown();
        console.warn('[WebSocket:Reconnect] 최대 재시도 횟수 초과, 수동 재입장 필요');
        showConnectionFailModal({
            title: '연결 실패',
            message: '서버와의 연결이 반복적으로 실패했습니다.\n방에 재입장하여 다시 연결해주시기 바랍니다.',
            dismissible: false,
            onConfirm: function () {
                leaveRoom('error');
                setTimeout(function () { window.location.reload(); }, 20);
            }
        });
        return;
    }
    if (!navigator.onLine) {
        waitingForOnline = true;
        updateReconnectOverlay('네트워크 연결 끊김. 온라인 복귀 대기 중...');
        return;
    }
    waitingForOnline = false;
    const baseDelay = Math.min(
        RECONNECT_BASE_DELAY_MS * Math.pow(2, reconnectAttempt),
        RECONNECT_MAX_DELAY_MS
    );
    const jitter = Math.floor(Math.random() * RECONNECT_JITTER_MS);
    const delay = baseDelay + jitter;
    console.log('[WebSocket:Reconnect] ' + (reconnectAttempt + 1) + '번째 재시도 예약 (delay=' + delay + 'ms)');
    // 배포 카운트다운이 동작 중이면 오버레이는 카운트다운이 갱신하므로 시도횟수로 덮어쓰지 않는다.
    if (reconnectCountdownTimerId === null) {
        const reconnectMessage = (reconnectNoticeMessage || '재연결 중...') + ' (' + (reconnectAttempt + 1) + '/' + RECONNECT_MAX_ATTEMPTS + ')';
        updateReconnectOverlay(reconnectMessage);
    }
    reconnectTimerId = setTimeout(function () {
        reconnectTimerId = null;
        doReconnect();
    }, delay);
}

/**
 * 실제 재연결을 실행한다.
 * 재입장 전 participants 전체를 dispose해 stale PeerConnection/타일 누수를 방지한다.
 * teardown 플래그는 세우지 않는다 — connectWebSocket이 내부에서 플래그를 리셋하기 때문이다.
 * 성공 판정은 existingParticipants 수신 시(resetReconnectState 호출).
 */
function doReconnect() {
    if (isIntentionalClose()) {
        return;
    }
    // 두 타이머가 동시에 doReconnect를 호출하는 race 차단
    // 첫 타이머 진입 시 reconnectInProgress=false라 통과하고 바로 true를 세움
    // 두 번째 타이머는 true를 보고 여기서 반환 → connectWebSocket 중복 실행 방지
    if (reconnectInProgress) {
        return;
    }
    reconnectInProgress = true;
    reconnectAttempt++;
    console.log('[WebSocket:Reconnect] doReconnect 실행 (attempt=' + reconnectAttempt + ')');
    Object.keys(participants).forEach(function (pid) {
        disposeParticipantEntry(pid);
    });
    participants = {};
    // 더미 비디오 캐시 무효화: 위 dispose가 cachedDummyStream 원본 트랙을 stop(→ended→canvas 애니메이션 정지)시키므로,
    // 캐시를 비우지 않으면 재입장 시 죽은 캐시를 clone해 검정 화면이 된다. 비워서 새 더미가 재생성되게 한다.
    if (window._cleanupDummyVideo) {
        window._cleanupDummyVideo();
    }
    pendingReconnectTimers.forEach(function (timerId) { clearTimeout(timerId); });
    pendingReconnectTimers.clear();
    reconnectMetaCache.clear();
    stopHeartbeat();
    // 구 소켓 핸들러 선 detach: 지연 도달하는 구 소켓 이벤트가 새 연결 상태를 오염시키지 못하게 한다.
    // onclose/onerror: scheduleReconnect 재진입 차단
    // onmessage: 지연 도달 메시지가 새 연결의 wsMessageHandlers를 잘못 실행하는 것 방지
    if (ws) {
        ws.onclose = null;
        ws.onerror = null;
        ws.onmessage = null;
    }
    closeWebSocketConnection();
    preflightRoomBeforeReconnect();
}

/**
 * 재연결 성공 후 재연결 상태를 초기화한다.
 * onopen만으로 리셋하면 JOIN 실패 시 폭주 가능하므로, existingParticipants 수신을 성공 기준으로 한다.
 */
function resetReconnectState() {
    if (!reconnectInProgress && reconnectAttempt === 0) {
        return;
    }
    reconnectAttempt = 0;
    reconnectInProgress = false;
    reconnectTimerId = null;
    waitingForOnline = false;
    roomListRedirectInProgress = false;
    reconnectNoticeMessage = null;
    stopReconnectCountdown();
    hideReconnectOverlay();
    showToast('재연결되었습니다.', 'success');
    console.log('[WebSocket:Reconnect] 재연결 성공, 상태 초기화');
}

/**
 * 남은 시간(ms)을 분:초 로 포맷한다.
 */
function formatRemainingTime(ms) {
    const totalSec = Math.max(0, Math.ceil(ms / 1000));
    const min = Math.floor(totalSec / 60);
    const sec = totalSec % 60;
    return min + ':' + (sec < 10 ? '0' + sec : sec);
}

/**
 * 배포 재연결 안내 오버레이에 자동 재연결까지 남은 시간을 1초 단위로 표시한다.
 * 이미 동작 중이면 재시작하지 않아 윈도우 시작 시점을 보존한다. 표시 전용이라 재연결 로직과 무관하다.
 */
function startReconnectCountdown() {
    if (reconnectCountdownTimerId !== null) {
        return;
    }
    reconnectCountdownDeadline = Date.now() + RECONNECT_WINDOW_MS;
    const render = function () {
        const remaining = reconnectCountdownDeadline - Date.now();
        updateReconnectOverlay((reconnectNoticeMessage || DEPLOY_RECONNECT_NOTICE) + ' (남은 시간 ' + formatRemainingTime(remaining) + ')');
        if (remaining <= 0) {
            stopReconnectCountdown();
        }
    };
    render();
    reconnectCountdownTimerId = setInterval(render, 1000);
}

/**
 * 카운트다운 타이머를 정지한다(setInterval 누수 방지).
 */
function stopReconnectCountdown() {
    if (reconnectCountdownTimerId !== null) {
        clearInterval(reconnectCountdownTimerId);
        reconnectCountdownTimerId = null;
    }
    reconnectCountdownDeadline = null;
}

function updateReconnectOverlay(message) {
    let $overlay = $('#ws-reconnect-overlay');
    if ($overlay.length === 0) {
        $overlay = $('<div id="ws-reconnect-overlay" style="position:fixed;top:0;left:0;width:100%;background:rgba(0,0,0,0.7);color:#fff;text-align:center;padding:8px 0;z-index:9999;font-size:14px;"></div>');
        $('body').prepend($overlay);
    }
    $overlay.text(message).show();
}

function hideReconnectOverlay() {
    $('#ws-reconnect-overlay').hide();
}

function disposeParticipantEntry(participantId) {
    const participant = participants[participantId];
    if (!participant) {
        return;
    }

    if (typeof recording !== 'undefined' && recording.audioMixer) {
        recording.removeParticipantAudio(participantId);
    }

    participant.dispose();
    if (participants[participantId] === participant) {
        delete participants[participantId];
    }
}

function teardownRoomSession(options) {
    if (roomTeardownStarted) {
        return;
    }

    roomTeardownStarted = true;
    forcedSessionExitInProgress = options?.forced === true;

    // in-flight doReconnect와의 경쟁 차단: 재연결 타이머/heartbeat/online 대기 즉시 취소
    cancelReconnect();
    resetRecoveryState();

    if (options?.sendLeaveRoom) {
        sendMessageToServer({
            event: 'LEAVE_ROOM',
            roomId: roomId,
            senderId: userId
        });
    }

    Object.keys(participants).forEach(function (participantId) {
        disposeParticipantEntry(participantId);
    });
    participants = {};

    // 재연결 타이머 전체 정리 — 방 퇴장 시 5분 카운터 누수 방지
    pendingReconnectTimers.forEach(function(timerId) { clearTimeout(timerId); });
    pendingReconnectTimers.clear();
    reconnectMetaCache.clear(); // reconnect nickName 캐시도 함께 정리

    if (window._cleanupDummyVideo) {
        window._cleanupDummyVideo();
    }

    if (options?.clearRoomToken !== false) {
        sessionStorage.removeItem('roomAccessToken');
    }

    if (options?.clearRoomCookie) {
        setCookie('room-id', '', -1);
    }

    clearConnectedSession();
    suppressWebSocketCloseWarning = true;
    closeWebSocketConnection();
}

function connectWebSocket() {
    roomTeardownStarted = false;
    forcedSessionExitInProgress = false;
    suppressWebSocketCloseWarning = false;
    ws = new WebSocket(window.__CONFIG__.API_BASE_URL.replace(/^http/, 'ws') + '/signal');

    ws.onopen = function () {
        console.log('[WebSocket] 연결 성공');
        sessionStorage.setItem(getConnectedKey(), 'true');
        register();
        // 재연결 경로면 initScript/initEvent 재호출 생략 — 이미 초기화된 모듈 중복 init 방지
        if (!reconnectInProgress) {
            initScript();
            initEvent();
        }
        startHeartbeat();
    };

    ws.onclose = function (event) {
        stopHeartbeat();
        // 의도적 종료(퇴장/강제퇴장/suppress)는 재연결 금지
        if (isIntentionalClose()) {
            return;
        }
        // guard 미설정 = 비의도적 종료 → close code와 무관하게 재연결한다.
        // WHY: 재연결 의도는 guard(suppress/teardown/forced — 우리가 세운 플래그)로만 판단하고,
        //      프로토콜 close code로 추정하지 않는다. 서버 graceful shutdown(재배포)은 ShutdownConfig가
        //      세션을 code=1000으로 닫지만 redis 방은 CREATED로 보존하므로, 1000을 "재연결 안 함"으로
        //      처리하면 재배포 후 복구 가능한 통화를 방치하게 된다. 진짜 종료된 방이면 재입장 시 R001 → GIVE_UP으로 수렴.
        console.warn('[WebSocket] 연결 종료 감지 (code=' + event.code + '), 재연결 시도');
        if (event.code === 1000) {
            setReconnectNoticeMessage(DEPLOY_RECONNECT_NOTICE);
        }
        clearConnectedSession();
        // doReconnect() 진행 중에 새 소켓이 existingParticipants 수신 전에 닫히면
        // reconnectInProgress=true 상태로 scheduleReconnect에 진입해 L347 가드에 막혀 영구 wedge된다.
        // 여기서 false로 푸는 것은 "실패 후 다음 시도를 허용"하는 것이지 성공 처리가 아니다.
        // 성공 판정(existingParticipants 수신)은 resetReconnectState()에서만 수행한다.
        reconnectInProgress = false;
        scheduleReconnect();
    };

    ws.onerror = function (error) {
        console.error('[WebSocket] 에러 발생:', error);
        clearConnectedSession();
        // onerror는 일반적으로 onclose로 이어져 scheduleReconnect가 호출된다.
        // scheduleReconnect 내 in-flight 가드로 중복 호출은 차단된다.
    };

    ws.onmessage = function (message) {
        const parsedMessage = JSON.parse(message.data);
        const handler = wsMessageHandlers[parsedMessage.id];
        if (handler) {
            handler(parsedMessage);
        } else {
            console.warn('Unrecognized message:', parsedMessage.id, parsedMessage);
            showWarningToast(parsedMessage?.message, 3000);
        }
    };
}

// 재연결 버튼 — 동적 생성 요소이므로 document delegation 1회 등록
$(document).on('click', '.reconnect-btn', function() {
    const participantId = $(this).data('participant-id');
    // 캐시에서 nickName 복원 — 없으면 userId만으로 폴백 (undefined 렌더링 방지)
    const meta = reconnectMetaCache.get(participantId) || { userId: participantId };
    // clearFallbackState가 placeholder 제거 + 타이머 취소 + 캐시 삭제를 일괄 처리
    clearFallbackState(participantId);
    // 사용자 명시 트리거로 1회 재연결 시도 (자동 재시도 아님)
    receiveVideo(meta);
});

// 새로고침 감지 및 연결 분기
$(function () {
    const connectedKey = getConnectedKey();

    if (sessionStorage.getItem(connectedKey) === 'true') {
        // 같은 방에서 새로고침: 재연결 모달
        $('#reconnectModal').modal('show');
        $('#reconnectModalBtn').off('click').on('click', function () {
            $('#reconnectModal').modal('hide');
            connectWebSocket();
        });
    } else {
        // 첫 입장 또는 다른 방: 이전 키 정리 후 즉시 연결
        clearConnectedSession();
        connectWebSocket();
    }

    // online/offline 리스너 1회 등록 ($(function(){...}) 단일 진입으로 중복 방지)
    window.addEventListener('offline', function () {
        if (reconnectTimerId !== null) {
            clearTimeout(reconnectTimerId);
            reconnectTimerId = null;
        }
        // 대기 중이던 online 디바운스도 취소 — offline 재진입 시 묵은 재연결 예약 방지
        if (onlineDebounceTimerId !== null) {
            clearTimeout(onlineDebounceTimerId);
            onlineDebounceTimerId = null;
        }
        if (!isIntentionalClose()) {
            waitingForOnline = true;
            updateReconnectOverlay('네트워크 연결 끊김. 온라인 복귀 대기 중...');
        }
    });

    window.addEventListener('online', function () {
        if (isIntentionalClose()) {
            return;
        }
        if (!waitingForOnline) {
            return;
        }
        // 디바운스: 다발 online 이벤트를 마지막 1회로 흡수. 직전 예약을 취소하고 재설정한다.
        if (onlineDebounceTimerId !== null) {
            clearTimeout(onlineDebounceTimerId);
        }
        onlineDebounceTimerId = setTimeout(function () {
            onlineDebounceTimerId = null;
            // 디바운스 대기 중 offline 복귀/의도적 종료가 발생했으면 재연결하지 않는다
            if (!waitingForOnline || isIntentionalClose()) {
                return;
            }
            waitingForOnline = false;
            console.log('[WebSocket:Reconnect] 온라인 복귀 감지, 즉시 재연결 시도');
            // 빠른 복귀를 위해 attempt 카운터를 낮춤(0 리셋이 아닌 이유: 이전 실패 이력 반영)
            reconnectAttempt = Math.max(0, reconnectAttempt - 1);
            scheduleReconnect();
        }, ONLINE_DEBOUNCE_MS);
    });
});

/**
 * TURN 자격증명을 발급받아 전역 상태에 저장한다.
 * 입장 시·재연결 경로(register 재호출) 에서 재호출되어 세션 자격증명을 갱신한다.
 * 인증 없이 정적 평문을 반환하던 /admin/turnconfig 를 대체 — 유출 시 폭발 반경을 TTL 이내로 한정.
 */
const initTurnServer = function () {
    const headers = Object.assign(
        { 'Content-Type': 'application/json' },
        buildTokenHeaders()  // Authorization + (비밀방이면) X-Room-Token 자동 포함
    );
    // getTargetRoomId(): 전역 roomId 세팅 전이어도 URL 파라미터로 roomId 를 보장
    const body = JSON.stringify({ roomId: getTargetRoomId() });

    fetchJson(
        window.__CONFIG__.API_BASE_URL + '/turn/credential',
        { method: 'POST', headers: headers, body: body },
        'TURN 자격증명을 발급받지 못했습니다.'
    )
        .then(response => {
            const { result, data } = response || {};
            if (result !== 'SUCCESS' || !data) {
                // 발급 실패 시 reload/재시도 금지 — STUN/host candidate 폴백으로 진행
                console.warn('[TURN] 자격증명 발급 실패');
                return;
            }
            turnUrls = data.urls;   // string[] — RTCIceServer.urls 에 배열로 주입
            turnUser = data.username;
            turnPwd  = data.credential;  // credential 은 직접 로깅 금지
            if (data.peerReconnectTimeoutMs != null && data.peerReconnectTimeoutMs > 0) {
                peerReconnectTimeoutMs = data.peerReconnectTimeoutMs;
            }
        })
        .catch(error => {
            // 발급 실패는 연결 차단이 아닌 경고 — turnUrls=null 상태로 STUN/host candidate 폴백
            console.error('[TURN] 자격증명 발급 오류:', error?.message || error);
        });
};

let initScript = function () {
    dataChannel.init();
    dataChannelChatting.init();
    dataChannelFileUtil.init();
    catchMind.init();
    recording.init();

    // 실시간 자막 기능
    initSpeechRecognition();
    initSubtitleUI();
}

let constraints = {
    audio: {
        autoGainControl: true,
        channelCount: 2,
        echoCancellation: true,
        latency: 0,
        noiseSuppression: true,
        sampleRate: 48000,
        sampleSize: 16,
        volume: 0.5
    },
    video: {
        width: { ideal: 1280, max: 1920 },
        height: { ideal: 720, max: 1080 },
        frameRate: { ideal: 30, min: 15, max: 30 }
    }
};

let initEvent = function(){
    $('#subtitleBtn').on('click', function(){
        toggleSubtitle();
    });    
    $('#screenShareBtn').on('click', function(){
        screenShare();
    });
}

// 오디오 권한과 입력 장치를 먼저 점검해 이후 WebRTC 초기화 실패를 줄인다.
function initializeMediaDevices() {
    return PopupLoader.loadPopup('audio_error')
        .then(function () {
            return checkAudioPermission();
        })
        .then(function (hasAudioPermission) {
            if (!hasAudioPermission.success) {
                return showAudioErrorModal(hasAudioPermission.errorType, hasAudioPermission.error);
            }

            return navigator.mediaDevices.getUserMedia({ audio: constraints.audio })
                .then(function (stream) {
                    stream.getTracks().forEach(function (track) {
                        track.stop();
                    });
                })
                .catch(function (error) {
                    console.error('Media devices initialization failed:', error);
                    return showAudioErrorModal(classifyMediaError(error), error);
                });
        });
}

// 함수 호출
initializeMediaDevices();

// getUserMedia 지원 확인
if (navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
    // 원본 getUserMedia 저장
    origGetUserMedia = navigator.mediaDevices.getUserMedia;
    let customGetUserMedia = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);

    // 더미 비디오 중복 생성 방지
    let hasDummyVideo = false;
    let cachedDummyStream = null;

    // getUserMedia 오버라이드 (에러 타입별 처리 개선)
    navigator.mediaDevices.getUserMedia = function (cs) {
        return customGetUserMedia(cs).catch(function (error) {
            console.error('[WebRTC:Media] getUserMedia 실패 -', error.name, ':', error.message);

            // 비디오 요청 실패 시 에러 타입별 처리
            if (cs.video) {
                // 에러 타입별 분기 처리
                if (error.name === 'NotAllowedError' || error.name === 'PermissionDeniedError') {
                    // 권한 거부: 즉시 더미 비디오 사용
                    console.warn('[WebRTC:Media] 권한 거부, 더미 비디오 사용');
                    return createDummyVideoStream(cs.audio);

                } else if (error.name === 'NotFoundError') {
                    // 장치 없음 or 제약조건 불일치: 완화된 constraints로 재시도
                    console.warn('[WebRTC:Media] 장치 없음, 완화된 constraints로 재시도');
                    return retryWithRelaxedConstraints(cs)
                        .catch(function(retryError) {
                            console.warn('[WebRTC:Media] 재시도 실패, 더미 비디오 사용:', retryError.name);
                            return createDummyVideoStream(cs.audio);
                        });

                } else if (error.name === 'NotReadableError' || error.name === 'TrackStartError') {
                    // 장치 사용 중: 1초 대기 후 재시도 (1회만)
                    console.warn('[WebRTC:Media] 장치 사용 중, 1초 후 재시도');
                    return delay(1000)
                        .then(function() {
                            return customGetUserMedia(cs);
                        })
                        .catch(function(retryError) {
                            console.warn('[WebRTC:Media] 재시도 실패, 더미 비디오 사용:', retryError.name);
                            return createDummyVideoStream(cs.audio);
                        });

                } else if (error.name === 'OverconstrainedError') {
                    // 제약조건 불만족: 완화된 constraints로 재시도
                    console.warn('[WebRTC:Media] 제약조건 불만족, 완화된 constraints로 재시도');
                    if (error.constraint) {
                        console.log('[WebRTC:Media] 실패한 제약조건:', error.constraint);
                    }
                    return retryWithRelaxedConstraints(cs)
                        .catch(function(retryError) {
                            console.warn('[WebRTC:Media] 재시도 실패, 더미 비디오 사용:', retryError.name);
                            return createDummyVideoStream(cs.audio);
                        });

                } else {
                    // 기타 에러: 즉시 더미 비디오 사용
                    console.warn('[WebRTC:Media] 알 수 없는 에러, 더미 비디오 사용:', error.name);
                    return createDummyVideoStream(cs.audio);
                }
            }

            return Promise.reject(error);
        });
    };

    /**
     * 더미 비디오 스트림 생성 (중복 생성 방지)
     */
    function createDummyVideoStream(audioConstraints) {
        // 이미 더미 비디오가 생성된 경우 트랙을 clone하여 반환
        // (rtcPeer.dispose() 시 공유 트랙이 stop되는 것을 방지)
        if (hasDummyVideo && cachedDummyStream) {
            console.log('[WebRTC:Media] 더미 비디오 캐시 재사용 (clone)');
            const clonedStream = new MediaStream();
            cachedDummyStream.getTracks().forEach(function(track) {
                clonedStream.addTrack(track.clone());
            });
            return Promise.resolve(clonedStream);
        }

        return customGetUserMedia({ audio: audioConstraints })
            .then(function (audioStream) {
                return getDummyVideoTrack().then(function (dummyVideoTrack) {
                    audioStream.addTrack(dummyVideoTrack);

                    // 캐시 저장
                    hasDummyVideo = true;
                    cachedDummyStream = audioStream;

                    console.log('[WebRTC:Media] 더미 비디오 트랙 추가 완료');
                    return audioStream;
                });
            })
            .catch(function (audioError) {
                console.error('[WebRTC:Media] 더미 비디오 오디오 획득 실패:', audioError);
                return Promise.reject(audioError);
            });
    }

    // 더미 비디오 캐시 정리 (세션 종료 시 호출)
    window._cleanupDummyVideo = function() {
        if (cachedDummyStream) {
            cachedDummyStream.getTracks().forEach(function(track) {
                if (track.readyState !== 'ended') {
                    track.stop();
                }
            });
            cachedDummyStream = null;
            hasDummyVideo = false;
            console.log('[WebRTC:Media] 더미 비디오 캐시 정리 완료');
        }
    };

    /**
     * 기본 constraints로 재시도
     * @param {Object} originalConstraints - 원본 제약조건
     * @returns {Promise<MediaStream>}
     */
    function retryWithRelaxedConstraints(originalConstraints) {
        // 모든 비디오 제약 조건 제거, 브라우저가 최적값 선택
        const relaxedConstraints = {
            video: true,  // 단순히 true만 전달
            audio: originalConstraints.audio
        };

        console.log('[WebRTC:Media] 완화된 제약조건으로 재시도:', JSON.stringify(relaxedConstraints.video));
        return customGetUserMedia(relaxedConstraints);
    }

    /**
     * 딜레이 유틸리티
     * Promise 기반 setTimeout 래퍼
     * @param {number} ms - 밀리초
     * @returns {Promise<void>}
     */
    function delay(ms) {
        return new Promise(function(resolve) {
            setTimeout(resolve, ms);
        });
    }

    // 더미 비디오 트랙 생성 (Promise 기반: 이미지 로드 완료 후 resolve)
    function getDummyVideoTrack() {
        return new Promise(function (resolve) {
            const canvas = document.createElement('canvas');
            canvas.width = 1280;
            canvas.height = 720;
            const ctx = canvas.getContext('2d');

            const randomImageNum = Math.floor(Math.random() * 4) + 1;
            const imagePath = 'images/webrtc/non-video/non_video_' + randomImageNum + '.png';

            const img = new Image();
            let imageLoaded = false;
            let drawX = 0, drawY = 0, drawWidth = 1280, drawHeight = 720;
            let intervalId = null;
            let resolved = false;

            // 캔버스에 프레임 그리기
            function drawFrame() {
                if (imageLoaded) {
                    ctx.fillStyle = '#000000';
                    ctx.fillRect(0, 0, canvas.width, canvas.height);
                    ctx.drawImage(img, drawX, drawY, drawWidth, drawHeight);
                } else {
                    ctx.fillStyle = '#2c3e50';
                    ctx.fillRect(0, 0, canvas.width, canvas.height);
                    ctx.fillStyle = 'white';
                    ctx.font = 'bold 32px Arial';
                    ctx.textAlign = 'center';
                    ctx.textBaseline = 'middle';
                    ctx.fillText('카메라 없음', canvas.width / 2, canvas.height / 2 - 20);
                    ctx.font = '20px Arial';
                    ctx.fillText('Camera Not Available', canvas.width / 2, canvas.height / 2 + 20);
                }
            }

            // 캔버스 스트림 생성 및 resolve 처리
            function resolveTrack() {
                if (resolved) return;
                resolved = true;

                // 첫 프레임 그리기
                drawFrame();

                // 키프레임 갱신을 위한 주기적 그리기
                intervalId = setInterval(drawFrame, 500);

                const dummyStream = canvas.captureStream(30);
                const videoTrack = dummyStream.getVideoTracks()[0];

                // 트랙 종료 시 interval 정리
                videoTrack.addEventListener('ended', function () {
                    if (intervalId) {
                        clearInterval(intervalId);
                        intervalId = null;
                        console.log('더미 비디오 interval 정리됨');
                    }
                });

                console.log('더미 비디오 스트림 생성:', videoTrack.id);
                resolve(videoTrack);
            }

            img.onload = function () {
                const imgRatio = img.width / img.height;
                const canvasRatio = canvas.width / canvas.height;

                if (imgRatio > canvasRatio) {
                    drawWidth = canvas.width;
                    drawHeight = img.height * (canvas.width / img.width);
                    drawX = 0;
                    drawY = (canvas.height - drawHeight) / 2;
                } else {
                    drawHeight = canvas.height;
                    drawWidth = img.width * (canvas.height / img.height);
                    drawX = (canvas.width - drawWidth) / 2;
                    drawY = 0;
                }

                imageLoaded = true;
                console.log('더미 이미지 로드 완료:', imagePath);
                resolveTrack();
            };

            img.onerror = function () {
                console.error('더미 이미지 로드 실패, 텍스트 fallback 사용:', imagePath);
                imageLoaded = false;
                resolveTrack();
            };

            img.src = imagePath;

            // 3초 타임아웃: 이미지 로드 지연 시 텍스트 fallback
            setTimeout(function () {
                if (!resolved) {
                    console.warn('더미 이미지 로드 타임아웃, 텍스트 fallback 사용');
                    resolveTrack();
                }
            }, 3000);
        });
    }
}


function register() {
    // kurentoroom.html 진입 시 서버에서 방/유저 정보 조회
    let kurentoRoomInfo = null;
    try {
        // 방 정보를 서버에서 조회
        const url = window.__CONFIG__.API_BASE_URL + '/chat/room/' + new URLSearchParams(window.location.search).get('roomId');
        const successCallback = (response) => {
            const { result, data } = response || {};
            if(result === 'REDIRECT_ROOM'){
                resetRecoveryState();
                clearConnectedSession();
                console.log('room redirect to : ', data?.roomId);
                location.reload();
            } else if (result === 'REDIRECT_RECOVER') {
                recoverRoomAndReconnect();
            } else if(result === 'REDIRECT_DASHBOARD'){
                redirectToRoomListOnce('현재 방에 참여할 수 없습니다. 잠시 후 다시 시도해주세요.');
            } else {
                if (data) {
                    kurentoRoomInfo = data;

                        // 방 정보가 있으면 필요한 데이터 할당
                        if (kurentoRoomInfo) {
                            // TODO userId 는 '@' 가 있어서 사용 불가능
                            userId = kurentoRoomInfo.nickName || kurentoRoomInfo.uuid;
                            nickName = kurentoRoomInfo.nickName;
                            roomId = kurentoRoomInfo.roomId;
                            roomName = kurentoRoomInfo.roomName;
                            // 추가 정보: userCount, maxUserCnt, roomPwd, secretChk, roomType 등
                        }
                        // roomId 할당 후 호출 — body에 확정된 roomId 를 담아 TURN 자격증명 발급
                        initTurnServer();
    
                        $('#room-header').text('ROOM ' + roomName);
                        $('#room').css('display', 'block');
    
                        let message = {
                            event: 'JOIN_ROOM',
                            roomId: roomId,
                            senderNickName : nickName,
                            senderId: userId,
                        }
                        sendMessageToServer(message);
                    
                }
            }
        };
        const errorCallback = (error) => {
            console.error('방 정보 조회 실패:', error);
            if (isAuthRequiredErrorCode(error?.responseJSON?.code)) {
                showWarningToast(getApiErrorMessage(error?.responseJSON, '로그인이 필요한 서비스입니다.'));
                // 자동 재연결 중(reconnectInProgress=true)이면 redirect 억제 — 통화 컨텍스트 유지
                // 초기 입장(reconnectInProgress=false)은 그대로 redirectToLogin → 미인증 첫 진입은 로그인으로 보내는 게 맞다
                if (!reconnectInProgress) {
                    clearConnectedSession();
                    redirectToLogin();
                } else {
                    // ws.close()로 onclose 유발 → guard 미설정이므로 재연결 경로로 수렴(reconnectInProgress=false → scheduleReconnect)
                    // 재연결 중 인증 실패는 토큰 갱신 후 다음 attempt에서 성공할 수 있으므로 stuck 방지가 목적
                    if (ws) { ws.close(); }
                }
            } else if (isInvalidRoomAccessErrorCode(error?.responseJSON?.code)
                    || error?.responseJSON?.code === 'R001') {
                showWarningToast(getApiErrorMessage(error.responseJSON, '입장 정보가 확인되지 않았습니다. 다시 시도해주세요.'));
                // 자동 재연결 중이면 redirect 억제 — 초기 입장에서만 방목록으로 이동
                if (!reconnectInProgress) {
                    clearConnectedSession();
                    redirectToRoomList();
                } else {
                    // R001(방 없음)은 재시도해도 성공하지 않으므로 GIVE_UP 경로로 유도
                    // ws.close() → onclose(guard 미설정) → scheduleReconnect → attempt 누적 → MAX 시 수동 재입장 모달
                    if (ws) { ws.close(); }
                }
            }
        };
        // AJAX 요청 실행
        tokenAjax(url, 'GET', false, '', successCallback, errorCallback);
    } catch (e) {
        console.error('kurentoRoomInfo 파싱 오류:', e);
    }
}

function onNewParticipant(request) {
    let newParticipant = request.data;
    receiveVideo(newParticipant);
}

/**
 * WebRTC peer 셋업/SDP 처리 실패 공통 처리.
 * 본인(role='self') 실패는 강제 퇴장 모달, 상대(role='remote') 실패는 Toast + 재연결 버튼.
 * @param {Object} ctx
 * @param {string} ctx.participantId - 실패 대상 userId
 * @param {'self'|'remote'} ctx.role - 본인 송수신 peer / 상대 수신 peer 구분
 * @param {'create'|'answer'} ctx.phase - 실패 단계
 * @param {Error} ctx.error - 원본 에러
 */
function handlePeerSetupError(ctx) {
    // 방 퇴장 진행 중이면 조용히 종료 — teardown 이후 도착한 비동기 콜백 무시
    if (roomTeardownStarted || forcedSessionExitInProgress) return;

    // error.message 는 TURN credential/IP 포함 가능 — name만 로깅
    console.error('[WebRTC:PeerError] participant=' + ctx.participantId +
        ' role=' + ctx.role + ' phase=' + ctx.phase, ctx.error?.name);

    // 본인 송수신 peer 실패 → 방에 머물 수 없으므로 강제 퇴장
    if (ctx.role === 'self') {
        // 즉시 퇴장하므로 dispose 와 모달 순서 무관
        if (participants[ctx.participantId]) {
            disposeParticipantEntry(ctx.participantId);
        }
        showConnectionFailModal({
            title: '미디어 연결 실패',
            message: '카메라/마이크 또는 네트워크 설정으로 인해\n통화를 시작할 수 없습니다.\n방에서 나간 뒤 다시 시도해주세요.',
            dismissible: false,
            onConfirm: () => leaveRoom('error')
        });
        return;
    }

    // 상대 수신 peer 실패 → dispose 전에 nickName 추출 (dispose 이후엔 participants 항목이 삭제됨)
    const displayName = (participants[ctx.participantId] && participants[ctx.participantId].nickName)
        ? participants[ctx.participantId].nickName
        : ctx.participantId;
    showWarningToast(displayName + '님과의 영상 연결이 일시적으로 끊겼습니다.');
    // placeholder 삽입은 dispose 전 — container DOM이 살아있어야 after()로 옆에 삽입 가능
    showParticipantPlaceholder(ctx.participantId, displayName);
    // reconnect 클릭 시 nickName 복원을 위해 dispose 전에 캐시 저장
    reconnectMetaCache.set(ctx.participantId, {
        userId: ctx.participantId,
        nickName: displayName
    });
    // placeholder 삽입 후 participant 정리 — ICE candidate race 차단 + stream track stop 보장
    if (participants[ctx.participantId]) {
        disposeParticipantEntry(ctx.participantId);
    }

    // 5분 후 새로고침 안내 Toast — 구조적 오류 시 사용자 안내
    const timerId = setTimeout(function() {
        showWarningToast('서버 연결이 원활하지 않습니다. 새로고침 후 다시 시도해 주세요.', 6000);
        pendingReconnectTimers.delete(ctx.participantId);
    }, peerReconnectTimeoutMs);
    pendingReconnectTimers.set(ctx.participantId, timerId);

    // 백엔드 통지 — Rate Limit 적용 (10초당 3회 초과 시 서버에서 silently drop)
    sendMessageToServer({
        event: 'PARTICIPANT_RECEIVE_FAILED',
        roomId: roomId,
        senderId: userId,
        targetUserId: ctx.participantId,
        phase: ctx.phase
    });
}

/**
 * 상대 수신 실패 fallback 상태(재연결 타이머/캐시/placeholder) 일괄 정리 헬퍼.
 * reconnect 클릭, 참가자 퇴장, 세션 교체 등 모든 정리 경로에서 공통 호출.
 * @param {string} participantId - 정리 대상 userId
 */
function clearFallbackState(participantId) {
    if (pendingReconnectTimers.has(participantId)) {
        clearTimeout(pendingReconnectTimers.get(participantId));
        pendingReconnectTimers.delete(participantId);
    }
    reconnectMetaCache.delete(participantId); // reconnect nickName 캐시도 함께 정리
    $('#participant-placeholder-' + participantId).remove();
}

/**
 * 상대 수신 peer 실패 시 participant 컨테이너 옆에 placeholder와 재연결 버튼을 삽입한다.
 * dispose 전에 호출되어야 $container가 존재하며, after()로 삽입해 dispose 후에도 DOM에 유지된다.
 * @param {string} participantId - 실패 대상 userId
 * @param {string} nickNameStr - 표시할 닉네임
 */
function showParticipantPlaceholder(participantId, nickNameStr) {
    // 실제 participant 컨테이너는 <div id="{userId}" class="participant [main]"> 구조
    const $container = $('#' + participantId);
    if ($container.length === 0) return;

    const placeholderId = 'participant-placeholder-' + participantId;
    if ($('#' + placeholderId).length > 0) return; // 중복 생성 방지

    // container 클래스를 그대로 물려받아 레이아웃 일관성 유지
    const containerClass = $container.attr('class') || 'participant';
    const $placeholder = $('<div>').attr('id', placeholderId).attr('class', containerClass + ' participant-placeholder-wrapper');
    const $inner = $('<div>').addClass('d-flex flex-column align-items-center justify-content-center h-100');
    const $nameSpan = $('<span>').addClass('placeholder-nickname text-muted small mb-2').text(nickNameStr);
    const $btn = $('<button>')
        .attr('id', 'reconnect-btn-' + participantId)
        .addClass('btn btn-sm btn-outline-secondary reconnect-btn')
        .attr('data-participant-id', participantId)
        .text('다시 연결');
    $inner.append($nameSpan, $btn);
    $placeholder.append($inner);
    // container 다음 위치에 삽입 — dispose 후 container가 제거돼도 placeholder는 DOM에 유지됨
    $container.after($placeholder);
}

function receiveVideoResponse(result) {
    if (!participants[result.name] || !participants[result.name].rtcPeer) return;
    participants[result.name].rtcPeer.processAnswer(result.sdpAnswer, function (error) {
        if (error) {
            return handlePeerSetupError({
                participantId: result.name,
                role: 'remote',
                phase: 'answer',
                error
            });
        }
    });
}

function callResponse(message) {
    if (message.response != 'accepted') {
        console.info('Call not accepted by peer. Closing call');
        stop();
    } else {
        webRtcPeer.processAnswer(message.sdpAnswer, function (error) {
            if (error) return console.error(error);
        });
    }
}

function onExistingParticipants(msg) {
    const participant = new Participant(userId, nickName, roomId);
    participants[userId] = participant;
    dataChannel.initDataChannelUser(participant);
    const video = participant.getVideoElement();
    const audio = participant.getAudioElement();

    function handleSuccess(stream) {
        const hasVideo = constraints.video && stream.getVideoTracks().length > 0;

        // 로컬 스트림 백업 (화면 공유 복원용)
        participant.setLocalStream(stream);

        const options = {
            localVideo: hasVideo ? video : null,
            localAudio: audio,
            videoStream: stream,
            mediaConstraints: constraints,
            onicecandidate: participant.onIceCandidate.bind(participant),
            dataChannels : true, // dataChannel 사용 여부
            dataChannelConfig: { // dataChannel event 설정
                id : dataChannel.getChannelName,
                // onopen : dataChannel.handleDataChannelOpen,
                // onclose : dataChannel.handleDataChannelClose,
                onmessage : dataChannel.handleDataChannelMessageReceived,
                onerror : dataChannel.handleDataChannelError
            },
            configuration: {
                iceServers: [
                    {
                        urls: turnUrls,  // string[] — 백엔드 응답 urls 배열 그대로 주입
                        username: turnUser,
                        credential: turnPwd
                    }
                ]
            }
        };

        participant.rtcPeer = new kurentoUtils.WebRtcPeer.WebRtcPeerSendrecv(options,
            function(error) {
                if (error) {
                    // 본인 송수신 peer 생성 실패 → 강제 퇴장
                    return handlePeerSetupError({
                        participantId: userId,
                        role: 'self',
                        phase: 'create',
                        error
                    });
                }

                this.generateOffer(participant.offerToReceiveVideo.bind(participant));
                mediaDevice.init(); // video 와 audio 장비를 모두 가져온 후 mediaDvice 장비 영역 세팅
            });
        msg.data.forEach(function(sender) {
            // JOIN 직후 상대방이 동시에 퇴장하는 경쟁 조건 방어:
            // rtcPeer가 이미 존재하면 기존 연결이 유효하므로 중복 생성을 방지한다.
            if (participants[sender.userId] && participants[sender.userId].rtcPeer) {
                console.debug('[WebRTC:Join] 이미 활성 peer 존재, skip:', sender.userId);
                return;
            }
            receiveVideo(sender);
        });
    }

    // 입장 직후에는 권한 검사 결과가 먼저 정리돼야 handleSuccess로 넘어간다.
    function initializeUserMedia() {
        return checkAudioPermission()
            .then(function (hasAudioPermission) {
                if (!hasAudioPermission.success) {
                    showAudioErrorModal(hasAudioPermission.errorType, hasAudioPermission.error);
                    return null;
                }

                return navigator.mediaDevices.getUserMedia(constraints)
                    .then(function (stream) {
                        handleSuccess(stream);
                        return stream;
                    })
                    .catch(function (error) {
                        console.error('getUserMedia failed:', error);
                        showAudioErrorModal(classifyMediaError(error), error);
                        return null;
                    });
            });
    }

    initializeUserMedia();
}

function receiveVideo(sender) {
    const participant = new Participant(sender.userId, sender.nickName, roomId);
    participants[sender.userId] = participant;
    const video = participant.getVideoElement();
    const audio = participant.getAudioElement();

    const options = {
        remoteVideo: video,
        remoteAudio : audio,
        onicecandidate: participant.onIceCandidate.bind(participant),
        dataChannels : true, // dataChannel 사용 여부
        dataChannelConfig: { // dataChannel event 설정
            id : dataChannel.getChannelName,
            onopen : dataChannel.handleDataChannelOpen,
            onclose : dataChannel.handleDataChannelClose,
            onmessage : dataChannel.handleDataChannelMessageReceived,
            onerror : dataChannel.handleDataChannelError
        },
        configuration: { // 이 부분에서 TURN 서버 연결 설정
            iceServers: [
                {
                    urls: turnUrls,  // string[] — 백엔드 응답 urls 배열 그대로 주입
                    username: turnUser,
                    credential: turnPwd
                }
            ]
        }
    }

    participant.rtcPeer = new kurentoUtils.WebRtcPeer.WebRtcPeerSendrecv(options,
        function (error) {
            if (error) {
                // 상대 수신 peer 생성 실패 → Toast + 재연결 버튼
                return handlePeerSetupError({
                    participantId: sender.userId,
                    role: 'remote',
                    phase: 'create',
                    error
                });
            }
            this.generateOffer(participant.offerToReceiveVideo.bind(participant));
        });

    participant.rtcPeer.peerConnection.onaddstream = function(event) {
        const audioTracks = event.stream.getAudioTracks();
        const videoTracks = event.stream.getVideoTracks();

        console.log('[WebRTC:Stream] 스트림 수신 - userId:', sender.userId,
            '| 비디오:', videoTracks.length, '| 오디오:', audioTracks.length);
        if (audioTracks.length === 0) console.warn('[WebRTC:Stream] 오디오 트랙 없음');
        if (videoTracks.length === 0) console.warn('[WebRTC:Stream] 비디오 트랙 없음');

        // 스트림 할당
        // Video 요소: 전체 스트림 (비디오+오디오) - 비디오 표시용
        video.srcObject = event.stream;

        // Audio 요소: 오디오 트랙만 포함한 별도 MediaStream - 오디오 재생용
        if (audioTracks.length > 0) {
            const audioOnlyStream = new MediaStream(audioTracks);
            audio.srcObject = audioOnlyStream;
            console.log('[WebRTC:Stream] 오디오 전용 스트림 생성:', audioOnlyStream.id);
        }

        // muted 상태로 설정하여 자동재생 정책 우회 (muted 자동재생은 항상 허용)
        video.muted = true;
        audio.muted = true;

        // 오디오 재생 함수 (비디오 재생 성공 후 호출)
        const playAudio = function() {
            if (audio && audio.srcObject) {
                audio.play().then(function() {
                    console.log('[WebRTC:Play] 오디오 재생 시작됨');
                    audio.muted = false;
                    audio.volume = 0.5;
                }).catch(function(error) {
                    console.error('[WebRTC:Play] 오디오 재생 실패:', error);
                    audio.muted = false;
                });
            }
        };

        // onloadedmetadata 에서 play() 한번만 호출 (중복 호출 제거)
        video.onloadedmetadata = function() {
            console.log('[WebRTC:Play] 비디오 메타데이터 로드됨 -', video.videoWidth, 'x', video.videoHeight);

            video.play().then(function() {
                console.log('[WebRTC:Play] 비디오 재생 시작됨');
                // 재생 성공 후 음소거 해제
                video.muted = false;
                video.volume = 0.5;
                playAudio();
            }).catch(function(error) {
                console.error('[WebRTC:Play] 비디오 재생 실패:', error);
            });
        };

        // 녹화 중이면 새 참가자의 오디오를 AudioMixer에 추가
        if (typeof recording !== 'undefined' && recording.isRecordingInProgress) {
            recording.addParticipantAudio(sender.userId, event.stream);
        }
    };
}

const leftUserfunc = function() {
    teardownRoomSession({
        sendLeaveRoom: true,
        clearRoomToken: false
    });
};

// 웹 종료 or 새로고침 시 이벤트
window.onbeforeunload = function () {
    if (roomTeardownStarted || forcedSessionExitInProgress) {
        return;
    }
    leaveRoom();
};

// 나가기 버튼 눌렀을 때 이벤트
// 결국 replace  되기 때문에 얘도 onbeforeunload 를 탄다
$('#button-leave').on('click', function(){
    clearConnectedSession();
    sessionStorage.removeItem('roomAccessToken');
    setCookie('room-id', '', -1); // 쿠키 삭제
    location.replace(window.__CONFIG__.BASE_URL + '/roomlist.html');
});

function leaveRoom(type) {
    if (roomTeardownStarted || forcedSessionExitInProgress) {
        return;
    }

    if(type !== 'error'){ // type 이 error 이 아닐 경우에만 퇴장 메시지 전송
        sendDataChannelMessage(" 님이 떠나셨습니다ㅠㅠ");
    }

    // 다른 유저들의 gameParticipants 에서 방을 떠난 유저 삭제
    // TODO 추후 삭제된 유저를 정의해서 특정 유저를 삭제할 필요 있음

    setTimeout(leftUserfunc, 10); // 퇴장 메시지 전송을 위해 timeout 설정
}

function onParticipantLeft(request) {
    console.log('[WebRTC:Participant] 퇴장:', request.name);

    // fallback placeholder + 재연결 타이머 정리 — participant 존재 여부와 무관하게 항상 선행 처리
    clearFallbackState(request.name);

    const participant = participants[request.name];

    if (!participant) {
        console.warn('[WebRTC:Participant] 참가자 없음:', request.name);
        return;
    }

    try {
        // 짧은 딜레이 후 participant 정리
        setTimeout(function() {
            try {
                if (participants[request.name] === participant) {
                    disposeParticipantEntry(request.name);
                    console.log('[WebRTC:Participant] 정리 완료:', request.name);
                } else if (participants[request.name]) {
                    participant.dispose();
                    console.log('[WebRTC:Participant] 기존 참가자 정리 (재연결 감지):', request.name);
                }
            } catch (error) {
                console.error('[WebRTC:Participant] 정리 중 에러:', error);
            }
        }, 100);  // 100ms 딜레이
    } catch (error) {
        console.error('[WebRTC:Participant] 제거 중 에러:', error);
    }
}

function onParticipantSessionReplaced(request) {
    const replacedParticipant = request.data;
    if (!replacedParticipant || !replacedParticipant.userId) {
        return;
    }

    console.log('[WebRTC:Participant] 세션 교체:', replacedParticipant.userId);
    // 세션 교체 전 fallback 상태 정리 — stale placeholder/타이머 방지
    clearFallbackState(replacedParticipant.userId);
    disposeParticipantEntry(replacedParticipant.userId);
    receiveVideo(replacedParticipant);
}

function onSessionReplaced(request) {
    teardownRoomSession({
        forced: true,
        clearRoomToken: true,
        clearRoomCookie: true
    });

    showConnectionFailModal({
        title: '세션 종료',
        message: request?.message || '동일한 계정으로 새 세션이 연결되어 현재 세션이 종료되었습니다.',
        dismissible: false,
        onConfirm: function () {
            window.location.replace(window.__CONFIG__.BASE_URL + '/roomlist.html');
        }
    });
}

function sendMessageToServer(message) {
    if (!ws || ws.readyState !== WebSocket.OPEN) {
        console.warn('[WebSocket] 연결되지 않은 상태에서 메시지 전송 시도:', message.event);
        return;
    }
    const jsonMessage = JSON.stringify(message);
    ws.send(jsonMessage);
}

// 메시지를 데이터 채널을 통해 전송하는 함수
function sendDataChannelMessage(message){
    if (roomTeardownStarted || forcedSessionExitInProgress) {
        return;
    }

    if (participants[userId] && participants[userId].rtcPeer && participants[userId].rtcPeer.dataChannel
            && participants[userId].rtcPeer.dataChannel.readyState === 'open') {
        dataChannel.sendMessage(message);
    } else {
        console.warn("Data channel is not open. Cannot send message.");
    }
}

/** 화면 공유 실행 과정
 * 나와 연결된 다른 peer 에 나의 화면을 공유하기 위해서는 다른 peer 에 보내는 Track 에서 stream 을 교체할 필요가 있다.
 * Track 이란 현재 MediaStream 을 구성하는 각 요소를 의미한다.
 *    - Track 는 오디오, 비디오, 자막 총 3개의 stream 으로 구성된다.
 *    - 때문에 Track 객체는 track[0] = 오디오, track[1] = 비디오 의 배열 구조로 되어있다
 * MediaStream 이란 video stream 과 audio steam 등의 미디어 스트림을 다루는 객체를 이야기한다
 * - stream(스트림)이란 쉽게 생각하자면 비디오와 오디오 데이터라고 이해하면 될 듯 하다 -
 *
 * 즉 상대방에게 보내는 track 에서 나의 웹캠 videoStream 대신 공유 화면에 해당하는 videoStream 으로 변경하는 것이다.
 *
 * 더 구체적으로는 아래 순서를 따른다.
 *
 * 1. startScreenShare() 함수를 호출합니다.
 * 2. ScreenHandler.start() 함수를 호출하여 shareView 변수에 화면 공유에 사용될 MediaStream 객체를 할당합니다.
 * 3. 화면 공유 화면을 로컬 화면에 표시합니다.
 * 4. 연결된 다른 peer에게 화면 공유 화면을 전송하기 위해 RTCRtpSender.replaceTrack() 함수를 사용하여 연결된 다른 peer에게 전송되는 비디오 Track을 shareView.getVideoTracks()[0]으로 교체합니다.
 * 5. shareView 객체의 비디오 Track이 종료되는 경우, stopScreenShare() 함수를 호출하여 화면 공유를 중지합니다.
 * 5. stopScreenShare() 함수에서는 ScreenHandler.end() 함수를 호출하여 shareView 객체에서 발생하는 모든 Track에 대해 stop() 함수를 호출하여 스트림 전송을 중지합니다.
 * 6. 원래 화면으로 되돌리기 위해 연결된 다른 peer에게 전송하는 Track을 로컬 비디오 Track으로 교체합니다.
 * 즉, 해당 코드는 WebRTC 기술을 사용하여 MediaStream 객체를 사용해 로컬에서 받은 Track을 다른 peer로 전송하고, replaceTrack() 함수를 사용하여 비디오 Track을 교체하여 화면 공유를 구현하는 코드입니다.
 * **/

// 화면 공유를 위한 변수 선언
const screenHandler = new ScreenHandler();
let shareView = null;

// 화면 공유 설정 및 통계
const screenShareConfig = {
    // 화질 프리셋
    qualityPresets: {
        'high': {
            width: { ideal: 1920, max: 1920 },
            height: { ideal: 1080, max: 1080 },
            frameRate: { ideal: 30, max: 30 }
        },
        'medium': {
            width: { ideal: 1280, max: 1280 },
            height: { ideal: 720, max: 720 },
            frameRate: { ideal: 15, max: 20 }
        },
        'low': {
            width: { ideal: 640, max: 640 },
            height: { ideal: 360, max: 360 },
            frameRate: { ideal: 10, max: 15 }
        },
        'auto': {
            width: { ideal: 1280, max: 1920 },
            height: { ideal: 720, max: 1080 },
            frameRate: { ideal: 15, max: 30 }
        }
    },
    
    // 현재 설정
    currentQuality: 'auto',
    
    // 통계 정보
    stats: {
        frameRate: 0,
        bitrate: 0,
        packetsLost: 0,
        timestamp: Date.now()
    },
    
    // 자동 최적화 설정
    autoOptimize: true,
    qualityAdjustInterval: null
};

// 네트워크 품질 감지
function detectNetworkQuality() {
    const participant = participants[userId];
    if (!participant || !participant.rtcPeer || !participant.rtcPeer.peerConnection) {
        return Promise.resolve('medium');
    }

    return participant.rtcPeer.peerConnection.getStats()
        .then(function (stats) {
            let outboundRtp = null;
            let candidatePair = null;

            stats.forEach(function (report) {
                if (report.type === 'outbound-rtp' && report.kind === 'video') {
                    outboundRtp = report;
                } else if (report.type === 'candidate-pair' && report.state === 'succeeded') {
                    candidatePair = report;
                }
            });

            if (!outboundRtp || !candidatePair) {
                return 'medium';
            }

            const bitrate = outboundRtp.bytesSent * 8 / 1000;
            const packetLoss = outboundRtp.packetsLost || 0;
            const rtt = candidatePair.currentRoundTripTime * 1000 || 0;
            const jitter = outboundRtp.jitter || 0;

            let qualityScore = 100;
            if (bitrate > 2000) {
                qualityScore += 0;
            } else if (bitrate > 1000) {
                qualityScore -= 15;
            } else {
                qualityScore -= 30;
            }

            qualityScore -= Math.min(packetLoss * 2, 40);

            if (rtt > 200) {
                qualityScore -= 20;
            } else if (rtt > 100) {
                qualityScore -= 10;
            }

            if (jitter > 0.05) {
                qualityScore -= 10;
            }

            let quality;
            if (qualityScore >= 80) {
                quality = 'high';
            } else if (qualityScore >= 50) {
                quality = 'medium';
            } else {
                quality = 'low';
            }

            lastNetworkQuality = quality;
            return quality;
        })
        .catch(function (error) {
            console.warn('네트워크 품질 감지 실패:', error);
            return 'medium';
        });
}

// 디바이스 성능 감지 시스템
const devicePerformance = {
    // 성능 지표
    metrics: {
        cpuUsage: 0,
        memoryUsage: 0,
        frameDropRate: 0,
        encodeTime: 0,
        lastUpdated: Date.now()
    },
    
    // 성능 등급
    grade: 'medium', // 'high', 'medium', 'low'
    
    // 성능 히스토리 (최근 10개 측정값)
    history: {
        frameRates: [],
        encodeTimes: [],
        bitrates: []
    }
};

// 디바이스 성능 감지
function detectDevicePerformance() {
    const participant = participants[userId];
    if (!participant || !participant.rtcPeer) {
        return Promise.resolve('medium');
    }

    return participant.rtcPeer.peerConnection.getStats()
        .then(function (stats) {
            let outboundRtp = null;

            stats.forEach(function (report) {
                if (report.type === 'outbound-rtp' && report.kind === 'video') {
                    outboundRtp = report;
                }
            });

            if (!outboundRtp) {
                return 'medium';
            }

            const currentFrameRate = outboundRtp.framesPerSecond || 0;
            const framesSent = outboundRtp.framesSent || 0;
            const framesEncoded = outboundRtp.framesEncoded || 0;
            const frameDropRate = framesSent > 0 ? ((framesEncoded - framesSent) / framesEncoded) * 100 : 0;
            const encodeTime = outboundRtp.totalEncodeTime || 0;
            const encodedFrames = outboundRtp.framesEncoded || 1;
            const avgEncodeTime = (encodeTime * 1000) / encodedFrames;

            devicePerformance.metrics.frameDropRate = frameDropRate;
            devicePerformance.metrics.encodeTime = avgEncodeTime;
            devicePerformance.metrics.lastUpdated = Date.now();

            updatePerformanceHistory(currentFrameRate, avgEncodeTime, outboundRtp.bytesSent);
            return calculatePerformanceGrade();
        })
        .catch(function (error) {
            console.warn('디바이스 성능 감지 실패:', error);
            return 'medium';
        });
}

// 성능 히스토리 업데이트
function updatePerformanceHistory(frameRate, encodeTime, bitrate) {
    const maxHistorySize = 10;
    
    // 프레임률 히스토리
    devicePerformance.history.frameRates.push(frameRate);
    if (devicePerformance.history.frameRates.length > maxHistorySize) {
        devicePerformance.history.frameRates.shift();
    }
    
    // 인코딩 시간 히스토리
    devicePerformance.history.encodeTimes.push(encodeTime);
    if (devicePerformance.history.encodeTimes.length > maxHistorySize) {
        devicePerformance.history.encodeTimes.shift();
    }
    
    // 비트레이트 히스토리
    devicePerformance.history.bitrates.push(bitrate);
    if (devicePerformance.history.bitrates.length > maxHistorySize) {
        devicePerformance.history.bitrates.shift();
    }
}

// 성능 등급 계산
function calculatePerformanceGrade() {
    const { frameDropRate, encodeTime } = devicePerformance.metrics;
    const { frameRates, encodeTimes } = devicePerformance.history;
    
    // 평균값 계산
    const avgFrameRate = frameRates.length > 0 ? 
        frameRates.reduce((a, b) => a + b, 0) / frameRates.length : 0;
    const avgEncodeTime = encodeTimes.length > 0 ? 
        encodeTimes.reduce((a, b) => a + b, 0) / encodeTimes.length : 0;
    
    // 성능 점수 계산 (0-100)
    let score = 100;
    
    // 프레임 드롭률 페널티
    score -= Math.min(frameDropRate * 2, 30);
    
    // 인코딩 시간 페널티 (>20ms 시 페널티)
    if (avgEncodeTime > 20) {
        score -= Math.min((avgEncodeTime - 20) * 2, 30);
    }
    
    // 평균 프레임률 보너스/페널티
    if (avgFrameRate < 10) {
        score -= 20;
    } else if (avgFrameRate > 25) {
        score += 10;
    }
    
    // 성능 등급 결정
    if (score >= 80) {
        devicePerformance.grade = 'high';
        return 'high';
    } else if (score >= 50) {
        devicePerformance.grade = 'medium';
        return 'medium';
    } else {
        devicePerformance.grade = 'low';
        return 'low';
    }
}

// 통합 품질 결정 (네트워크 + 디바이스)
function determineOptimalQuality() {
    return Promise.all([
        detectNetworkQuality(),
        detectDevicePerformance()
    ]).then(function (qualities) {
        const networkQuality = qualities[0];
        const deviceQuality = qualities[1];
        const qualityMatrix = {
            'high_high': 'high',
            'high_medium': 'medium',
            'high_low': 'medium',
            'medium_high': 'medium',
            'medium_medium': 'medium',
            'medium_low': 'low',
            'low_high': 'low',
            'low_medium': 'low',
            'low_low': 'low'
        };

        const key = `${networkQuality}_${deviceQuality}`;
        const optimalQuality = qualityMatrix[key] || 'medium';

        console.log(`품질 결정: 네트워크(${networkQuality}) + 디바이스(${deviceQuality}) = ${optimalQuality}`);
        return optimalQuality;
    });
}

// 고급 화면 공유 품질 자동 조정
function adjustScreenShareQuality() {
    if (!shareView || !screenShareConfig.autoOptimize) {
        return Promise.resolve();
    }

    return determineOptimalQuality()
        .then(function (optimalQuality) {
            if (optimalQuality === screenShareConfig.currentQuality) {
                return null;
            }

            const currentPreset = screenShareConfig.qualityPresets[optimalQuality];
            const videoTrack = shareView.getVideoTracks()[0];
            if (!videoTrack || !currentPreset) {
                return null;
            }

            return createSmoothQualityTransition(
                screenShareConfig.currentQuality,
                optimalQuality
            ).then(function (smoothTransition) {
                return videoTrack.applyConstraints(smoothTransition)
                    .then(function () {
                        screenShareConfig.currentQuality = optimalQuality;
                        console.log(`화면 공유 품질 조정: ${optimalQuality} (점진적 전환)`);
                        updateScreenShareUI(optimalQuality);
                        showQualityChangeNotification(optimalQuality);
                    })
                    .catch(function (error) {
                        console.warn('화면 공유 품질 조정 실패:', error);
                        const fallbackPreset = screenShareConfig.qualityPresets[screenShareConfig.currentQuality];
                        if (!fallbackPreset) {
                            return null;
                        }
                        return videoTrack.applyConstraints(fallbackPreset).catch(function (rollbackError) {
                            console.error('품질 롤백 실패:', rollbackError);
                            return null;
                        });
                    });
            });
        });
}

// 점진적 품질 전환 생성
function createSmoothQualityTransition(currentQuality, targetQuality) {
    const current = screenShareConfig.qualityPresets[currentQuality];
    const target = screenShareConfig.qualityPresets[targetQuality];
    
    // 중간값 계산으로 부드러운 전환
    const transition = {
        width: {
            ideal: Math.round((current.width.ideal + target.width.ideal) / 2),
            max: target.width.max
        },
        height: {
            ideal: Math.round((current.height.ideal + target.height.ideal) / 2),
            max: target.height.max
        },
        frameRate: {
            ideal: Math.round((current.frameRate.ideal + target.frameRate.ideal) / 2),
            max: target.frameRate.max
        }
    };
    
    return Promise.resolve(transition);
}

// 품질 변경 알림
function showQualityChangeNotification(quality) {
    const qualityLabels = {
        'high': '🟢 고화질',
        'medium': '🟡 중화질',
        'low': '🔴 저화질',
        'auto': '🔄 자동'
    };
    
    // 임시 알림 표시
    const notification = document.createElement('div');
    notification.style.cssText = `
        position: fixed;
        top: 50px;
        right: 10px;
        background: rgba(0,0,0,0.8);
        color: white;
        padding: 8px 12px;
        border-radius: 6px;
        font-size: 12px;
        z-index: 10000;
        opacity: 0;
        transition: opacity 0.3s ease;
    `;
    notification.textContent = `화질 조정: ${qualityLabels[quality]}`;
    
    document.body.appendChild(notification);
    
    // 페이드 인
    setTimeout(() => {
        notification.style.opacity = '1';
    }, 100);
    
    // 3초 후 자동 제거
    setTimeout(() => {
        notification.style.opacity = '0';
        setTimeout(() => {
            if (notification.parentNode) {
                notification.parentNode.removeChild(notification);
            }
        }, 300);
    }, 3000);
}

// 강화된 화면 공유 통계 업데이트
function updateScreenShareStats() {
    if (!shareView) {
        return Promise.resolve();
    }

    const participant = participants[userId];
    if (!participant || !participant.rtcPeer) {
        return Promise.resolve();
    }

    return participant.rtcPeer.peerConnection.getStats()
        .then(function (stats) {
            let outboundRtp = null;

            stats.forEach(function (report) {
                if (report.type === 'outbound-rtp' && report.kind === 'video') {
                    outboundRtp = report;
                }
            });

            if (!outboundRtp) {
                return;
            }

            const now = Date.now();
            const timeDiff = (now - screenShareConfig.stats.timestamp) / 1000;
            if (timeDiff <= 0) {
                return;
            }

            const bytesDiff = outboundRtp.bytesSent - (screenShareConfig.stats.bytesLastSent || 0);
            screenShareConfig.stats.bitrate = (bytesDiff * 8) / (timeDiff * 1000);
            screenShareConfig.stats.frameRate = outboundRtp.framesPerSecond || 0;
            screenShareConfig.stats.packetsLost = outboundRtp.packetsLost || 0;
            screenShareConfig.stats.bytesLastSent = outboundRtp.bytesSent;
            screenShareConfig.stats.timestamp = now;

            screenShareConfig.stats.framesEncoded = outboundRtp.framesEncoded || 0;
            screenShareConfig.stats.framesSent = outboundRtp.framesSent || 0;
            screenShareConfig.stats.encodeTime = outboundRtp.totalEncodeTime || 0;
            screenShareConfig.stats.qualityLimitationReason = outboundRtp.qualityLimitationReason || 'none';

            analyzeQualityLimitation(outboundRtp.qualityLimitationReason);
            checkPerformanceWarnings();
            updateStatsDisplay();
        })
        .catch(function (error) {
            console.warn('화면 공유 통계 업데이트 실패:', error);
        });
}

// 화질 제한 원인 분석
function analyzeQualityLimitation(reason) {
    const limitationReasons = {
        'none': '제한 없음',
        'cpu': 'CPU 성능 제한',
        'bandwidth': '대역폭 제한',
        'other': '기타 제한'
    };
    
    if (reason && reason !== 'none') {
        console.warn(`화질 제한 감지: ${limitationReasons[reason] || reason}`);
        
        // 제한 원인별 자동 대응
        if (reason === 'cpu') {
            // CPU 제한 시 프레임률 우선 감소
            suggestCpuOptimization();
        } else if (reason === 'bandwidth') {
            // 대역폭 제한 시 해상도 우선 감소
            suggestBandwidthOptimization();
        }
    }
}

// CPU 최적화 제안
function suggestCpuOptimization() {
    if (screenShareConfig.currentQuality !== 'low') {
        console.log('CPU 부하로 인한 자동 최적화 제안: 프레임률 감소');
        // 필요시 자동으로 낮은 품질로 전환
        if (screenShareConfig.autoOptimize) {
            const currentPreset = screenShareConfig.qualityPresets[screenShareConfig.currentQuality];
            const optimizedPreset = {
                ...currentPreset,
                frameRate: {
                    ideal: Math.max(currentPreset.frameRate.ideal - 5, 10),
                    max: currentPreset.frameRate.max
                }
            };
            
            const videoTrack = shareView.getVideoTracks()[0];
            if (videoTrack) {
                videoTrack.applyConstraints(optimizedPreset);
            }
        }
    }
}

// 대역폭 최적화 제안
function suggestBandwidthOptimization() {
    if (screenShareConfig.currentQuality !== 'low') {
        console.log('대역폭 부족으로 인한 자동 최적화 제안: 해상도 감소');
        // 필요시 자동으로 낮은 품질로 전환
        if (screenShareConfig.autoOptimize) {
            const currentPreset = screenShareConfig.qualityPresets[screenShareConfig.currentQuality];
            const optimizedPreset = {
                ...currentPreset,
                width: {
                    ideal: Math.round(currentPreset.width.ideal * 0.8),
                    max: currentPreset.width.max
                },
                height: {
                    ideal: Math.round(currentPreset.height.ideal * 0.8),
                    max: currentPreset.height.max
                }
            };
            
            const videoTrack = shareView.getVideoTracks()[0];
            if (videoTrack) {
                videoTrack.applyConstraints(optimizedPreset);
            }
        }
    }
}

// 강화된 통계 표시 UI 업데이트
function updateStatsDisplay() {
    const statsElement = document.getElementById('screenShareStats');
    if (statsElement) {
        const stats = screenShareConfig.stats;
        const deviceStats = devicePerformance.metrics;
        
        statsElement.innerHTML = `
            <div class="stats-item">📊 ${Math.round(stats.bitrate)} kbps</div>
            <div class="stats-item">🎞️ ${Math.round(stats.frameRate)} fps</div>
            <div class="stats-item">📉 ${stats.packetsLost} lost</div>
            <div class="stats-item">⚡ ${Math.round(deviceStats.encodeTime)}ms</div>
        `;
        
        // 성능 상태에 따른 색상 변경
        const performanceColor = getPerformanceColor();
        statsElement.style.borderLeft = `3px solid ${performanceColor}`;
        
        // 차트 데이터 업데이트
        updateChartData(stats.frameRate, stats.bitrate);
        
        // 네트워크 및 디바이스 상태 업데이트
        updateNetworkDeviceStatus();
    }
}

// 네트워크 및 디바이스 상태 업데이트
function updateNetworkDeviceStatus() {
    // 네트워크 상태 업데이트
    const networkElement = document.getElementById('networkStatus');
    if (networkElement) {
        const networkQuality = getLastNetworkQuality();
        const networkLabels = {
            'high': '🟢 우수',
            'medium': '🟡 보통',
            'low': '🔴 불량'
        };
        networkElement.textContent = networkLabels[networkQuality] || '🟡 보통';
    }
    
    // 디바이스 상태 업데이트
    const deviceElement = document.getElementById('deviceStatus');
    if (deviceElement) {
        const deviceGrade = devicePerformance.grade;
        const deviceLabels = {
            'high': '🟢 우수',
            'medium': '🟡 보통',
            'low': '🔴 불량'
        };
        deviceElement.textContent = deviceLabels[deviceGrade] || '🟡 보통';
    }
}

// 마지막 네트워크 품질 저장 및 반환
let lastNetworkQuality = 'medium';
function getLastNetworkQuality() {
    return lastNetworkQuality;
}

// 성능 상태 색상 결정
function getPerformanceColor() {
    const grade = devicePerformance.grade;
    switch (grade) {
        case 'high': return '#28a745'; // 초록
        case 'medium': return '#ffc107'; // 노랑
        case 'low': return '#dc3545'; // 빨강
        default: return '#6c757d'; // 회색
    }
}

// 화면 공유 UI 업데이트 개선
function updateScreenShareUI(quality) {
    const qualityElement = document.getElementById('screenShareQuality');
    if (qualityElement) {
        const qualityLabels = {
            'high': '🟢 고화질',
            'medium': '🟡 중화질', 
            'low': '🔴 저화질',
            'auto': '🔄 자동'
        };
        
        // 기본 품질 표시
        qualityElement.innerHTML = `
            <div>${qualityLabels[quality] || qualityLabels['auto']}</div>
        `;
        
        // 품질 선택 드롭다운 동기화
        const qualitySelector = document.getElementById('qualitySelector');
        if (qualitySelector && screenShareConfig.currentQuality !== 'auto') {
            qualitySelector.value = quality;
        }
    }
}

/**
 * ScreenHandler 클래스 정의
 */
function ScreenHandler() {
    /**
     * Cross Browser Screen Capture API를 호출합니다.
     * Chrome 72 이상에서는 navigator.mediaDevices.getDisplayMedia API 호출
     * Chrome 70~71에서는 navigator.getDisplayMedia API 호출 (experimental feature 활성화 필요)
     * 다른 브라우저에서는 screen sharing not supported in this browser 에러 반환
     * @returns {Promise<MediaStream>} 미디어 스트림을 반환합니다.
     */
    function getCrossBrowserScreenCapture() {
        // 화면 공유 최적화 제약조건 적용
        const constraints = {
            video: {
                ...screenShareConfig.qualityPresets[screenShareConfig.currentQuality],
                mediaSource: 'screen',
                // 화면 공유 최적화 설정
                googLeakyBucket: true,
                googCpuOveruseDetection: true,
                googCpuOveruseEncodeUsage: true,
                googHighStartBitrate: true,
                googVeryHighBitrate: true
            },
            audio: {
                // 시스템 오디오 캡처 활성화
                echoCancellation: false,  // 화면 공유 오디오에는 에코 제거 불필요
                noiseSuppression: false,  // 원본 소리 그대로 전달
                autoGainControl: false,   // 자동 볼륨 조절 비활성화
                sampleRate: 48000,        // 고품질 오디오
                channelCount: 2           // 스테레오
            }
        };

        if (navigator.mediaDevices.getDisplayMedia) {
            return navigator.mediaDevices.getDisplayMedia(constraints);
        } else if (navigator.getDisplayMedia) {
            return navigator.getDisplayMedia(constraints);
        } else {
            throw new Error('Screen sharing not supported in this browser');
        }
    }

    /**
     * 화면 공유를 시작합니다.
     * @returns {Promise<MediaStream>} 화면 공유에 사용되는 미디어 스트림을 반환합니다.
     */
    // 화면 공유 핵심 경로는 트랙 교체와 종료 이벤트 순서가 민감해서 async/await 흐름을 유지한다.
    async function start() {
        try {
            shareView = await getCrossBrowserScreenCapture();
            
            // 화면 공유 시작 시 모니터링 시작
            startScreenShareMonitoring();
            
            // 초기 화질 설정 적용
            const videoTrack = shareView.getVideoTracks()[0];
            if (videoTrack) {
                // contentHint 설정 - 화면 공유에 최적화
                videoTrack.contentHint = "detail"; // 텍스트나 상세한 내용에 최적화
                
                // 트랙 종료 이벤트 리스너
                videoTrack.addEventListener('ended', () => {
                    console.log('[WebRTC:Screen] 화면 공유가 종료되었습니다.');
                    stopScreenShare();
                });
            }
            
        } catch (err) {
            console.error('[WebRTC:Screen] getDisplayMedia 실패:', err);
            // 사용자 친화적인 에러 메시지
            showScreenShareError(err);
        }
        return shareView;
    }

    /**
     * 화면 공유를 종료합니다.
     */
    function end() {
        if (shareView) {
            // shareView에서 발생하는 모든 트랙들에 대해 stop() 함수를 호출하여 스트림 전송 중지
            shareView.getTracks().forEach(track => track.stop());
            shareView = null;
            
            // 모니터링 중지
            stopScreenShareMonitoring();
        }
    }

    // 생성자로 반환할 public 변수 선언
    this.start = start;
    this.end = end;
}

// 화면 공유 모니터링 시작 개선
function startScreenShareMonitoring() {
    // 기존 인터벌 정리
    if (screenShareConfig.qualityAdjustInterval) {
        clearInterval(screenShareConfig.qualityAdjustInterval);
    }
    
    // 통계 업데이트 및 품질 조정 (2초마다 - 더 빠른 반응성)
    screenShareConfig.qualityAdjustInterval = setInterval(function () {
        updateScreenShareStats()
            .then(function () {
                return adjustScreenShareQuality();
            })
            .catch(function (error) {
                console.warn('화면 공유 모니터링 루프 실패:', error);
            });
    }, 2000);
    
    // UI 표시
    showScreenShareControls();
    
    // 초기 상태 업데이트
    setTimeout(() => {
        updateNetworkDeviceStatus();
    }, 1000);
}

// 화면 공유 모니터링 중지
function stopScreenShareMonitoring() {
    if (screenShareConfig.qualityAdjustInterval) {
        clearInterval(screenShareConfig.qualityAdjustInterval);
        screenShareConfig.qualityAdjustInterval = null;
    }
    
    // UI 숨기기
    hideScreenShareControls();
}

// 화면 공유 에러 표시
function showScreenShareError(error) {
    let message = '';
    switch (error.name) {
        case 'NotAllowedError':
            message = '화면 공유 권한이 거부되었습니다.';
            break;
        case 'NotFoundError':
            message = '공유할 화면을 찾을 수 없습니다.';
            break;
        case 'NotSupportedError':
            message = '브라우저에서 화면 공유를 지원하지 않습니다.';
            break;
        default:
            message = '화면 공유 중 오류가 발생했습니다.';
    }
    
    console.warn(`[WebRTC:Screen] 화면 공유 오류: ${message}`, error);
    showWarningToast(message);
}


// 화면 공유 컨트롤 UI 숨기기
function hideScreenShareControls() {
    const controls = document.getElementById('screenShareControls');
    if (controls) {
        controls.style.display = 'none';
    }
}

// 자동 최적화 토글
function toggleAutoOptimize() {
    screenShareConfig.autoOptimize = !screenShareConfig.autoOptimize;
    const btn = document.getElementById('autoOptimizeBtn');
    if (btn) {
        btn.textContent = screenShareConfig.autoOptimize ? '자동 최적화' : '수동 모드';
        btn.style.background = screenShareConfig.autoOptimize ? '#28a745' : '#6c757d';
    }
}

// 화면 공유 품질 수동 변경
function changeScreenShareQuality(quality) {
    screenShareConfig.currentQuality = quality;

    if (!shareView) {
        return Promise.resolve();
    }

    const videoTrack = shareView.getVideoTracks()[0];
    const constraints = screenShareConfig.qualityPresets[quality];

    if (!videoTrack || !constraints) {
        return Promise.resolve();
    }

    return videoTrack.applyConstraints(constraints)
        .then(function () {
            updateScreenShareUI(quality);
            console.log(`[WebRTC:Screen] 품질 변경: ${quality}`);
        })
        .catch(function (error) {
            console.warn('[WebRTC:Screen] 품질 변경 실패:', error);
        });
}

/**
 * 강화된 화면 공유 시작 함수
 * @returns {Promise<void>}
 */
// 이 구간은 WebRTC sender.replaceTrack와 AudioContext 정리 타이밍이 민감해 Promise 체인으로 바꾸지 않는다.
async function startScreenShare() {
    try {
        // 화면 공유 스트림 획득
        await screenHandler.start();
        
        if (!shareView) {
            console.error('[WebRTC:Screen] 스트림을 생성할 수 없습니다.');
            return;
        }

        const participant = participants[userId];
        if (!participant) {
            console.error('[WebRTC:Screen] 참가자를 찾을 수 없습니다.');
            return;
        }

        const video = participant.getVideoElement();

        // 기존 스트림 백업
        participant.setLocalStream(video.srcObject);

        // 로컬 비디오에 화면 공유 표시
        video.srcObject = shareView;
        
        // 원격 참가자들에게 화면 공유 전송 (비디오 + 오디오)
        const senders = participant.rtcPeer.peerConnection.getSenders();
        
        // 비디오 트랙 교체
        for (const sender of senders) {
            if (sender.track && sender.track.kind === 'video') {
                const videoTrack = shareView.getVideoTracks()[0];
                if (videoTrack) {
                    // 화면 공유에 최적화된 설정 적용
                    videoTrack.contentHint = "detail";
                    
                    // 트랙 교체
                    await sender.replaceTrack(videoTrack);
                    console.log('[WebRTC:Screen] 비디오 트랙 교체 완료');
                }
                break;
            }
        }
        
        // 오디오 트랙 처리: 시스템 오디오 + 마이크 오디오 믹싱
        const audioTracks = shareView.getAudioTracks();
        if (audioTracks.length > 0) {
            // 시스템 오디오가 있는 경우, 마이크와 믹싱
            for (const sender of senders) {
                if (sender.track && sender.track.kind === 'audio') {
                    const systemAudioTrack = audioTracks[0];
                    const microphoneTrack = sender.track;

                    try {
                        // 이전 AudioContext가 있으면 먼저 정리
                        if (participant.audioContext) {
                            try {
                                participant.systemSource?.disconnect();
                                participant.micSource?.disconnect();
                                participant.destination?.disconnect();
                                await participant.audioContext.close();
                                console.log('[WebRTC:Screen] 이전 AudioContext 정리 완료');
                            } catch (cleanupError) {
                                console.error('[WebRTC:Screen] 이전 AudioContext 정리 실패:', cleanupError);
                            }
                        }

                        // Web Audio API를 사용한 오디오 믹싱
                        const audioContext = new (window.AudioContext || window.webkitAudioContext)();

                        // 시스템 오디오 소스 생성
                        const systemStream = new MediaStream([systemAudioTrack]);
                        const systemSource = audioContext.createMediaStreamSource(systemStream);

                        // 마이크 오디오 소스 생성
                        const micStream = new MediaStream([microphoneTrack]);
                        const micSource = audioContext.createMediaStreamSource(micStream);

                        // Destination (믹싱 출력)
                        const destination = audioContext.createMediaStreamDestination();

                        // 두 오디오를 destination으로 연결 (믹싱)
                        systemSource.connect(destination);
                        micSource.connect(destination);

                        // 믹싱된 오디오 트랙
                        const mixedAudioTrack = destination.stream.getAudioTracks()[0];

                        // 기존 트랙 백업 및 노드 참조 저장 (정리용)
                        participant.originalAudioTrack = microphoneTrack;
                        participant.audioContext = audioContext;
                        participant.systemSource = systemSource;
                        participant.micSource = micSource;
                        participant.destination = destination;

                        // 믹싱된 트랙으로 교체
                        await sender.replaceTrack(mixedAudioTrack);
                        console.log('[WebRTC:Screen] 시스템 오디오 + 마이크 오디오 믹싱 완료');
                    } catch (error) {
                        console.error('[WebRTC:Screen] 오디오 믹싱 실패, 시스템 오디오만 사용:', error);
                        // 믹싱 실패 시 시스템 오디오만 사용
                        participant.originalAudioTrack = sender.track;
                        await sender.replaceTrack(systemAudioTrack);
                        console.log('[WebRTC:Screen] 시스템 오디오 트랙으로 교체 완료');
                    }
                    break;
                }
            }
        } else {
            console.log('[WebRTC:Screen] 시스템 오디오 없음, 마이크 오디오만 유지');
        }

        console.log('[WebRTC:Screen] 화면 공유 시작됨');

        // 녹화 중이라면 믹싱된 오디오가 자동으로 녹화에 포함됩니다
        // (WebRTC를 통해 전송되므로 별도 처리 불필요)
        if (typeof recording !== 'undefined' && recording.isRecordingInProgress) {
            console.log('[WebRTC:Screen] 녹화 중: 시스템 오디오 + 마이크 오디오 모두 녹화');
        }

    } catch (error) {
        console.error('[WebRTC:Screen] 시작 실패:', error);
        showScreenShareError(error);
    }
}

/**
 * 강화된 화면 공유 중지 함수
 * @returns {Promise<void>}
 */
// 중지 시에는 원본 스트림 복원과 AudioContext close 순서가 중요해서 async/await를 유지한다.
async function stopScreenShare() {
    try {
        const participant = participants[userId];
        if (!participant) {
            console.error('[WebRTC:Screen] 참가자를 찾을 수 없습니다.');
            return;
        }

        const video = participant.getVideoElement();

        // 원래 스트림으로 복원
        const originalStream = participant.getLocalStream();
        if (originalStream) {
            video.srcObject = originalStream;
            
            // 원격 참가자들에게 원래 비디오/오디오 전송
            const senders = participant.rtcPeer.peerConnection.getSenders();
            
            // 비디오 트랙 복원
            for (const sender of senders) {
                if (sender.track && sender.track.kind === 'video') {
                    const videoTrack = originalStream.getVideoTracks()[0];
                    if (videoTrack) {
                        await sender.replaceTrack(videoTrack);
                        console.log('[WebRTC:Screen] 원래 비디오 트랙으로 복원 완료');
                    }
                    break;
                }
            }
            
            // 오디오 트랙 복원 (백업된 오디오 트랙이 있는 경우)
            if (participant.originalAudioTrack) {
                for (const sender of senders) {
                    if (sender.track && sender.track.kind === 'audio') {
                        await sender.replaceTrack(participant.originalAudioTrack);
                        console.log('[WebRTC:Screen] 원래 마이크 오디오 트랙으로 복원 완료');

                        // 백업 제거
                        delete participant.originalAudioTrack;
                        break;
                    }
                }
            }

            // Web Audio 노드 및 AudioContext 정리
            if (participant.systemSource) {
                try {
                    participant.systemSource.disconnect();
                    delete participant.systemSource;
                } catch (error) {
                    console.error('[WebRTC:Screen] systemSource 정리 중 에러:', error);
                }
            }

            if (participant.micSource) {
                try {
                    participant.micSource.disconnect();
                    delete participant.micSource;
                } catch (error) {
                    console.error('[WebRTC:Screen] micSource 정리 중 에러:', error);
                }
            }

            if (participant.destination) {
                try {
                    participant.destination.disconnect();
                    delete participant.destination;
                } catch (error) {
                    console.error('[WebRTC:Screen] destination 정리 중 에러:', error);
                }
            }

            if (participant.audioContext) {
                try {
                    participant.audioContext.close();
                    console.log('[WebRTC:Screen] AudioContext 정리 완료');
                    delete participant.audioContext;
                } catch (error) {
                    console.error('[WebRTC:Screen] AudioContext 정리 중 에러:', error);
                }
            }
        }

        // 화면 공유 스트림 정리
        await screenHandler.end();

        // 버튼 상태 초기화
        const screenShareBtn = $("#screenShareBtn");
        screenShareBtn.data("flag", false);
        screenShareBtn.attr("src", "/images/webrtc/screen-share-on.svg");

        console.log('[WebRTC:Screen] 화면 공유 중지됨');

        // 녹화 중이라면 원래 마이크 오디오로 자동 전환됩니다
        // (WebRTC를 통해 전송되므로 별도 처리 불필요)
        if (typeof recording !== 'undefined' && recording.isRecordingInProgress) {
            console.log('[WebRTC:Screen] 녹화: 마이크 오디오로 전환됨');
        }

    } catch (error) {
        console.error('[WebRTC:Screen] 중지 실패:', error);
    }
}

/**
 * 화면 공유 토글 함수 (버튼 클릭 시 호출)
 * @returns {Promise<void>}
 */
// 토글 함수는 시작/중지 성공 여부를 순차적으로 반영해야 해서 async/await를 유지한다.
async function screenShare() {
    const screenShareBtn = $("#screenShareBtn");
    const isScreenShare = screenShareBtn.data("flag");

    if (isScreenShare) {
        // 화면 공유 중지
        await stopScreenShare();
    } else {
        // 화면 공유 시작
        await startScreenShare();
        
        // 시작 성공 시 버튼 상태 업데이트
        if (shareView) {
        screenShareBtn.data("flag", true);
            screenShareBtn.attr("src", "/images/webrtc/screen-share-off.svg");
        }
    }
}

// 성능 차트 초기화
function initPerformanceChart() {
    const canvas = document.getElementById('chartCanvas');
    if (!canvas) return;
    
    const ctx = canvas.getContext('2d');
    
    // 차트 데이터 초기화
    screenShareConfig.chartData = {
        frameRates: Array(30).fill(0),
        bitrates: Array(30).fill(0),
        times: Array(30).fill(0)
    };
    
    // 첫 번째 차트 그리기
    drawPerformanceChart(ctx);
}

// 성능 차트 그리기
function drawPerformanceChart(ctx) {
    const canvas = ctx.canvas;
    const width = canvas.width;
    const height = canvas.height;
    
    // 캔버스 클리어
    ctx.clearRect(0, 0, width, height);
    
    const { frameRates, bitrates } = screenShareConfig.chartData;
    const maxPoints = frameRates.length;
    
    // 배경 그리드
    ctx.strokeStyle = 'rgba(255,255,255,0.1)';
    ctx.lineWidth = 1;
    for (let i = 0; i < 4; i++) {
        const y = (height / 4) * i;
        ctx.beginPath();
        ctx.moveTo(0, y);
        ctx.lineTo(width, y);
        ctx.stroke();
    }
    
    // 프레임률 그래프 (녹색)
    ctx.strokeStyle = '#28a745';
    ctx.lineWidth = 2;
    ctx.beginPath();
    for (let i = 0; i < maxPoints; i++) {
        const x = (width / (maxPoints - 1)) * i;
        const y = height - (frameRates[i] / 60) * height; // 60fps 기준
        if (i === 0) {
            ctx.moveTo(x, y);
        } else {
            ctx.lineTo(x, y);
        }
    }
    ctx.stroke();
    
    // 비트레이트 그래프 (파란색) - 스케일 조정
    ctx.strokeStyle = '#007bff';
    ctx.lineWidth = 2;
    ctx.beginPath();
    for (let i = 0; i < maxPoints; i++) {
        const x = (width / (maxPoints - 1)) * i;
        const y = height - (bitrates[i] / 5000) * height; // 5000kbps 기준
        if (i === 0) {
            ctx.moveTo(x, y);
        } else {
            ctx.lineTo(x, y);
        }
    }
    ctx.stroke();
    
    // 범례
    ctx.fillStyle = '#28a745';
    ctx.fillRect(5, 5, 10, 2);
    ctx.fillStyle = 'white';
    ctx.font = '10px Arial';
    ctx.fillText('FPS', 20, 12);
    
    ctx.fillStyle = '#007bff';
    ctx.fillRect(5, 15, 10, 2);
    ctx.fillStyle = 'white';
    ctx.fillText('Kbps', 20, 22);
}

// 차트 데이터 업데이트
function updateChartData(frameRate, bitrate) {
    if (!screenShareConfig.chartData) return;
    
    // 새 데이터 추가하고 오래된 데이터 제거
    screenShareConfig.chartData.frameRates.push(frameRate);
    screenShareConfig.chartData.frameRates.shift();
    
    screenShareConfig.chartData.bitrates.push(bitrate / 1000); // kbps로 변환
    screenShareConfig.chartData.bitrates.shift();
    
    // 차트 다시 그리기
    const canvas = document.getElementById('chartCanvas');
    if (canvas) {
        const ctx = canvas.getContext('2d');
        drawPerformanceChart(ctx);
    }
}

// 컨트롤 패널 토글
function toggleScreenShareControls() {
    const controls = document.getElementById('screenShareControls');
    const stats = document.getElementById('screenShareStats');
    const chart = document.getElementById('performanceChart');
    const buttons = controls.querySelector('div:last-child');
    
    if (stats.style.display === 'none') {
        // 확장
        stats.style.display = 'grid';
        chart.style.display = 'block';
        buttons.style.display = 'flex';
        controls.querySelector('button').textContent = '−';
    } else {
        // 축소
        stats.style.display = 'none';
        chart.style.display = 'none';
        buttons.style.display = 'none';
        controls.querySelector('button').textContent = '+';
    }
}

// 상세 통계 모달 표시
function showDetailedStats() {
    const stats = screenShareConfig.stats;
    const deviceStats = devicePerformance.metrics;
    
    const modalHTML = `
        <div id="detailedStatsModal" style="
            position: fixed;
            top: 0;
            left: 0;
            width: 100%;
            height: 100%;
            background: rgba(0,0,0,0.8);
            display: flex;
            justify-content: center;
            align-items: center;
            z-index: 10001;
        ">
            <div style="
                background: white;
                padding: 20px;
                border-radius: 10px;
                max-width: 600px;
                width: 90%;
                max-height: 80%;
                overflow-y: auto;
            ">
                <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px;">
                    <h3 style="margin: 0;">📊 상세 성능 통계</h3>
                    <button onclick="closeDetailedStats()" style="
                        background: none;
                        border: none;
                        font-size: 20px;
                        cursor: pointer;
                    ">×</button>
                </div>
                
                <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 20px;">
                    <!-- 네트워크 통계 -->
                    <div>
                        <h4>🌐 네트워크 성능</h4>
                        <table style="width: 100%; border-collapse: collapse;">
                            <tr><td>비트레이트</td><td><strong>${Math.round(stats.bitrate)} kbps</strong></td></tr>
                            <tr><td>프레임률</td><td><strong>${Math.round(stats.frameRate)} fps</strong></td></tr>
                            <tr><td>패킷 손실</td><td><strong>${stats.packetsLost}</strong></td></tr>
                            <tr><td>전송된 프레임</td><td><strong>${stats.framesSent || 0}</strong></td></tr>
                            <tr><td>인코딩된 프레임</td><td><strong>${stats.framesEncoded || 0}</strong></td></tr>
                        </table>
                    </div>
                    
                    <!-- 디바이스 통계 -->
                    <div>
                        <h4>💻 디바이스 성능</h4>
                        <table style="width: 100%; border-collapse: collapse;">
                            <tr><td>성능 등급</td><td><strong>${deviceStats.grade || 'medium'}</strong></td></tr>
                            <tr><td>프레임 드롭률</td><td><strong>${Math.round(deviceStats.frameDropRate)}%</strong></td></tr>
                            <tr><td>평균 인코딩 시간</td><td><strong>${Math.round(deviceStats.encodeTime)}ms</strong></td></tr>
                            <tr><td>화질 제한 원인</td><td><strong>${stats.qualityLimitationReason || 'none'}</strong></td></tr>
                        </table>
                    </div>
                </div>
                
                <!-- 히스토리 차트 -->
                <div style="margin-top: 20px;">
                    <h4>📈 성능 히스토리</h4>
                    <div style="display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 10px; text-align: center;">
                        <div>
                            <strong>평균 프레임률</strong><br>
                            ${getAverageFromHistory('frameRates')} fps
                        </div>
                        <div>
                            <strong>평균 인코딩 시간</strong><br>
                            ${getAverageFromHistory('encodeTimes')} ms
                        </div>
                        <div>
                            <strong>평균 비트레이트</strong><br>
                            ${getAverageFromHistory('bitrates')} kbps
                        </div>
                    </div>
                </div>
                
                <!-- 최적화 제안 -->
                <div style="margin-top: 20px; padding: 15px; background: #f8f9fa; border-radius: 8px;">
                    <h4>💡 최적화 제안</h4>
                    <div id="optimizationSuggestions">
                        ${generateOptimizationSuggestions()}
                    </div>
                </div>
            </div>
        </div>
    `;
    
    document.body.insertAdjacentHTML('beforeend', modalHTML);
}

// 히스토리 평균값 계산
function getAverageFromHistory(type) {
    const history = devicePerformance.history[type];
    if (!history || history.length === 0) return 0;
    
    const sum = history.reduce((a, b) => a + b, 0);
    return Math.round(sum / history.length);
}

// 최적화 제안 생성
function generateOptimizationSuggestions() {
    const suggestions = [];
    const stats = screenShareConfig.stats;
    const deviceStats = devicePerformance.metrics;
    
    // 프레임 드롭률 체크
    if (deviceStats.frameDropRate > 10) {
        suggestions.push('⚠️ 프레임 드롭률이 높습니다. CPU 사용량을 줄이거나 화질을 낮춰보세요.');
    }
    
    // 인코딩 시간 체크
    if (deviceStats.encodeTime > 30) {
        suggestions.push('⚠️ 인코딩 시간이 깁니다. 해상도를 낮추거나 다른 프로그램을 종료해보세요.');
    }
    
    // 패킷 손실 체크
    if (stats.packetsLost > 100) {
        suggestions.push('⚠️ 네트워크 패킷 손실이 발생했습니다. 네트워크 연결을 확인해보세요.');
    }
    
    // 프레임률 체크
    if (stats.frameRate < 10) {
        suggestions.push('⚠️ 프레임률이 낮습니다. 자동 최적화를 활성화하거나 화질을 낮춰보세요.');
    }
    
    // 비트레이트 체크
    if (stats.bitrate < 500) {
        suggestions.push('ℹ️ 낮은 비트레이트로 전송 중입니다. 네트워크 대역폭이 제한될 수 있습니다.');
    }
    
    if (suggestions.length === 0) {
        suggestions.push('✅ 현재 화면 공유가 최적 상태로 동작하고 있습니다.');
    }
    
    return suggestions.map(suggestion => `<div style="margin: 5px 0;">${suggestion}</div>`).join('');
}

// 상세 통계 모달 닫기
function closeDetailedStats() {
    const modal = document.getElementById('detailedStatsModal');
    if (modal) {
        modal.remove();
    }
}

// 성능 경고 시스템
function checkPerformanceWarnings() {
    const stats = screenShareConfig.stats;
    const deviceStats = devicePerformance.metrics;
    const warnings = [];
    
    // 심각한 성능 문제 감지
    if (deviceStats.frameDropRate > 20) {
        warnings.push({
            type: 'critical',
            message: '심각한 프레임 드롭 발생',
            suggestion: '화질을 낮추거나 다른 프로그램을 종료하세요.'
        });
    }
    
    if (stats.frameRate < 5) {
        warnings.push({
            type: 'critical',
            message: '매우 낮은 프레임률',
            suggestion: '네트워크 연결을 확인하고 화질을 낮춰보세요.'
        });
    }
    
    if (stats.packetsLost > 500) {
        warnings.push({
            type: 'warning',
            message: '네트워크 불안정',
            suggestion: '네트워크 연결을 확인해보세요.'
        });
    }
    
    // 경고 표시
    if (warnings.length > 0) {
        showPerformanceWarning(warnings[0]);
    }
}

// 성능 경고 표시
function showPerformanceWarning(warning) {
    // 기존 경고가 있으면 제거
    const existingWarning = document.getElementById('performanceWarning');
    if (existingWarning) {
        existingWarning.remove();
    }
    
    const warningColor = warning.type === 'critical' ? '#dc3545' : '#ffc107';
    const warningIcon = warning.type === 'critical' ? '🚨' : '⚠️';
    
    const warningHTML = `
        <div id="performanceWarning" style="
            position: fixed;
            top: 80px;
            right: 10px;
            background: ${warningColor};
            color: white;
            padding: 10px;
            border-radius: 6px;
            font-size: 12px;
            z-index: 10000;
            max-width: 250px;
            animation: slideIn 0.3s ease;
        ">
            <div style="font-weight: bold; margin-bottom: 4px;">
                ${warningIcon} ${warning.message}
            </div>
            <div style="font-size: 11px; opacity: 0.9;">
                ${warning.suggestion}
            </div>
            <button onclick="this.parentElement.remove()" style="
                position: absolute;
                top: 2px;
                right: 4px;
                background: none;
                border: none;
                color: white;
                cursor: pointer;
                font-size: 14px;
            ">×</button>
        </div>
    `;
    
    document.body.insertAdjacentHTML('beforeend', warningHTML);
    
    // 10초 후 자동 제거
    setTimeout(() => {
        const warning = document.getElementById('performanceWarning');
        if (warning) {
            warning.remove();
        }
    }, 10000);
}

// ========== 화면 공유 UI 개선 시스템 ==========

// UI 상태 관리
const screenShareUI = {
    isDragging: false,
    dragOffset: { x: 0, y: 0 },
    isMinimalMode: false,
    keyboardShortcutsEnabled: true,
    currentTheme: 'dark' // 'dark', 'light', 'auto'
};

// CSS 스타일 주입
function injectAdvancedStyles() {
    const styleId = 'screenShareAdvancedStyles';
    if (document.getElementById(styleId)) return;
    
    const styles = `
        <style id="${styleId}">
            /* 키프레임 애니메이션 */
            @keyframes slideIn {
                from { transform: translateX(100%); opacity: 0; }
                to { transform: translateX(0); opacity: 1; }
            }
            
            @keyframes pulse {
                0%, 100% { transform: scale(1); }
                50% { transform: scale(1.05); }
            }
            
            @keyframes glow {
                0%, 100% { box-shadow: 0 0 5px rgba(255, 255, 255, 0.5); }
                50% { box-shadow: 0 0 20px rgba(255, 255, 255, 0.8), 0 0 30px rgba(0, 123, 255, 0.6); }
            }
            
            @keyframes shake {
                0%, 100% { transform: translateX(0); }
                25% { transform: translateX(-5px); }
                75% { transform: translateX(5px); }
            }
            
            /* 화면 공유 컨트롤 기본 스타일 */
            .screen-share-controls {
                transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
                user-select: none;
                border: 1px solid rgba(255, 255, 255, 0.2);
            }
            
            .screen-share-controls:hover {
                transform: translateY(-2px);
                box-shadow: 0 8px 25px rgba(0, 0, 0, 0.4);
            }
            
            .screen-share-controls.dragging {
                transform: rotate(3deg) scale(1.05);
                box-shadow: 0 12px 30px rgba(0, 0, 0, 0.6);
                z-index: 10001 !important;
            }
            
            .screen-share-controls.minimal {
                min-width: 120px !important;
                padding: 8px !important;
            }
            
            /* 빠른 품질 버튼 */
            .quality-btn {
                background: linear-gradient(45deg, #333, #555);
                border: 1px solid rgba(255, 255, 255, 0.3);
                color: white;
                padding: 6px 10px;
                border-radius: 20px;
                font-size: 10px;
                cursor: pointer;
                transition: all 0.2s ease;
                position: relative;
                overflow: hidden;
            }
            
            .quality-btn:hover {
                transform: translateY(-1px);
                box-shadow: 0 4px 8px rgba(0, 0, 0, 0.3);
            }
            
            .quality-btn.active {
                background: linear-gradient(45deg, #28a745, #20c997);
                animation: glow 2s infinite;
            }
            
            .quality-btn:before {
                content: '';
                position: absolute;
                top: 0;
                left: -100%;
                width: 100%;
                height: 100%;
                background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.2), transparent);
                transition: left 0.5s;
            }
            
            .quality-btn:hover:before {
                left: 100%;
            }
            
            /* 화면 공유 상태 오버레이 */
            .screen-share-overlay {
                position: absolute;
                top: 10px;
                left: 10px;
                background: rgba(0, 0, 0, 0.8);
                color: white;
                padding: 8px 12px;
                border-radius: 6px;
                font-size: 12px;
                z-index: 1000;
                animation: slideIn 0.5s ease;
                border-left: 4px solid #007bff;
            }
            
            .screen-share-overlay.recording {
                border-left-color: #dc3545;
                animation: pulse 1.5s infinite;
            }
            
            /* 드래그 핸들 */
            .drag-handle {
                cursor: move;
                padding: 4px;
                text-align: center;
                background: rgba(255, 255, 255, 0.1);
                border-radius: 4px;
                margin-bottom: 6px;
                transition: background 0.2s ease;
            }
            
            .drag-handle:hover {
                background: rgba(255, 255, 255, 0.2);
            }
            
            .drag-handle:active {
                background: rgba(255, 255, 255, 0.3);
            }
            
            /* 키보드 단축키 힌트 */
            .keyboard-hint {
                position: fixed;
                bottom: 10px;
                right: 10px;
                background: rgba(0, 0, 0, 0.9);
                color: white;
                padding: 10px;
                border-radius: 6px;
                font-size: 11px;
                z-index: 9998;
                opacity: 0;
                transition: opacity 0.3s ease;
                max-width: 200px;
            }
            
            .keyboard-hint.show {
                opacity: 1;
            }
            
            /* 토스트 알림 개선 */
            .toast-notification {
                position: fixed;
                top: 50px;
                right: 10px;
                background: linear-gradient(45deg, #007bff, #0056b3);
                color: white;
                padding: 12px 16px;
                border-radius: 8px;
                font-size: 13px;
                z-index: 10000;
                box-shadow: 0 4px 12px rgba(0, 123, 255, 0.3);
                animation: slideIn 0.3s ease;
                max-width: 300px;
                border-left: 4px solid #ffc107;
            }
            
            .toast-notification.success {
                background: linear-gradient(45deg, #28a745, #20c997);
                border-left-color: #17a2b8;
            }
            
            .toast-notification.warning {
                background: linear-gradient(45deg, #ffc107, #e0a800);
                border-left-color: #dc3545;
            }
            
            .toast-notification.error {
                background: linear-gradient(45deg, #dc3545, #c82333);
                border-left-color: #6f42c1;
            }
            
            /* 테마 관련 스타일 */
            .theme-light .screen-share-controls {
                background: rgba(255, 255, 255, 0.95) !important;
                color: #333 !important;
                border-color: rgba(0, 0, 0, 0.2) !important;
            }
            
            .theme-light .quality-btn {
                background: linear-gradient(45deg, #f8f9fa, #e9ecef);
                color: #333;
                border-color: rgba(0, 0, 0, 0.2);
            }
        </style>
    `;
    
    document.head.insertAdjacentHTML('beforeend', styles);
}

// 향상된 화면 공유 컨트롤 UI 표시
function showScreenShareControls() {
    // 스타일 주입
    injectAdvancedStyles();
    
    // 컨트롤 패널이 없으면 생성
    if (!document.getElementById('screenShareControls')) {
        const controlsHTML = `
            <div id="screenShareControls" class="screen-share-controls" style="
                position: fixed;
                top: 10px;
                right: 10px;
                background: rgba(0,0,0,0.95);
                color: white;
                padding: 14px;
                border-radius: 12px;
                font-size: 12px;
                z-index: 9999;
                display: flex;
                flex-direction: column;
                gap: 10px;
                min-width: 300px;
                max-width: 350px;
                box-shadow: 0 6px 20px rgba(0,0,0,0.4);
                backdrop-filter: blur(15px);
            ">
                <!-- 드래그 핸들 -->
                <div class="drag-handle" title="드래그하여 이동">
                    <span style="color: #888;">⋮⋮⋮</span>
                </div>
                
                <!-- 헤더 -->
                <div style="
                    font-weight: bold; 
                    text-align: center; 
                    padding-bottom: 10px; 
                    border-bottom: 1px solid rgba(255,255,255,0.2);
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                ">
                    <span>🖥️ 화면 공유 컨트롤</span>
                    <div style="display: flex; gap: 4px;">
                        <button onclick="toggleMinimalMode()" title="미니멀 모드" style="
                            background: none;
                            border: none;
                            color: white;
                            cursor: pointer;
                            font-size: 14px;
                            padding: 2px;
                            opacity: 0.7;
                            border-radius: 3px;
                        ">📱</button>
                        <button onclick="showKeyboardShortcuts()" title="단축키 보기" style="
                            background: none;
                            border: none;
                            color: white;
                            cursor: pointer;
                            font-size: 14px;
                            padding: 2px;
                            opacity: 0.7;
                            border-radius: 3px;
                        ">⌨️</button>
                        <button onclick="toggleScreenShareControls()" style="
                            background: none;
                            border: none;
                            color: white;
                            cursor: pointer;
                            font-size: 16px;
                            padding: 0;
                            opacity: 0.7;
                        ">−</button>
                    </div>
                </div>
                
                <!-- 빠른 품질 변경 버튼 -->
                <div id="quickQualityButtons" style="
                    display: flex;
                    gap: 6px;
                    justify-content: center;
                    margin: 4px 0;
                ">
                    <button onclick="quickQualityChange('high')" class="quality-btn" id="qualityBtnHigh">
                        🟢 고화질
                    </button>
                    <button onclick="quickQualityChange('medium')" class="quality-btn active" id="qualityBtnMedium">
                        🟡 중화질
                    </button>
                    <button onclick="quickQualityChange('low')" class="quality-btn" id="qualityBtnLow">
                        🔴 저화질
                    </button>
                </div>
                
                <!-- 현재 상태 -->
                <div style="display: flex; justify-content: space-between; align-items: center;">
                    <span>현재 화질:</span>
                    <span id="screenShareQuality" style="font-weight: bold;">🔄 자동</span>
                </div>
                
                <!-- 실시간 통계 -->
                <div id="screenShareStats" style="
                    background: rgba(255,255,255,0.1);
                    padding: 10px;
                    border-radius: 8px;
                    display: grid;
                    grid-template-columns: 1fr 1fr;
                    gap: 6px;
                    font-size: 11px;
                ">
                    <div class="stats-item">📊 0 kbps</div>
                    <div class="stats-item">🎞️ 0 fps</div>
                    <div class="stats-item">📉 0 lost</div>
                    <div class="stats-item">⚡ 0ms</div>
                </div>
                
                <!-- 성능 차트 -->
                <div id="performanceChart" style="
                    height: 70px;
                    background: rgba(255,255,255,0.1);
                    border-radius: 8px;
                    position: relative;
                    overflow: hidden;
                ">
                    <canvas id="chartCanvas" width="280" height="70" style="display: block;"></canvas>
                </div>
                
                <!-- 네트워크 및 디바이스 상태 -->
                <div style="display: flex; justify-content: space-between; font-size: 11px;">
                    <div>
                        <span style="opacity: 0.8;">네트워크:</span>
                        <span id="networkStatus">🟡 중간</span>
                    </div>
                    <div>
                        <span style="opacity: 0.8;">디바이스:</span>
                        <span id="deviceStatus">🟡 중간</span>
                    </div>
                </div>
                
                <!-- 컨트롤 버튼 -->
                <div style="display: flex; gap: 6px; margin-top: 6px;">
                    <button onclick="toggleAutoOptimize()" id="autoOptimizeBtn" style="
                        background: #28a745;
                        color: white;
                        border: none;
                        padding: 6px 10px;
                        border-radius: 6px;
                        font-size: 10px;
                        cursor: pointer;
                        flex: 1;
                        transition: all 0.2s ease;
                    ">자동 최적화</button>
                    <button onclick="oneClickOptimize()" style="
                        background: #007bff;
                        color: white;
                        border: none;
                        padding: 6px 10px;
                        border-radius: 6px;
                        font-size: 10px;
                        cursor: pointer;
                        flex: 1;
                        transition: all 0.2s ease;
                    ">⚡ 원클릭 최적화</button>
                    <button onclick="showDetailedStats()" style="
                        background: #6f42c1;
                        color: white;
                        border: none;
                        padding: 6px 10px;
                        border-radius: 6px;
                        font-size: 10px;
                        cursor: pointer;
                        transition: all 0.2s ease;
                    ">📊</button>
                </div>
            </div>
        `;
        document.body.insertAdjacentHTML('beforeend', controlsHTML);
        
        // 차트 초기화
        initPerformanceChart();
        
        // 드래그 기능 초기화
        makePanelDraggable();
        
        // 키보드 단축키 초기화
        initKeyboardShortcuts();
        
        // 화면 공유 상태 오버레이 표시
        showScreenShareOverlay();
    }
    
    document.getElementById('screenShareControls').style.display = 'flex';
}

// 패널을 드래그 가능하게 만들기
function makePanelDraggable() {
    const panel = document.getElementById('screenShareControls');
    const dragHandle = panel.querySelector('.drag-handle');
    
    if (!panel || !dragHandle) return;
    
    dragHandle.addEventListener('mousedown', startDrag);
    
    function startDrag(e) {
        e.preventDefault();
        screenShareUI.isDragging = true;
        
        const rect = panel.getBoundingClientRect();
        screenShareUI.dragOffset.x = e.clientX - rect.left;
        screenShareUI.dragOffset.y = e.clientY - rect.top;
        
        panel.classList.add('dragging');
        
        document.addEventListener('mousemove', drag);
        document.addEventListener('mouseup', stopDrag);
        
        // 드래그 시작 시 선택 방지
        document.body.style.userSelect = 'none';
    }
    
    function drag(e) {
        if (!screenShareUI.isDragging) return;
        
        e.preventDefault();
        
        let newX = e.clientX - screenShareUI.dragOffset.x;
        let newY = e.clientY - screenShareUI.dragOffset.y;
        
        // 화면 경계 제한
        const maxX = window.innerWidth - panel.offsetWidth;
        const maxY = window.innerHeight - panel.offsetHeight;
        
        newX = Math.max(0, Math.min(newX, maxX));
        newY = Math.max(0, Math.min(newY, maxY));
        
        panel.style.left = newX + 'px';
        panel.style.top = newY + 'px';
        panel.style.right = 'auto';
    }
    
    function stopDrag() {
        screenShareUI.isDragging = false;
        panel.classList.remove('dragging');
        
        document.removeEventListener('mousemove', drag);
        document.removeEventListener('mouseup', stopDrag);
        
        // 선택 복원
        document.body.style.userSelect = '';
        
        // 위치 저장 (로컬 스토리지)
        localStorage.setItem('screenShareControlsPosition', JSON.stringify({
            left: panel.style.left,
            top: panel.style.top
        }));
    }
    
    // 저장된 위치 복원
    const savedPosition = localStorage.getItem('screenShareControlsPosition');
    if (savedPosition) {
        try {
            const position = JSON.parse(savedPosition);
            if (position.left && position.top) {
                panel.style.left = position.left;
                panel.style.top = position.top;
                panel.style.right = 'auto';
            }
        } catch (e) {
            console.warn('화면 공유 컨트롤 위치 복원 실패:', e);
        }
    }
}

// 빠른 품질 변경
function quickQualityChange(quality) {
    // 기존 활성 버튼 비활성화
    document.querySelectorAll('.quality-btn').forEach(btn => {
        btn.classList.remove('active');
    });
    
    // 새 버튼 활성화
    const targetBtn = document.getElementById(`qualityBtn${quality.charAt(0).toUpperCase() + quality.slice(1)}`);
    if (targetBtn) {
        targetBtn.classList.add('active');
    }
    
    // 품질 변경 실행
    changeScreenShareQuality(quality);
    
    // 피드백 토스트
    showToast(`화질을 ${getQualityLabel(quality)}로 변경했습니다.`, 'success');
}

// 품질 라벨 가져오기
function getQualityLabel(quality) {
    const labels = {
        'high': '고화질',
        'medium': '중화질',
        'low': '저화질',
        'auto': '자동'
    };
    return labels[quality] || quality;
}

// 키보드 단축키 초기화
function initKeyboardShortcuts() {
    if (!screenShareUI.keyboardShortcutsEnabled) return;
    
    document.addEventListener('keydown', handleKeyboardShortcut);
}

// 키보드 단축키 처리
function handleKeyboardShortcut(e) {
    if (!shareView) return; // 화면 공유 중이 아니면 무시
    
    // Ctrl + Shift 조합 확인
    if (e.ctrlKey && e.shiftKey) {
        switch (e.key.toLowerCase()) {
            case 'q': // 품질 변경 사이클
                e.preventDefault();
                cycleQuality();
                break;
            case 's': // 통계 토글
                e.preventDefault();
                showDetailedStats();
                break;
            case 'm': // 미니멀 모드 토글
                e.preventDefault();
                toggleMinimalMode();
                break;
            case 'o': // 원클릭 최적화
                e.preventDefault();
                oneClickOptimize();
                break;
            case 'h': // 도움말 표시
                e.preventDefault();
                showKeyboardShortcuts();
                break;
        }
    }
}

// 품질 순환 변경
function cycleQuality() {
    const qualities = ['low', 'medium', 'high'];
    const currentIndex = qualities.indexOf(screenShareConfig.currentQuality);
    const nextIndex = (currentIndex + 1) % qualities.length;
    const nextQuality = qualities[nextIndex];
    
    quickQualityChange(nextQuality);
}

// 키보드 단축키 도움말 표시
function showKeyboardShortcuts() {
    const existingHint = document.getElementById('keyboardHint');
    if (existingHint) {
        existingHint.remove();
        return;
    }
    
    const hintHTML = `
        <div id="keyboardHint" class="keyboard-hint show">
            <div style="font-weight: bold; margin-bottom: 8px;">⌨️ 키보드 단축키</div>
            <div style="line-height: 1.4;">
                <div><kbd>Ctrl+Shift+Q</kbd> 품질 변경</div>
                <div><kbd>Ctrl+Shift+S</kbd> 상세 통계</div>
                <div><kbd>Ctrl+Shift+M</kbd> 미니멀 모드</div>
                <div><kbd>Ctrl+Shift+O</kbd> 원클릭 최적화</div>
                <div><kbd>Ctrl+Shift+H</kbd> 이 도움말</div>
            </div>
            <div style="text-align: center; margin-top: 8px;">
                <button onclick="this.closest('#keyboardHint').remove()" style="
                    background: #007bff;
                    color: white;
                    border: none;
                    padding: 4px 8px;
                    border-radius: 4px;
                    font-size: 10px;
                    cursor: pointer;
                ">닫기</button>
            </div>
        </div>
    `;
    
    document.body.insertAdjacentHTML('beforeend', hintHTML);
    
    // 10초 후 자동 제거
    setTimeout(() => {
        const hint = document.getElementById('keyboardHint');
        if (hint) {
            hint.classList.remove('show');
            setTimeout(() => hint.remove(), 300);
        }
    }, 10000);
}

// 화면 공유 상태 오버레이 표시
function showScreenShareOverlay() {
    // 사용자의 비디오 엘리먼트 찾기
    const userVideo = document.querySelector(`video[id*="${userId}"]`);
    if (!userVideo) return;
    
    const container = userVideo.parentElement;
    if (!container) return;
    
    // 기존 오버레이 제거
    const existingOverlay = container.querySelector('.screen-share-overlay');
    if (existingOverlay) {
        existingOverlay.remove();
    }
    
    // 새 오버레이 생성
    const overlayHTML = `
        <div class="screen-share-overlay recording">
            <div style="display: flex; align-items: center; gap: 6px;">
                <span style="animation: pulse 1s infinite;">🔴</span>
                <span style="font-weight: bold;">화면 공유 중</span>
            </div>
            <div style="font-size: 10px; opacity: 0.9; margin-top: 2px;">
                <span id="shareTime">00:00</span> | 
                <span id="shareQuality">${getQualityLabel(screenShareConfig.currentQuality)}</span>
            </div>
        </div>
    `;
    
    container.style.position = 'relative';
    container.insertAdjacentHTML('beforeend', overlayHTML);
    
    // 시간 업데이트 시작
    startShareTimeCounter();
}

// 화면 공유 시간 카운터
let shareStartTime = null;
let shareTimeInterval = null;

function startShareTimeCounter() {
    shareStartTime = Date.now();
    
    if (shareTimeInterval) {
        clearInterval(shareTimeInterval);
    }
    
    shareTimeInterval = setInterval(() => {
        const elapsed = Math.floor((Date.now() - shareStartTime) / 1000);
        const minutes = Math.floor(elapsed / 60);
        const seconds = elapsed % 60;
        
        const timeElement = document.getElementById('shareTime');
        if (timeElement) {
            timeElement.textContent = `${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}`;
        }
    }, 1000);
}

// 미니멀 모드 토글
function toggleMinimalMode() {
    const controls = document.getElementById('screenShareControls');
    if (!controls) return;
    
    screenShareUI.isMinimalMode = !screenShareUI.isMinimalMode;
    
    if (screenShareUI.isMinimalMode) {
        // 미니멀 모드 활성화
        controls.classList.add('minimal');
        
        // 불필요한 요소들 숨기기
        const hideElements = [
            '#screenShareStats',
            '#performanceChart',
            '#quickQualityButtons div:last-child',
            '.drag-handle'
        ];
        
        hideElements.forEach(selector => {
            const element = controls.querySelector(selector);
            if (element) {
                element.style.display = 'none';
            }
        });
        
        // 헤더 텍스트 변경
        const header = controls.querySelector('div:nth-child(2) span');
        if (header) {
            header.textContent = '🖥️ 화면공유';
        }
        
        showToast('미니멀 모드가 활성화되었습니다.', 'info');
    } else {
        // 미니멀 모드 비활성화
        controls.classList.remove('minimal');
        
        // 요소들 다시 표시
        const showElements = [
            '#screenShareStats',
            '#performanceChart',
            '#quickQualityButtons div:last-child',
            '.drag-handle'
        ];
        
        showElements.forEach(selector => {
            const element = controls.querySelector(selector);
            if (element) {
                element.style.display = '';
            }
        });
        
        // 헤더 텍스트 복원
        const header = controls.querySelector('div:nth-child(2) span');
        if (header) {
            header.textContent = '🖥️ 화면 공유 컨트롤';
        }
        
        showToast('미니멀 모드가 비활성화되었습니다.', 'info');
    }
}

// 원클릭 최적화
function oneClickOptimize() {
    showToast('최적화를 진행하고 있습니다...', 'info');

    return Promise.all([
        detectNetworkQuality(),
        detectDevicePerformance(),
        determineOptimalQuality()
    ]).then(function (qualities) {
        const networkQuality = qualities[0];
        const deviceQuality = qualities[1];
        const optimalQuality = qualities[2];

        const applyQualityChange = optimalQuality !== screenShareConfig.currentQuality
            ? changeScreenShareQuality(optimalQuality).then(function () {
                quickQualityChange(optimalQuality);
            })
            : Promise.resolve();

        return applyQualityChange.then(function () {
            if (!screenShareConfig.autoOptimize) {
                toggleAutoOptimize();
            }

            const resultMessage = `최적화 완료!\n네트워크: ${getQualityLabel(networkQuality)}\n디바이스: ${getQualityLabel(deviceQuality)}\n적용된 화질: ${getQualityLabel(optimalQuality)}`;
            showToast(resultMessage, 'success');

            console.log('원클릭 최적화 완료:', {
                networkQuality,
                deviceQuality,
                optimalQuality
            });
        });
    }).catch(function (error) {
        console.error('원클릭 최적화 실패:', error);
        showToast('최적화 중 오류가 발생했습니다.', 'error');
    });
}

// 향상된 토스트 알림
function showToast(message, type = 'info', duration = 3000) {
    // 기존 토스트 제거
    const existingToast = document.querySelector('.toast-notification');
    if (existingToast) {
        existingToast.remove();
    }
    
    const toastHTML = `
        <div class="toast-notification ${type}">
            <div style="font-weight: bold; margin-bottom: 4px;">
                ${getToastIcon(type)} ${getToastTitle(type)}
            </div>
            <div style="font-size: 12px; opacity: 0.95; line-height: 1.3;">
                ${message.replace(/\n/g, '<br>')}
            </div>
        </div>
    `;
    
    document.body.insertAdjacentHTML('beforeend', toastHTML);
    
    const toast = document.querySelector('.toast-notification');
    
    // 자동 제거
    setTimeout(() => {
        if (toast && toast.parentNode) {
            toast.style.opacity = '0';
            toast.style.transform = 'translateX(100%)';
            setTimeout(() => {
                if (toast.parentNode) {
                    toast.parentNode.removeChild(toast);
                }
            }, 300);
        }
    }, duration);
    
    // 클릭으로 닫기
    toast.addEventListener('click', () => {
        toast.style.opacity = '0';
        toast.style.transform = 'translateX(100%)';
        setTimeout(() => {
            if (toast.parentNode) {
                toast.parentNode.removeChild(toast);
            }
        }, 300);
    });
}

// 토스트 아이콘 및 제목
function getToastIcon(type) {
    const icons = {
        'info': 'ℹ️',
        'success': '✅',
        'warning': '⚠️',
        'error': '❌'
    };
    return icons[type] || 'ℹ️';
}

function getToastTitle(type) {
    const titles = {
        'info': '정보',
        'success': '성공',
        'warning': '주의',
        'error': '오류'
    };
    return titles[type] || '알림';
}

// 화면 공유 컨트롤 UI 숨기기 (오버라이드)
function hideScreenShareControls() {
    const controls = document.getElementById('screenShareControls');
    if (controls) {
        controls.style.display = 'none';
    }
    
    // 키보드 단축키 제거
    document.removeEventListener('keydown', handleKeyboardShortcut);
    
    // 시간 카운터 정리
    if (shareTimeInterval) {
        clearInterval(shareTimeInterval);
        shareTimeInterval = null;
    }
    
    // 화면 공유 오버레이 제거
    document.querySelectorAll('.screen-share-overlay').forEach(overlay => {
        overlay.remove();
    });
}

// ===== 자막 기능 관련 함수들 =====

/**
 * 자막 UI 초기화
 */
function initSubtitleUI() {
    // SpeechRecognitionManager 상태 변경 이벤트 리스너
    document.addEventListener('speechRecognitionStatusChanged', function(event) {
        updateSubtitleButtonUI(event.detail);
    });
    
    // 초기 상태 설정
    updateSubtitleButtonUI({
        status: 'stopped',
        isEnabled: false,
        isRecognizing: false,
        isElectronBlocked: false,
        canManualRetry: false
    });
}

/**
 * 자막 토글 함수
 */
function toggleSubtitle() {
    try {
        const isEnabled = toggleSpeechRecognition();
        console.log('자막 기능 토글:', isEnabled ? '켜짐' : '꺼짐');
        
        // 사용자에게 피드백 제공
        if (isEnabled) {
            showToast('실시간 자막이 시작되었습니다', 'success');
        } else {
            showToast('실시간 자막이 중지되었습니다', 'info');
        }
        
    } catch (error) {
        console.error('자막 토글 실패:', error);
        showToast('자막 기능을 사용할 수 없습니다', 'error');
    }
}

/**
 * 자막 버튼 UI 업데이트
 */
function updateSubtitleButtonUI(status) {
    const $subtitleBtn = $('#subtitleBtn')[0];
    if (!$subtitleBtn) return;
    
    const { isEnabled, isRecognizing, status: currentStatus, isElectronBlocked, canManualRetry } = status;
    
    // 버튼 상태 업데이트
    $subtitleBtn.setAttribute('data-flag', isEnabled.toString());
    
    // 아이콘 변경
    if (isEnabled) {
        $subtitleBtn.src = 'images/webrtc/subtitle-on.svg';
        $subtitleBtn.title = '실시간 자막 (켜짐)';
    } else {
        $subtitleBtn.src = 'images/webrtc/subtitle-off.svg';
        $subtitleBtn.title = '실시간 자막 (꺼짐)';
    }
    
    // 상태에 따른 시각적 피드백
    $subtitleBtn.style.opacity = isEnabled ? '1.0' : '0.6';
    
    // 인식 중일 때 애니메이션 효과
    if (isRecognizing) {
        $subtitleBtn.style.animation = 'pulse 1.5s infinite';
    } else {
        $subtitleBtn.style.animation = 'none';
    }
    
    // 에러 상태 처리
    if (currentStatus === 'error' || currentStatus === 'permission-denied') {
        $subtitleBtn.style.opacity = '0.4';
        $subtitleBtn.title = '자막 기능 오류';
    } else if (currentStatus === 'unsupported' || isElectronBlocked) {
        $subtitleBtn.style.opacity = canManualRetry === false ? '0.4' : '0.6';
        $subtitleBtn.title = 'Electron 음성 인식 불안정 - 수동 재시도 가능';
    }
}

/**
 * 브라우저 지원 여부 확인 및 사용자 알림
 */
function checkSubtitleSupport() {
    const status = getSpeechRecognitionStatus();
    
    if (!status.isSupported) {
        showToast('이 브라우저는 음성 인식을 지원하지 않습니다', 'warning');
        return false;
    }
    
    return true;
}
