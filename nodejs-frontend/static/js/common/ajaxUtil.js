/**
 * ajax 사용 시 공통화를 위한 js
 */

const AUTH_REQUIRED_ERROR_CODES = ['A002', 'A003', 'A004', 'A006', 'U001'];
const INVALID_ROOM_ACCESS_ERROR_CODES = ['R004', 'R005'];
const FILE_EXTENSION_ERROR_CODES = ['F001'];
let accessTokenRefreshPromise = null;
let roomTokenRefreshPromise = null;

function isAuthRequiredErrorCode(code) {
    return AUTH_REQUIRED_ERROR_CODES.includes(code);
}

function isInvalidRoomAccessErrorCode(code) {
    return INVALID_ROOM_ACCESS_ERROR_CODES.includes(code);
}

function isFileExtensionErrorCode(code) {
    return FILE_EXTENSION_ERROR_CODES.includes(code);
}

function redirectToLogin() {
    window.location.href = window.__CONFIG__.BASE_URL + '/login/chatlogin.html';
}

function redirectToRoomList() {
    window.location.href = window.__CONFIG__.BASE_URL + '/roomlist.html';
}

/**
 * 토큰 보호 API 요청에 공통으로 사용할 인증 헤더를 구성한다.
 * @returns {{Authorization: string, 'X-Room-Token'?: string}}
 */
function buildTokenHeaders() {
    const headers = {
        'Authorization': localStorage.getItem('access_token') || ''
    };

    const roomToken = sessionStorage.getItem('roomAccessToken');
    if (roomToken) {
        headers['X-Room-Token'] = roomToken;
    }

    return headers;
}

/**
 * Firebase REST API를 통해 access token을 갱신한다.
 * @returns {Promise<string>}
 */
function refreshAccessTokenWithFirebaseRest() {
    const refreshToken = localStorage.getItem('refresh_token');
    const apiKey = window.__CONFIG__?.GOOGLE_OAUTH?.API_KEY;

    if (!refreshToken || !apiKey) {
        return Promise.reject(new Error('Missing refresh token or Firebase API key'));
    }

    return fetch('https://securetoken.googleapis.com/v1/token?key=' + apiKey, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/x-www-form-urlencoded'
        },
        body: new URLSearchParams({
            grant_type: 'refresh_token',
            refresh_token: refreshToken
        }).toString()
    }).then(function(response) {
        return response.json().then(function(payload) {
            if (!response.ok || !payload?.id_token) {
                throw payload || new Error('Firebase REST refresh failed');
            }

            localStorage.setItem('access_token', payload.id_token);
            if (payload.refresh_token) {
                localStorage.setItem('refresh_token', payload.refresh_token);
            }

            return payload.id_token;
        });
    });
}

/**
 * 진행 중인 refresh Promise를 재사용하거나 새 refresh 요청을 시작한다.
 * @returns {Promise<string>}
 */
function getAccessTokenRefreshPromise() {
    if (!accessTokenRefreshPromise) {
        accessTokenRefreshPromise = refreshAccessTokenWithFirebaseRest()
            .finally(function() {
                accessTokenRefreshPromise = null;
            });
    }

    return accessTokenRefreshPromise;
}

/**
 * room page인 경우 URL query에서 roomId를 반환한다. 그 외 페이지는 null.
 * @returns {string|null}
 */
function getCurrentRoomId() {
    const pathName = window.location.pathname || '';
    if (pathName.indexOf('/room/kurentoroom.html') === -1) {
        return null;
    }
    return new URLSearchParams(window.location.search).get('roomId');
}

/**
 * 현재 방 통화 페이지에서 WebSocket 세션이 활성 상태인지 확인한다.
 * @returns {boolean}
 */
function isInRoomCallPage() {
    const pathName = window.location.pathname || '';
    if (pathName.indexOf('/room/kurentoroom.html') === -1) {
        return false;
    }
    const roomId = new URLSearchParams(window.location.search).get('roomId');
    if (!roomId) return false;
    const connectedKey = roomId.replaceAll('-', '') + '_connected';
    return sessionStorage.getItem(connectedKey) === 'true';
}

