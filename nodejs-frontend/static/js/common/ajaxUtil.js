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
 * R005 복구에 사용할 roomId를 요청 단위 override 또는 room page URL에서 가져온다.
 * @param {{roomId?: string}} requestOptions
 * @returns {string|null}
 */
function resolveRoomTokenRecoveryRoomId(requestOptions) {
    return requestOptions.roomId || getCurrentRoomId();
}

/**
 * 만료된 room token을 백엔드 refresh API를 통해 갱신한다.
 * X-Room-Token에 만료 토큰을 그대로 전송한다 — validateRefreshable이 허용한다.
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
 * 실제 복구 범위는 room page URL 또는 request-level roomId override로 제한한다.
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
 * @param {{url: string, method: string, data?: *, async?: boolean, contentType?: string, roomId?: string, successCallback?: Function, errorCallback?: Function, completeCallback?: Function, hasRetriedAccessToken?: boolean, skipAuthRetry?: boolean}} requestOptions
 * @returns {void}
 */
function executeTokenAjax(requestOptions) {
    $.ajax({
        url: requestOptions.url,
        type: requestOptions.method,
        data: requestOptions.data,
        async: requestOptions.async !== undefined ? requestOptions.async : true,
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

function fileUploadAjax(url, method, async, data, successCallback, errorCallback) {
    $.ajax({
        url: url,
        type: method,
        data: data,
        async: async !== undefined ? async : true,
        processData: false,
        contentType: false,
        xhrFields: {
            withCredentials: true
        },
        headers: {
            'Authorization': localStorage.getItem('access_token') || ''
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
        }
    })
}

function fileDownloadAjax(url, method, async, data, successCallback, errorCallback){
    $.ajax({
        url: url,
        type: method,
        data: data,
        async: async !== undefined ? async : true,
        headers: {
            'Authorization': localStorage.getItem('access_token') || ''
        },
        dataType: 'binary', // 파일 다운로드를 위해서는 binary 타입으로 받아야한다.
        xhrFields: {
            withCredentials: true,
            'responseType': 'blob' // 여기도 마찬가지
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
        }
    });
}
