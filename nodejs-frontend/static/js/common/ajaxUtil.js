/**
 * ajax 사용 시 공통화를 위한 js
 */

const AUTH_REQUIRED_ERROR_CODES = ['A002', 'A003', 'A004', 'U001'];
const INVALID_ROOM_ACCESS_ERROR_CODES = ['R004'];
const FILE_EXTENSION_ERROR_CODES = ['F001'];

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

function tokenAjax(url, method, async, data, successCallback, errorCallback, completeCallback) {
    const headers = {
        'Authorization': localStorage.getItem('access_token') || ''
    };

    const roomToken = sessionStorage.getItem('roomAccessToken');
    if (roomToken) {
        headers['X-Room-Token'] = roomToken;
    }
    $.ajax({
        url: url,
        type: method,
        data: data,
        async: async !== undefined ? async : true,
        xhrFields: {
            withCredentials: true
        },
        headers: headers,
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

function tokenAjaxToJson(url, method, async, data, successCallback, errorCallback, completeCallback) {
    $.ajax({
        url: url,
        type: method,
        data: JSON.stringify(data),
        async: async !== undefined ? async : true,
        contentType : 'application/json; charset=UTF-8',
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
        },
        complete : function(result){
            if (completeCallback && typeof completeCallback === "function") {
                completeCallback(result);
            }
        }
    })
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