/**
 * R005 복구에 사용할 roomId를 요청 단위 override 또는 room page URL에서 가져온다.
 * @param {{roomId?: string}} requestOptions
 * @returns {string|null}
 */
function resolveRoomTokenRecoveryRoomId(requestOptions) {
    return requestOptions.roomId || getCurrentRoomId();
}

/**
 * 만료된 room token을 백엔드 refresh API를 통해 갱신한다.
 * @param {string} roomId
 * @returns {Promise<string>}
 */
function refreshRoomTokenWithApi(roomId) {
    return Promise.resolve(
        $.ajax({
            url: window.__CONFIG__.API_BASE_URL + '/chat/room/token/refresh/' + roomId,
            type: 'POST',
            xhrFields: { withCredentials: true },
            headers: {
                'Authorization': localStorage.getItem('access_token') || '',
                'X-Room-Token': sessionStorage.getItem('roomAccessToken') || ''
            }
        })
    ).then(function(response) {
        const token = response?.data?.token;
        if (!token) {
            return Promise.reject(new Error('Room token missing in refresh response'));
        }
        sessionStorage.setItem('roomAccessToken', token);
        return token;
    });
}

/**
 * 진행 중인 room token refresh Promise를 재사용하거나 새 요청을 시작한다.
 * @param {string} roomId
 * @returns {Promise<string>}
 */
function getRoomTokenRefreshPromise(roomId) {
    if (!roomTokenRefreshPromise) {
        roomTokenRefreshPromise = refreshRoomTokenWithApi(roomId)
            .finally(function() {
                roomTokenRefreshPromise = null;
            });
    }
    return roomTokenRefreshPromise;
}

/**
 * 실패한 요청이 access token refresh 이후 재시도 대상인지 확인한다.
 * @param {{hasRetriedAccessToken?: boolean, skipAuthRetry?: boolean}} requestOptions
 * @param {{responseJSON?: {code?: string}}} error
 * @returns {boolean}
 */
function shouldRetryAccessTokenRequest(requestOptions, error) {
    return error?.responseJSON?.code === 'A003'
        && !requestOptions.hasRetriedAccessToken
        && !requestOptions.skipAuthRetry;
}

/**
 * 실패한 요청이 room token refresh 이후 재시도 대상인지 확인한다.
 * @param {{hasRetriedRoomToken?: boolean, skipRoomRecovery?: boolean}} requestOptions
 * @param {{responseJSON?: {code?: string}}} error
 * @returns {boolean}
 */
function shouldRetryRoomTokenRequest(requestOptions, error) {
    return error?.responseJSON?.code === 'R005'
        && !requestOptions.hasRetriedRoomToken
        && !requestOptions.skipRoomRecovery;
}

/**
 * 토큰 요청 실패를 호출자 errorCallback 또는 공통 에러 처리기로 전달한다.
 * @param {{errorCallback?: Function}} requestOptions
 * @param {*} error
 * @returns {void}
 */
function handleTokenAjaxFailure(requestOptions, error) {
    if (requestOptions.errorCallback && typeof requestOptions.errorCallback === 'function') {
        requestOptions.errorCallback(error);
    } else {
        handleApiError(error);
    }
}

/**
 * access token 1회 재시도 규칙을 포함한 보호 API ajax 요청을 수행한다.
 * @param {{url: string, method: string, data?: *, async?: boolean, contentType?: string, roomId?: string, successCallback?: Function, errorCallback?: Function, completeCallback?: Function, hasRetriedAccessToken?: boolean, hasRetriedRoomToken?: boolean, skipAuthRetry?: boolean}} requestOptions
 * @returns {void}
 */
