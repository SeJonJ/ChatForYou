const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

/**
 * ChatForYou 프론트엔드 동기화 엔진
 * nodejs-frontend -> chatforyou-desktop 자동 동기화
 */
class SyncEngine {
  constructor(options = {}) {
    // 기본 경로 설정
    this.sourceDir = options.sourceDir || path.join(__dirname, '../../nodejs-frontend');
    this.targetDir = options.targetDir || path.join(__dirname, '../src');
    this.backupDir = options.backupDir || path.join(__dirname, '../.backup');
    
    // 동기화 옵션
    this.options = {
      createBackup: options.createBackup !== false,
      overwriteExisting: options.overwriteExisting !== false,
      validateChecksums: options.validateChecksums !== false,
      ...options
    };

    // 제외할 파일/디렉토리 패턴
    this.excludePatterns = [
      '.DS_Store',
      'Thumbs.db',
      '*.tmp',
      '*.log',
      'node_modules',
      '.git',
      '.gitignore',
      'build-test',  // nodejs-frontend의 build-test 디렉토리 제외
      '*.map'        // source map 파일 제외
    ];

    // 로깅
    this.logger = options.logger || console;
    
    // 통계
    this.stats = {
      filesProcessed: 0,
      filesSkipped: 0,
      filesConverted: 0,
      errors: 0,
      startTime: null,
      endTime: null
    };
  }

  /**
   * 메인 동기화 프로세스
   */
  async sync() {
    this.stats.startTime = new Date();
    this.logger.info('🚀 ChatForYou 프론트엔드 동기화 시작...');

    try {
      // 1. 사전 검증
      await this.validateDirectories();
      
      // 2. 백업 생성
      if (this.options.createBackup) {
        await this.createBackup();
      }
      
      // 3. static 폴더 동기화
      await this.syncStaticFiles();
      
      // 4. templates 폴더 동기화
      await this.syncTemplateFiles();
      
      // 5. config 파일 동기화
      await this.syncConfigFiles();
      
      // 6. 검증
      if (this.options.validateChecksums) {
        await this.validateSync();
      }
      
      this.stats.endTime = new Date();
      this.printSummary();
      
      return true;
      
    } catch (error) {
      this.logger.error('❌ 동기화 실패:', error.message);
      
      // 롤백 시도
      if (this.options.createBackup) {
        await this.rollback();
      }
      
      throw error;
    }
  }

  /**
   * 디렉토리 존재 여부 및 권한 검증
   */
  async validateDirectories() {
    const directories = [
      { path: this.sourceDir, name: 'nodejs-frontend' },
      { path: path.dirname(this.targetDir), name: 'chatforyou-desktop' }
    ];

    for (const dir of directories) {
      if (!fs.existsSync(dir.path)) {
        throw new Error(`❌ ${dir.name} 디렉토리를 찾을 수 없습니다: ${dir.path}`);
      }
      
      try {
        fs.accessSync(dir.path, fs.constants.R_OK | fs.constants.W_OK);
      } catch (error) {
        throw new Error(`❌ ${dir.name} 디렉토리 접근 권한이 없습니다: ${dir.path}`);
      }
    }

    this.logger.info('✅ 디렉토리 검증 완료');
  }

  /**
   * 백업 생성
   */
  async createBackup() {
    const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
    const backupPath = path.join(this.backupDir, `backup-${timestamp}`);
    
    this.logger.info('💾 백업 생성 중...');
    
    // 백업 디렉토리 생성
    await this.ensureDirectory(backupPath);
    
    // 기존 파일들 백업
    const targetPaths = [
      path.join(this.targetDir, 'static'),
      path.join(this.targetDir, 'templates'),
      path.join(this.targetDir, 'config')
    ];

    for (const targetPath of targetPaths) {
      if (fs.existsSync(targetPath)) {
        const backupTarget = path.join(backupPath, path.basename(targetPath));
        await this.copyDirectory(targetPath, backupTarget);
      }
    }
    
    this.backupPath = backupPath;
    this.logger.info(`✅ 백업 완료: ${backupPath}`);
  }

