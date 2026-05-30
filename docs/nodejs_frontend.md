## 개발 시 유의점 : frontend

### 역할 정의
- Claude는 직접 개발하지 않고 **개발 가이드 작성**이 기본 역할
- 유저가 명시적으로 "개발해줘"라고 요청할 때만 코드를 작성
- 가이드 요청 시 순서:
  1. 관련 코드 전체 분석
  2. 필요한 정보 유저에게 문의
  3. `nodejs-frontend/plan_docs/[기능명]_plan.md` 작성 (코드 레벨 상세 설계 — 자유 양식. 권장 섹션: Goal, Code-level Design, File-by-file Plan, Unit Test Plan, Electron Sync Notes)
  4. "개발을 시작할까요?" 확인 요청

### 루트 plan_docs 와의 관계
- 루트 `plan_docs/00–06/` 은 기능 단위 PDCA 표준 산출물 (cross-component contract)
- `nodejs-frontend/plan_docs/` 는 프론트엔드 컴포넌트 내부의 코드 레벨 상세 설계
- 동일 기능은 stem 이름 일치 권장 (예: `plan_docs/02-design/foo.md` ↔ `nodejs-frontend/plan_docs/foo_plan.md`)
- 책임 분배·작성 순서: `docs/agent/pdca-templates.md` 의 **Component-level Plan Docs** 참조

---

## 기술 스택 배경 (AI 코드 생성 시 필수 인지)

### jQuery를 유지하는 이유
이 프로젝트는 Next.js/React 전환이 불가하다. 이유:

1. **Kurento 의존성**: `kurento-utils.js`가 브라우저 전역 스크립트 방식으로만 동작
2. **WebSocket 신호 처리**: `wsMessageHandlers` 맵 기반 구조가 jQuery DOM 이벤트와 강하게 결합
3. **개발자 친숙도**: jQuery + 순수 JS 기반 유지가 현재 팀에 최적

> **AI 코드 생성 금지 항목**: React 컴포넌트, Vue SFC, TypeScript, axios, import/export (브라우저 모듈)

### Electron 고려 사항 (MANDATORY)
`chatforyou-desktop`은 `nodejs-frontend`를 sync해서 사용한다. 모든 코드는 Electron 환경을 반드시 고려해야 한다.

- **환경 감지**: `isElectron()` 함수 사용 (`common.js` 전역 선언)
  ```javascript
  if (isElectron()) {
      // Electron 전용 처리
  }
  ```
- **API URL**: `window.__CONFIG__.API_BASE_URL` 사용 — 하드코딩 금지 (Electron config 자동 변환됨)
- **isMobile()**: Electron에서 항상 `false` 반환 — 모바일 분기 추가 불필요
- **파일 경로**: `window.location.href` 방식 그대로 사용 (Electron이 처리)
- **Node.js API**: `window.require`, `window.process`, `window.__dirname` — 브라우저 코드에서 직접 사용 금지 (Electron 전용이므로 isElectron() 분기 안에서만)
- **SCSS/CSS 수정 시**: Electron sync 후 반드시 `npm run scss:build` 실행 필요
- **프론트엔드 코드 수정 완료 후 (MANDATORY)**: `chatforyou-desktop/` 에서 `npm run sync` 를 실행하여 Electron 환경에서 빌드 에러가 없는지 확인해야 한다. 에러 없음이 확인될 때까지 작업 완료로 간주하지 않는다.

---

## 코드 컨벤션

### 1. 변수 / 함수 네이밍

| 범위 | 케이스 | 예시 |
|---|---|---|
| 변수 (전역 상태) | `camelCase` + `let` | `let roomId = null`, `let participants = {}` |
| 변수 (로컬 / 상수) | `camelCase` + `const` | `const $tbody = $('#roomTableBody')` |
| 함수명 | `camelCase` 동사 시작 | `connectWebSocket()`, `loadRoomList()` |
| 상수 객체 (설정 맵) | `UPPER_SNAKE_CASE` | `RECORDING_UI_STATES`, `TOAST_THEMES` |
| Boolean 플래그 | `is` / `has` 접두사 | `isGameStart`, `hasRecordedOnce` |
| jQuery 캐싱 변수 | `$` 접두사 | `const $btn = $('#submitBtn')` |
| CSS 클래스명 | `kebab-case` | `.room-user-count`, `.floating-chat` |

