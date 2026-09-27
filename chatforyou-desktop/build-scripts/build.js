#!/usr/bin/env node

/**
 * ChatForYou Desktop 통합 빌드 스크립트
 * 모든 빌드 과정을 하나의 스크립트에서 처리
 */

const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

// 색상 코드
const colors = {
  reset: '\x1b[0m',
  bright: '\x1b[1m',
  red: '\x1b[31m',
  green: '\x1b[32m',
  yellow: '\x1b[33m',
  blue: '\x1b[34m',
  magenta: '\x1b[35m',
  cyan: '\x1b[36m'
};

// 로거
const log = {
  info: (msg) => console.log(`${colors.blue}ℹ️  ${msg}${colors.reset}`),
  success: (msg) => console.log(`${colors.green}✅ ${msg}${colors.reset}`),
  warning: (msg) => console.log(`${colors.yellow}⚠️  ${msg}${colors.reset}`),
  error: (msg) => console.log(`${colors.red}❌ ${msg}${colors.reset}`),
  step: (msg) => console.log(`${colors.cyan}${colors.bright}🚀 ${msg}${colors.reset}`),
  header: (msg) => {
    console.log('');
    console.log(`${colors.magenta}${'═'.repeat(60)}${colors.reset}`);
    console.log(`${colors.magenta}${colors.bright}  ${msg}${colors.reset}`);
    console.log(`${colors.magenta}${'═'.repeat(60)}${colors.reset}`);
    console.log('');
  }
};

class ChatForYouBuilder {
  constructor() {
    this.startTime = Date.now();
    this.rootDir = path.join(__dirname, '..');  // build-scripts에서 한 단계 위로
    this.srcDir = path.join(this.rootDir, 'src');
    this.nodejsDir = path.join(this.rootDir, '../nodejs-frontend');
    this.scriptsDir = __dirname;  // 현재 디렉토리가 build-scripts
    
    // 명령행 인수 파싱
    this.args = this.parseArgs();
    this.platform = this.args.platform || 'mac';
    this.env = this.args.env || 'prod';
    this.mode = this.args.mode || 'build';
  }

  parseArgs() {
    const args = process.argv.slice(2);
    const parsed = {};
    
    for (let i = 0; i < args.length; i++) {
      const arg = args[i];
      if (arg.startsWith('--')) {
        const key = arg.replace('--', '');
        const value = args[i + 1] && !args[i + 1].startsWith('--') ? args[++i] : true;
        parsed[key] = value;
      }
    }
    
    return parsed;
  }

  showBuildInfo() {
    try {
      // package.json 정보
      const pkg = JSON.parse(fs.readFileSync(path.join(this.rootDir, 'package.json'), 'utf8'));
      
      // Git 정보
      let gitInfo = { branch: 'unknown', commit: 'unknown', message: 'N/A' };
      try {
        gitInfo.branch = execSync('git rev-parse --abbrev-ref HEAD', { encoding: 'utf8' }).trim();
        gitInfo.commit = execSync('git rev-parse --short HEAD', { encoding: 'utf8' }).trim();
        gitInfo.message = execSync('git log -1 --pretty=%s', { encoding: 'utf8' }).trim();
      } catch (e) {}

      // 플랫폼 아이콘
      const icons = { mac: '🍎', win: '🪟', linux: '🐧', all: '🌍', dev: '🔧' };
      const icon = icons[this.platform] || '💻';

      log.header('ChatForYou Desktop Build');
      console.log(`${icon} 플랫폼: ${colors.bright}${this.platform.toUpperCase()}${colors.reset}`);
      console.log(`📦 버전: ${colors.bright}v${pkg.version}${colors.reset}`);
      console.log(`🌍 환경: ${colors.bright}${this.env.toUpperCase()}${colors.reset}`);
      console.log(`⏰ 시간: ${colors.bright}${new Date().toLocaleString('ko-KR')}${colors.reset}`);
      console.log(`📚 Git: ${colors.bright}${gitInfo.branch}@${gitInfo.commit}${colors.reset}`);
      console.log('');
    } catch (error) {
      log.warning('빌드 정보 표시 중 오류가 발생했습니다.');
    }
  }

  async syncFrontend() {
    log.step('1단계: 프론트엔드 동기화');
    
    try {
      const syncScript = path.join(this.scriptsDir, 'sync-frontend.js');
      const syncCmd = `node "${syncScript}" --env ${this.env}`;
      
      log.info('nodejs-frontend → chatforyou-desktop 동기화 시작...');
      execSync(syncCmd, { stdio: 'pipe' });
      log.success('프론트엔드 동기화 완료');
    } catch (error) {
      log.error('프론트엔드 동기화 실패');
      // 동기화 스크립트는 실패 원인(프론트 빌드 오류 포함)을 stdout 에 쓴다. 파이프로 받았으므로 여기서 남긴다.
      if (error.stdout && error.stdout.length) process.stdout.write(error.stdout);
      if (error.stderr && error.stderr.length) process.stderr.write(error.stderr);
      throw error;
    }
  }

