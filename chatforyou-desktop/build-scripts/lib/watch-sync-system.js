const fs = require('fs');
const path = require('path');
const { EventEmitter } = require('events');

/**
 * 실시간 워치 및 동기화 시스템
 * 파일 변경 감지, 지능형 동기화, 성능 최적화
 */
class WatchSyncSystem extends EventEmitter {
  constructor(options = {}) {
    super();
    
    this.options = {
      verbose: options.verbose || false,
      debounceMs: options.debounceMs || 300,
      batchSize: options.batchSize || 10,
      enableSmartSync: options.enableSmartSync !== false,
      excludePatterns: options.excludePatterns || [],
      ...options
    };

    this.logger = options.logger || console;
    
    // 컴포넌트들
    this.syncEngine = options.syncEngine;
    this.pathConverter = options.pathConverter;
    this.scssBuilder = options.scssBuilder;
    this.fileDetector = options.fileDetector;
    
    // 워치 상태
    this.isWatching = false;
    this.watchers = new Map();
    this.changeQueue = new Map();
    this.processingQueue = false;
    
    // 디바운스 타이머
    this.debounceTimers = new Map();
    
    // 파일 상태 추적
    this.fileStates = new Map();
    this.lastSyncTime = new Date();
    
    // 성능 통계
    this.stats = {
      changesDetected: 0,
      filesProcessed: 0,
      batchesProcessed: 0,
      totalSyncTime: 0,
      errors: 0,
      smartSyncSkips: 0
    };

    // 의존성 그래프 (파일 간 관계 추적)
    this.dependencyGraph = new Map();
  }

  /**
   * 워치 모드 시작
   */
  async startWatching(sourceDir, targetDir) {
    if (this.isWatching) {
      this.logger.warn('⚠️ 이미 워치 모드가 실행 중입니다.');
      return;
    }

    this.logger.info(`👁️  실시간 워치 시작: ${sourceDir} -> ${targetDir}`);
    
    try {
      // 초기 동기화
      await this.performInitialSync(sourceDir, targetDir);
      
      // 파일 워처 설정
      await this.setupWatchers(sourceDir, targetDir);
      
      // 의존성 그래프 초기화
      await this.buildInitialDependencyGraph(sourceDir);
      
      this.isWatching = true;
      this.emit('watch-started', { sourceDir, targetDir });
      
      this.logger.info('🔍 파일 변경 감시 중... (Ctrl+C로 종료)');
      
    } catch (error) {
      this.logger.error('❌ 워치 모드 시작 실패:', error.message);
      throw error;
    }
  }

  /**
   * 초기 동기화 수행
   */
  async performInitialSync(sourceDir, targetDir) {
    this.logger.info('🔄 초기 동기화 수행 중...');
    
    try {
      // 1. 파일 동기화
      if (this.syncEngine) {
        await this.syncEngine.sync();
      }
      
      // 2. 경로 변환
      if (this.pathConverter) {
        const targetDirs = [
          path.join(targetDir, 'static'),
          path.join(targetDir, 'templates')
        ];
        
        for (const dir of targetDirs) {
          if (fs.existsSync(dir)) {
            await this.pathConverter.convertDirectory(dir);
          }
        }
      }
      
      // 3. SCSS 빌드
      if (this.scssBuilder) {
        const scssDir = path.join(targetDir, 'static/scss');
        const cssDir = path.join(targetDir, 'static/css');
        
        if (fs.existsSync(scssDir)) {
          await this.scssBuilder.buildProject(scssDir, cssDir);
        }
      }
      
      this.logger.info('✅ 초기 동기화 완료');
      
    } catch (error) {
      this.logger.error('❌ 초기 동기화 실패:', error.message);
      throw error;
    }
  }

