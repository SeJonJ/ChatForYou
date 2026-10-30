import { describe, test, afterEach } from 'vitest';
import assert from 'node:assert/strict';
import { getApiBaseUrl, getBaseUrl, getGoogleOAuth } from '../static/js/common/config.js';

/**
 * 설정 접근 모듈의 경계값 동작.
 *
 * 계약 테스트(config.test.js)가 정상 주입과 전역 부재를 다룬다면, 여기서는 그 사이에
 * 있는 값들을 고정한다. 설정 주입은 배포 시점에 파일을 갈아끼우는 방식이라 전역이
 * 아예 없는 경우보다 일부만 채워진 경우가 실제로 더 흔하고, 그때 접근자가 예외를
 * 던지는지 undefined 를 흘리는지가 호출부의 실패 모양을 결정한다.
 */
describe('common/config 경계값', () => {
    afterEach(() => {
        delete window.__CONFIG__;
    });

    test('falsy 전역은 주입되지 않은 것과 같게 예외로 드러난다', () => {
        for (const injected of [null, undefined, 0, '', false, Number.NaN]) {
            window.__CONFIG__ = injected;
            assert.throws(() => getApiBaseUrl(), /__CONFIG__/);
            assert.throws(() => getBaseUrl(), /__CONFIG__/);
            assert.throws(() => getGoogleOAuth(), /__CONFIG__/);
        }
    });

    test('전역은 있는데 키가 비면 예외가 아니라 undefined 가 흘러나간다', () => {
        // 접근자는 얇은 경계라 키 단위 검증을 하지 않는다. 호출부는 URL 조립에 이 값을
        // 바로 쓰므로 부분 주입은 'undefined/chat/room/list' 같은 요청으로 이어진다.
        window.__CONFIG__ = {};

        assert.equal(getApiBaseUrl(), undefined);
        assert.equal(getBaseUrl(), undefined);
        assert.equal(getGoogleOAuth(), undefined);
        assert.equal(`${getApiBaseUrl()}/chat/room/list`, 'undefined/chat/room/list');
    });

    test('빈 문자열 URL 은 값이 있는 것으로 취급되어 그대로 전달된다', () => {
        window.__CONFIG__ = { API_BASE_URL: '', BASE_URL: '' };

        assert.equal(getApiBaseUrl(), '');
        assert.equal(getBaseUrl(), '');
    });

    test('전역이 지워지면 다음 호출부터 다시 예외가 된다', () => {
        window.__CONFIG__ = { BASE_URL: 'https://example.test' };
        assert.equal(getBaseUrl(), 'https://example.test');

        delete window.__CONFIG__;
        assert.throws(() => getBaseUrl(), /__CONFIG__/);
    });

    test('접근자는 호출 때마다 전역을 다시 읽어 참조를 붙들지 않는다', () => {
        const first = { GOOGLE_OAUTH: { API_KEY: 'first' } };
        window.__CONFIG__ = first;
        assert.equal(getGoogleOAuth().API_KEY, 'first');

        // 같은 객체를 제자리에서 수정한 경우에도 최신 값이 보여야 한다.
        first.GOOGLE_OAUTH.API_KEY = 'rotated';
        assert.equal(getGoogleOAuth().API_KEY, 'rotated');

        window.__CONFIG__ = { GOOGLE_OAUTH: { API_KEY: 'replaced' } };
        assert.equal(getGoogleOAuth().API_KEY, 'replaced');
    });
});