> `var` 사용 금지 — 레거시 코드에 존재하더라도 신규 코드는 `const` / `let` 만 사용

---

### 2. 파일 / 모듈 구조

```
static/js/
├── common/          # ajaxUtil.js, common.js, fileUtil.js (공통 유틸)
├── login/           # 로그인 관련
├── popup/           # 팝업 모듈
├── roomlist/        # 방 목록
└── rtc/             # WebRTC 핵심 (kurento-service.js, dataChannel.js 등)
```

**Temporary artifacts cleanup (MANDATORY)**

프론트엔드 임시 테스트 산출물, 스크린샷, 로컬 재현용 HTML/JS, 실험 로그는 프로젝트 루트의 `.test-temp/`에 둔다.
`nodejs-frontend/` 내부에 임시 산출물을 누적하지 말고, 작업 완료 전 정리하거나 남겨야 하는 이유를 최종 보고에 명시한다.

**모듈 패턴 — 객체 리터럴 방식 (MANDATORY)**

`$(document).ready()` 사용을 최소화한다. 이벤트 바인딩은 반드시 모듈의 `init()` 안에서 처리한다.

```javascript
const myModule = {
    $submitBtn: $('#submitBtn'),

    init: function() {
        const self = this;
        // 이벤트 바인딩은 init() 안에서 1회만
        self.$submitBtn.on('click', function() {
            self.handleSubmit();
        });
        // 동적 요소는 document 위임
        $(document).on('click', '#dynamicBtn', function() {
            self.handleDynamic();
        });
    },

    handleSubmit: function() { ... },
    handleDynamic: function() { ... }
};

// HTML 마지막 <script> 또는 페이지 전용 JS 파일 최하단에서 1회 호출
// $(document).ready() 대신 DOMContentLoaded 시점에 init() 직접 호출
$(function() {
    myModule.init();
});

// 전역 노출이 필요한 경우
window.myModule = myModule;
```

> `$(document).ready()` / `$(function() {...})` 는 **모듈 `init()` 호출 용도로만** 사용한다. 이벤트 바인딩 코드를 직접 넣지 않는다.

> **ES6 class 문법**: `SpeechRecognitionManager`처럼 독립적인 상태 관리가 필요한 경우에만 사용 — 사용 전 유저 확인 필수

---

### 3. jQuery 사용 규칙

**선택자 기준 (STRICT)**
- **기본**: HTML `id` 기반 (`$('#elementId')`)
- **동적 요소**: `$(document).on('click', '#id', ...)` 위임 바인딩
- **class 선택자**: 동일 기능 그룹에만 허용 (`$('.messages')`)
- **선택자 캐싱**: 반복 사용하는 요소는 `const $el = $('#el')` 로 캐싱

```javascript
// Good - ID 기반 + 캐싱
const $tbody = $('#roomTableBody');
$tbody.empty();
$tbody.append(html);

// Bad - 반복 선택 (성능 낭비)
$('#roomTableBody').empty();
$('#roomTableBody').append(html);
```

**이벤트 바인딩 규칙**

| 방식 | 사용 시점 |
|---|---|
| `init()` 안에서 `$('#id').on(...)` | **기본** — 정적 요소, init() 1회 호출로 보장 |
| `init()` 안에서 `$(document).on('#id', ...)` | 동적으로 생성되는 요소 (템플릿으로 추가되는 버튼 등) |
| `$('#id').off('event').on('event', fn)` | init()이 여러 번 호출될 수 있는 경우에만 |
| `.click()`, `.change()` 단축 메서드 | 신규 코드 사용 금지 |