function executeTokenAjax(requestOptions) {
    const isRetry = requestOptions.hasRetriedAccessToken || requestOptions.hasRetriedRoomToken;
    const effectiveAsync = requestOptions.async !== undefined ? requestOptions.async : true;

    function sendRequest() {
        $.ajax({
            url: requestOptions.url,
            type: requestOptions.method,
            data: requestOptions.data,
            async: effectiveAsync,
            contentType: requestOptions.contentType,
            xhrFields: {
                withCredentials: true
            },
            headers: buildTokenHeaders(),
            success: function (data) {
                if (requestOptions.successCallback && typeof requestOptions.successCallback === 'function') {
                    requestOptions.successCallback(data);
                }
            },
            error: function (error) {
                if (shouldRetryAccessTokenRequest(requestOptions, error)) {
                    getAccessTokenRefreshPromise()
                        .then(function() {
                            executeTokenAjax({
                                ...requestOptions,
                                hasRetriedAccessToken: true
                            });
                        })
                        .catch(function () {
                            // 통화 중이면 페이지 이동 없이 toast만 표시
                            if (isInRoomCallPage()) {
                                showApiErrorToast('세션이 만료되었습니다. 통화 종료 후 다시 로그인해주세요.');
                                return;
                            }
                            redirectToLogin();
                        });
                    return;
                }

                const roomId = resolveRoomTokenRecoveryRoomId(requestOptions);
                if (shouldRetryRoomTokenRequest(requestOptions, error) && roomId) {
                    getRoomTokenRefreshPromise(roomId)
                        .then(function() {
                            executeTokenAjax({
                                ...requestOptions,
                                hasRetriedRoomToken: true
                            });
                        })
                        .catch(function() {
                            // 통화 중이면 페이지 이동 없이 toast만 표시
                            if (isInRoomCallPage()) {
                                showApiErrorToast('방 접근 토큰이 만료되었습니다. 통화 종료 후 방에 다시 입장해주세요.');
                                return;
                            }
                            redirectToRoomList();
                        });
                    return;
                }

                handleTokenAjaxFailure(requestOptions, error);
            },
            complete: function (result) {
                if (requestOptions.completeCallback && typeof requestOptions.completeCallback === 'function') {
                    requestOptions.completeCallback(result);
                }
            }
        });
    }

    if (isRetry || !effectiveAsync) {
        sendRequest();
    } else {
        ensureFreshAccessTokenIfExpiring().catch(function() {
            // 선제 갱신 실패는 무시하고 요청을 진행한다
        }).then(sendRequest);
    }
}

/**
 * 공통 API 에러 메시지를 토스트로 노출한다.
 * Toast 유틸이 없는 화면에서는 콘솔 경고로 최소한의 추적 정보를 남긴다.
 * @param {string} message
 */
function showApiErrorToast(message) {
    if (typeof showWarningToast === 'function') {
        showWarningToast(message);
        return;
    }

    if (typeof Toastify === 'function') {
        Toastify({
            text: message,
            duration: 4000,
            newWindow: true,
            close: true,
            gravity: 'top',
            position: 'center',
            style: {
                background: 'linear-gradient(to right, #FF6B6B, #FFE66D)',
            },
        }).showToast();
        return;
    }

    console.warn('[API Error]', message);
}

/**
 * 백엔드 표준 에러 응답을 파싱하여 사용자에게 안내
 * @param {Object} jqXHR - jQuery XHR 객체 또는 { responseJSON: {...} } 형태의 래핑 객체
 */
function handleApiError(jqXHR) {
    const errorData = jqXHR?.responseJSON;
    if (!errorData) {
        showApiErrorToast('서버와의 연결 문제로 요청이 실패했습니다.');
        return;
    }
    const { code, message, traceId } = errorData;
    if (code === 'C003') {
        // 서버 내부 오류는 traceId를 함께 안내하여 장애 추적 지원
        showApiErrorToast(`${message}\n(오류 코드: ${traceId})`);
    } else {
        showApiErrorToast(message);
    }
}

/**
 * 표준 에러 응답이 없는 경우를 포함해 사용자에게 보여줄 메시지를 정규화한다.
 * @param {?Object} errorData
 * @param {string} fallbackMessage
 * @returns {string}
 */
function getApiErrorMessage(errorData, fallbackMessage) {
    if (!errorData) {
        return fallbackMessage || '서버와의 연결 문제로 요청이 실패했습니다.';
    }

    if (errorData.code === 'C003' && errorData.traceId) {
        return `${errorData.message}\n(오류 코드: ${errorData.traceId})`;
    }

    return errorData.message || fallbackMessage || '요청 처리 중 오류가 발생했습니다.';
}

