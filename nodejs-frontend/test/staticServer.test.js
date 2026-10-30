// @vitest-environment node
import { afterAll, beforeAll, describe, test } from 'vitest';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';

/**
 * 정적 서버의 서빙 경계 검증.
 *
 * 요청 경로가 그대로 파일 경로가 되므로 `..` 을 넣으면 서빙 루트 밖 파일(서버 소스, 호스트 설정)이
 * 그대로 응답에 실릴 수 있다. 실제 프로세스를 띄워 dist 밖 파일이 나오지 않는지 확인한다.
 */

/**
 * 요청 경로를 정규화하지 않고 그대로 보낸다.
 * fetch 와 브라우저는 `..` 을 먼저 정리해 버리므로 이 경로로는 서버의 경계 검사를 확인할 수 없다.
 */
function rawRequest(port, target) {
    return new Promise((resolve, reject) => {
        const socket = net.connect(port, '127.0.0.1', () => {
            socket.write(`GET ${target} HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n`);
        });
        let raw = '';
        socket.setEncoding('utf8');
        socket.on('data', (chunk) => { raw += chunk; });
        socket.on('error', reject);
        socket.on('end', () => {
            const status = Number(raw.slice(9, 12));
            resolve({ status, body: raw.slice(raw.indexOf('\r\n\r\n') + 4) });
        });
    });
}

const frontendRoot = path.resolve(import.meta.dirname, '..');

let sandbox;
let server;
let port;

beforeAll(async () => {
    sandbox = fs.mkdtempSync(path.join(os.tmpdir(), 'cfy-server-'));
    fs.mkdirSync(path.join(sandbox, 'dist', 'templates'), { recursive: true });
    fs.writeFileSync(path.join(sandbox, 'dist', 'templates', 'roomlist.html'), '<html>room</html>');
    fs.writeFileSync(path.join(sandbox, 'dist', '.build-complete'), 'complete\n');
    fs.writeFileSync(path.join(sandbox, 'secret.txt'), 'SERVER_ONLY_SECRET');
    fs.copyFileSync(path.join(frontendRoot, 'server.js'), path.join(sandbox, 'server.js'));

    // 고정 포트는 다른 프로세스와 충돌할 수 있으므로 운영체제가 고른 빈 포트를 배너에서 읽는다.
    server = spawn(process.execPath, ['server.js'], {
        cwd: sandbox,
        env: { ...process.env, PORT: '0' },
        stdio: ['ignore', 'pipe', 'pipe'],
    });

    port = await new Promise((resolve, reject) => {
        let stderr = '';
        const timer = setTimeout(() => reject(new Error('서버가 시간 안에 뜨지 않았다')), 10000);
        server.stderr.on('data', (chunk) => { stderr += chunk; });
        server.stdout.on('data', (chunk) => {
            const banner = chunk.toString().match(/Static server running at http:\/\/localhost:(\d+)/);
            if (banner) {
                clearTimeout(timer);
                resolve(Number(banner[1]));
            }
        });
        server.on('error', reject);
        server.on('exit', (code) => {
            clearTimeout(timer);
            reject(new Error(`서버가 시작 전에 종료됐다 (code ${code}): ${stderr}`));
        });
    });
});

afterAll(() => {
    server?.kill();
    if (sandbox) {
        fs.rmSync(sandbox, { recursive: true, force: true });
    }
});

describe('정적 서버 서빙 경계', () => {
    test('서빙 루트 안의 파일은 그대로 응답한다', async () => {
        const response = await rawRequest(port, '/chatforyou/');
        assert.equal(response.status, 200);
        assert.match(response.body, /room/);
    });

    test('상위 경로로 서빙 루트 밖 파일을 읽을 수 없다', async () => {
        const outside = [
            '/../secret.txt',
            '/chatforyou/../secret.txt',
            '/chatforyou/../../../../../../../../etc/hosts',
            '/../server.js',
        ];

        for (const target of outside) {
            const response = await rawRequest(port, target);
            assert.equal(response.status, 404, `${target} 가 404 가 아니다`);
            assert.doesNotMatch(response.body, /SERVER_ONLY_SECRET|createServer/, `${target} 응답에 서빙 루트 밖 내용이 있다`);
        }
    });
});

