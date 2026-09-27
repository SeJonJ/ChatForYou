#!/usr/bin/env node

/**
 * ChatForYou Frontend Sync Script
 * nodejs-frontend -> chatforyou-desktop 자동 동기화 및 빌드 통합
 */

const path = require('path');
const fs = require('fs');

// 사용자 정의 모듈들
const SyncEngine = require('./lib/sync-engine');
const PathConverter = require('./lib/convert_path');
const ConfigProcessor = require('./lib/config-processor');

/**
 * 메인 동기화 클래스
 */
class FrontendSyncScript {
  constructor() {
    // 프로젝트 경로 설정
    this.projectRoot = path.join(__dirname, '../..');
    this.nodejsFrontendPath = path.join(this.projectRoot, 'nodejs-frontend');
    // 동기화 소스는 소스 트리가 아니라 vite build 산출물이다. 소스 트리 경로는
    // 환경별 config 원본(config.local.js/config.prod.js)을 읽을 때만 쓴다.
    this.frontendDistPath = path.join(this.nodejsFrontendPath, 'dist');
    this.desktopSrcPath = path.join(__dirname, '../src');
    
    // 옵션 파싱
    this.options = this.parseOptions();
    
    // 로거 설정
    this.setupLogger();
    
    // 컴포넌트 초기화
    this.syncEngine = new SyncEngine({
      sourceDir: this.frontendDistPath,
      targetDir: this.desktopSrcPath,
      logger: this.logger,
      ...this.options,
      createBackup: !this.options.dryRun && !this.options.skipBackup
    });
    
    this.pathConverter = new PathConverter({
      logger: this.logger,
      verbose: this.options.verbose,
      dryRun: this.options.dryRun,
      useAdvancedMode: false  // 기본 모드로 시작
    });
    
    this.configProcessor = new ConfigProcessor({
      logger: this.logger,
      verbose: this.options.verbose,
      dryRun: this.options.dryRun,
      createBackup: !this.options.dryRun && !this.options.skipBackup
    });
  }

  /**
   * 메인 실행 함수
   */
  async run() {
    const startTime = Date.now();
    
    try {
      this.printHeader();
      
      // 사전 검증
      await this.validateEnvironment();
      
      // 1단계: 파일 동기화
      this.logger.info('🔄 1단계: 파일 동기화 시작...');
      await this.syncEngine.sync();
      
      // 2단계: 빌드 산출물 동기화
      this.logger.info('🔄 2단계: 빌드 산출물 동기화 시작...');
      await this.syncBuildOutput();
      await this.pruneBundledSources();

      // 3단계: 경로 변환
      this.logger.info('🔄 3단계: 경로 변환 시작...');
      await this.convertPaths();
      
      // 4단계: 설정 파일 처리
      this.logger.info('🔄 4단계: 설정 파일 처리 시작...');
      await this.processConfigs();
      
      // 5단계: 빌드 준비
      this.logger.info('🔄 5단계: 빌드 준비...');
      await this.prepareBuild();
      
      // 완료 보고
      this.printSuccess(startTime);
      
      return true;
      
    } catch (error) {
      this.printError(error, startTime);
      process.exit(1);
    }
  }

  /**
   * 동기화 직전에 nodejs-frontend 를 빌드해 dist 를 현재 소스와 맞춘다.
   * dist 는 gitignore 대상 파생물이라, 빌드를 건너뛰면 마지막으로 빌드한 시점의 화면이 그대로 패키징된다.
   * --dry-run 은 파일을 바꾸지 않아야 하므로 빌드하지 않고, 방금 빌드한 호출자는 --skip-build 로 생략한다.
   */
  buildFrontend() {
    if (this.options.dryRun || this.options.skipBuild) {
      this.logger.info('⏭️  프론트엔드 빌드 생략 (기존 dist 사용)');
      return;
    }

    this.logger.info('🏗️  nodejs-frontend 빌드 중 (npm run build)...');
    const { execSync } = require('child_process');
    try {
      // 프론트 빌드에는 GitHub 토큰이 필요 없다. 호출 환경에 있더라도 빌드 도구에 넘기지 않는다.
      const env = { ...process.env };
      delete env.GH_TOKEN;
      delete env.GITHUB_TOKEN;
      execSync('npm run build', { cwd: this.nodejsFrontendPath, stdio: 'pipe', env });
    } catch (error) {
      const output = `${error.stdout || ''}${error.stderr || ''}`.trim().split('\n').slice(-20).join('\n');
      throw new Error(`❌ nodejs-frontend 빌드 실패 (npm run build)\n${output}`);
    }
    this.logger.info('✅ nodejs-frontend 빌드 완료');
  }

