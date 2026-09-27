/**
 * 동기화된 화면을 실제 Electron 에서 열어 ES 모듈이 로드·실행되는지 확인한다.
 *
 * 실행: npm run smoke:modules  (npm run sync 로 src/templates 를 채운 뒤)
 *
 * 앱과 같은 webPreferences 로 file:// 를 연다. 일반 Chrome 은 file:// 모듈을 CORS 로 막지만
 * Electron 은 GrantFileProtocolExtraPrivileges 퓨즈로 허용하므로, 이 검사는 반드시 Electron 으로 돌려야 한다.
 * 백엔드 연결 실패는 판정에서 제외한다. 보는 것은 모듈·팝업 로드 실패와 스크립트 예외뿐이다.
 */
const { app, BrowserWindow } = require('electron');
const fs = require('fs');
const path = require('path');
const { pathToFileURL } = require('url');
const { createMainWebPreferences } = require('../src/main/web-preferences');

const TEMPLATES_DIR = path.join(__dirname, '../src/templates');
const SETTLE_MS = 2000;
const BLOCKED_PATTERNS = [
    /blocked by CORS policy/i,
    /Failed to load module script/i,
    /Cross origin requests are only supported/i,
    /Popup 로드 실패/,
];

/** 모듈 스크립트를 가진 템플릿을 찾는다. */
function findModulePages(dir) {
    const pages = [];
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const fullPath = path.join(dir, entry.name);
        if (entry.isDirectory()) {
            pages.push(...findModulePages(fullPath));
        } else if (entry.name.endsWith('.html') && /<script\b[^>]*type=["']module["']/.test(fs.readFileSync(fullPath, 'utf8'))) {
            pages.push(fullPath);
        }
    }
    return pages;
}

/**
 * 앱과 같은 설정의 창 하나를 만들고 DevTools 프로토콜을 붙인다.
 * 화면마다 같은 창에서 이동하며, 수집 상태는 checkPage 가 화면 단위로 비운다.
 * @returns {Promise<{ win: BrowserWindow, state: { failures: string[], parsedScripts: Set<string> } }>}
 */
async function createProbeWindow() {
    const win = new BrowserWindow({ show: false, webPreferences: createMainWebPreferences() });
    const state = { failures: [], parsedScripts: new Set() };

    win.webContents.on('console-message', (event) => {
        const message = String(event.message ?? '');
        if (BLOCKED_PATTERNS.some((pattern) => pattern.test(message))) {
            state.failures.push(`콘솔: ${message.slice(0, 200)}`);
        }
    });

    // 모듈이 실제로 평가됐는지는 DevTools 프로토콜의 스크립트 파싱·예외 이벤트로 판정한다.
    // 렌더러가 생기기 전에는 명령이 거부되므로 빈 페이지를 먼저 띄운 뒤 붙는다.
    await win.loadURL('about:blank');
    const cdp = win.webContents.debugger;
    cdp.attach('1.3');
    cdp.on('message', (event, method, params) => {
        if (method === 'Debugger.scriptParsed' && params.url) {
            state.parsedScripts.add(params.url);
        }
        if (method === 'Runtime.exceptionThrown') {
            // jQuery 3 는 ready 콜백 안의 예외를 잡았다가 자기 setTimeout 에서 다시 던진다.
            // 그러면 던진 위치는 jQuery 가 되므로, 원래 예외의 스택에 build/ 청크가 있는지도 본다.
            const details = params.exceptionDetails;
            const where = details.url || details.stackTrace?.callFrames?.[0]?.url || '';
            const stack = details.exception?.description || '';
            const origin = where.includes('/build/') ? where : (stack.match(/\S*\/build\/[^\s):]+/) || [])[0];
            if (origin) {
                state.failures.push(`예외: ${stack.split('\n')[0] || details.text} (${path.basename(origin)})`);
            }
        }
    });
    await cdp.sendCommand('Runtime.enable');
    await cdp.sendCommand('Debugger.enable');

    return { win, state };
}

/**
 * 한 화면을 열고 실패 사유 목록을 돌려준다. 비어 있으면 통과다.
 * @param {{ win: BrowserWindow, state: { failures: string[], parsedScripts: Set<string> } }} probe
 * @param {string} pagePath
 * @returns {Promise<string[]>}
 */
async function checkPage({ win, state }, pagePath) {
    state.failures = [];
    state.parsedScripts = new Set();

    await win.loadURL(pathToFileURL(pagePath).toString());
    await new Promise((resolve) => setTimeout(resolve, SETTLE_MS));

    const expected = await win.webContents.executeJavaScript(
        `[...document.querySelectorAll('script[type=module][src], link[rel=modulepreload][href]')].map((el) => el.src || el.href)`
    );
    if (expected.length === 0) {
        state.failures.push('모듈 스크립트를 찾지 못함');
    }
    for (const url of expected) {
        if (!state.parsedScripts.has(url)) {
            state.failures.push(`모듈 미실행: ${path.basename(new URL(url).pathname)}`);
        }
    }

    return state.failures;
}

// 검사 도중의 예외가 창을 붙든 채 멈추지 않도록 실패로 끝낸다.
process.on('unhandledRejection', (reason) => {
    console.error(`스모크 검사 오류: ${reason?.message || reason}`);
    app.exit(1);
});

app.whenReady().then(async () => {
    if (!fs.existsSync(TEMPLATES_DIR)) {
        console.error(`템플릿이 없습니다: ${TEMPLATES_DIR} (npm run sync 를 먼저 실행하세요)`);
        app.exit(1);
        return;
    }

    const pages = findModulePages(TEMPLATES_DIR);
    if (pages.length === 0) {
        console.error('모듈 스크립트를 가진 템플릿이 없습니다. 동기화 결과를 확인하세요.');
        app.exit(1);
        return;
    }

    const probe = await createProbeWindow();
    let failed = 0;
    for (const pagePath of pages) {
        const failures = await checkPage(probe, pagePath);
        const label = path.relative(TEMPLATES_DIR, pagePath);
        if (failures.length === 0) {
            console.log(`PASS ${label}`);
        } else {
            failed++;
            console.log(`FAIL ${label}`);
            for (const failure of failures) {
                console.log(`     ${failure}`);
            }
        }
    }

    console.log(`\n모듈 로드 스모크: ${pages.length - failed}/${pages.length} 통과 (Electron ${process.versions.electron})`);
    app.exit(failed === 0 ? 0 : 1);
});