/**
 * fetch 실패 응답 body를 JSON으로 읽는다.
 * 응답 본문이 비어 있거나 JSON이 아니면 null로 처리한다.
 * @param {Response} response
 * @returns {Promise<?Object>}
 */
function parseApiErrorResponse(response) {
    try {
        return response.json().catch(function () {
            return null;
        });
    } catch (error) {
        return Promise.resolve(null);
    }
}

/**
 * fetch 성공 응답이 JSON일 때만 body를 파싱한다.
 * @param {Response} response
 * @returns {Promise<?Object>}
 */
function parseApiSuccessResponse(response) {
    const contentType = response.headers.get('content-type') || '';
    if (!contentType.includes('application/json')) {
        return Promise.resolve(null);
    }

    try {
        return response.json().catch(function () {
            return null;
        });
    } catch (error) {
        return Promise.resolve(null);
    }
}

/**
 * fetch 기반 요청에서 표준 에러 응답과 성공 응답 파싱을 공통 처리한다.
 * @param {string} url
 * @param {RequestInit} options
 * @param {string} fallbackMessage
 * @returns {Promise<?Object>}
 */
function fetchJson(url, options, fallbackMessage) {
    return fetch(url, options)
        .then(function (response) {
            if (!response.ok) {
                return parseApiErrorResponse(response).then(function (errorData) {
                    handleApiError({ responseJSON: errorData });
                    throw {
                        response: response,
                        responseJSON: errorData,
                        message: getApiErrorMessage(errorData, fallbackMessage)
                    };
                });
            }

            return parseApiSuccessResponse(response);
        });
}

function ajax(url, method, async, data, successCallback, errorCallback, completeCallback) {
    $.ajax({
        url: url,
        type: method,
        data: data,
        async: async !== undefined ? async : true,
        xhrFields: {
            withCredentials: true
        },
        success: function (data) {
            if (successCallback && typeof successCallback === 'function') {
                successCallback(data);
            }
        },
        error: function (error) {
            if (errorCallback && typeof errorCallback === 'function') {
                errorCallback(error);
            } else {
                handleApiError(error);
            }
        },
        complete : function(result){
            if (completeCallback && typeof completeCallback === "function") {
                completeCallback(result);
            }
        }
    })
}

function ajaxToJson(url, method, async, data, successCallback, errorCallback, completeCallback) {
    $.ajax({
        url: url,
        type: method,
        data: JSON.stringify(data),
        async: async !== undefined ? async : true,
        contentType : 'application/json; charset=UTF-8',
        xhrFields: {
            withCredentials: true
        },
        success: function (data) {
            if (successCallback && typeof successCallback === 'function') {
                successCallback(data);
            }
        },
        error: function (error) {
            if (errorCallback && typeof errorCallback === 'function') {
                errorCallback(error);
            } else {
                handleApiError(error);
            }
        },
        complete : function(result){
            if (completeCallback && typeof completeCallback === "function") {
                completeCallback(result);
            }
        }
    })
}

function tokenAjax(url, method, async, data, successCallback, errorCallback, completeCallback, requestOptions) {
    executeTokenAjax({
        url: url,
        method: method,
        data: data,
        async: async,
        successCallback: successCallback,
        errorCallback: errorCallback,
        completeCallback: completeCallback,
        ...(requestOptions || {})
    });
}

function tokenAjaxToJson(url, method, async, data, successCallback, errorCallback, completeCallback, requestOptions) {
    executeTokenAjax({
        url: url,
        method: method,
        data: JSON.stringify(data),
        async: async,
        contentType: 'application/json; charset=UTF-8',
        successCallback: successCallback,
        errorCallback: errorCallback,
        completeCallback: completeCallback,
        ...(requestOptions || {})
    });
}

/**
 * Promise 를 return 하는 ajax
 * @param {String }url
 * @param {String} method
 * @param data
 * @returns {Promise<unknown>}
 */
function ajaxToJsonPromise(url, method, data) {
    return new Promise((resolve, reject) => {
        $.ajax({
            url: url,
            method: method,
            data: JSON.stringify(data),
            xhrFields: {
                withCredentials: true
            },
            success: resolve,
            error: reject
        });
    });
}

