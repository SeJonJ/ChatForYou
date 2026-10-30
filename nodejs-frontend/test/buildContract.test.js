// @vitest-environment node
import { describe, test } from 'vitest';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

/**
 * 빌드 구성과 소스 트리 사이의 정합 검증.
 *
 * Vite 엔트리 목록과 실제로 모듈 스크립트를 쓰는 HTML 이 어긋나면 빌드는 성공하는데
 * 화면만 비고, 전환이 끝난 JS 가 복사 목록에 남으면 dist 에 모듈 청크와 원본이 함께
 * 놓여 브라우저가 어느 쪽을 실행하는지 추적할 수 없다. 둘 다 실행해야만 드러나는
 * 종류의 사고라서 설정 파일 자체를 읽어 미리 막는다.
 */

const frontendRoot = path.resolve(import.meta.dirname, '..');
const viteConfigSource = fs.readFileSync(path.join(frontendRoot, 'vite.config.js'), 'utf8');

/** 빈 임시 루트에 묶은 finalize 플러그인. 루트에 완료 표식이 남은 dist 를 만들어 둔다. */
async function loadFinalizePlugin() {
    const { default: config } = await import('../vite.config.js');
    const finalize = config.plugins.find((plugin) => plugin.name === 'chatforyou-finalize-distribution');
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'cfy-lock-'));
    fs.mkdirSync(path.join(root, 'dist'));
    const marker = path.join(root, 'dist', '.build-complete');
    fs.writeFileSync(marker, 'complete\n');
    finalize.configResolved({ root, base: '/chatforyou/', build: { outDir: 'dist', assetsDir: 'build' } });
    return { finalize, root, marker, lock: path.join(root, '.dist-build.lock') };
}

/** buildStart 가 쓰는 플러그인 컨텍스트만 흉내 낸다. */
function fakeContext(watchMode) {
    const watched = [];
    return { meta: { watchMode }, watched, addWatchFile: (file) => watched.push(file) };
}

/**
 * npm engines 범위에 버전이 들어가는지 판정한다. 테스트 의존성을 늘리지 않으려고 semver 의 필요한 부분만 옮겼다.
 * `||`, 공백으로 이은 비교식, `A - B`, `^`·`~`·`>=`·`>`·`<=`·`<`·`=`, `x`/`*`/생략 자리, `v` 접두사를 다룬다.
 * 해석하지 못한 표기는 통과시키지 않고 예외로 알린다.
 */
function satisfiesNodeRange(version, range) {
    const target = version.split('.').map(Number);
    const compare = (a, b) => {
        for (let i = 0; i < 3; i += 1) {
            if (a[i] !== b[i]) {
                return a[i] < b[i] ? -1 : 1;
            }
        }
        return 0;
    };
    const parse = (text) => {
        const match = text.match(/^v?(\d+|[xX*])(?:\.(\d+|[xX*]))?(?:\.(\d+|[xX*]))?$/);
        if (!match) {
            throw new Error(`해석할 수 없는 범위: ${range}`);
        }
        const parts = match.slice(1, 4).map((part) => (part === undefined || /[xX*]/.test(part) ? null : Number(part)));
        const fixed = parts.findIndex((part) => part === null);
        return { parts, fixed: fixed === -1 ? 3 : fixed };
    };
    const low = ({ parts }) => parts.map((part) => part ?? 0);
    const nextUp = ({ parts }, depth) => {
        const upper = parts.slice(0, depth).map((part) => part ?? 0);
        upper[depth - 1] += 1;
        return [...upper, 0, 0, 0].slice(0, 3);
    };
    // 한 비교식을 [하한, 하한 포함 여부, 상한, 상한 포함 여부] 조건으로 바꿔 target 을 검사한다.
    const test = (comparator) => {
        const op = comparator.match(/^(>=|<=|>|<|=|\^|~)?/)[0];
        const spec = parse(comparator.slice(op.length));
        const cmpLow = compare(target, low(spec));
        if (spec.fixed === 0) {
            return op === '<' ? false : op === '>' ? false : true;
        }
        switch (op) {
            case '>=': return cmpLow >= 0;
            case '>': return spec.fixed < 3 ? compare(target, nextUp(spec, spec.fixed)) >= 0 : cmpLow > 0;
            case '<': return cmpLow < 0;
            case '<=': return spec.fixed < 3 ? compare(target, nextUp(spec, spec.fixed)) < 0 : cmpLow <= 0;
            case '^': {
                const depth = spec.parts[0] > 0 || spec.fixed === 1 ? 1 : spec.parts[1] > 0 || spec.fixed === 2 ? 2 : 3;
                return cmpLow >= 0 && compare(target, nextUp(spec, depth)) < 0;
            }
            case '~': {
                const depth = spec.fixed >= 2 ? 2 : 1;
                return cmpLow >= 0 && compare(target, nextUp(spec, depth)) < 0;
            }
            default:
                return spec.fixed < 3
                    ? cmpLow >= 0 && compare(target, nextUp(spec, spec.fixed)) < 0
                    : cmpLow === 0;
        }
    };
    return range.split('||').some((set) => {
        const trimmed = set.trim();
        if (trimmed === '' || trimmed === '*') {
            return true;
        }
        const hyphen = trimmed.match(/^(\S+)\s+-\s+(\S+)$/);
        const comparators = hyphen
            ? [`>=${hyphen[1]}`, `<=${hyphen[2]}`]
            : trimmed.replace(/(>=|<=|>|<|=|\^|~)\s+/g, '$1').split(/\s+/);
        return comparators.every(test);
    });
}

