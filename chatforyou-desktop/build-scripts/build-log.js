#!/usr/bin/env node

const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

/**
 * ChatForYou Build Information Display
 * 빌드 정보를 보기 좋게 출력하는 스크립트
 */

// 인자 파싱
const args = process.argv.slice(2);
const getArg = (name, def) => {
  const found = args.find(a => a.startsWith(`--${name}=`));
  return found ? found.split('=')[1] : def;
};

const env = getArg('env', 'prod');
const platform = getArg('platform', process.platform);
const buildType = getArg('type', 'build');

// Git 정보 가져오기
function getGitInfo() {
  try {
    const branch = execSync('git rev-parse --abbrev-ref HEAD', { encoding: 'utf8' }).trim();
    const commit = execSync('git rev-parse --short HEAD', { encoding: 'utf8' }).trim();
    const status = execSync('git status --porcelain', { encoding: 'utf8' }).trim();
    const hasChanges = status.length > 0;
    
    return {
      branch,
      commit,
      hasChanges,
      commitMessage: execSync('git log -1 --pretty=%s', { encoding: 'utf8' }).trim()
    };
  } catch (e) {
    return {
      branch: 'unknown',
      commit: 'unknown',
      hasChanges: false,
      commitMessage: 'N/A'
    };
  }
}

// package.json 정보  
const pkg = JSON.parse(fs.readFileSync(path.join(__dirname, '../package.json'), 'utf8'));
const version = pkg.version || '1.0.0';

// config 정보
let config = {};
try {
  const configPath = path.join(__dirname, '../src', 'config', 'config.js');
  if (fs.existsSync(configPath)) {
    const content = fs.readFileSync(configPath, 'utf8');
    // window.__CONFIG__ = { ... } 형태 파싱
    const match = content.match(/window\.__CONFIG__\s*=\s*({[\s\S]*?});/);
    if (match) {
      config = JSON.parse(match[1]);
    }
  }
} catch (e) {
  config = { API_BASE_URL: 'N/A', BASE_URL: 'N/A' };
}

// 빌드 정보 수집
const buildInfo = {
  version,
  environment: env,
  platform,
  buildType,
  git: getGitInfo(),
  config,
  system: {
    os: require('os').platform(),
    arch: require('os').arch(),
    node: process.version,
    electron: pkg.devDependencies?.electron || 'N/A'
  },
  timestamp: new Date().toISOString(),
  buildTime: new Date().toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })
};

// 플랫폼별 아이콘
const platformIcons = {
  'mac': '🍎',
  'win': '🪟',
  'linux': '🐧',
  'all': '🌍',
  'pack': '📦',
  'dist': '🚀'
};

const platformIcon = platformIcons[platform] || '💻';

// 환경별 색상 (콘솔 색상 코드)
const envColors = {
  'prod': '\x1b[32m',     // 녹색
  'local': '\x1b[33m',    // 노란색
  'dev': '\x1b[36m'       // 청록색
};

const envColor = envColors[env] || '\x1b[37m'; // 기본 흰색
const resetColor = '\x1b[0m';

// 로고 ASCII 아트
const logo = `
╔══════════════════════════════════════════════════════════════╗
║                    🚀 ChatForYou Desktop                     ║
║                     Build Information                        ║
╚══════════════════════════════════════════════════════════════╝`;

// 로그 문자열 생성
let log = '';
log += logo + '\n';
log += '\n';
log += `${platformIcon} 빌드 타겟: ${envColor}${platform.toUpperCase()}${resetColor} (${buildType})\n`;
log += `📦 앱 버전: ${envColor}v${version}${resetColor}\n`;
log += `🌍 환경: ${envColor}${env.toUpperCase()}${resetColor}\n`;
log += `⏰ 빌드 시간: ${buildInfo.buildTime}\n`;
log += '\n';

// Git 정보
log += '📚 Git 정보:\n';
log += `   브랜치: ${buildInfo.git.branch}\n`;
log += `   커밋: ${buildInfo.git.commit}${buildInfo.git.hasChanges ? ' (수정됨)' : ''}\n`;
log += `   메시지: ${buildInfo.git.commitMessage}\n`;
log += '\n';

// 시스템 정보
log += '💻 시스템 정보:\n';
log += `   OS: ${buildInfo.system.os} ${buildInfo.system.arch}\n`;
log += `   Node.js: ${buildInfo.system.node}\n`;
log += `   Electron: ${buildInfo.system.electron}\n`;
log += '\n';

// Config 정보
if (Object.keys(config).length > 0) {
  log += '⚙️ 설정 정보:\n';
  Object.keys(config).forEach(key => {
    let value = config[key];
    // 긴 URL은 축약
    if (typeof value === 'string' && value.length > 50) {
      value = value.substring(0, 47) + '...';
    }
    log += `   ${key}: ${value}\n`;
  });
  log += '\n';
}

// 빌드 상태
const statusIcon = buildInfo.git.hasChanges ? '⚠️' : '✅';
log += `${statusIcon} 빌드 상태: ${buildInfo.git.hasChanges ? '수정된 파일 있음' : '깨끗한 상태'}\n`;
log += '\n';

log += '═'.repeat(62) + '\n';

// 콘솔 출력
console.log(log);

// 빌드 정보를 파일로 저장 (선택적)
if (args.includes('--save')) {
  const buildInfoFile = path.join(__dirname, 'build-info.json');
  fs.writeFileSync(buildInfoFile, JSON.stringify(buildInfo, null, 2));
  console.log(`📝 빌드 정보가 저장되었습니다: ${buildInfoFile}`);
} 