  /**
   * 환경 검증
   */
  async validateEnvironment() {
    this.logger.info('🔍 환경 검증 중...');
    
    // Node.js 버전 확인
    const nodeVersion = process.version;
    this.logger.info(`📟 Node.js 버전: ${nodeVersion}`);
    
    // 필수 디렉토리 확인
    const requiredDirs = [
      { path: this.nodejsFrontendPath, name: 'nodejs-frontend' },
      { path: path.dirname(this.desktopSrcPath), name: 'chatforyou-desktop' }
    ];
    
    for (const dir of requiredDirs) {
      if (!fs.existsSync(dir.path)) {
        throw new Error(`❌ 필수 디렉토리를 찾을 수 없습니다: ${dir.name} (${dir.path})`);
      }
      this.logger.info(`✅ ${dir.name} 디렉토리 확인됨`);
    }

    this.buildFrontend();

    // dist 가 없으면 빈 동기화로 데스크톱 자산을 비워버리게 되므로 여기서 멈춘다.
    if (!fs.existsSync(this.frontendDistPath)) {
      throw new Error(
        `❌ 빌드 산출물을 찾을 수 없습니다: ${this.frontendDistPath}\n` +
        '   --skip-build 로 실행했다면 nodejs-frontend 에서 npm run build 를 먼저 실행하세요.'
      );
    }
    // 표식이 없으면 빌드 중이거나 도중에 실패한 dist 다. 이런 dist 를 쓰면 빠진 파일을
    // 번들로 흡수된 것으로 보고 데스크톱 사본을 지우게 된다. 존재 확인과 stamp 읽기를 한 번에 해서
    // 그 사이에 표식이 사라지는 틈을 없앤다.
    this.distStamp = this.readDistStamp();
    if (this.distStamp === null) {
      throw new Error(
        `❌ 완성되지 않은 빌드 산출물입니다 (완료 표식 없음): ${this.frontendDistPath}\n` +
        '   빌드가 실패했거나 진행 중입니다. nodejs-frontend 에서 npm run build 를 다시 실행하세요.'
      );
    }
    this.logger.info('✅ nodejs-frontend/dist 확인됨');
    
    // package.json 확인
    const packagePath = path.join(__dirname, '../package.json');
    if (fs.existsSync(packagePath)) {
      const pkg = JSON.parse(fs.readFileSync(packagePath, 'utf8'));
      this.logger.info(`📦 프로젝트: ${pkg.name} v${pkg.version}`);
    }
    
    this.logger.info('✅ 환경 검증 완료');
  }

  /**
   * 해시된 모듈 청크(dist/build) 를 데스크톱 src 로 동기화한다.
   *
   * SyncEngine 은 static/templates/config 만 다루고, 파일명이 매 빌드마다 바뀌므로
   * 기존 build 디렉터리를 지우고 새로 복사한다. 청크는 이미 최종 산출물이라
   * 경로 변환 대상이 아니다.
   */
  buildMarkerPath() {
    return path.join(this.frontendDistPath, '.build-complete');
  }

  readDistStamp() {
    try {
      const stat = fs.statSync(this.buildMarkerPath());
      return `${stat.ino}:${stat.mtimeMs}`;
    } catch (error) {
      return null;
    }
  }

  /**
   * 환경 검증 이후 다른 빌드가 dist 를 비우고 다시 쓰기 시작했는지 확인한다.
   * 표식은 빌드 시작 때 지워지고 끝날 때 새로 쓰이므로, 사라졌거나 바뀌었으면 지금 읽는 dist 는
   * 처음 확인한 산출물이 아니다. 파일을 지우거나 덮기 전에 멈춘다.
   */
  assertDistUnchanged() {
    if (this.options.dryRun) {
      return;
    }
    // 표식이 없는 상태끼리 같다고 보면 빌드가 도는 내내 확인을 통과하므로, 없음은 항상 변경으로 본다.
    const current = this.readDistStamp();
    if (current === null || current !== this.distStamp) {
      throw new Error('❌ 동기화 중에 nodejs-frontend/dist 가 다시 빌드되었습니다. 빌드가 끝난 뒤 다시 실행하세요.');
    }
  }

