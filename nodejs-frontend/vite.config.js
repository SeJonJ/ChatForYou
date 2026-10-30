import path from 'node:path';
import fs from 'node:fs';
import { randomUUID } from 'node:crypto';
import { defineConfig } from 'vite';

// ESM 으로 전환한 화면만 Vite 엔트리가 된다. 여기 없는 HTML 은 빌드 그래프에 들어가지
// 않고 아래 COPY_TARGETS 로 원본 그대로 dist 에 복사된다.
const ENTRY_HTML = [
  'templates/roomlist.html',
  'templates/login/chatlogin.html',
  'templates/login/qr/qrlogin.html',
  'templates/login/qr/qrscan.html',
];

// 빌드 그래프에 들어가지 않는 자산을 dist 로 그대로 옮기는 목록.
// dir 항목은 디렉터리 전체, file 항목은 단일 파일이다. filter 는 dir 항목에서
// 복사할 확장자를 좁힐 때만 쓴다.
// 전환이 끝난 JS 는 여기 들어가면 안 된다 — 모듈 청크와 원본이 dist 에 공존하면
// 브라우저가 어느 쪽을 실행하는지 추적할 수 없다.
const COPY_TARGETS = [
  { kind: 'file', from: 'templates/room/kurentoroom.html' },
  { kind: 'dir', from: 'templates/popup', filter: (name) => name.endsWith('.html') },
  // 전환 페이지와 미전환 페이지가 함께 비모듈 <script> 로 로드하는 공유 코드.
  // config.js 만 모듈로 전환됐으므로 청크와 원본이 겹치지 않도록 제외한다.
  { kind: 'dir', from: 'static/js/common', filter: (name) => name !== 'config.js' },
  { kind: 'dir', from: 'static/js/popup' },
  { kind: 'dir', from: 'static/js/rtc' },
  { kind: 'dir', from: 'static/js/chatroom' },
  { kind: 'file', from: 'static/js/roomlist/bootstrap-show-password.min.js' },
  { kind: 'dir', from: 'static/css' },
  // 데스크톱은 동기화받은 이 원본을 자체적으로 컴파일해 앱 스타일을 만든다.
  // 빠지면 Electron 빌드가 스타일 없이 산출된다.
  { kind: 'dir', from: 'static/scss' },
  { kind: 'dir', from: 'static/images' },
  { kind: 'dir', from: 'static/fonts' },
  { kind: 'dir', from: 'static/vendor' },
  // 비모듈 <script> 가 window.__CONFIG__ 를 주입하는 동결 전역.
  // 빌드 전에 npm run local / npm run prod 가 내용을 확정해야 한다.
  { kind: 'file', from: 'config/config.js' },
];

// 성공한 빌드가 모든 복사를 마친 뒤에만 dist 루트에 남기는 완료 표식. 빌드 시작 때 지우므로
// 빌드 중이거나 실패한 dist 에는 없다. 데스크톱 동기화는 이 표식이 없으면 dist 를 쓰지 않는다.
const BUILD_COMPLETE_MARKER = '.build-complete';

// 동시에 dist 를 쓰는 빌드를 막는 잠금 파일. 프로젝트 루트 기준 경로다.
const BUILD_LOCK = '.dist-build.lock';

/**
 * 엔트리 HTML 의 모듈 스크립트가 실제로 빌드 산출물로 치환됐는지 확인한다.
 *
 * Vite 는 해석하지 못한 HTML 자산 참조를 경고만 남기고 원본 그대로 통과시킨다.
 * 그대로 두면 빌드는 성공하는데 화면이 비는 상태가 되므로 여기서 실패시킨다.
 * CSS 나 CDN 참조처럼 런타임에 해석돼야 하는 링크는 검사 대상이 아니다.
 */