/** vite.config.js 의 배열 리터럴에서 따옴표로 묶인 경로만 뽑는다. */
function readPathArray(constantName) {
    const block = viteConfigSource.match(new RegExp(`const ${constantName} = \\[([\\s\\S]*?)\\n\\];`));
    assert.ok(block, `vite.config.js 에서 ${constantName} 을 찾지 못했다`);
    return [...block[1].matchAll(/['"]([^'"]+\/[^'"]*)['"]/g)].map((match) => match[1]);
}

/** 소스 트리에서 type="module" 스크립트를 쓰는 HTML 을 모두 찾는다. */
function findTemplatesUsingModuleScript() {
    const templatesDir = path.join(frontendRoot, 'templates');
    const found = [];

    const walk = (directory) => {
        for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
            const next = path.join(directory, entry.name);
            if (entry.isDirectory()) {
                walk(next);
            } else if (entry.name.endsWith('.html')) {
                const html = fs.readFileSync(next, 'utf8');
                if (/<script\b[^>]*\btype=["']module["']/.test(html)) {
                    found.push(path.relative(frontendRoot, next));
                }
            }
        }
    };

    walk(templatesDir);
    return found.sort();
}

describe('빌드 구성 계약', () => {
    test('Vite 엔트리 목록과 모듈 스크립트를 쓰는 HTML 집합이 같다', () => {
        const entries = readPathArray('ENTRY_HTML').sort();
        assert.deepEqual(entries, findTemplatesUsingModuleScript());
    });

    test('전환이 끝난 소스는 복사 목록에 없다', () => {
        const copyTargets = readPathArray('COPY_TARGETS');
        const convertedPrefixes = [
            'static/js/roomlist/roomlist.js',
            'static/js/login',
            'static/js/error',
            'static/js/common/config.js',
        ];

        for (const target of copyTargets) {
            for (const prefix of convertedPrefixes) {
                assert.ok(
                    !target.startsWith(prefix),
                    `전환된 소스가 복사 목록에 있다: ${target}`
                );
            }
        }
    });

    test('공유 디렉터리를 통째로 복사할 때 전환된 파일은 제외된다', () => {
        // static/js/common 은 디렉터리째 복사되지만 config.js 만 모듈로 전환됐다.
        // 필터가 사라지면 dist 에 원본과 청크가 함께 놓인다.
        const commonTarget = viteConfigSource.match(/\{ kind: 'dir', from: 'static\/js\/common'[^}]*\}/);
        assert.ok(commonTarget, 'static/js/common 복사 항목을 찾지 못했다');
        assert.match(commonTarget[0], /config\.js/);
    });

    test('완료 표식은 모든 파일 쓰기가 성공한 뒤에만 남는다', async () => {
        // closeBundle 은 출력·쓰기 단계가 실패해도 오류 인자 없이 불린다. 거기서 표식을 쓰면
        // 실패한 dist 가 완성본으로 동기화되므로, 마지막 writeBundle 에서만 쓰도록 고정한다.
        const { finalize, root, marker } = await loadFinalizePlugin();
        try {
            finalize.buildStart.call(fakeContext(false));
            finalize.closeBundle();
            assert.ok(!fs.existsSync(marker), 'closeBundle 이 완료 표식을 남겼다');
        } finally {
            fs.rmSync(root, { recursive: true, force: true });
        }
        assert.equal(finalize.writeBundle.order, 'post');
        assert.equal(finalize.writeBundle.sequential, true);
        assert.equal(typeof finalize.writeBundle.handler, 'function');
    });

    test('다른 빌드가 쓰는 중이면 dist 를 건드리지 않고 멈춘다', async () => {
        // 두 빌드가 겹치면 뒤 빌드가 앞 빌드의 복사 도중 dist 를 비우고, 앞 빌드는 빈 트리 위에
        // 완료 표식을 남긴다. 살아 있는 다른 프로세스가 잠금을 쥐고 있으면 시작 단계에서 실패해야 한다.
        const { finalize, root, marker, lock } = await loadFinalizePlugin();
        const holder = spawn(process.execPath, ['-e', 'setTimeout(() => {}, 30000)']);
        try {
            fs.writeFileSync(lock, String(holder.pid));
            assert.throws(() => finalize.buildStart.call(fakeContext(false)), /다른 빌드가 dist 를 쓰고 있습니다/);
            assert.ok(fs.existsSync(marker), '다른 빌드의 완료 표식을 지웠다');
            assert.equal(fs.readFileSync(lock, 'utf8'), String(holder.pid));
        } finally {
            holder.kill();
            fs.rmSync(root, { recursive: true, force: true });
        }
    });

    test('종료된 빌드가 남긴 잠금은 회수하고, 빌드가 끝나면 잠금을 푼다', async () => {
        const { finalize, root, marker, lock } = await loadFinalizePlugin();
        const dead = spawn(process.execPath, ['-e', '']);
        await new Promise((resolve) => dead.on('exit', resolve));
        try {
            fs.writeFileSync(lock, String(dead.pid));
            finalize.buildStart.call(fakeContext(false));
            assert.match(fs.readFileSync(lock, 'utf8'), new RegExp(`^${process.pid}:`));
            assert.ok(!fs.existsSync(marker), '빌드 시작 때 완료 표식을 지우지 않았다');

            finalize.closeBundle();
            assert.ok(!fs.existsSync(lock), '빌드가 끝났는데 잠금이 남았다');
        } finally {
            fs.rmSync(root, { recursive: true, force: true });
        }
    });

    test('비었거나 해석되지 않는 잠금은 회수한다', async () => {
        const { finalize, root, lock } = await loadFinalizePlugin();
        try {
            for (const content of ['', 'garbage']) {
                fs.writeFileSync(lock, content);
                finalize.buildStart.call(fakeContext(false));
                assert.match(fs.readFileSync(lock, 'utf8'), new RegExp(`^${process.pid}:`));
                finalize.closeBundle();
            }
        } finally {
            fs.rmSync(root, { recursive: true, force: true });
        }
    });

    test('다른 빌드가 회수해 새로 건 잠금은 해제하지 않는다', async () => {
        const { finalize, root, lock } = await loadFinalizePlugin();
        try {
            finalize.buildStart.call(fakeContext(false));
            fs.writeFileSync(lock, '424242:other-build');
            finalize.closeBundle();
            assert.equal(fs.readFileSync(lock, 'utf8'), '424242:other-build');
        } finally {
            fs.rmSync(root, { recursive: true, force: true });
        }
    });

    test('복사 단계가 실패해도 잠금을 풀고 표식은 쓰지 않는다', async () => {
        // watch 모드에서 writeBundle 이 던지면 rollup 은 renderError 도 closeBundle 도 부르지 않는다.
        const { finalize, root, marker, lock } = await loadFinalizePlugin();
        try {
            finalize.buildStart.call(fakeContext(false));
            assert.throws(() => finalize.writeBundle.handler.call({ info() {} }), /엔트리 HTML 이 산출되지 않았습니다/);
            assert.ok(!fs.existsSync(lock), '복사 단계 실패 뒤에도 잠금이 남았다');
            assert.ok(!fs.existsSync(marker), '실패한 빌드에 완료 표식을 남겼다');
        } finally {
            fs.rmSync(root, { recursive: true, force: true });
        }
    });

    test('렌더 단계가 실패하면 잠금을 푼다', async () => {
        // watch 모드는 실패한 재빌드 뒤 closeBundle 을 부르지 않는다. renderError 에서 풀지 않으면
        // 대기 중인 watcher 가 잠금을 쥔 채로 남아 다른 빌드가 모두 거부된다.
        const { finalize, root, lock } = await loadFinalizePlugin();
        try {
            finalize.buildStart.call(fakeContext(false));
            assert.ok(fs.existsSync(lock));
            finalize.renderError(new Error('write failed'));
            assert.ok(!fs.existsSync(lock), '출력 단계 실패 뒤에도 잠금이 남았다');
        } finally {
            fs.rmSync(root, { recursive: true, force: true });
        }
    });

    test('watch 모드는 복사 대상 디렉터리도 감시해 새로 만든 파일을 놓치지 않는다', async () => {
        // 실제 프로젝트 루트에 잠금을 걸면 동시에 도는 빌드와 서로 막는다. 원본 디렉터리를 링크한 임시 루트를 쓴다.
        const { default: config } = await import('../vite.config.js');
        const finalize = config.plugins.find((plugin) => plugin.name === 'chatforyou-finalize-distribution');
        const root = fs.mkdtempSync(path.join(os.tmpdir(), 'cfy-watch-'));
        for (const dir of ['static', 'templates', 'config']) {
            // junction 은 Windows 에서 관리자 권한 없이 만들 수 있고 다른 OS 에서는 무시된다.
            fs.symlinkSync(path.join(frontendRoot, dir), path.join(root, dir), 'junction');
        }
        finalize.configResolved({ root, base: '/chatforyou/', build: { outDir: 'dist', assetsDir: 'build' } });
        const context = fakeContext(true);
        // 개발 중 실제 빌드가 잠금을 쥐고 있을 수 있다. 이 테스트가 그 잠금을 건드리지 않았는지만 본다.
        const realLock = path.join(frontendRoot, '.dist-build.lock');
        const readRealLock = () => (fs.existsSync(realLock) ? fs.readFileSync(realLock, 'utf8') : null);
        const realLockBefore = readRealLock();
        try {
            finalize.buildStart.call(context);
            finalize.closeBundle();
            assert.ok(context.watched.includes(path.join(root, 'static', 'css')), 'static/css 디렉터리를 감시하지 않는다');
            assert.ok(context.watched.includes(path.join(root, 'static', 'js', 'rtc')), 'static/js/rtc 디렉터리를 감시하지 않는다');
            assert.ok(context.watched.includes(path.join(root, 'config', 'config.js')));
            assert.equal(readRealLock(), realLockBefore, '실제 프로젝트 루트의 잠금을 바꿨다');
        } finally {
            fs.rmSync(root, { recursive: true, force: true });
        }
    });

    test('정적 서버가 기다리는 잠금 파일 이름이 빌드 잠금과 같다', () => {
        // 이름이 어긋나면 서버는 빌드가 도는 동안에도 기다리지 않아 반쯤 쓰인 dist 를 내보낸다.
        const lockName = viteConfigSource.match(/const BUILD_LOCK = '([^']+)'/);
        assert.ok(lockName, 'vite.config.js 에서 BUILD_LOCK 을 찾지 못했다');
        const serverSource = fs.readFileSync(path.join(frontendRoot, 'server.js'), 'utf8');
        assert.ok(serverSource.includes(`path.join(__dirname, '${lockName[1]}')`), 'server.js 가 다른 잠금 파일을 본다');
    });

    test('watch 모드는 잠금에 막혀도 감시 대상을 먼저 등록해 watcher 가 살아남는다', async () => {
        // 첫 빌드가 잠금에 막혀 실패할 때 감시 대상이 비어 있으면 rollup watcher 가 조용히 종료된다.
        const { default: config } = await import('../vite.config.js');
        const finalize = config.plugins.find((plugin) => plugin.name === 'chatforyou-finalize-distribution');
        const root = fs.mkdtempSync(path.join(os.tmpdir(), 'cfy-watch-busy-'));
        for (const dir of ['static', 'templates', 'config']) {
            fs.symlinkSync(path.join(frontendRoot, dir), path.join(root, dir), 'junction');
        }
        const holder = spawn(process.execPath, ['-e', 'setTimeout(() => {}, 30000)']);
        finalize.configResolved({ root, base: '/chatforyou/', build: { outDir: 'dist', assetsDir: 'build' } });
        const context = fakeContext(true);
        try {
            fs.writeFileSync(path.join(root, '.dist-build.lock'), `${holder.pid}:other`);
            assert.throws(() => finalize.buildStart.call(context), /다른 빌드가 dist 를 쓰고 있습니다/);
            assert.ok(context.watched.includes(path.join(root, 'static', 'css')), '잠금에 막힌 빌드가 감시 대상을 등록하지 않았다');
            // 모듈 그래프를 읽기 전에 실패하므로 엔트리 HTML 과 모듈 소스도 직접 등록돼 있어야 한다.
            assert.ok(context.watched.includes(path.join(root, 'templates', 'roomlist.html')), '엔트리 HTML 을 감시하지 않는다');
            assert.ok(context.watched.includes(path.join(root, 'static', 'js')), '모듈 소스 디렉터리를 감시하지 않는다');
        } finally {
            holder.kill();
            fs.rmSync(root, { recursive: true, force: true });
        }
    });

    test('watch 모드에서 복사 대상이 없으면 복사 단계와 같은 메시지로 실패한다', async () => {
        const { finalize, root } = await loadFinalizePlugin();
        try {
            assert.throws(() => finalize.buildStart.call(fakeContext(true)), /복사 대상 경로가 없습니다/);
        } finally {
            finalize.closeBundle();
            fs.rmSync(root, { recursive: true, force: true });
        }
    });

    test('엔진 범위 판정기가 npm 범위 표기를 해석한다', () => {
        const cases = [
            ['^20.19.0 || ^22.12.0 || >=24.0.0', true],
            ['>=12.22.7', true],
            ['>=v12.22.7', true],
            ['6.* || 8.* || >= 10.*', true],
            ['^22.20 || ^24.12 || >=25', false],
            ['>=v22.14.0', false],
            ['>= 22.14.0', false],
            ['>22.13.0', false],
            ['22.14.x', false],
            ['>=23', false],
            ['^20.19.0 || >=24.0.0', false],
            ['>=18 <22.13', false],
            ['18 - 22.13.0', true],
            ['*', true],
        ];
        for (const [range, expected] of cases) {
            assert.equal(satisfiesNodeRange('22.13.0', range), expected, range);
        }
        assert.throws(() => satisfiesNodeRange('22.13.0', 'latest'), /해석할 수 없는 범위/);
    });

    test('선언한 Node 하한이 잠금 파일에 고정된 의존성의 Node 요구를 만족한다', () => {
        // 하한 버전이 의존성의 engines 범위 밖이면 그 버전에서 설치 경고가 나고 지원되지 않는 조합으로 테스트가 돈다.
        const engines = JSON.parse(fs.readFileSync(path.join(frontendRoot, 'package.json'), 'utf8')).engines.node;
        const floor = engines.match(/^>=(\d+\.\d+\.\d+)$/)[1];
        const lock = JSON.parse(fs.readFileSync(path.join(frontendRoot, 'package-lock.json'), 'utf8'));
        for (const [name, meta] of Object.entries(lock.packages)) {
            const range = meta.engines && meta.engines.node;
            // 선택 의존성(플랫폼별 네이티브 바이너리 등)은 엔진이 맞지 않으면 npm 이 경고만 내고 쓰지 않는다.
            if (name && typeof range === 'string' && !meta.optional) {
                assert.ok(satisfiesNodeRange(floor, range), `${name} 의 engines.node(${range}) 가 하한 ${floor} 를 허용하지 않는다`);
            }
        }
    });

    test('Node 하한이 .nvmrc·engines·Dockerfile 에서 어긋나지 않는다', () => {
        const nvmrc = fs.readFileSync(path.join(frontendRoot, '.nvmrc'), 'utf8').trim();
        const engines = JSON.parse(fs.readFileSync(path.join(frontendRoot, 'package.json'), 'utf8')).engines.node;
        const dockerfile = fs.readFileSync(path.join(frontendRoot, 'Dockerfile'), 'utf8');

        // nvm 은 .nvmrc 를 그 접두사에 맞는 최신 설치본으로 푼다. engines 하한보다 넓으면 하한 미만 버전이 골라진다.
        const floor = engines.match(/^>=(\d+)\.(\d+)\.\d+$/);
        assert.ok(floor, `engines.node 형식이 >=X.Y.Z 가 아니다: ${engines}`);
        assert.equal(floor[1], '22');
        assert.equal(nvmrc, `${floor[1]}.${floor[2]}`, `.nvmrc(${nvmrc}) 가 engines 하한(${engines})과 다르다`);
        // builder 와 runtime 이 서로 다른 메이저를 쓰면 빌드된 산출물이 검증되지 않은 런타임에서 돈다.
        const nodeImages = [...dockerfile.matchAll(/^FROM (node:[^\s]+)/gm)].map((match) => match[1]);
        assert.equal(nodeImages.length, 2);
        assert.equal(nodeImages[0], nodeImages[1]);
        assert.match(nodeImages[0], /^node:22-/);
    });
});
