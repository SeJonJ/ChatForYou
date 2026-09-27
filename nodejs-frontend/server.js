const http = require('http');
const fs = require('fs');
const path = require('path');
const url = require('url');

const mimeTypes = {
  '.html': 'text/html',
  '.js': 'application/javascript',
  '.css': 'text/css',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.eot': 'application/vnd.ms-fontobject',
  '.json': 'application/json',
};

// 서빙 루트는 vite build 산출물이다. 소스 트리는 더 이상 직접 서빙하지 않는다.
const rootDir = path.join(__dirname, 'dist');

const BASE_PATH = process.env.BASE_PATH || '/chatforyou';

// 빌드는 dist 를 비운 뒤 다시 채우고 마지막에 완료 표식을 쓴다. 개발 중 watch 재빌드가 도는 동안
// 읽으면 아직 없는 파일은 404, 쓰는 중인 파일은 잘린 채 나가고, 이전 빌드의 HTML 은 곧 지워질
// 청크를 가리킨다. 빌드가 도는 동안(잠금이 있고 표식이 없을 때)에는 끝날 때까지(최대 5초) 기다렸다가 읽는다.
// 실패한 빌드는 잠금을 풀고 표식을 남기지 않으므로, 잠금 없이 표식만 없을 때는 기다리지 않는다.
// 잠금 파일 이름은 vite.config.js 의 BUILD_LOCK 과 같아야 한다.
const BUILD_MARKER = path.join(rootDir, '.build-complete');
const BUILD_LOCK = path.join(__dirname, '.dist-build.lock');
const BUILD_WAIT_MS = 5000;
const BUILD_POLL_MS = 50;

// 강제 종료(SIGKILL 등)된 빌드가 남긴 잠금은 빌드 중이 아니다. 잠금에 기록된 pid 가 살아 있을 때만 기다린다.
function isBuilding() {
  if (fs.existsSync(BUILD_MARKER)) {
    return false;
  }
  let owner;
  try {
    owner = Number(fs.readFileSync(BUILD_LOCK, 'utf8').split(':')[0]);
  } catch {
    return false;
  }
  if (!Number.isInteger(owner) || owner <= 0) {
    return false;
  }
  try {
    process.kill(owner, 0);
    return true;
  } catch (error) {
    // EPERM 은 다른 사용자의 살아 있는 프로세스다.
    return error.code === 'EPERM';
  }
}

function readServedFile(absPath, deadline, callback) {
  if (Date.now() < deadline && isBuilding()) {
    setTimeout(() => readServedFile(absPath, deadline, callback), BUILD_POLL_MS);
    return;
  }
  fs.readFile(absPath, callback);
}

const server = http.createServer((req, res) => {
  // 해석할 수 없는 요청 대상은 url.parse 가 던지거나('http://[::1/') 경로 없이 돌려준다('http://').
  // 둘 다 그대로 두면 프로세스가 죽으므로 400 으로 끝낸다.
  let filePath;
  try {
    filePath = url.parse(req.url).pathname;
  } catch {
    filePath = null;
  }
  if (!filePath) {
    res.writeHead(400, { 'Content-Type': 'text/plain' });
    res.end('400 Bad Request');
    return;
  }
  if (filePath.startsWith(BASE_PATH)) {
    filePath = filePath.replace(new RegExp('^' + BASE_PATH), '') || '/';
  }
  if (filePath === '/' || filePath === '/index.html') {
    filePath = '/templates/roomlist.html';
  }
  if (filePath.startsWith('/css') || filePath.startsWith('/js') || filePath.startsWith('/images') || filePath.startsWith('/fonts') || filePath.startsWith('/vendor')) {
    filePath = '/static' + filePath;
  } else if (filePath.startsWith('/templates')) {
    // 그대로 사용
  } else if (filePath.endsWith('.html')) {
    filePath = '/templates' + filePath;
  }
  // 요청 경로의 .. 로 dist 밖 파일(server.js, /etc/hosts 등)을 읽지 못하게 서빙 루트 안으로 제한한다.
  const absPath = path.resolve(rootDir, '.' + filePath);
  if (!absPath.startsWith(rootDir + path.sep)) {
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('404 Not Found');
    return;
  }
  readServedFile(absPath, Date.now() + BUILD_WAIT_MS, (err, data) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('404 Not Found');
      return;
    }
    const ext = path.extname(absPath);
    let mimeType = mimeTypes[ext] || 'application/octet-stream';
    if (mimeType.startsWith('text/') || mimeType === 'application/javascript') {
      mimeType += '; charset=utf-8';
    }
    res.writeHead(200, { 'Content-Type': mimeType });
    res.end(data);
  });
});

// PORT=0 이면 운영체제가 빈 포트를 고른다. 실제로 열린 포트를 출력한다.
const PORT = process.env.PORT ? Number(process.env.PORT) : 3000;
server.listen(PORT, () => {
  console.log(`Static server running at http://localhost:${server.address().port}`);
});