  async syncBuildOutput() {
    const sourceBuildDir = path.join(this.frontendDistPath, 'build');
    const targetBuildDir = path.join(this.desktopSrcPath, 'build');

    if (!fs.existsSync(sourceBuildDir)) {
      throw new Error(`❌ 빌드 청크 디렉토리를 찾을 수 없습니다: ${sourceBuildDir}`);
    }

    if (this.options.dryRun) {
      this.logger.info(`📝 dry-run: ${sourceBuildDir} → ${targetBuildDir} 복사를 건너뜁니다`);
      return;
    }
    this.assertDistUnchanged();

    fs.rmSync(targetBuildDir, { recursive: true, force: true });
    fs.cpSync(sourceBuildDir, targetBuildDir, { recursive: true });

    const chunkCount = fs.readdirSync(targetBuildDir).length;
    this.logger.info(`✅ 빌드 산출물 동기화 완료 (${chunkCount}개 파일)`);
  }

  /**
   * Desktop 에만 있어도 되는 파일 목록. verify-desktop-sync.sh 와 같은 목록을 기준으로 삼는다.
   * 목록 없이 정리하면 Desktop 전용 파일까지 지우게 되므로 파일이 없으면 멈춘다.
   */
  readDesktopOnlyBaseline() {
    const baselinePath = path.join(this.projectRoot, 'scripts', 'desktop-sync-extra-baseline.txt');
    if (!fs.existsSync(baselinePath)) {
      throw new Error(`❌ Desktop 전용 baseline 을 찾을 수 없습니다: ${baselinePath}`);
    }
    return new Set(
      fs.readFileSync(baselinePath, 'utf8')
        .split(/\r?\n/)
        .map((line) => line.trim())
        .filter((line) => line && !line.startsWith('#'))
    );
  }

  /**
   * dist 에 없고 Desktop 전용 baseline 에도 없는 Desktop 사본을 지운다.
   * SyncEngine 은 추가·갱신만 하므로, 모듈 번들로 흡수됐거나 web 에서 삭제된 파일의 사본은
   * 지우지 않으면 계속 남아 패키징되고 동기화 검증에서도 drift 로 잡힌다.
   */
  async pruneBundledSources() {
    this.assertDistUnchanged();
    const desktopOnly = this.readDesktopOnlyBaseline();
    const stale = [];
    for (const dir of ['static', 'templates']) {
      const walk = (relative) => {
        const desktopPath = path.join(this.desktopSrcPath, relative);
        for (const entry of fs.readdirSync(desktopPath, { withFileTypes: true })) {
          const child = path.join(relative, entry.name);
          if (entry.isDirectory()) {
            walk(child);
          } else if (!fs.existsSync(path.join(this.frontendDistPath, child))
                     && !desktopOnly.has(child.split(path.sep).join('/'))) {
            stale.push(child);
          }
        }
      };
      if (fs.existsSync(path.join(this.desktopSrcPath, dir))) {
        walk(dir);
      }
    }

    if (stale.length === 0) {
      return;
    }
    if (this.options.dryRun) {
      this.logger.info(`📝 dry-run: dist 에 없는 Desktop 사본 ${stale.length}개 삭제를 건너뜁니다: ${stale.join(', ')}`);
      return;
    }
    // 삭제 목록은 dist 를 훑어 만든다. 훑는 동안 재빌드가 dist 를 비우면 전부 stale 로 잡히므로
    // 목록을 확정한 뒤 지우기 직전에 한 번 더 확인한다.
    this.assertDistUnchanged();

    for (const relative of stale) {
      fs.rmSync(path.join(this.desktopSrcPath, relative), { force: true });
      // 비게 된 상위 디렉터리도 web 쪽에 없는 구조이므로 static/templates 루트 직전까지 정리한다.
      let parent = path.dirname(relative);
      while (parent.includes(path.sep)) {
        const parentPath = path.join(this.desktopSrcPath, parent);
        if (fs.existsSync(path.join(this.frontendDistPath, parent)) || fs.readdirSync(parentPath).length > 0) {
          break;
        }
        fs.rmdirSync(parentPath);
        parent = path.dirname(parent);
      }
    }
    this.logger.info(`🧹 dist 에 없는 Desktop 사본 ${stale.length}개 삭제: ${stale.join(', ')}`);
  }