describe('빌드 중 서빙', () => {
    const distDir = () => path.join(sandbox, 'dist');
    const marker = () => path.join(distDir(), '.build-complete');
    const lock = () => path.join(sandbox, '.dist-build.lock');

    /** 빌드가 도는 상태: 잠금이 걸려 있고 완료 표식은 지워져 있다. */
    const startBuild = () => {
        fs.writeFileSync(lock(), `${process.pid}:test`);
        fs.rmSync(marker(), { force: true });
    };
    const finishBuild = () => {
        fs.writeFileSync(marker(), 'complete\n');
        fs.rmSync(lock(), { force: true });
    };

    test('빌드가 끝난 dist 에 없는 파일은 기다리지 않고 404 다', async () => {
        const startedAt = Date.now();
        const response = await rawRequest(port, '/chatforyou/js/missing.js');
        assert.equal(response.status, 404);
        assert.ok(Date.now() - startedAt < 1000, '완료된 dist 에서 404 를 지연했다');
    });

    test('완료 표식이 없는 동안에는 이미 있는 파일도 빌드가 끝난 내용으로 응답한다', async () => {
        // 재빌드 중에 이전 빌드의 HTML 을 내보내면 곧 지워질 청크를 가리켜 화면이 빈다.
        const page = path.join(distDir(), 'templates', 'rebuilt.html');
        fs.writeFileSync(page, '<html>previous build</html>');
        startBuild();
        try {
            const pending = rawRequest(port, '/chatforyou/templates/rebuilt.html');
            await new Promise((resolve) => setTimeout(resolve, 300));
            fs.writeFileSync(page, '<html>next build</html>');
            finishBuild();

            const response = await pending;
            assert.equal(response.status, 200);
            assert.match(response.body, /next build/);
        } finally {
            finishBuild();
        }
    });

    test('완료 표식이 없는 동안 아직 쓰이지 않은 파일은 빌드가 끝나면 응답한다', async () => {
        startBuild();
        try {
            const pending = rawRequest(port, '/chatforyou/build/late-chunk.js');
            await new Promise((resolve) => setTimeout(resolve, 300));
            fs.mkdirSync(path.join(distDir(), 'build'), { recursive: true });
            fs.writeFileSync(path.join(distDir(), 'build', 'late-chunk.js'), 'export const late = 1;');
            finishBuild();

            const response = await pending;
            assert.equal(response.status, 200);
            assert.match(response.body, /late = 1/);
        } finally {
            finishBuild();
        }
    });

    test('강제 종료된 빌드가 남긴 잠금으로는 기다리지 않는다', async () => {
        const dead = spawn(process.execPath, ['-e', '']);
        await new Promise((resolve) => dead.on('exit', resolve));
        fs.writeFileSync(lock(), `${dead.pid}:killed`);
        fs.rmSync(marker());
        try {
            const startedAt = Date.now();
            const response = await rawRequest(port, '/chatforyou/');
            assert.equal(response.status, 200);
            assert.ok(Date.now() - startedAt < 1000, '죽은 빌드의 잠금으로 응답을 지연했다');
        } finally {
            finishBuild();
        }
    });

    test('실패한 빌드가 표식 없이 끝나면 기다리지 않고 남은 dist 로 응답한다', async () => {
        // 번들 단계에서 실패한 빌드는 표식을 지운 채 잠금만 푼다. 이때 기다리면 고칠 때까지 모든 요청이 5초씩 걸린다.
        fs.rmSync(marker());
        try {
            const startedAt = Date.now();
            const response = await rawRequest(port, '/chatforyou/');
            assert.equal(response.status, 200);
            assert.ok(Date.now() - startedAt < 1000, '빌드가 돌지 않는데 응답을 지연했다');
        } finally {
            finishBuild();
        }
    });
});

describe('요청 해석', () => {
    test('해석할 수 없는 요청 대상은 400 이고 서버는 계속 응답한다', async () => {
        // 'http://[::1/' 은 url.parse 가 던지고, 'http://' 는 경로 없이 돌려준다.
        for (const target of ['http://[::1/', 'http://']) {
            const response = await rawRequest(port, target);
            assert.equal(response.status, 400, `${target} 가 400 이 아니다`);
            const next = await rawRequest(port, '/chatforyou/');
            assert.equal(next.status, 200, `${target} 이후 서버가 응답하지 않는다`);
        }
    });
});