> **`.off().on()` 사용 기준**: `init()`이 단 1회만 호출됨이 보장되면 `.on()` 으로 충분.
> 팝업처럼 열릴 때마다 `init()`이 재호출될 수 있는 경우에만 `.off().on()` 사용.

```javascript
// Good - init() 안에서 정적 요소 바인딩
init: function() {
    const self = this;
    $('#logoutBtn').on('click', function() { self.logout(); });
    $('#createRoomForm').on('submit', function(e) {
        e.preventDefault();
        self.handleCreateRoom();
    });
},

// Good - 동적 요소는 document 위임
init: function() {
    const self = this;
    $(document).on('click', '#showPasswordModal', function(e) {
        e.preventDefault();
        self.roomId = $(this).data('room-id');
    });
},

// Bad - $(document).ready() 안에 바인딩 직접 작성
$(document).ready(function() {
    $('#logoutBtn').on('click', function() { ... }); // init()으로 이동해야 함
});
```

**DOM 조작**

```javascript
// HTML 생성: 템플릿 리터럴 사용
const html = `
    <tr data-room-id="${room.roomId}">
        <td>${room.roomName}</td>
        <td><span class="badge bg-primary">${room.userCount}/${room.maxUserCnt}</span></td>
    </tr>
`;
$('#roomTableBody').prepend(html);

// 문자열 배열 join: 가독성보다 템플릿 리터럴 선호
// Bad
$container.append(['<li class="self">', message, '</li>'].join(''));
// Good
$container.append(`<li class="self">${message}</li>`);
```

---

### 4. AJAX 사용 규칙

**`ajaxUtil.js` 래퍼 함수를 반드시 사용한다 — 직접 `$.ajax()` 호출 금지**

| 함수 | 용도 |
|---|---|
| `ajax(url, method, async, data, successCb, errorCb, completeCb)` | 일반 폼 데이터 전송 |
| `ajaxToJson(...)` | JSON body 전송 |
| `tokenAjaxToJson(...)` | JWT 토큰 포함 JSON 전송 |
| `ajaxToJsonPromise(url, method, data)` | Promise 반환 필요 시 |
| `fileUploadAjax(...)` | FormData 파일 업로드 |

```javascript
// Good - 래퍼 함수 사용
ajaxToJson(
    window.__CONFIG__.API_BASE_URL + '/chat/room/create',
    'POST',
    true,
    requestData,
    function(response) {           // 성공 콜백
        const { result, data } = response || {};
        if (result === 'SUCCESS') {
            window.location.href = `${window.__CONFIG__.BASE_URL}/chat?roomId=${data.roomId}`;
        }
    },
    function(error) {              // 에러 콜백
        console.error('방 생성 실패:', error);
    }
);

// Bad - 직접 $.ajax 호출
$.ajax({ url: '...', type: 'POST', ... });
```

**fetch 사용**: WebRTC 설정 조회처럼 jQuery 의존이 불필요한 경우에만 허용

```javascript
// fetch 허용 케이스 (jQuery 바인딩 없는 초기화 코드)
fetch(window.__CONFIG__.API_BASE_URL + '/admin/turnconfig', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' }
})
    .then(response => response.json())
    .then(response => {
        const { data } = response || {};
        turnUrl = data.url;
    })
    .catch(error => console.error('TURN 서버 설정 실패:', error));
```

---

### 5. ES6 사용 규칙

**사전 승인 없이 사용 가능한 문법**

| 문법 | 예시 |
|---|---|
| `const` / `let` | `const self = this` |
| 템플릿 리터럴 | `` `roomId=${roomId}` `` |
| 화살표 함수 | `(msg) => handler(msg)` |
| 구조 분해 | `const { roomId, userCount } = JSON.parse(data)` |
| 옵셔널 체이닝 | `recording?.handleAutoStop?.(msg)` |
| `Map` / `Set` | `new Map()`, `new Set()` |
| `Promise` | `.then().catch()` |
| Spread | `...createHandlers([...])` |

**유저 확인 후 사용해야 하는 문법**

