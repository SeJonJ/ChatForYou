/**
 * ajax 사용 시 공통화를 위한 js
 */

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
    $.ajax({
        url: url,
        type: method,
        data: data,
        async: async !== undefined ? async : true,
        xhrFields: {
            withCredentials: true
        },
        headers: {
            'Authorization': localStorage.getItem('access_token')
        },
        success: function (data) {
            if (successCallback && typeof successCallback === 'function') {
                successCallback(data);
            }
        },
        error: function (error) {
            if (errorCallback && typeof errorCallback === 'function') {
                errorCallback(error);
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
            'Authorization': localStorage.getItem('access_token')
        },
        success: function (data) {
            if (successCallback && typeof successCallback === 'function') {
                successCallback(data);
            }
        },
        error: function (error) {
            if (errorCallback && typeof errorCallback === 'function') {
                errorCallback(error);
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
        success: function (data) {
            if (successCallback && typeof successCallback === 'function') {
                successCallback(data);
            }
        },
        error: function (error) {
            if (errorCallback && typeof errorCallback === 'function') {
                errorCallback(error);
            }
        }
    })
}

function fileDownloadAjax(url, method, async, data, successCallback, errorCallback){
    $.ajax({
        url: url,
        type: method,
        data: data,
        async: async !== '' ? async : true,
        xhrFields: {
            withCredentials: true
        },
        dataType: 'binary', // 파일 다운로드를 위해서는 binary 타입으로 받아야한다.
        xhrFields: {
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
            }
        }
    })
}