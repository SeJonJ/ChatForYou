// ESLint flat config — ChatForYou 프론트엔드 lint 기준 (CFY-FB-03)
//
// 규칙 베이스: eslint:recommended (스타일이 아닌 명백한 버그/오류 위주 — no-undef,
// no-unused-vars, no-unreachable, no-dupe-keys 등). 기존 대규모 legacy 위반은
// eslint-suppressions.json 으로 동결하고, 신규 코드에만 실질 적용한다(단계적 도입).
//
// 대상 코드 성격:
//   - static/js/** : <script> 태그로 로드되는 브라우저 스크립트. 파일 간 전역 공유.
//   - server.js, config/** : Node.js 정적 서버·설정 코드.

const js = require('@eslint/js');
const globals = require('globals');

module.exports = [
  // 검사 제외: 의존성·컴파일 산출물·미니파이 자산·서드파티 라이브러리
  // (서드파티는 자체 코딩 스타일·인라인 disable 을 가져 우리 규칙 적용 대상이 아님)
  {
    ignores: [
      'node_modules/**',
      'static/css/**',
      'static/scss/**',
      'static/vendor/**',
      '**/*.min.js',
      'static/js/chatroom/jquery-3.6.1.js',
      'static/js/rtc/kurento-utils.js',
    ],
  },

  js.configs.recommended,

  // 브라우저 스크립트 (RTCPeerConnection 등 브라우저 전역 + jQuery `$` 포함)
  {
    files: ['static/js/**/*.js'],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'script',
      globals: {
        ...globals.browser,
        ...globals.jquery,
        // reconnectPolicy.js 가 <script> 로 먼저 로드되어 window 에 노출하는 전역
        ReconnectPolicy: 'readonly',
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

  // Node 단위 테스트 (node:test 러너)
  {
    files: ['test/**/*.js'],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'commonjs',
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
