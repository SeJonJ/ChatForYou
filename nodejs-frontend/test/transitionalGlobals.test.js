import { describe, test } from 'vitest';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

/**
 * 엔트리 페이지별 전역 해결 가능성 검증.
 *
 * ES 모듈로 전환된 진입 스크립트는 아직 비모듈 <script> 로 남은 공유 코드의 함수를
 * 전역으로 참조한다. 그 함수를 정의하는 파일이 해당 페이지에서 실제로 로드되지 않으면
 * 빌드도 lint 도 통과하지만 런타임에 ReferenceError 로 터진다.
 * eslint 전역 선언과 eslint-suppressions.json 은 이 상황을 가려버리므로,
 * 여기서는 선언을 신뢰하지 않고 HTML 이 로드하는 파일에서 정의를 직접 찾는다.
 */

const require = createRequire(import.meta.url);
const espree = require('espree');
const globals = require('globals');
const { Linter } = require('eslint');

const frontendRoot = path.resolve(import.meta.dirname, '..');

/**
 * 원격 스크립트가 제공하는 전역. URL 만 보고는 알 수 없어 페이지별로 직접 적는다.
 * 여기 없는 이름이 필요해지면 그 페이지가 라이브러리를 실제로 로드하는지 다시 확인해야 한다.
 */
const CDN_GLOBALS = {
    'https://code.jquery.com/jquery-3.6.1.min.js': ['$', 'jQuery'],
    'https://cdn.jsdelivr.net/npm/bootstrap@5.2.0/dist/js/bootstrap.bundle.min.js': ['bootstrap'],
    'https://cdn.jsdelivr.net/npm/toastify-js': ['Toastify'],
    'https://cdn.jsdelivr.net/npm/qrcodejs@1.0.0/qrcode.min.js': ['QRCode'],
    'https://www.gstatic.com/firebasejs/8.6.5/firebase-app.js': ['firebase'],
    'https://www.gstatic.com/firebasejs/9.22.0/firebase-app-compat.js': ['firebase'],
    'https://www.gstatic.com/firebasejs/ui/6.0.2/firebase-ui-auth.js': ['firebaseui'],
};

/**
 * 정의 파일이 페이지에 로드되지 않아 런타임에 반드시 ReferenceError 가 되는 이름.
 * 전환 이전부터 있던 결함이며 이번 사이클은 동작을 바꾸지 않기 위해 그대로 두었다.
 * 목록이 늘어나면 새로 생긴 결함이므로 이 테스트가 실패한다.
 */
const KNOWN_UNRESOLVED = {
    'templates/roomlist.html': ['showWarningToast'],
};

const ENTRY_PAGES = [
    { html: 'templates/roomlist.html', module: 'static/js/roomlist/roomlist.js' },
    { html: 'templates/login/chatlogin.html', module: 'static/js/login/main.js' },
    { html: 'templates/login/qr/qrlogin.html', module: 'static/js/login/qr/qrlogin.js' },
    { html: 'templates/login/qr/qrscan.html', module: 'static/js/login/qr/qrscan.js' },
];