  async compileSCSS() {
    log.step('2단계: SCSS 컴파일');
    
    try {
      const scssDir = path.join(this.srcDir, 'static/scss');
      const cssDir = path.join(this.srcDir, 'static/css');
      
      if (fs.existsSync(scssDir)) {
        log.info('SCSS 파일 컴파일 중...');
        const sassCmd = `npx sass "${scssDir}:${cssDir}" --style=compressed --no-source-map`;
        execSync(sassCmd, { stdio: 'pipe' });
        log.success('SCSS 컴파일 완료');
      } else {
        log.info('SCSS 파일이 없어 건너뜀');
      }
    } catch (error) {
      log.error('SCSS 컴파일 실패');
      throw error;
    }
  }

  async buildElectron() {
    log.step('3단계: Electron 앱 패키징');
    
    try {
      let builderCmd = 'npx electron-builder';
      
      // 플랫폼별 빌드 옵션
      switch (this.platform) {
        case 'mac':
          builderCmd += ' --mac';
          break;
        case 'win':
          builderCmd += ' --win';
          break;
        case 'linux':
          builderCmd += ' --linux';
          break;
        case 'all':
          // 모든 플랫폼 빌드 (Mac과 Windows)
          builderCmd += ' --mac --win';
          break;
        case 'pack':
          builderCmd += ' --dir';
          break;
        default:
          builderCmd += ` --${this.platform}`;
      }


    // publish 강제 비활성화
    builderCmd += ' --publish never';

      log.info(`Electron 앱 빌드 중... (${this.platform})`);
      execSync(builderCmd, { stdio: 'inherit' });
      log.success('Electron 앱 빌드 완료');
    } catch (error) {
      log.error('Electron 앱 빌드 실패');
      throw error;
    }
  }

  async runDev() {
    log.step('개발 모드 실행');
    
    try {
      await this.syncFrontend();
      await this.compileSCSS();
      
      log.info('Electron 개발 모드 시작...');
      const electronCmd = `npx electron . ${this.args.dev ? '--dev' : ''}`;
      execSync(electronCmd, { stdio: 'inherit' });
    } catch (error) {
      log.error('개발 모드 실행 실패');
      throw error;
    }
  }

  async build() {
    try {
      this.showBuildInfo();
      
      if (this.mode === 'dev' || this.mode === 'start') {
        await this.runDev();
        return;
      }

      // 빌드 과정
      await this.syncFrontend();
      await this.compileSCSS();
      await this.buildElectron();

      // 완료 메시지
      const duration = ((Date.now() - this.startTime) / 1000).toFixed(1);
      log.header('빌드 완료');
      log.success(`총 소요 시간: ${duration}초`);
      
      // 결과 파일 확인
      const distDir = path.join(this.rootDir, 'dist');
      if (fs.existsSync(distDir)) {
        const files = fs.readdirSync(distDir).filter(f => f.endsWith('.dmg') || f.endsWith('.exe') || f.endsWith('.AppImage'));
        if (files.length > 0) {
          log.info('생성된 파일:');
          files.forEach(file => console.log(`  📦 ${file}`));
        }
      }
      
    } catch (error) {
      const duration = ((Date.now() - this.startTime) / 1000).toFixed(1);
      log.header('빌드 실패');
      log.error(`오류: ${error.message}`);
      log.error(`소요 시간: ${duration}초`);
      process.exit(1);
    }
  }

  static showHelp() {
    console.log(`
ChatForYou Desktop 통합 빌드 스크립트

사용법:
  node build.js [옵션]

옵션:
  --platform <플랫폼>   빌드 플랫폼 (mac|win|linux|all|pack) [기본: mac]
  --env <환경>          빌드 환경 (prod|local|dev) [기본: prod]  
  --mode <모드>         실행 모드 (build|dev|start) [기본: build]
  --dev                 개발 모드 플래그
  --help               이 도움말 표시

예시:
  node build.js --platform mac --env prod     # Mac용 프로덕션 빌드
  node build.js --platform win --env prod     # Windows용 프로덕션 빌드
  node build.js --platform all --env prod     # 모든 플랫폼 빌드
  node build.js --mode dev --env local        # 개발 모드 실행
  node build.js --mode start --env local      # 일반 실행
`);
  }
}

// 메인 실행
if (require.main === module) {
  const builder = new ChatForYouBuilder();
  
  if (builder.args.help) {
    ChatForYouBuilder.showHelp();
    process.exit(0);
  }
  
  builder.build().catch(error => {
    console.error('치명적 오류:', error.message);
    process.exit(1);
  });
}

module.exports = ChatForYouBuilder;