  /**
   * 파일 워처 설정
   */
  async setupWatchers(sourceDir, targetDir) {
    // chokidar 사용 (설치되어 있을 경우)
    let chokidar;
    try {
      chokidar = require('chokidar');
    } catch (error) {
      // fs.watch 사용 (fallback)
      return this.setupFsWatchers(sourceDir, targetDir);
    }

    const watchOptions = {
      ignored: this.buildIgnorePatterns(),
      persistent: true,
      ignoreInitial: true,
      followSymlinks: false,
      usePolling: false,
      awaitWriteFinish: {
        stabilityThreshold: 100,
        pollInterval: 50
      }
    };

    const watcher = chokidar.watch(sourceDir, watchOptions);
    
    watcher
      .on('add', (filePath) => this.handleFileChange('add', filePath, sourceDir, targetDir))
      .on('change', (filePath) => this.handleFileChange('change', filePath, sourceDir, targetDir))
      .on('unlink', (filePath) => this.handleFileChange('unlink', filePath, sourceDir, targetDir))
      .on('addDir', (dirPath) => this.handleDirectoryChange('addDir', dirPath, sourceDir, targetDir))
      .on('unlinkDir', (dirPath) => this.handleDirectoryChange('unlinkDir', dirPath, sourceDir, targetDir))
      .on('error', (error) => {
        this.logger.error('❌ 파일 워치 에러:', error.message);
        this.stats.errors++;
      });

    this.watchers.set(sourceDir, watcher);
    
    if (this.options.verbose) {
      this.logger.debug(`🔧 Chokidar 워처 설정 완료: ${sourceDir}`);
    }
  }

  /**
   * fs.watch 기반 워처 설정 (fallback)
   */
  setupFsWatchers(sourceDir, targetDir) {
    const setupRecursiveWatch = (dir) => {
      try {
        const watcher = fs.watch(dir, { recursive: true }, (eventType, filename) => {
          if (!filename) return;
          
          const fullPath = path.join(dir, filename);
          this.handleFileChange(eventType, fullPath, sourceDir, targetDir);
        });
        
        this.watchers.set(dir, watcher);
        
        if (this.options.verbose) {
          this.logger.debug(`🔧 fs.watch 설정 완료: ${dir}`);
        }
        
      } catch (error) {
        this.logger.warn(`⚠️ 워처 설정 실패: ${dir}`, error.message);
      }
    };

    setupRecursiveWatch(sourceDir);
  }

  /**
   * 파일 변경 처리
   */
  async handleFileChange(eventType, filePath, sourceDir, targetDir) {
    // 무시할 파일인지 확인
    if (this.shouldIgnoreFile(filePath)) {
      return;
    }

    const relativePath = path.relative(sourceDir, filePath);
    this.stats.changesDetected++;
    
    if (this.options.verbose) {
      this.logger.debug(`📝 파일 ${eventType}: ${relativePath}`);
    }

    // 변경 정보를 큐에 추가
    const changeInfo = {
      eventType,
      filePath,
      relativePath,
      sourceDir,
      targetDir,
      timestamp: Date.now(),
      processed: false
    };

    this.addToChangeQueue(changeInfo);
    
    // 디바운스된 처리 스케줄링
    this.scheduleQueueProcessing(sourceDir, targetDir);
  }

  /**
   * 디렉토리 변경 처리
   */
  async handleDirectoryChange(eventType, dirPath, sourceDir, targetDir) {
    const relativePath = path.relative(sourceDir, dirPath);
    
    if (this.options.verbose) {
      this.logger.debug(`📁 디렉토리 ${eventType}: ${relativePath}`);
    }

    // 디렉토리 변경은 즉시 처리
    if (eventType === 'addDir') {
      const targetPath = path.join(targetDir, relativePath);
      if (!fs.existsSync(targetPath)) {
        fs.mkdirSync(targetPath, { recursive: true });
      }
    } else if (eventType === 'unlinkDir') {
      const targetPath = path.join(targetDir, relativePath);
      if (fs.existsSync(targetPath)) {
        fs.rmSync(targetPath, { recursive: true, force: true });
      }
    }
  }

  /**
   * 변경 큐에 추가
   */
  addToChangeQueue(changeInfo) {
    const key = changeInfo.filePath;
    
    // 기존 변경 정보가 있으면 덮어쓰기 (최신 상태 유지)
    this.changeQueue.set(key, changeInfo);
  }

  /**
   * 큐 처리 스케줄링 (디바운스)
   */
  scheduleQueueProcessing(sourceDir, targetDir) {
    const key = `${sourceDir}->${targetDir}`;
    
    // 기존 타이머 취소
    if (this.debounceTimers.has(key)) {
      clearTimeout(this.debounceTimers.get(key));
    }
    
    // 새 타이머 설정
    const timer = setTimeout(async () => {
      this.debounceTimers.delete(key);
      await this.processChangeQueue(sourceDir, targetDir);
    }, this.options.debounceMs);
    
    this.debounceTimers.set(key, timer);
  }