  /**
   * 경로 변환 실행
   */
  async convertPaths() {
    const targetDirs = [
      path.join(this.desktopSrcPath, 'static'),
      path.join(this.desktopSrcPath, 'templates')
    ];
    
    for (const targetDir of targetDirs) {
      if (fs.existsSync(targetDir)) {
        this.logger.info(`🔧 경로 변환: ${path.basename(targetDir)}`);
        await this.pathConverter.convertDirectory(targetDir);
      }
    }
  }

  /**
   * 설정 파일 처리
   */
  async processConfigs() {
    const sourceConfigDir = path.join(this.nodejsFrontendPath, 'config');
    const targetConfigDir = path.join(this.desktopSrcPath, 'config');
    
    // 환경 설정 결정 (기본값: local)
    const environment = this.options.environment || 'local';
    
    if (fs.existsSync(sourceConfigDir)) {
      await this.configProcessor.convertWebConfigToElectron(sourceConfigDir, targetConfigDir, environment);
    } else {
      this.logger.warn('⚠️ nodejs-frontend/config 디렉토리를 찾을 수 없습니다. 기본 설정으로 진행합니다.');
      
      // 기본 설정 생성
      if (!this.options.dryRun && !fs.existsSync(targetConfigDir)) {
        fs.mkdirSync(targetConfigDir, { recursive: true });
      }
      
      await this.configProcessor.createDefaultConfig(
        path.join(targetConfigDir, 'config.js'), 
        environment
      );
    }
  }

  /**
   * 빌드 준비
   */
  async prepareBuild() {
    // SCSS 컴파일 확인
    const scssDir = path.join(this.desktopSrcPath, 'static/scss');
    if (fs.existsSync(scssDir)) {
      this.logger.info('📋 SCSS 파일 감지됨 - 빌드 시 컴파일 예정');
    }
    
    // Electron 메인 프로세스 파일 확인
    const mainFile = path.join(this.desktopSrcPath, 'main/electron-main.js');
    if (fs.existsSync(mainFile)) {
      this.logger.info('✅ Electron 메인 프로세스 확인됨');
    }

    if (this.options.dryRun) {
      this.logger.info('📝 dry-run: 빌드 정보 파일 생성을 건너뜁니다');
      return;
    }
    
    // 빌드 상태 파일 생성
    const buildInfo = {
      syncedAt: new Date().toISOString(),
      sourceCommit: await this.getGitCommit(this.nodejsFrontendPath),
      version: this.getAppVersion(),
      environment: this.options.environment || 'development'
    };
    
    const buildInfoPath = path.join(this.desktopSrcPath, '.build-info.json');
    fs.writeFileSync(buildInfoPath, JSON.stringify(buildInfo, null, 2));
    
    this.logger.info('📝 빌드 정보 파일 생성됨');
  }

  /**
   * Git 커밋 해시 가져오기
   */
  async getGitCommit(repoPath) {
    try {
      const { execSync } = require('child_process');
      const commit = execSync('git rev-parse HEAD', { 
        cwd: repoPath, 
        encoding: 'utf8' 
      }).trim();
      return commit.substring(0, 8);
    } catch (error) {
      return 'unknown';
    }
  }

  /**
   * 앱 버전 가져오기
   */
  getAppVersion() {
    try {
      const packagePath = path.join(__dirname, '../package.json');
      const pkg = JSON.parse(fs.readFileSync(packagePath, 'utf8'));
      return pkg.version || '1.0.0';
    } catch (error) {
      return '1.0.0';
    }
  }

  /**
   * 명령행 옵션 파싱
   */
  parseOptions() {
    const args = process.argv.slice(2);
    const options = {
      verbose: false,
      dryRun: false,
      skipBackup: false,
      skipBuild: false,
      environment: null
    };
    
    for (let i = 0; i < args.length; i++) {
      const arg = args[i];
      
      switch (arg) {
        case '--verbose':
        case '-v':
          options.verbose = true;
          break;
          
        case '--dry-run':
        case '-d':
          options.dryRun = true;
          break;
          
        case '--skip-backup':
          options.skipBackup = true;
          break;

        case '--skip-build':
          options.skipBuild = true;
          break;
          
        case '--env':
        case '-e':
          options.environment = args[++i];
          break;
          
        case '--help':
        case '-h':
          this.printHelp();
          process.exit(0);
          break;
          
        default:
          if (arg.startsWith('-')) {
            console.warn(`⚠️ 알 수 없는 옵션: ${arg}`);
          }
      }
    }
    
    return options;
  }

