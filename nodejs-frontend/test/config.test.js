import { describe, it, expect, afterEach } from 'vitest';
import { getApiBaseUrl, getBaseUrl, getGoogleOAuth } from '../static/js/common/config.js';

/**
 * 설정 접근 모듈의 계약 테스트.
 *
 * 이 모듈은 window.__CONFIG__ 를 읽는 유일한 통로이며, 값을 저장하지 않고 호출마다
 * 다시 읽는다. 값을 잡아두면 그 자체가 캐싱이라 설정 주입이 늦거나 교체될 때
 * 오래된 스냅샷을 들고 있게 된다. 아래 테스트가 그 성질을 고정한다.
 */
describe('common/config', () => {
    afterEach(() => {
        delete window.__CONFIG__;
    });

    it('주입된 설정 값을 그대로 반환한다', () => {
        window.__CONFIG__ = {
            API_BASE_URL: 'https://example.test/chatforyou/api',
            BASE_URL: 'https://example.test/chatforyou',
            GOOGLE_OAUTH: { PROJECT_ID: 'p', API_KEY: 'k', AUTH_DOMAIN: 'd' },
        };

        expect(getApiBaseUrl()).toBe('https://example.test/chatforyou/api');
        expect(getBaseUrl()).toBe('https://example.test/chatforyou');
        expect(getGoogleOAuth()).toEqual({ PROJECT_ID: 'p', API_KEY: 'k', AUTH_DOMAIN: 'd' });
    });

    it('전역이 주입되기 전이면 예외를 던진다', () => {
        expect(() => getApiBaseUrl()).toThrow(/__CONFIG__/);
        expect(() => getBaseUrl()).toThrow(/__CONFIG__/);
        expect(() => getGoogleOAuth()).toThrow(/__CONFIG__/);
    });

    it('전역이 교체되면 다음 호출부터 새 값을 읽는다', () => {
        window.__CONFIG__ = { BASE_URL: 'https://before.test' };
        expect(getBaseUrl()).toBe('https://before.test');

        window.__CONFIG__ = { BASE_URL: 'https://after.test' };
        expect(getBaseUrl()).toBe('https://after.test');
    });

    it('선택 항목인 GOOGLE_OAUTH 부재는 예외가 아니라 undefined 로 전달한다', () => {
        window.__CONFIG__ = { API_BASE_URL: 'https://example.test/api' };
        expect(getGoogleOAuth()).toBeUndefined();
    });
});