  /**
   * 변경 큐 처리
   */
  async processChangeQueue(sourceDir, targetDir) {
    if (this.processingQueue) {
      return;
    }

    this.processingQueue = true;
    const startTime = Date.now();
    
    try {
      const changes = Array.from(this.changeQueue.values())
        .filter(change => !change.processed && 
                (change.sourceDir === sourceDir && change.targetDir === targetDir));
      
      if (changes.length === 0) {
        return;
      }

      this.logger.info(`🔄 변경 사항 처리 중: ${changes.length}개 파일`);
      
      // 변경 사항을 배치로 그룹화
      const batches = this.groupChangesIntoBatches(changes);
      
      for (const batch of batches) {
        await this.processBatch(batch, sourceDir, targetDir);
        this.stats.batchesProcessed++;
      }
      
      // 처리된 변경 사항 표시
      changes.forEach(change => {
        change.processed = true;
        this.changeQueue.delete(change.filePath);
      });
      
      const duration = Date.now() - startTime;
      this.stats.totalSyncTime += duration;
      this.stats.filesProcessed += changes.length;
      
      this.logger.info(`✅ 동기화 완료: ${changes.length}개 파일 (${duration}ms)`);
      
      // 이벤트 발생
      this.emit('sync-completed', {
        filesProcessed: changes.length,
        duration,
        sourceDir,
        targetDir
      });
      
    } catch (error) {
      this.logger.error('❌ 변경 큐 처리 실패:', error.message);
      this.stats.errors++;
      this.emit('sync-error', error);
    } finally {
      this.processingQueue = false;
    }
  }

  /**
   * 변경 사항을 배치로 그룹화
   */
  groupChangesIntoBatches(changes) {
    const batches = [];
    
    // 파일 타입별로 그룹화
    const fileTypeGroups = new Map();
    
    for (const change of changes) {
      const fileType = this.getFileType(change.filePath);
      if (!fileTypeGroups.has(fileType)) {
        fileTypeGroups.set(fileType, []);
      }
      fileTypeGroups.get(fileType).push(change);
    }
    
    // 각 그룹을 배치 크기로 분할
    for (const [fileType, group] of fileTypeGroups) {
      for (let i = 0; i < group.length; i += this.options.batchSize) {
        const batch = group.slice(i, i + this.options.batchSize);
        batches.push({
          fileType,
          changes: batch
        });
      }
    }
    
    return batches;
  }

  /**
   * 배치 처리
   */
  async processBatch(batch, sourceDir, targetDir) {
    const { fileType, changes } = batch;
    
    if (this.options.verbose) {
      this.logger.debug(`📦 배치 처리: ${fileType} (${changes.length}개 파일)`);
    }

    // 파일 타입별 처리 전략
    switch (fileType) {
      case 'html':
      case 'js':
      case 'json':
        await this.processRegularFiles(changes, sourceDir, targetDir);
        break;
        
      case 'scss':
      case 'css':
        await this.processStyleFiles(changes, sourceDir, targetDir);
        break;
        
      case 'image':
      case 'font':
      case 'asset':
        await this.processAssetFiles(changes, sourceDir, targetDir);
        break;
        
      default:
        await this.processRegularFiles(changes, sourceDir, targetDir);
    }
  }

  /**
   * 일반 파일 처리
   */
  async processRegularFiles(changes, sourceDir, targetDir) {
    for (const change of changes) {
      try {
        await this.processIndividualFile(change, sourceDir, targetDir);
      } catch (error) {
        this.logger.error(`❌ 파일 처리 실패: ${change.relativePath}`, error.message);
        this.stats.errors++;
      }
    }
  }

  /**
   * 스타일 파일 처리 (SCSS, CSS)
   */
  async processStyleFiles(changes, sourceDir, targetDir) {
    // SCSS 파일이 있으면 전체 빌드 수행
    const scssChanges = changes.filter(c => path.extname(c.filePath) === '.scss');
    
    if (scssChanges.length > 0 && this.scssBuilder) {
      const scssDir = path.join(targetDir, 'static/scss');
      const cssDir = path.join(targetDir, 'static/css');
      
      if (fs.existsSync(scssDir)) {
        await this.scssBuilder.buildProject(scssDir, cssDir);
      }
    }
    
    // 일반 CSS 파일 처리
    const cssChanges = changes.filter(c => path.extname(c.filePath) === '.css');
    await this.processRegularFiles(cssChanges, sourceDir, targetDir);
  }

