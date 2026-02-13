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
    this.desktopSrcPath = path.join(__dirname, '../src');
    
    // 옵션 파싱
    this.options = this.parseOptions();
    
    // 로거 설정
    this.setupLogger();
    
    // 컴포넌트 초기화
    this.syncEngine = new SyncEngine({
      sourceDir: this.nodejsFrontendPath,
      targetDir: this.desktopSrcPath,
      logger: this.logger,
      ...this.options
    });
    
    this.pathConverter = new PathConverter({
      logger: this.logger,
      verbose: this.options.verbose,
      dryRun: this.options.dryRun,
      useAdvancedMode: false  // 기본 모드로 시작
    });
    
    this.configProcessor = new ConfigProcessor({
      logger: this.logger,
      verbose: this.options.verbose
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
      
      // 2단계: 경로 변환
      this.logger.info('🔄 2단계: 경로 변환 시작...');
      await this.convertPaths();
      
      // 3단계: 설정 파일 처리
      this.logger.info('🔄 3단계: 설정 파일 처리 시작...');
      await this.processConfigs();
      
      // 4단계: 빌드 준비
      this.logger.info('🔄 4단계: 빌드 준비...');
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
    
    // package.json 확인
    const packagePath = path.join(__dirname, '../package.json');
    if (fs.existsSync(packagePath)) {
      const pkg = JSON.parse(fs.readFileSync(packagePath, 'utf8'));
      this.logger.info(`📦 프로젝트: ${pkg.name} v${pkg.version}`);
    }
    
    this.logger.info('✅ 환경 검증 완료');
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
      if (!fs.existsSync(targetConfigDir)) {
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
    
    console.log(`📁 소스: ${this.nodejsFrontendPath}`);
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