  /**
   * static 폴더 동기화
   */
  async syncStaticFiles() {
    this.logger.info('📁 static 폴더 동기화 중...');
    
    const sourceStatic = path.join(this.sourceDir, 'static');
    const targetStatic = path.join(this.targetDir, 'static');
    
    if (!fs.existsSync(sourceStatic)) {
      throw new Error('❌ nodejs-frontend/static 폴더를 찾을 수 없습니다');
    }
    
    await this.copyDirectory(sourceStatic, targetStatic);
    this.logger.info('✅ static 폴더 동기화 완료');
  }

  /**
   * templates 폴더 동기화
   */
  async syncTemplateFiles() {
    this.logger.info('📄 templates 폴더 동기화 중...');
    
    const sourceTemplates = path.join(this.sourceDir, 'templates');
    const targetTemplates = path.join(this.targetDir, 'templates');
    
    if (!fs.existsSync(sourceTemplates)) {
      throw new Error('❌ nodejs-frontend/templates 폴더를 찾을 수 없습니다');
    }
    
    await this.copyDirectory(sourceTemplates, targetTemplates);
    this.logger.info('✅ templates 폴더 동기화 완료');
  }

  /**
   * config 파일 동기화
   */
  async syncConfigFiles() {
    this.logger.info('⚙️ config 파일 동기화 중...');
    
    const sourceConfig = path.join(this.sourceDir, 'config');
    const targetConfig = path.join(this.targetDir, 'config');
    
    if (!fs.existsSync(sourceConfig)) {
      this.logger.warn('⚠️ nodejs-frontend/config 폴더를 찾을 수 없습니다. 건너뜁니다.');
      return;
    }
    
    await this.copyDirectory(sourceConfig, targetConfig);
    this.logger.info('✅ config 파일 동기화 완료');
  }

  /**
   * 디렉토리 재귀 복사
   */
  async copyDirectory(sourceDir, targetDir) {
    await this.ensureDirectory(targetDir);
    
    const entries = fs.readdirSync(sourceDir, { withFileTypes: true });
    
    for (const entry of entries) {
      // 제외 패턴 확인
      if (this.shouldExclude(entry.name)) {
        if (this.options.verbose) {
          this.logger.debug(`⏭️  제외됨: ${entry.name}`);
        }
        continue;
      }
      
      const sourcePath = path.join(sourceDir, entry.name);
      const targetPath = path.join(targetDir, entry.name);
      
      if (entry.isDirectory()) {
        await this.copyDirectory(sourcePath, targetPath);
      } else {
        await this.copyFile(sourcePath, targetPath);
      }
    }
  }

  /**
   * 파일/디렉토리 제외 여부 확인
   */
  shouldExclude(fileName) {
    return this.excludePatterns.some(pattern => {
      if (pattern.includes('*')) {
        // 와일드카드 패턴 처리
        const regex = new RegExp('^' + pattern.replace(/\*/g, '.*') + '$');
        return regex.test(fileName);
      } else {
        // 정확한 매치
        return fileName === pattern;
      }
    });
  }

  /**
   * 파일 복사
   */
  async copyFile(sourcePath, targetPath) {
    try {
      // 파일 존재 여부 확인
      if (fs.existsSync(targetPath) && !this.options.overwriteExisting) {
        this.stats.filesSkipped++;
        return;
      }
      
      // 파일 복사
      fs.copyFileSync(sourcePath, targetPath);
      this.stats.filesProcessed++;
      
      // 진행 상황 출력 (100개 파일마다)
      if (this.stats.filesProcessed % 100 === 0) {
        this.logger.info(`📋 진행 상황: ${this.stats.filesProcessed}개 파일 처리됨`);
      }
      
    } catch (error) {
      this.stats.errors++;
      this.logger.error(`❌ 파일 복사 실패: ${sourcePath} -> ${targetPath}`, error.message);
      throw error;
    }
  }

  /**
   * 디렉토리 생성
   */
  async ensureDirectory(dirPath) {
    if (!fs.existsSync(dirPath)) {
      fs.mkdirSync(dirPath, { recursive: true });
    }
  }

  /**
   * 동기화 검증
   */
  async validateSync() {
    this.logger.info('🔍 동기화 결과 검증 중...');
    
    const validationPairs = [
      {
        source: path.join(this.sourceDir, 'static'),
        target: path.join(this.targetDir, 'static')
      },
      {
        source: path.join(this.sourceDir, 'templates'), 
        target: path.join(this.targetDir, 'templates')
      }
    ];

    for (const pair of validationPairs) {
      await this.validateDirectorySync(pair.source, pair.target);
    }
    
    this.logger.info('✅ 동기화 검증 완료');
  }