  /**
   * 에셋 파일 처리 (이미지, 폰트 등)
   */
  async processAssetFiles(changes, sourceDir, targetDir) {
    // 에셋 파일은 단순 복사
    for (const change of changes) {
      if (change.eventType !== 'unlink') {
        const targetPath = path.join(targetDir, change.relativePath);
        const targetDirPath = path.dirname(targetPath);
        
        if (!fs.existsSync(targetDirPath)) {
          fs.mkdirSync(targetDirPath, { recursive: true });
        }
        
        fs.copyFileSync(change.filePath, targetPath);
      }
    }
  }

  /**
   * 개별 파일 처리
   */
  async processIndividualFile(change, sourceDir, targetDir) {
    const { eventType, filePath, relativePath } = change;
    const targetPath = path.join(targetDir, relativePath);
    
    switch (eventType) {
      case 'add':
      case 'change':
        // 스마트 동기화 확인
        if (this.options.enableSmartSync && await this.canSkipSync(filePath, targetPath)) {
          this.stats.smartSyncSkips++;
          if (this.options.verbose) {
            this.logger.debug(`⏭️ 스마트 동기화 스킵: ${relativePath}`);
          }
          return;
        }
        
        // 파일 복사
        const targetDirPath = path.dirname(targetPath);
        if (!fs.existsSync(targetDirPath)) {
          fs.mkdirSync(targetDirPath, { recursive: true });
        }
        
        fs.copyFileSync(filePath, targetPath);
        
        // 경로 변환 적용
        if (this.pathConverter && this.needsPathConversion(filePath)) {
          await this.pathConverter.convertFile(targetPath);
        }
        
        break;
        
      case 'unlink':
        // 파일 삭제
        if (fs.existsSync(targetPath)) {
          fs.unlinkSync(targetPath);
        }
        break;
    }
  }

  /**
   * 스마트 동기화 스킵 가능 여부 확인
   */
  async canSkipSync(sourcePath, targetPath) {
    if (!fs.existsSync(targetPath)) {
      return false;
    }
    
    const sourceStats = fs.statSync(sourcePath);
    const targetStats = fs.statSync(targetPath);
    
    // 수정 시간 비교
    if (sourceStats.mtime <= targetStats.mtime) {
      return true;
    }
    
    // 파일 크기 비교
    if (sourceStats.size === targetStats.size && this.options.useContentHash) {
          const crypto = require('crypto');
          const sourceHash = crypto.createHash('md5').update(fs.readFileSync(sourcePath)).digest('hex');
          const targetHash = crypto.createHash('md5').update(fs.readFileSync(targetPath)).digest('hex');
          return sourceHash === targetHash;
    }

    
    return false;
  }

  /**
   * 경로 변환 필요 여부 확인
   */
  needsPathConversion(filePath) {
    const ext = path.extname(filePath).toLowerCase();
    return ['.html', '.htm', '.js', '.jsx', '.ts', '.tsx', '.css', '.scss'].includes(ext);
  }

  /**
   * 파일 타입 결정
   */
  getFileType(filePath) {
    const ext = path.extname(filePath).toLowerCase();
    
    const typeMap = {
      '.html': 'html',
      '.htm': 'html',
      '.js': 'js',
      '.jsx': 'js',
      '.ts': 'js',
      '.tsx': 'js',
      '.css': 'css',
      '.scss': 'scss',
      '.sass': 'scss',
      '.json': 'json',
      '.png': 'image',
      '.jpg': 'image',
      '.jpeg': 'image',
      '.gif': 'image',
      '.svg': 'image',
      '.ico': 'image',
      '.woff': 'font',
      '.woff2': 'font',
      '.ttf': 'font',
      '.eot': 'font'
    };
    
    return typeMap[ext] || 'asset';
  }

  /**
   * 무시 패턴 생성
   */
  buildIgnorePatterns() {
    const defaultPatterns = [
      '**/node_modules/**',
      '**/.git/**',
      '**/.vscode/**',
      '**/.idea/**',
      '**/dist/**',
      '**/build/**',
      '**/.backup/**',
      '**/.cache/**',
      '**/*.log',
      '**/.DS_Store',
      '**/Thumbs.db',
      '**/*.tmp',
      '**/*.temp'
    ];
    
    return [...defaultPatterns, ...this.options.excludePatterns];
  }

