const path = require('path');

/**
 * 메인 창의 webPreferences 를 만든다.
 * 모듈 로드 스모크 검사도 이 값으로 창을 띄워야 실제 앱과 같은 조건이 되므로 한곳에 둔다.
 * @returns {Electron.WebPreferences}
 */
function createMainWebPreferences() {
    return {
        nodeIntegration: false,
        contextIsolation: true,
        enableRemoteModule: false,
        preload: path.join(__dirname, 'preload.js'),
        webSecurity: true,
        allowRunningInsecureContent: false,
        autoplayPolicy: 'no-user-gesture-required'
    };
}

module.exports = { createMainWebPreferences };