/**
 * 파일 다운로드 error 콜백의 blob/responseText 에러 본문을 {code, message}로 정규화한다.
 * responseType:'blob'이면 에러 본문이 Blob으로 와서 responseJSON이 undefined가 되므로 필요하다.
 * @param {Object} error - jQuery error 콜백 파라미터 (jqXHR)
 * @returns {Promise<{code?: string, message?: string}|null>}
 */
function normalizeBlobError(error) {
    if (error && error.responseJSON) {
        return Promise.resolve(error.responseJSON);
    }

    const blob = error && error.response;
    if (blob && typeof Blob !== 'undefined' && blob instanceof Blob) {
        return blob.text().then(function(text) {
            try {
                return JSON.parse(text);
            } catch (e) {
                return null;
            }
        }).catch(function() {
            return null;
        });
    }

    const responseText = error && error.responseText;
    if (responseText) {
        try {
            return Promise.resolve(JSON.parse(responseText));
        } catch (e) {
            return Promise.resolve(null);
        }
    }

    return Promise.resolve(null);
}

/**
 * JWT access_token의 payload를 디코드하여 exp claim(epoch seconds)을 반환한다.
 * exp 부재/파싱 실패 시 null을 반환하며 예외를 전파하지 않는다.
 * @param {string} token - Firebase JWT access_token
 * @returns {number|null} exp(epoch seconds) 또는 null
 */
function decodeAccessTokenExp(token) {
    try {
        if (!token || typeof token !== 'string') {
            return null;
        }
        const parts = token.split('.');
        if (parts.length !== 3) {
            return null;
        }
        // base64url → base64 변환 후 디코드
        const payload = parts[1].replace(/-/g, '+').replace(/_/g, '/');
        const decoded = JSON.parse(atob(payload));
        const exp = decoded && decoded.exp;
        return (typeof exp === 'number') ? exp : null;
    } catch (e) {
        return null;
    }
}

/**
 * access_token이 만료 임박(exp - now < 300초)이면 선제 갱신을 트리거한다.
 * exp 부재/파싱 실패 시 아무 동작도 하지 않는다.
 * @returns {Promise<void>}
 */
function ensureFreshAccessTokenIfExpiring() {
    const token = localStorage.getItem('access_token');
    const exp = decodeAccessTokenExp(token);

    if (exp === null) {
        return Promise.resolve();
    }

    const nowSec = Math.floor(Date.now() / 1000);
    const secondsRemaining = exp - nowSec;

    if (secondsRemaining < 300) {
        return getAccessTokenRefreshPromise().catch(function() {
            // 선제 갱신 실패는 무시한다
        });
    }

    return Promise.resolve();
}

/**
 * FormData 파일 업로드 요청을 수행한다.
 * A003(토큰 만료) 수신 시 access token을 1회 갱신 후 재시도한다.
 * @param {string} url
 * @param {string} method
 * @param {boolean} async
 * @param {FormData} data
 * @param {Function} successCallback
 * @param {Function} errorCallback
 * @param {boolean} [hasRetried=false] - 재시도 여부 (무한 루프 방지 가드)
 */
function fileUploadAjax(url, method, async, data, successCallback, errorCallback, hasRetried) {
    const effectiveAsync = async !== undefined ? async : true;

    function sendRequest() {
        $.ajax({
            url: url,
            type: method,
            data: data,
            async: effectiveAsync,
            processData: false,
            contentType: false,
            xhrFields: {
                withCredentials: true
            },
            headers: buildTokenHeaders(),
            success: function (responseData) {
                if (successCallback && typeof successCallback === 'function') {
                    successCallback(responseData);
                }
            },
            error: function (error) {
                // A003(토큰 만료) 시 1회 갱신 후 재시도
                if (error && error.responseJSON && error.responseJSON.code === 'A003' && !hasRetried) {
                    getAccessTokenRefreshPromise()
                        .then(function() {
                            fileUploadAjax(url, method, async, data, successCallback, errorCallback, true);
                        })
                        .catch(function() {
                            // 통화 중이면 페이지 이동 없이 toast만 표시
                            if (isInRoomCallPage()) {
                                showApiErrorToast('세션이 만료되었습니다. 통화 종료 후 다시 로그인해주세요.');
                                return;
                            }
                            redirectToLogin();
                        });
                    return;
                }

                if (errorCallback && typeof errorCallback === 'function') {
                    errorCallback(error);
                } else {
                    handleApiError(error);
                }
            }
        });
    }

    if (hasRetried || !effectiveAsync) {
        sendRequest();
    } else {
        ensureFreshAccessTokenIfExpiring().catch(function() {
            // 선제 갱신 실패는 무시하고 요청을 진행한다
        }).then(sendRequest);
    }
}

