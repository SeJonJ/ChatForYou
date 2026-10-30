// ESLint flat config — ChatForYou 프론트엔드 lint 기준 (CFY-FB-03)
//
// 규칙 베이스: eslint:recommended (스타일이 아닌 명백한 버그/오류 위주 — no-undef,
// no-unused-vars, no-unreachable, no-dupe-keys 등). 기존 대규모 legacy 위반은
// eslint-suppressions.json 으로 동결하고, 신규 코드에만 실질 적용한다(단계적 도입).
//
// 대상 코드 성격:
//   - static/js/{roomlist,login,error}/**, static/js/common/config.js : ES 모듈로 전환된
//     브라우저 코드. import/export 로 의존이 드러난다.
//   - static/js/{rtc,chatroom,common,popup}/** : 여전히 <script> 로 로드되는 전역 코드.
//     전환 코드와 미전환 화상통화 페이지가 함께 쓰므로 이번 범위에서 전역으로 남는다.
//   - server.js, config/** : Node.js 정적 서버·설정 코드.

const js = require('@eslint/js');
const globals = require('globals');

module.exports = [
  // 검사 제외: 의존성·컴파일 산출물·미니파이 자산·서드파티 라이브러리
  // (서드파티는 자체 코딩 스타일·인라인 disable 을 가져 우리 규칙 적용 대상이 아님)
  {
    ignores: [
      'node_modules/**',
      'dist/**',
      'static/css/**',
      'static/scss/**',
      'static/vendor/**',
      '**/*.min.js',
      'static/js/chatroom/jquery-3.6.1.js',
      'static/js/rtc/kurento-utils.js',
    ],
  },

  js.configs.recommended,

  // 브라우저 코드 (RTCPeerConnection 등 브라우저 전역 + jQuery `$` 포함)
  {
    files: ['static/js/**/*.js'],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'module',
      globals: {
        ...globals.browser,
        ...globals.jquery,
        // reconnectPolicy.js 가 <script> 로 먼저 로드되어 window 에 노출하는 전역
        ReconnectPolicy: 'readonly',
      },
    },
  },

  // 미전환 전역 스크립트. 여전히 <script> 로 로드되고 전역으로 서로를 참조한다.
  // common/config.js 만 전환됐으므로 그 파일은 이 규칙에서 뺀다.
  {
    files: [
      'static/js/rtc/**/*.js',
      'static/js/chatroom/**/*.js',
      'static/js/common/**/*.js',
      'static/js/popup/**/*.js',
    ],
    ignores: ['static/js/common/config.js'],
    languageOptions: {
      sourceType: 'script',
    },
  },

  // 전환 코드가 참조하는 공유 전역. ajaxUtil.js 와 common.js 가 <script> 로 먼저
  // 로드되어 window 에 노출하며, 전환 코드는 모듈이라 이를 import 할 수 없다.
  // 이 전역들을 없애는 것은 공유 코드를 모듈로 옮기는 다음 단계의 일이다.
  {
    files: [
      'static/js/roomlist/**/*.js',
      'static/js/login/**/*.js',
      'static/js/error/**/*.js',
    ],
    languageOptions: {
      globals: {
        ajax: 'readonly',
        tokenAjax: 'readonly',
        getApiErrorMessage: 'readonly',
        showApiErrorToast: 'readonly',
        handleApiError: 'readonly',
        isElectron: 'readonly',
        isAuthRequiredErrorCode: 'readonly',
        isInvalidRoomAccessErrorCode: 'readonly',
        redirectToLogin: 'readonly',
        fetchJson: 'readonly',
        // 페이지가 CDN <script> 로 로드하는 라이브러리 전역
        Toastify: 'readonly',
        QRCode: 'readonly',
        firebase: 'readonly',
        firebaseui: 'readonly',
      },
    },
  },

  // ReconnectPolicy 를 선언하는 원본 파일에서는 전역 선언을 끈다(재선언 오류 방지)
  {
    files: ['static/js/rtc/reconnectPolicy.js'],
    languageOptions: {
      globals: {
        ReconnectPolicy: 'off',
      },
    },
  },

  // Vitest 단위 테스트 (jsdom/node 환경에서 ES 모듈로 실행)
  {
    files: ['test/**/*.js'],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'module',
      globals: {
        ...globals.node,
        ...globals.browser,
      },
    },
  },

  // 빌드 설정. Vite 가 번들링해 실행하므로 ES 모듈이고 Node 전역을 쓴다.
  {
    files: ['vite.config.js'],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'module',
      globals: {
        ...globals.node,
      },
    },
  },

  // Node 실행 코드
  {
    files: ['server.js', 'config/**/*.js'],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'commonjs',
      globals: {
        ...globals.node,
      },
    },
  },
];