  /**
   * 디렉토리 동기화 검증
   */
  async validateDirectorySync(sourceDir, targetDir) {
    if (!fs.existsSync(sourceDir) || !fs.existsSync(targetDir)) {
      return;
    }

    const sourceFiles = this.getFileList(sourceDir);
    const targetFiles = this.getFileList(targetDir);
    
    // 파일 개수 차이 허용 범위 (SCSS 컴파일된 CSS 파일 등 고려)
    const tolerance = 5;
    const difference = Math.abs(sourceFiles.length - targetFiles.length);
    
    if (difference > tolerance) {
      this.logger.warn(`⚠️ 파일 개수 차이 발견: ${sourceDir} (${sourceFiles.length}) vs ${targetDir} (${targetFiles.length})`);
      
      // 상세 비교를 위한 디버그 정보
      if (this.options.verbose) {
        const sourceFileNames = sourceFiles.map(f => path.relative(sourceDir, f)).sort();
        const targetFileNames = targetFiles.map(f => path.relative(targetDir, f)).sort();
        
        const extraInTarget = targetFileNames.filter(f => !sourceFileNames.includes(f));
        const missingInTarget = sourceFileNames.filter(f => !targetFileNames.includes(f));
        
        if (extraInTarget.length > 0) {
          this.logger.debug(`📁 타겟에만 있는 파일: ${extraInTarget.join(', ')}`);
        }
        if (missingInTarget.length > 0) {
          this.logger.debug(`📁 소스에만 있는 파일: ${missingInTarget.join(', ')}`);
        }
      }
    } else {
      this.logger.info(`✅ 파일 개수 검증 통과 (소스: ${sourceFiles.length}, 타겟: ${targetFiles.length})`);
    }
  }

  /**
   * 파일 목록 가져오기
   */
  getFileList(dirPath, fileList = []) {
    const entries = fs.readdirSync(dirPath, { withFileTypes: true });
    
    for (const entry of entries) {
      // 제외 패턴 확인
      if (this.shouldExclude(entry.name)) {
        continue;
      }
      
      const fullPath = path.join(dirPath, entry.name);
      
      if (entry.isDirectory()) {
        this.getFileList(fullPath, fileList);
      } else {
        fileList.push(fullPath);
      }
    }
    
    return fileList;
  }

  /**
   * 롤백
   */
  async rollback() {
    if (!this.backupPath || !fs.existsSync(this.backupPath)) {
      this.logger.warn('⚠️ 백업을 찾을 수 없어 롤백할 수 없습니다');
      return;
    }
    
    this.logger.info('🔄 롤백 진행 중...');
    
    const backupPaths = [
      { backup: path.join(this.backupPath, 'static'), target: path.join(this.targetDir, 'static') },
      { backup: path.join(this.backupPath, 'templates'), target: path.join(this.targetDir, 'templates') },
      { backup: path.join(this.backupPath, 'config'), target: path.join(this.targetDir, 'config') }
    ];

    for (const pathPair of backupPaths) {
      if (fs.existsSync(pathPair.backup)) {
        // 현재 파일 삭제
        if (fs.existsSync(pathPair.target)) {
          fs.rmSync(pathPair.target, { recursive: true, force: true });
        }
        
        // 백업에서 복원
        await this.copyDirectory(pathPair.backup, pathPair.target);
      }
    }
    
    this.logger.info('✅ 롤백 완료');
  }

  /**
   * 요약 정보 출력
   */
  printSummary() {
    const duration = this.stats.endTime - this.stats.startTime;
    
    this.logger.info('\n📊 동기화 완료 요약:');
    this.logger.info(`⏱️  소요 시간: ${duration}ms`);
    this.logger.info(`📁 처리된 파일: ${this.stats.filesProcessed}개`);
    this.logger.info(`⏭️  건너뛴 파일: ${this.stats.filesSkipped}개`);
    this.logger.info(`🔄 변환된 파일: ${this.stats.filesConverted}개`);
    this.logger.info(`❌ 오류: ${this.stats.errors}개`);
    
    if (this.stats.errors === 0) {
      this.logger.info('🎉 동기화가 성공적으로 완료되었습니다!');
    }
  }
}

module.exports = SyncEngine;