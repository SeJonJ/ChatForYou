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
    recordingInterrupted: (msg) => recording.handleRecordingInterruptedByServer(msg),

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
 * 빠른 복구 재시도를 소진했을 때 재연결 루프로 인계한다.
 * 복구 타이머·카운터를 먼저 정리해 재시도 루프가 둘 다 도는 것을 막고, 재연결 예약은 한 건만 남긴다.
 */
function handOffRecoveryToReconnect() {
    if (isIntentionalClose()) {
        return;
    }

    resetRecoveryState();
    if (!reconnectNoticeMessage) {
        setReconnectNoticeMessage(DEPLOY_RECONNECT_NOTICE);
    }

    if (reconnectInProgress) {
        reconnectInProgress = false;
        scheduleReconnect();
        return;
    }

    if (ws) {
        // onclose 가 재연결 경로로 수렴시킨다.
        ws.close();
    }
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
                console.log('room redirect to : ', data?.roomId);
                if (ReconnectPolicy.decideRedirectRoomAction(reconnectInProgress) === 'RECONNECT') {
                    // 응답의 Set-Cookie 로 라우팅이 갱신됐으므로 다음 재연결 시도가 새 owner 로 간다.
                    reconnectInProgress = false;
                    scheduleReconnect();
                    return;
                }
                clearConnectedSession();
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

            if (result === 'REDIRECT_RECOVER' && ReconnectPolicy.isRetryableRecoveryReason(data?.reason)) {
                if (ReconnectPolicy.decideRecoveryRetry(recoveryRetryCount, RECOVERY_MAX_RETRY) === 'HAND_OFF') {
                    handOffRecoveryToReconnect();
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

    // teardownRoomSession은 정상 퇴장(leaveRoom)과 강제 세션 킥(onSessionReplaced) 양쪽의 단일 합류점이므로 여기서 정리한다.
    if (typeof SpeakingDetector !== 'undefined') {
        try {
            SpeakingDetector.stopSpeaking();
        } catch (speakingError) {
            console.error('[SpeakingDetector] 전체 정리 중 에러:', speakingError);
        }
    }

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
                // 발급 실패 시 reload/재시도 금지 — turnUrls=null 유지, buildIceServers 가 TURN 엔트리 제외
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
            // 발급 실패는 연결 차단이 아닌 경고 — turnUrls=null 유지(buildIceServers 가 host/srflx 로 진행)
            console.error('[TURN] 자격증명 발급 오류:', error?.message || error);
        });
};

/**
 * RTCPeerConnection 의 iceServers 배열을 구성한다.
 * turnUrls 가 유효한 비어있지 않은 배열일 때만 TURN 엔트리를 포함하고,
 * 미발급(null)이면 빈 배열을 반환한다 — {urls:null} 주입 시 RTCPeerConnection 이
 * "'null' is not a valid URL" 로 동기 throw 하므로 그 크래시를 차단하기 위함.
 * 빈 배열이면 브라우저는 host/srflx candidate 로 연결을 시도한다(relay 부재 = degrade, 크래시 아님).
 */