/** HTML 의 <script src> 를 등장 순서대로 뽑는다. type="module" 은 전환 스크립트라 제외한다. */
function readClassicScriptSources(htmlPath) {
    const html = fs.readFileSync(path.join(frontendRoot, htmlPath), 'utf8');
    const tags = html.match(/<script\b[^>]*\bsrc=[^>]*>/g) || [];
    return tags
        .filter((tag) => !/\btype=["']module["']/.test(tag))
        .map((tag) => (tag.match(/\bsrc=["']([^"']+)["']/) || [])[1])
        .filter(Boolean);
}

/**
 * 브라우저가 <base href="/chatforyou/"> 로 해석하는 URL 을 소스 트리 경로로 되돌린다.
 * server.js 의 가상 매핑(js/ → static/js/ 등)과 같은 규칙이어야 한다.
 */
function resolveLocalScriptPath(src) {
    for (const prefix of ['js/', 'css/', 'images/', 'fonts/', 'vendor/']) {
        if (src.startsWith(prefix)) {
            return `static/${src}`;
        }
    }
    return src;
}

/** 비모듈 스크립트가 전역으로 노출하는 이름을 수집한다. */
function collectScriptGlobals(sourcePath) {
    const absolute = path.join(frontendRoot, sourcePath);
    if (!fs.existsSync(absolute)) {
        return [];
    }

    const code = fs.readFileSync(absolute, 'utf8');
    const names = [];

    let ast;
    try {
        ast = espree.parse(code, { ecmaVersion: 2022, sourceType: 'script' });
    } catch {
        // 미니파이된 서드파티는 파싱 대상이 아니다. 전역은 CDN_GLOBALS 와 같은 방식으로 다룬다.
        return names;
    }

    for (const node of ast.body) {
        if (node.type === 'FunctionDeclaration' || node.type === 'ClassDeclaration') {
            names.push(node.id.name);
        } else if (node.type === 'VariableDeclaration') {
            for (const declaration of node.declarations) {
                if (declaration.id.type === 'Identifier') {
                    names.push(declaration.id.name);
                }
            }
        }
    }

    // window.X = ... 로 붙이는 전역은 선언 위치가 자유로워 본문 전체에서 찾는다.
    for (const match of code.matchAll(/\bwindow\.([A-Za-z_$][\w$]*)\s*=/g)) {
        names.push(match[1]);
    }

    return names;
}

/**
 * 전환 모듈이 import 하지 않고 참조하는 이름을 모은다.
 * 프로젝트 eslint 설정의 전역 선언은 검증 대상 자체라서 쓰지 않고,
 * 브라우저·jQuery 표준 전역만 준 상태로 no-undef 를 돌린다.
 */
function collectFreeGlobals(modulePath) {
    const code = fs.readFileSync(path.join(frontendRoot, modulePath), 'utf8');
    const messages = new Linter().verify(code, {
        languageOptions: {
            ecmaVersion: 2022,
            sourceType: 'module',
            globals: { ...globals.browser, ...globals.jquery },
        },
        rules: { 'no-undef': 'error' },
    });

    return [...new Set(
        messages
            .filter((message) => message.ruleId === 'no-undef')
            .map((message) => (message.message.match(/'([^']+)'/) || [])[1])
            .filter(Boolean)
    )].sort();
}

describe('엔트리 페이지의 과도기 전역', () => {
    for (const page of ENTRY_PAGES) {
        test(`${page.html} 이 로드하는 스크립트가 ${path.basename(page.module)} 의 전역 참조를 모두 제공한다`, () => {
            const scriptSources = readClassicScriptSources(page.html);

            const provided = new Set();
            for (const src of scriptSources) {
                if (src.startsWith('http')) {
                    for (const name of CDN_GLOBALS[src] || []) {
                        provided.add(name);
                    }
                } else {
                    for (const name of collectScriptGlobals(resolveLocalScriptPath(src))) {
                        provided.add(name);
                    }
                }
            }

            const known = KNOWN_UNRESOLVED[page.html] || [];
            const unresolved = collectFreeGlobals(page.module).filter((name) => !provided.has(name));

            assert.deepEqual(
                unresolved,
                [...known].sort(),
                `${page.module} 가 참조하는 전역 중 ${page.html} 이 로드하지 않는 것: ${unresolved.join(', ')}`
            );
        });
    }

    test('모듈 스크립트는 비모듈 스크립트보다 뒤에 실행되도록 배치된다', () => {
        for (const page of ENTRY_PAGES) {
            const html = fs.readFileSync(path.join(frontendRoot, page.html), 'utf8');
            const moduleTags = html.match(/<script\b[^>]*\btype=["']module["'][^>]*>/g) || [];

            assert.equal(moduleTags.length, 1, `${page.html} 의 모듈 스크립트는 1개여야 한다`);
            // defer 시맨틱이 순서를 보장하는 것은 src 가 있는 외부 모듈일 때뿐이다.
            // 인라인 모듈이 되면 파싱 도중 실행돼 전역 참조가 앞당겨진다.
            assert.match(moduleTags[0], /\bsrc=/, `${page.html} 의 모듈 스크립트는 외부 파일이어야 한다`);
            assert.doesNotMatch(moduleTags[0], /\basync\b/, `${page.html} 의 모듈 스크립트에 async 가 붙으면 순서가 깨진다`);
        }
    });

    test('전환 모듈은 설정 전역을 직접 읽지 않고 접근 모듈만 거친다', () => {
        for (const page of ENTRY_PAGES) {
            const code = fs.readFileSync(path.join(frontendRoot, page.module), 'utf8');
            // 주석 안의 이름은 참조가 아니므로 소스 텍스트가 아니라 식별자 토큰으로 판정한다.
            const tokens = espree.tokenize(code, { ecmaVersion: 2022, sourceType: 'module' });
            const readsConfigGlobal = tokens.some(
                (token) => token.type === 'Identifier' && token.value === '__CONFIG__'
            );
            assert.equal(readsConfigGlobal, false, `${page.module} 이 설정 전역을 직접 참조한다`);
        }
    });
});
