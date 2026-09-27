/**
 * 런타임 설정 접근 모듈.
 *
 * `config/config.js` 가 비모듈 스크립트로 주입하는 `window.__CONFIG__` 를 읽는 유일한 통로다.
 * 모듈 코드는 전역을 직접 참조하지 않고 이 파일의 접근자만 사용한다.
 *
 * 이 모듈은 얇은 경계이며 기본값 주입·값 가공·캐싱·환경 분기를 하지 않는다.
 * 값을 저장하지 않고 호출 시점마다 전역을 다시 읽으므로, 주입이 늦어지거나 교체되어도
 * 모듈이 오래된 스냅샷을 들고 있지 않는다.
 */

/**
 * 주입된 설정 전역을 반환한다. 주입 전이면 예외를 던진다.
 * 설정 부재를 호출 지점에서 즉시 드러내기 위한 것으로, `undefined` 를 흘려보내
 * 화면 깊은 곳에서 터지게 두지 않는다.
 * @returns {Object} window.__CONFIG__
 */
function requireConfig() {
    const config = window.__CONFIG__;
    if (!config) {
        throw new Error('window.__CONFIG__ is not injected. config/config.js must load before module scripts.');
    }
    return config;
}

/**
 * 백엔드 API 베이스 URL 을 반환한다.
 * 반환값은 `/chatforyou/api` 까지 포함하므로 호출부는 리소스 경로만 이어 붙인다.
 * @returns {string}
 */
export function getApiBaseUrl() {
    return requireConfig().API_BASE_URL;
}

/**
 * 프론트엔드 베이스 URL 을 반환한다.
 * @returns {string}
 */
export function getBaseUrl() {
    return requireConfig().BASE_URL;
}

/**
 * Firebase / Google OAuth 설정 객체를 반환한다.
 * 설정 파일에 없을 수 있는 선택 항목이라 부재 시 `undefined` 를 그대로 전달한다.
 * @returns {{API_KEY?: string, AUTH_DOMAIN?: string, PROJECT_ID?: string}|undefined}
 */
export function getGoogleOAuth() {
    return requireConfig().GOOGLE_OAUTH;
}