| 문법 | 이유 |
|---|---|
| `async` / `await` | 기존 코드가 콜백/Promise 체인 기반 — 혼용 시 스타일 불일치 |
| `class` 문법 | 객체 리터럴 모듈 패턴이 기본 — 신규 독립 모듈에만 허용 |
| `import` / `export` | 브라우저 전역 스크립트 방식 유지 (모듈 번들러 없음) |

---

### 6. this 바인딩 규칙

객체 리터럴 모듈 패턴에서 `this`는 **호출 방법**에 따라 달라진다.
콜백으로 전달되는 함수는 `this`가 바뀌므로 아래 중 하나로 고정해야 한다.

```javascript
// 방법 1 - const self = this (기존 코드 주 사용 방식)
init: function() {
    const self = this;
    $('#btn').on('click', function() {
        self.handleClick(); // self는 항상 모듈 객체
    });
},

// 방법 2 - 화살표 함수 (신규 코드 권장)
init: function() {
    $('#btn').on('click', () => {
        this.handleClick(); // 화살표 함수는 this를 상위 스코프에서 캡처
    });
},

// 방법 3 - .bind(this) (WebRTC 콜백처럼 함수 참조를 직접 전달해야 할 때)
init: function() {
    // rtcPeer가 이 함수를 직접 호출하면 this가 rtcPeer가 되어버림
    // bind로 this를 모듈 객체로 고정
    this.handleOpen = this.handleOpen.bind(this);
    rtcPeer.ondatachannel = this.handleOpen; // 이제 this.user 접근 가능
},
```

> **기존 코드와의 혼용**: 같은 파일 내에서는 한 가지 방식으로 통일. `self` 패턴이 이미 있으면 `self` 유지.

---

### 7. 주석 규칙

- **인라인**: WHY가 담긴 한 줄 한글 주석 (WHAT 설명 금지)
- **JSDoc**: 공통 유틸 함수와 모듈의 공개 진입 함수/상태 전환 함수에 작성, `@param` / `@returns` 포함
- **TODO**: 리팩토링 예정 코드에만, 이유 명시

```javascript
// Good - WHY 주석
this.handleOpen = this.handleOpen.bind(this);
// rtcPeer 콜백으로 전달 시 this가 peer로 변경되는 문제 방지

// Bad - WHAT 주석
// 이벤트 바인딩 함수
bindEvents: function() { ... }

// JSDoc 예시 (공통 유틸 / 공개 함수)
/**
 * Promise를 반환하는 AJAX
 * @param {string} url
 * @param {string} method
 * @param {Object} data
 * @returns {Promise}
 */
function ajaxToJsonPromise(url, method, data) { ... }
```

공개 함수라도 내부 로컬 헬퍼, 짧은 이벤트 핸들러는 JSDoc 대신 WHY 주석만 유지한다.

### 7-1. 성공 응답 처리 규칙

- 공통 AJAX 유틸과 `fetchJson`은 **표준 wrapper 전체**를 그대로 넘긴다
- 성공 콜백 파라미터 이름은 `response`로 통일한다
- 콜백 첫 줄에서 `const { result, data, code, message, detail } = response || {};` 형태로 구조 분해한다
- 이후 로직은 `result`, `data`만 사용하고 `resp.data`, `result.result`, `data.data` 같은 혼합 표현은 피한다
- `REDIRECT_ROOM`, `REDIRECT_DASHBOARD`처럼 결과 타입 자체가 분기 키인 경우도 `result` 변수로만 비교한다
- 레거시 엔드포인트가 아직 소문자 `success`를 반환하면, 해당 이유를 한 줄 주석으로 남기고 후속 정리 대상으로 기록한다

---

### 7. 에러 처리 규칙

**우선순위:**

| 순위 | 방식 | 사용 시점 |
|---|---|---|
| 1 | AJAX `errorCallback` | ajaxUtil 래퍼 함수 사용 시 |
| 2 | `.catch(error => ...)` | Promise / fetch 사용 시 |
| 3 | `try-catch` | Web API (AudioContext, SpeechRecognition 등) 초기화 |