  /**
   * 로거 설정
   */
  setupLogger() {
    const logLevel = this.options.verbose ? 'debug' : 'info';
    
    this.logger = {
      info: (message) => {
        const timestamp = new Date().toISOString().split('T')[1].split('.')[0];
        console.log(`[${timestamp}] ℹ️  ${message}`);
      },
      warn: (message) => {
        const timestamp = new Date().toISOString().split('T')[1].split('.')[0];
        console.warn(`[${timestamp}] ⚠️  ${message}`);
      },
      error: (message, error = null) => {
        const timestamp = new Date().toISOString().split('T')[1].split('.')[0];
        console.error(`[${timestamp}] ❌ ${message}`);
        if (error && this.options.verbose) {
          console.error(error);
        }
      },
      debug: (message) => {
        if (this.options.verbose) {
          const timestamp = new Date().toISOString().split('T')[1].split('.')[0];
          console.log(`[${timestamp}] 🔍 ${message}`);
        }
      }
    };
  }

  /**
   * 헤더 출력
   */
  printHeader() {
    console.log('\n' + '='.repeat(60));
    console.log('🚀 ChatForYou Frontend Sync Script');
    console.log('   nodejs-frontend → chatforyou-desktop');
    console.log('='.repeat(60));
    
    if (this.options.dryRun) {
      console.log('⚠️  DRY RUN 모드 - 실제 파일 변경 없음');
    }
    
    console.log(`📁 소스: ${this.frontendDistPath}`);
    console.log(`📁 타겟: ${this.desktopSrcPath}`);
    console.log('');
  }

  /**
   * 성공 메시지 출력
   */
  printSuccess(startTime) {
    const duration = Date.now() - startTime;
    
    console.log('\n' + '='.repeat(60));
    console.log('🎉 동기화 성공!');
    console.log(`⏱️  총 소요 시간: ${duration}ms`);
    console.log('='.repeat(60));
    
    if (!this.options.dryRun) {
      console.log('\n📋 다음 단계:');
      console.log('   npm run scss:build  # SCSS 컴파일');
      console.log('   npm run build       # Electron 앱 빌드');
      console.log('   npm run start       # 개발 모드 실행');
    }
    
    console.log('');
  }

  /**
   * 에러 메시지 출력
   */
  printError(error, startTime) {
    const duration = Date.now() - startTime;
    
    console.log('\n' + '='.repeat(60));
    console.log('❌ 동기화 실패!');
    console.log(`💥 오류: ${error.message}`);
    console.log(`⏱️  소요 시간: ${duration}ms`);
    console.log('='.repeat(60));
    
    if (this.options.verbose && error.stack) {
      console.log('\n📋 상세 오류:');
      console.log(error.stack);
    }
    
    console.log('\n🛠️  해결 방법:');
    console.log('   1. 경로가 올바른지 확인하세요');
    console.log('   2. 파일 권한을 확인하세요');
    console.log('   3. --verbose 옵션으로 상세 로그를 확인하세요');
    console.log('   4. --dry-run 옵션으로 미리보기를 해보세요');
    console.log('');
  }

  /**
   * 도움말 출력
   */
  printHelp() {
    console.log(`
ChatForYou Frontend Sync Script

사용법:
  node sync-frontend.js [옵션]

옵션:
  -v, --verbose      상세 로그 출력
  -d, --dry-run      실제 변경 없이 미리보기만
  --skip-backup      백업 생성 건너뛰기
  --skip-build       동기화 전 nodejs-frontend 빌드 건너뛰기 (방금 빌드한 경우)
  -e, --env <환경>   환경 설정 (local|prod)
  -h, --help         이 도움말 출력

예시:
  node sync-frontend.js --verbose
  node sync-frontend.js --dry-run --env prod
  node sync-frontend.js --skip-backup
`);
  }
}

// 스크립트 실행
if (require.main === module) {
  const script = new FrontendSyncScript();
  script.run().catch(error => {
    console.error('❌ 치명적 오류:', error.message);
    process.exit(1);
  });
}

module.exports = FrontendSyncScript;