  /**
   * 파일 무시 여부 확인
   */
  shouldIgnoreFile(filePath) {
    const fileName = path.basename(filePath);
    const patterns = this.buildIgnorePatterns();
    
    for (const pattern of patterns) {
      // 간단한 glob 매칭
      const regex = new RegExp(pattern.replace(/\*\*/g, '.*').replace(/\*/g, '[^/]*'));
      if (regex.test(filePath) || regex.test(fileName)) {
        return true;
      }
    }
    
    return false;
  }

  /**
   * 의존성 그래프 초기화
   */
  async buildInitialDependencyGraph(sourceDir) {
    if (!this.fileDetector) {
      return;
    }

    // 모든 파일 검색
    const allFiles = [];
    const walkDirectory = (dir) => {
      const entries = fs.readdirSync(dir, { withFileTypes: true });
      
      for (const entry of entries) {
        const fullPath = path.join(dir, entry.name);
        
        if (entry.isDirectory() && !this.shouldIgnoreFile(fullPath)) {
          walkDirectory(fullPath);
        } else if (entry.isFile() && !this.shouldIgnoreFile(fullPath)) {
          allFiles.push(fullPath);
        }
      }
    };

    walkDirectory(sourceDir);
    
    // 파일 분석 및 의존성 그래프 구축
    for (const filePath of allFiles) {
      try {
        const analysis = await this.fileDetector.detectFileType(filePath);
        if (analysis && analysis.patterns.length > 0) {
          this.dependencyGraph.set(filePath, analysis);
        }
      } catch (error) {
        if (this.options.verbose) {
          this.logger.debug(`⚠️ 의존성 분석 실패: ${filePath}`, error.message);
        }
      }
    }
    
    if (this.options.verbose) {
      this.logger.debug(`📊 의존성 그래프 구축 완료: ${this.dependencyGraph.size}개 파일`);
    }
  }

  /**
   * 워치 모드 중지
   */
  async stopWatching() {
    if (!this.isWatching) {
      return;
    }

    this.logger.info('🛑 워치 모드 중지 중...');
    
    // 모든 워처 닫기
    for (const [sourceDir, watcher] of this.watchers) {
      if (watcher.close) {
        watcher.close();
      }
      if (this.options.verbose) {
        this.logger.debug(`🔧 워처 중지: ${sourceDir}`);
      }
    }
    
    // 디바운스 타이머 정리
    for (const timer of this.debounceTimers.values()) {
      clearTimeout(timer);
    }
    
    // 상태 초기화
    this.watchers.clear();
    this.debounceTimers.clear();
    this.changeQueue.clear();
    this.isWatching = false;
    
    this.emit('watch-stopped');
    this.logger.info('✅ 워치 모드가 중지되었습니다.');
  }

  /**
   * 통계 출력
   */
  printStats() {
    this.logger.info('\n📊 워치 & 동기화 통계:');
    this.logger.info(`👁️  감지된 변경: ${this.stats.changesDetected}회`);
    this.logger.info(`📁 처리된 파일: ${this.stats.filesProcessed}개`);
    this.logger.info(`📦 처리된 배치: ${this.stats.batchesProcessed}개`);
    this.logger.info(`⚡ 스마트 동기화 스킵: ${this.stats.smartSyncSkips}회`);
    this.logger.info(`⏱️  총 동기화 시간: ${this.stats.totalSyncTime}ms`);
    this.logger.info(`❌ 에러: ${this.stats.errors}개`);
    
    if (this.stats.batchesProcessed > 0) {
      const avgTime = Math.round(this.stats.totalSyncTime / this.stats.batchesProcessed);
      this.logger.info(`📈 평균 배치 처리 시간: ${avgTime}ms`);
    }
  }

  /**
   * 상태 초기화
   */
  resetStats() {
    this.stats = {
      changesDetected: 0,
      filesProcessed: 0,
      batchesProcessed: 0,
      totalSyncTime: 0,
      errors: 0,
      smartSyncSkips: 0
    };
  }

  /**
   * 정리
   */
  cleanup() {
    this.stopWatching();
    this.dependencyGraph.clear();
    this.fileStates.clear();
  }
}

module.exports = WatchSyncSystem;