**에러 표시 방식:**

| 방식 | 사용 시점 |
|---|---|
| `Toastify` (showWarningToast) | 사용자에게 알려야 하는 비즈니스 에러 |
| `console.error()` | 개발 디버깅용 기술 에러 |
| `alert()` | 파일 업로드 등 즉각 확인이 필요한 단순 경고 (최소화) |
| Bootstrap Modal | 재연결 / 연결 실패처럼 사용자 액션이 필요한 에러 |

```javascript
// 에러 콜백 패턴
function(error) {
    const errorJson = error?.responseJSON;
    if (!errorJson) {
        alert('서버와의 연결 문제로 실패했습니다');
        return;
    }
    if (errorJson?.code === '40022') {
        showWarningToast('허용되지 않는 파일 형식입니다');
    }
}
```

---

### 8. WebSocket / DataChannel 패턴

**메시지 핸들러는 `wsMessageHandlers` 맵에 등록한다 (kurento-service.js)**

```javascript
// 새 메시지 타입 추가 시 맵에 추가
const wsMessageHandlers = {
    existingParticipants: (msg) => onExistingParticipants(msg),
    newParticipantArrived: (msg) => onNewParticipant(msg),
    // 신규 핸들러 추가
    myNewEvent: (msg) => handleMyNewEvent(msg),
};
```

**DataChannel 메시지 타입 추가 시 `dataChannel.js`의 분기에 추가**

```javascript
// type 기반 분기 처리
if (recvMessage.type === 'newType') {
    myModule.handleEvent(recvMessage);
}
```

---

### 9. SCSS 규칙 (STRICT)

**SCSS는 꼭 필요한 경우가 아니면 절대 수정하지 않는다.**

| 허용 | 금지 |
|---|---|
| Bootstrap 유틸리티 클래스 HTML에 직접 사용 | 기존 SCSS 파일 수정 |
| 인라인 `style` 속성으로 간단한 동적 스타일 | 기능 변경 없이 스타일만 조정 |
| 신규 화면 추가 시 새 SCSS 파일 생성 | 전역 `_variables.scss` 임의 수정 |

불가피하게 수정 시:
- 색상/크기: `_variables.scss` 변수 사용 (`$color-primary` 등)
- 수정 후 `npm run sass` 실행 필요
- Electron 반영 시 `npm run sync` 추가 실행

---

### 10. PLAN 파일의 코드 컨벤션 검증 항목 (lint 체크리스트)
- 코드 컨벤션 검증 시에는 frontend-convention-checker agents 를 사용해서 확인한다.
```
- [ ] var 미사용 (const / let 만 사용)
- [ ] jQuery 선택자 ID 기반 확인
- [ ] 선택자 캐싱 여부 ($el 패턴)
- [ ] 이벤트 바인딩이 init() 안에 있는지 확인 ($(document).ready() 안에 바인딩 코드 없음)
- [ ] 동적 요소는 document 위임, 정적 요소는 init() 내 .on() 사용
- [ ] .off().on() 은 init() 재호출 가능성 있을 때만 사용
- [ ] ajaxUtil.js 래퍼 함수 사용 (직접 $.ajax 금지)
- [ ] 템플릿 리터럴로 HTML 생성
- [ ] 모듈 패턴 (객체 리터럴 + self 또는 화살표 함수로 this 고정)
- [ ] 에러 처리 존재 (errorCallback / .catch)
- [ ] 주석: WHY 중심 한글 인라인, WHAT 설명 제거
- [ ] SCSS 미수정 또는 불가피한 수정 시 변수 사용 확인
- [ ] 유저 미승인 ES6 문법 미사용 (async/await, class, import)
- [ ] Electron 환경 고려 (isElectron() 분기, window.__CONFIG__ 사용)
- [ ] wsMessageHandlers 맵에 신규 이벤트 등록 여부 (WebSocket 관련 시)
- [ ] alert 대신 showToast 활용
```