/**
 * 파일 다운로드 요청을 수행한다. 성공 콜백은 Blob 객체를 전달한다.
 * A003(토큰 만료) 감지 시 access token을 1회 갱신 후 재시도한다.
 * @param {string} url
 * @param {string} method
 * @param {boolean} async
 * @param {Object} data
 * @param {Function} successCallback - 성공 시 Blob 객체 전달
 * @param {Function} errorCallback - 실패 시 정규화된 error 전달
 * @param {boolean} [hasRetried=false] - 재시도 여부 (무한 루프 방지 가드)
 */
function fileDownloadAjax(url, method, async, data, successCallback, errorCallback, hasRetried) {
    const effectiveAsync = async !== undefined ? async : true;

    function sendRequest() {
        $.ajax({
            url: url,
            type: method,
            data: data,
            async: effectiveAsync,
            headers: buildTokenHeaders(),
            dataType: 'binary', // 파일 다운로드를 위해서는 binary 타입으로 받아야한다.
            xhrFields: {
                withCredentials: true,
                'responseType': 'blob' // 여기도 마찬가지
            },
            success: function (blobData) {
                if (successCallback && typeof successCallback === 'function') {
                    successCallback(blobData);
                }
            },
            error: function (error) {
                // blob 에러는 responseJSON이 없으므로 정규화 후 판정
                normalizeBlobError(error).then(function(errorJson) {
                    // A003(토큰 만료) 시 1회 갱신 후 재시도
                    if (errorJson && errorJson.code === 'A003' && !hasRetried) {
                        getAccessTokenRefreshPromise()
                            .then(function() {
                                fileDownloadAjax(url, method, async, data, successCallback, errorCallback, true);
                            })
                            .catch(function() {
                                // 통화 중이면 페이지 이동 없이 toast만 표시
                                if (isInRoomCallPage()) {
                                    showApiErrorToast('세션이 만료되었습니다. 통화 종료 후 다시 로그인해주세요.');
                                    return;
                                }
                                redirectToLogin();
                            });
                        return;
                    }

                    // 그 외 에러는 정규화된 error를 콜백에 전달
                    const normalizedError = errorJson
                        ? { responseJSON: errorJson, status: error.status }
                        : error;

                    if (errorCallback && typeof errorCallback === 'function') {
                        errorCallback(normalizedError);
                    } else {
                        handleApiError({ responseJSON: errorJson });
                    }
                });
            }
        });
    }

    if (hasRetried || !effectiveAsync) {
        sendRequest();
    } else {
        ensureFreshAccessTokenIfExpiring().catch(function() {
            // 선제 갱신 실패는 무시하고 요청을 진행한다
        }).then(sendRequest);
    }
}

// ─── 토큰 자동 갱신 타이머 ───────────────────────────────────────────────────

let _autoRefreshStarted = false;

/**
 * 방 페이지에서만 60초 간격으로 토큰 만료 임박 여부를 체크하고 선제 갱신한다.
 * 탭/슬립 복귀(visibilitychange) 시에도 즉시 1회 체크한다.
 */
function startAccessTokenAutoRefresh() {
    if (!getCurrentRoomId()) {
        return;
    }

    if (_autoRefreshStarted) {
        return;
    }
    _autoRefreshStarted = true;

    setInterval(function() {
        ensureFreshAccessTokenIfExpiring();
    }, 60000);

    document.addEventListener('visibilitychange', function() {
        if (document.visibilityState === 'visible') {
            ensureFreshAccessTokenIfExpiring();
        }
    });
}

// 로드 시 자동 시작
(function() {
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function() {
            startAccessTokenAutoRefresh();
        });
    } else {
        startAccessTokenAutoRefresh();
    }
}());