function buildIceServers() {
    if (Array.isArray(turnUrls) && turnUrls.length > 0) {
        return [{ urls: turnUrls, username: turnUser, credential: turnPwd }];
    }
    return [];
}

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
                console.log('room redirect to : ', data?.roomId);
                if (ReconnectPolicy.decideRedirectRoomAction(reconnectInProgress) === 'RECONNECT') {
                    if (ws) { ws.close(); }
                    return;
                }
                clearConnectedSession();
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
            } else if (ReconnectPolicy.decideRegisterFailureAction(reconnectInProgress) === 'RECONNECT') {
                // 배포 중 일시적인 서버 오류로 입장 요청을 못 보낸 채 소켓만 열려 있으면 화면이 멈춘다.
                if (ws) { ws.close(); }
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

        // 재연결로 마이크·카메라를 새로 열면 트랙은 켜진 상태로 시작하는데 버튼은 끔 상태 그대로다.
        // 화면과 실제 송출이 어긋나 음소거가 조용히 풀리므로 버튼 상태를 새 트랙에 다시 적용한다.
        ReconnectPolicy.applyLocalMediaStates(
            stream,
            ReconnectPolicy.resolveMediaFlag($('#audioBtn').data('flag')),
            ReconnectPolicy.resolveMediaFlag($('#videoBtn').data('flag'))
        );

        // 로컬 발화 감지 tap 부착 — 실패해도 WebRTC 입장 흐름은 계속되어야 한다.
        if (typeof SpeakingDetector !== 'undefined') {
            participant.speakingToken = SpeakingDetector.attachSpeaking(userId, stream, participant.getElement());
        }

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
                iceServers: buildIceServers()  // turnUrls=null 시 [] 반환 → RTCPeerConnection 크래시 방지
            }
        };

        try {
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
        } catch (e) {
            // RTCPeerConnection 동기 throw(잘못된 iceServers 등)는 비동기 콜백으로 오지 않는다.
            // getUserMedia 의 catch 로 새어 audio 오류로 오분류되지 않도록 여기서 peer 오류로 처리.
            return handlePeerSetupError({ participantId: userId, role: 'self', phase: 'create', error: e });
        }
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
            iceServers: buildIceServers()  // turnUrls=null 시 [] 반환 → RTCPeerConnection 크래시 방지
        }
    }

    try {
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
    } catch (e) {
        // RTCPeerConnection 동기 throw → 비동기 콜백 미경유. peer 오류로 분류(audio 모달 방지).
        return handlePeerSetupError({ participantId: sender.userId, role: 'remote', phase: 'create', error: e });
    }

    participant.rtcPeer.peerConnection.onaddstream = function(event) {
        const audioTracks = event.stream.getAudioTracks();
        const videoTracks = event.stream.getVideoTracks();

        console.log('[WebRTC:Stream] 스트림 수신 - userId:', sender.userId,
            '| 비디오:', videoTracks.length, '| 오디오:', audioTracks.length);
        if (audioTracks.length === 0) console.warn('[WebRTC:Stream] 오디오 트랙 없음');
        if (videoTracks.length === 0) console.warn('[WebRTC:Stream] 비디오 트랙 없음');

        // 스트림 할당
        // Video 요소: 비디오 트랙만 포함한다. 원격 오디오는 audio element가 단독 재생한다.
        if (videoTracks.length > 0) {
            const videoOnlyStream = new MediaStream(videoTracks);
            video.srcObject = videoOnlyStream;
            console.log('[WebRTC:Stream] 비디오 전용 스트림 생성:', videoOnlyStream.id);
        } else {
            video.srcObject = null;
        }

        // Audio 요소: 오디오 트랙만 포함한 별도 MediaStream - 오디오 재생용
        if (audioTracks.length > 0) {
            const audioOnlyStream = new MediaStream(audioTracks);
            audio.srcObject = audioOnlyStream;
            console.log('[WebRTC:Stream] 오디오 전용 스트림 생성:', audioOnlyStream.id);

            // 원격 발화 감지 tap 부착 — 실패해도 원격 재생 흐름은 계속되어야 한다.
            if (typeof SpeakingDetector !== 'undefined') {
                participant.speakingToken = SpeakingDetector.attachSpeaking(sender.userId, audioOnlyStream, participant.getElement());
            }
        } else {
            audio.srcObject = null;
        }

        // muted 상태로 설정하여 자동재생 정책 우회 (muted 자동재생은 항상 허용)
        video.muted = true;
        audio.muted = true;

        // 오디오 재생 함수 (remote audio의 유일한 playback sink)
        let audioPlaybackStarted = false;
        const playAudio = function() {
            if (audioPlaybackStarted) {
                return;
            }

            if (audio && audio.srcObject) {
                audioPlaybackStarted = true;
                audio.play().then(function() {
                    console.log('[WebRTC:Play] 오디오 재생 시작됨');
                    audio.muted = !ParticipantUtils.getAudioState(sender.userId).enabled;
                    audio.volume = 0.5;
                }).catch(function(error) {
                    audioPlaybackStarted = false;
                    console.error('[WebRTC:Play] 오디오 재생 실패:', error);
                    audio.muted = !ParticipantUtils.getAudioState(sender.userId).enabled;
                });
            }
        };

        // onloadedmetadata 에서 play() 한번만 호출 (중복 호출 제거)
        video.onloadedmetadata = function() {
            console.log('[WebRTC:Play] 비디오 메타데이터 로드됨 -', video.videoWidth, 'x', video.videoHeight);

            video.play().then(function() {
                console.log('[WebRTC:Play] 비디오 재생 시작됨');
                video.muted = true;
                video.volume = 0;
                playAudio();
            }).catch(function(error) {
                console.error('[WebRTC:Play] 비디오 재생 실패:', error);
                playAudio();
            });
        };

        if (videoTracks.length === 0) {
            playAudio();
        }

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