function assertEntryModulesResolved(outDir, assetPrefix) {
  for (const entry of ENTRY_HTML) {
    const emitted = path.resolve(outDir, entry);
    if (!fs.existsSync(emitted)) {
      throw new Error(`엔트리 HTML 이 산출되지 않았습니다: ${entry}`);
    }

    const html = fs.readFileSync(emitted, 'utf8');
    const moduleScripts = html.match(/<script\b[^>]*\btype=["']module["'][^>]*>/g) || [];

    if (moduleScripts.length === 0) {
      throw new Error(
        `엔트리 HTML 에 모듈 스크립트가 없습니다: ${entry} (전환 스크립트 재지정 누락)`
      );
    }

    for (const tag of moduleScripts) {
      const src = (tag.match(/\bsrc=["']([^"']+)["']/) || [])[1];
      if (!src || !src.startsWith(assetPrefix)) {
        throw new Error(
          `엔트리 HTML 의 모듈 경로를 Vite 가 해석하지 못했습니다: ${entry} → ${src || tag}`
        );
      }
    }
  }
}

/**
 * 미전환 자산을 dist 로 복사한다.
 *
 * 복사 대상 누락이나 빌드 산출물과의 경로 충돌은 런타임 404 로만 드러나 추적 비용이
 * 크므로 경고가 아니라 빌드 실패로 처리한다.
 */
function copyUnbundledAssets(rootDir, outDir) {
  /** 목적지가 이미 존재하면 빌드 산출물을 덮어쓰는 상황이므로 실패시킨다. */
  const copyFile = (absoluteSource, absoluteDestination) => {
    if (fs.existsSync(absoluteDestination)) {
      throw new Error(
        `복사 대상이 빌드 산출물과 충돌합니다: ${path.relative(outDir, absoluteDestination)}`
      );
    }
    fs.mkdirSync(path.dirname(absoluteDestination), { recursive: true });
    fs.copyFileSync(absoluteSource, absoluteDestination);
  };

  const copyDirectory = (absoluteSource, absoluteDestination, filter) => {
    for (const entry of fs.readdirSync(absoluteSource, { withFileTypes: true })) {
      const nextSource = path.join(absoluteSource, entry.name);
      const nextDestination = path.join(absoluteDestination, entry.name);
      if (entry.isDirectory()) {
        copyDirectory(nextSource, nextDestination, filter);
      } else if (entry.isFile() && (!filter || filter(entry.name))) {
        copyFile(nextSource, nextDestination);
      }
    }
  };

  for (const target of COPY_TARGETS) {
    const absoluteSource = path.resolve(rootDir, target.from);
    const absoluteDestination = path.resolve(outDir, target.from);

    if (!fs.existsSync(absoluteSource)) {
      throw new Error(`복사 대상 경로가 없습니다: ${target.from}`);
    }

    if (target.kind === 'dir') {
      copyDirectory(absoluteSource, absoluteDestination, target.filter);
    } else {
      copyFile(absoluteSource, absoluteDestination);
    }
  }
}

/**
 * 복사 대상의 절대 경로 목록. watch 모드에서 감시 대상으로 등록한다.
 * dir 항목은 디렉터리 자체도 넣는다. 파일만 등록하면 새로 만든 파일은 감시되지 않아 dist 에 빠진다.
 */
function listCopySources(rootDir) {
  const paths = [];
  const walk = (absolute, filter) => {
    paths.push(absolute);
    for (const entry of fs.readdirSync(absolute, { withFileTypes: true })) {
      const next = path.join(absolute, entry.name);
      if (entry.isDirectory()) {
        walk(next, filter);
      } else if (entry.isFile() && (!filter || filter(entry.name))) {
        paths.push(next);
      }
    }
  };

  for (const target of COPY_TARGETS) {
    const absolute = path.resolve(rootDir, target.from);
    if (!fs.existsSync(absolute)) {
      throw new Error(`복사 대상 경로가 없습니다: ${target.from}`);
    }
    if (target.kind === 'dir') {
      walk(absolute, target.filter);
    } else {
      paths.push(absolute);
    }
  }
  return paths;
}

// 모듈 그래프를 읽기 전에도 감시해야 하는 번들 입력. 엔트리 HTML 과, 엔트리가 import 하는 모듈 소스가
// 들어 있는 디렉터리다. 그래프를 읽은 빌드에서는 rollup 이 같은 파일을 이미 감시하므로 중복 등록은 무해하다.
const BUNDLE_WATCH_DIRS = ['static/js'];

/** watch 모드 감시 대상. 복사 대상에 번들 입력(엔트리 HTML·모듈 소스 디렉터리)을 더한다. */
function listWatchSources(rootDir) {
  return [
    ...listCopySources(rootDir),
    ...ENTRY_HTML.map((entry) => path.resolve(rootDir, entry)),
    ...BUNDLE_WATCH_DIRS.map((dir) => path.resolve(rootDir, dir)),
  ];
}

/**
 * dist 를 쓰는 빌드를 한 번에 하나로 제한하는 잠금. 얻으면 자기 토큰을 돌려준다.
 *
 * 두 빌드가 겹치면 뒤 빌드의 emptyOutDir 가 앞 빌드의 복사 도중에 dist 를 비우고, 앞 빌드는
 * 빈 트리에 나머지를 복사한 뒤 완료 표식을 남긴다. 표식만으로는 이 상태를 구분할 수 없으므로
 * 빌드 시작부터 표식을 쓸 때까지 잠금을 쥔다. 잠금 파일은 emptyOutDir 에 지워지지 않도록 dist 밖에 둔다.
 *
 * 내용(`pid:토큰`)을 임시 파일에 다 쓴 뒤 link 로 잠금 경로에 건다. 파일 생성과 내용 쓰기가
 * 따로 일어나면 다른 빌드가 빈 잠금을 읽고 죽은 잠금으로 오인해 지울 수 있다.
 * 비정상 종료로 남은 잠금은 기록된 pid 가 살아 있지 않으면 회수한다.
 */
function acquireBuildLock(lockPath) {
  const token = `${process.pid}:${randomUUID()}`;
  const staging = `${lockPath}.${process.pid}.${randomUUID()}`;
  fs.writeFileSync(staging, token);
  try {
    for (let attempt = 0; attempt < 5; attempt += 1) {
      try {
        fs.linkSync(staging, lockPath);
        return token;
      } catch (error) {
        if (error.code !== 'EEXIST') {
          throw error;
        }
      }
      const held = readLockToken(lockPath);
      if (held === null) {
        continue;
      }
      // 이 함수가 쓴 잠금은 항상 pid 를 담는다. 비었거나 해석되지 않으면 외부에서 손상된 파일이라 회수한다.
      const owner = Number(held.split(':')[0]);
      if (Number.isInteger(owner) && owner > 0 && owner !== process.pid && isProcessAlive(owner)) {
        throw new Error(
          `다른 빌드가 dist 를 쓰고 있습니다 (pid ${owner}). 그 빌드가 끝난 뒤 다시 실행하세요. ` +
          `실행 중인 빌드가 없다면 잠금 파일을 지우세요: ${lockPath}`
        );
      }
      reclaimStaleLock(lockPath, held);
    }
  } finally {
    fs.rmSync(staging, { force: true });
  }
  throw new Error(`빌드 잠금을 얻지 못했습니다: ${lockPath}`);
}

/**
 * 죽은 빌드가 남긴 잠금을 치운다.
 *
 * 지우기 전에 다른 빌드가 먼저 회수하고 새 잠금을 걸었을 수 있으므로, rename 으로 옮긴 뒤 내용이
 * 확인한 잠금과 같을 때만 버린다. 다르면 살아 있는 잠금을 옮긴 것이라 제자리에 되돌린다.
 */
function reclaimStaleLock(lockPath, staleToken) {
  const moved = `${lockPath}.stale.${process.pid}.${randomUUID()}`;
  try {
    fs.renameSync(lockPath, moved);
  } catch (error) {
    if (error.code === 'ENOENT') {
      return;
    }
    throw error;
  }
  if (readLockToken(moved) !== staleToken) {
    try {
      fs.linkSync(moved, lockPath);
    } catch (error) {
      if (error.code !== 'EEXIST') {
        throw error;
      }
    }
  }
  fs.rmSync(moved, { force: true });
}

/** 잠금 파일 내용. 그 사이 사라졌으면 null. */
function readLockToken(lockPath) {
  try {
    return fs.readFileSync(lockPath, 'utf8');
  } catch (error) {
    if (error.code === 'ENOENT') {
      return null;
    }
    throw error;
  }
}

function isProcessAlive(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    // EPERM 은 다른 사용자의 살아 있는 프로세스다.
    return error.code === 'EPERM';
  }
}

/**
 * 모듈 빌드 직후의 dist 마무리 단계.
 *
 * 엔트리 검증이 먼저 끝나야 복사 단계가 산출물과의 충돌을 정확히 판정할 수 있으므로
 * 두 단계를 한 훅 안에서 순서대로 실행한다.
 */
function finalizeDistribution() {
  let rootDir;
  let outDir;
  let assetPrefix;
  let lockPath;
  let lockToken = null;

  // 자기 토큰이 남아 있을 때만 지운다. 다른 빌드가 회수해 새로 건 잠금을 지우면 안 된다.
  const releaseLock = () => {
    if (lockToken !== null) {
      if (readLockToken(lockPath) === lockToken) {
        fs.rmSync(lockPath, { force: true });
      }
      lockToken = null;
    }
  };

  // Ctrl+C·터미널 종료 등 신호로 끝나면 exit 이벤트가 오지 않아 잠금이 남는다. 풀고 나서 원래 신호로 종료한다.
  const releaseOnSignal = (signal) => {
    releaseLock();
    if (process.listenerCount(signal) === 0) {
      process.kill(process.pid, signal);
    }
  };

  return {
    name: 'chatforyou-finalize-distribution',
    apply: 'build',
    configResolved(config) {
      rootDir = config.root;
      outDir = path.resolve(config.root, config.build.outDir);
      assetPrefix = `${config.base}${config.build.assetsDir}/`;
      lockPath = path.join(config.root, BUILD_LOCK);
      process.on('exit', releaseLock);
      // SIGHUP 은 터미널·IDE 탭을 닫을 때 온다.
      for (const signal of ['SIGINT', 'SIGTERM', 'SIGHUP']) {
        process.once(signal, releaseOnSignal);
      }
    },
    // watch 모드에서는 복사 대상도 감시한다. 번들 그래프 밖이라 등록하지 않으면
    // sass 출력이나 classic 스크립트를 고쳐도 다시 빌드되지 않아 dist 가 낡은 채로 남는다.
    // 잠금보다 먼저 등록한다. 첫 빌드가 잠금에 막혀 실패하면 rollup 은 그때까지 등록된 파일만 감시하고
    // 모듈 그래프는 읽지 않은 상태라, 엔트리 HTML 과 모듈 소스도 여기서 함께 등록해야 수정이 반영된다.
    // 잠금을 먼저 얻어야 다른 빌드가 쓰는 중인 dist 의 표식을 건드리지 않는다.
    // 번들 단계에서 실패하면 emptyOutDir 전에 멈춰 이전 dist 가 남으므로 표식을 먼저 지운다.
    buildStart() {
      if (this.meta.watchMode) {
        for (const source of listWatchSources(rootDir)) {
          this.addWatchFile(source);
        }
      }
      // watch 모드에서 앞선 재빌드가 풀지 못한 자기 잠금을 먼저 정리한다.
      releaseLock();
      lockToken = acquireBuildLock(lockPath);
      fs.rmSync(path.join(outDir, BUILD_COMPLETE_MARKER), { force: true });
    },
    buildEnd(error) {
      if (error) {
        releaseLock();
      }
    },
    // closeBundle 은 출력·쓰기 단계가 실패해도 오류 인자 없이 호출되어 원래 오류를 가리고
    // 실패한 dist 에 표식을 남긴다. writeBundle 은 모든 파일 쓰기가 성공한 뒤에만 불리고,
    // order: 'post' + sequential 로 다른 플러그인의 writeBundle 까지 끝난 다음에 실행된다.
    writeBundle: {
      order: 'post',
      sequential: true,
      handler() {
        // 여기서 실패하면 rollup 은 renderError 도 closeBundle(watch 모드) 도 부르지 않는다.
        // 대기 중인 watcher 가 잠금을 계속 쥐지 않도록 성공·실패 모두 여기서 푼다.
        try {
          assertEntryModulesResolved(outDir, assetPrefix);
          copyUnbundledAssets(rootDir, outDir);
          this.info(`미전환 자산 ${COPY_TARGETS.length}개 항목 복사 완료`);
          fs.writeFileSync(path.join(outDir, BUILD_COMPLETE_MARKER), 'complete\n');
        } finally {
          releaseLock();
        }
      },
    },
    // 청크 렌더 단계가 실패하면 불린다. watch 모드는 실패한 재빌드 뒤 closeBundle 을 부르지 않으므로
    // 여기서 풀지 않으면 대기 중인 watcher 가 잠금을 계속 쥔다.
    renderError() {
      releaseLock();
    },
    // 단일 빌드에서 쓰기 단계가 실패해도 잠금을 푼다. 표식은 쓰지 않는다.
    closeBundle() {
      releaseLock();
    },
  };
}

export default defineConfig({
  // 템플릿의 <base href="/chatforyou/"> 때문에 상대 base 는 /assets/... 로 해석되고,
  // 그 prefix 는 ingress 관할 밖이라 404 가 된다. 절대 base 를 고정한다.
  base: '/chatforyou/',
  // 미전환 자산은 아래 복사 단계가 목록 기반으로 옮긴다. Vite 의 publicDir 는 단일
  // 디렉터리여서 엔트리 HTML 과 같은 트리에 섞인 이번 자산 구성을 표현할 수 없다.
  publicDir: false,
  build: {
    outDir: 'dist',
    // Electron 경로 변환이 src="assets/" 를 src="static/assets/" 로 재작성하므로
    // 기본값 assets 를 쓰면 데스크톱에서 산출물 경로가 깨진다.
    assetsDir: 'build',
    emptyOutDir: true,
    rollupOptions: {
      input: ENTRY_HTML.map((entry) => path.resolve(import.meta.dirname, entry)),
    },
  },
  plugins: [finalizeDistribution()],
  test: {
    // 전환 대상이 브라우저 코드라 DOM 전역이 있어야 단위 테스트가 성립한다.
    environment: 'jsdom',
    include: ['test/**/*.test.js'],
  },
});
