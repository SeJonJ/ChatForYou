const fs = require('fs');
const path = require('path');
const { execSync, spawn } = require('child_process');

/**
 * 통합 SCSS 빌드 시스템
 * 자동 의존성 추적, 증분 빌드, 워치 모드 지원
 */
class ScssBuildSystem {
  constructor(options = {}) {
    this.options = {
      verbose: options.verbose || false,
      watchMode: options.watchMode || false,
      sourceMap: options.sourceMap !== false,
      compressed: options.compressed || false,
      autoprefixer: options.autoprefixer !== false,
      ...options
    };

    this.logger = options.logger || console;
    
    // 빌드 상태
    this.isBuilding = false;
    this.buildQueue = [];
    this.dependencyGraph = new Map();
    this.lastBuildTimes = new Map();
    
    // 워치 관련
    this.watchers = new Map();
    this.watchedFiles = new Set();
    
    // 통계
    this.stats = {
      buildsCompleted: 0,
      totalBuildTime: 0,
      filesProcessed: 0,
      errors: 0,
      incrementalBuilds: 0
    };

    // 빌드 도구 확인
    this.availableTools = new Map();
    this.checkAvailableTools();
  }

  /**
   * 사용 가능한 빌드 도구 확인
   */
  checkAvailableTools() {
    const tools = [
      { name: 'sass', command: 'sass --version' },
      { name: 'node-sass', command: 'node-sass --version' },
      { name: 'dart-sass', command: 'dart-sass --version' }
    ];

    for (const tool of tools) {
      try {
        execSync(tool.command, { stdio: 'ignore' });
        this.availableTools.set(tool.name, true);
        if (this.options.verbose) {
          this.logger.debug(`✅ ${tool.name} 사용 가능`);
        }
      } catch (error) {
        this.availableTools.set(tool.name, false);
        if (this.options.verbose) {
          this.logger.debug(`❌ ${tool.name} 사용 불가`);
        }
      }
    }

    // 기본 도구 선택
    if (this.availableTools.get('sass')) {
      this.defaultTool = 'sass';
    } else if (this.availableTools.get('dart-sass')) {
      this.defaultTool = 'dart-sass';
    } else if (this.availableTools.get('node-sass')) {
      this.defaultTool = 'node-sass';
    } else {
      this.logger.warn('⚠️ SCSS 컴파일러를 찾을 수 없습니다. sass 패키지를 설치해주세요.');
      this.defaultTool = null;
    }
  }

  /**
   * SCSS 프로젝트 빌드
   */
  async buildProject(sourceDir, outputDir) {
    if (!this.defaultTool) {
      throw new Error('SCSS 컴파일러가 없습니다.');
    }

    this.logger.info(`🎨 SCSS 빌드 시작: ${sourceDir} -> ${outputDir}`);
    
    const startTime = Date.now();
    
    try {
      // 1. SCSS 파일 검색
      const scssFiles = await this.findScssFiles(sourceDir);
      
      if (scssFiles.length === 0) {
        this.logger.info('📄 빌드할 SCSS 파일이 없습니다.');
        return { success: true, filesProcessed: 0 };
      }

      // 2. 의존성 그래프 생성
      await this.buildDependencyGraph(scssFiles);
      
      // 3. 빌드 순서 결정
      const buildOrder = this.calculateBuildOrder(scssFiles);
      
      // 4. 증분 빌드 확인
      const filesToBuild = this.getFilesToBuild(buildOrder, sourceDir);
      
      if (filesToBuild.length === 0) {
        this.logger.info('🚀 모든 파일이 최신 상태입니다.');
        return { success: true, filesProcessed: 0 };
      }

      // 5. 실제 빌드 실행
      const results = await this.executeBuilds(filesToBuild, sourceDir, outputDir);
      
      // 6. 후처리 (autoprefixer 등)
      if (this.options.autoprefixer) {
        await this.runPostProcessing(results, outputDir);
      }

      const duration = Date.now() - startTime;
      this.stats.buildsCompleted++;
      this.stats.totalBuildTime += duration;
      this.stats.filesProcessed += filesToBuild.length;

      this.logger.info(`✅ SCSS 빌드 완료: ${filesToBuild.length}개 파일 (${duration}ms)`);
      
      return {
        success: true,
        filesProcessed: filesToBuild.length,
        duration,
        outputFiles: results.map(r => r.outputFile)
      };

    } catch (error) {
      this.stats.errors++;
      this.logger.error(`❌ SCSS 빌드 실패:`, error.message);
      throw error;
    }
  }

  /**
   * SCSS 파일 검색
   */
  async findScssFiles(sourceDir) {
    const scssFiles = [];
    
    const walkDirectory = (dir) => {
      const entries = fs.readdirSync(dir, { withFileTypes: true });
      
      for (const entry of entries) {
        const fullPath = path.join(dir, entry.name);
        
        if (entry.isDirectory() && !this.shouldSkipDirectory(entry.name)) {
          walkDirectory(fullPath);
        } else if (entry.isFile() && this.isScssFile(entry.name)) {
          scssFiles.push({
            fullPath,
            name: entry.name,
            relativePath: path.relative(sourceDir, fullPath),
            isPartial: entry.name.startsWith('_')
          });
        }
      }
    };

    if (fs.existsSync(sourceDir)) {
      walkDirectory(sourceDir);
    }

    if (this.options.verbose) {
      this.logger.debug(`🔍 발견된 SCSS 파일: ${scssFiles.length}개`);
    }

    return scssFiles;
  }

  /**
   * 의존성 그래프 생성
   */
  async buildDependencyGraph(scssFiles) {
    this.dependencyGraph.clear();

    for (const file of scssFiles) {
      const dependencies = await this.extractDependencies(file.fullPath);
      this.dependencyGraph.set(file.fullPath, dependencies);
      
      if (this.options.verbose) {
        this.logger.debug(`📋 ${file.name}: ${dependencies.length}개 의존성`);
      }
    }
  }

  /**
   * SCSS 파일의 의존성 추출
   */
  async extractDependencies(filePath) {
    const dependencies = [];
    
    try {
      const content = fs.readFileSync(filePath, 'utf8');
      const importRegex = /@(?:import|use|forward)\s+["']([^"']+)["']/g;
      
      let match;
      while ((match = importRegex.exec(content)) !== null) {
        const importPath = match[1];
        const resolvedPath = this.resolveImportPath(importPath, path.dirname(filePath));
        
        if (resolvedPath && fs.existsSync(resolvedPath)) {
          dependencies.push(resolvedPath);
        }
      }
    } catch (error) {
      this.logger.warn(`⚠️ 의존성 추출 실패: ${filePath}`, error.message);
    }

    return dependencies;
  }

  /**
   * import 경로 해석
   */
  resolveImportPath(importPath, baseDir) {
    // 확장자가 없으면 .scss 추가
    if (!path.extname(importPath)) {
      importPath += '.scss';
    }

    // partial 파일 확인 (_filename.scss)
    const dirname = path.dirname(importPath);
    const basename = path.basename(importPath, '.scss');
    const partialPath = path.join(dirname, `_${basename}.scss`);

    // 가능한 경로들
    const possiblePaths = [
      path.resolve(baseDir, importPath),
      path.resolve(baseDir, partialPath),
      path.resolve(baseDir, importPath + '.scss'),
      path.resolve(baseDir, `_${importPath}.scss`)
    ];

    for (const possiblePath of possiblePaths) {
      if (fs.existsSync(possiblePath)) {
        return possiblePath;
      }
    }

    return null;
  }

  /**
   * 빌드 순서 계산 (위상 정렬)
   */
  calculateBuildOrder(scssFiles) {
    const visited = new Set();
    const visiting = new Set();
    const buildOrder = [];

    const visit = (filePath) => {
      if (visiting.has(filePath)) {
        this.logger.warn(`⚠️ 순환 의존성 감지: ${filePath}`);
        return;
      }
      
      if (visited.has(filePath)) {
        return;
      }

      visiting.add(filePath);
      
      const dependencies = this.dependencyGraph.get(filePath) || [];
      for (const dep of dependencies) {
        visit(dep);
      }
      
      visiting.delete(filePath);
      visited.add(filePath);
      buildOrder.push(filePath);
    };

    // 메인 파일들만 빌드 (partial 파일 제외)
    const mainFiles = scssFiles.filter(file => !file.isPartial);
    
    for (const file of mainFiles) {
      visit(file.fullPath);
    }

    return buildOrder;
  }

  /**
   * 빌드가 필요한 파일 결정 (증분 빌드)
   */
  getFilesToBuild(buildOrder, sourceDir) {
    const filesToBuild = [];
    
    for (const filePath of buildOrder) {
      const outputPath = this.getOutputPath(filePath, sourceDir);
      const shouldBuild = this.shouldRebuild(filePath, outputPath);
      
      if (shouldBuild) {
        filesToBuild.push(filePath);
        this.stats.incrementalBuilds++;
      }
    }

    return filesToBuild;
  }

  /**
   * 재빌드 필요 여부 확인
   */
  shouldRebuild(inputPath, outputPath) {
    if (!fs.existsSync(outputPath)) {
      return true;
    }

    const inputStat = fs.statSync(inputPath);
    const outputStat = fs.statSync(outputPath);
    
    if (inputStat.mtime > outputStat.mtime) {
      return true;
    }

    // 의존성 파일 확인
    const dependencies = this.dependencyGraph.get(inputPath) || [];
    for (const dep of dependencies) {
      if (fs.existsSync(dep)) {
        const depStat = fs.statSync(dep);
        if (depStat.mtime > outputStat.mtime) {
          return true;
        }
      }
    }

    return false;
  }

  /**
   * 실제 빌드 실행
   */
  async executeBuilds(filesToBuild, sourceDir, outputDir) {
    const results = [];
    
    // 출력 디렉토리 생성
    if (!fs.existsSync(outputDir)) {
      fs.mkdirSync(outputDir, { recursive: true });
    }

    for (const inputFile of filesToBuild) {
      const outputFile = this.getOutputPath(inputFile, sourceDir, outputDir);
      const outputDirPath = path.dirname(outputFile);
      
      // 출력 디렉토리 생성
      if (!fs.existsSync(outputDirPath)) {
        fs.mkdirSync(outputDirPath, { recursive: true });
      }

      try {
        const result = await this.compileSingleFile(inputFile, outputFile);
        results.push({ inputFile, outputFile, ...result });
        
        if (this.options.verbose) {
          this.logger.debug(`✅ 컴파일 완료: ${path.basename(inputFile)}`);
        }
      } catch (error) {
        this.logger.error(`❌ 컴파일 실패: ${inputFile}`, error.message);
        throw error;
      }
    }

    return results;
  }

  /**
   * 단일 파일 컴파일
   */
  async compileSingleFile(inputFile, outputFile) {
    const startTime = Date.now();
    
    // 명령어 구성
    const cmd = this.buildCompileCommand(inputFile, outputFile);
    
    return new Promise((resolve, reject) => {
      const process = spawn('sh', ['-c', cmd], {
        stdio: ['pipe', 'pipe', 'pipe']
      });

      let stdout = '';
      let stderr = '';

      process.stdout.on('data', (data) => {
        stdout += data.toString();
      });

      process.stderr.on('data', (data) => {
        stderr += data.toString();
      });

      process.on('close', (code) => {
        const duration = Date.now() - startTime;
        
        if (code === 0) {
          resolve({
            success: true,
            duration,
            output: stdout
          });
        } else {
          reject(new Error(`컴파일 실패 (${code}): ${stderr}`));
        }
      });

      process.on('error', (error) => {
        reject(error);
      });
    });
  }

  /**
   * 컴파일 명령어 구성
   */
  buildCompileCommand(inputFile, outputFile) {
    const options = [];
    
    // 스타일 옵션
    if (this.options.compressed) {
      options.push('--style=compressed');
    } else {
      options.push('--style=expanded');
    }
    
    // 소스맵 옵션
    if (this.options.sourceMap) {
      options.push('--source-map');
    } else {
      options.push('--no-source-map');
    }

    // 도구별 명령어
    switch (this.defaultTool) {
      case 'sass':
        return `sass ${options.join(' ')} "${inputFile}" "${outputFile}"`;
      
      case 'dart-sass':
        return `dart-sass ${options.join(' ')} "${inputFile}" "${outputFile}"`;
      
      case 'node-sass':
        const nodeOptions = options
          .map(opt => opt.replace('--style=', '--output-style='))
          .join(' ');
        return `node-sass ${nodeOptions} "${inputFile}" -o "${path.dirname(outputFile)}"`;
      
      default:
        throw new Error(`지원하지 않는 SCSS 컴파일러: ${this.defaultTool}`);
    }
  }

  /**
   * 후처리 실행 (autoprefixer 등)
   */
  async runPostProcessing(results, outputDir) {
    if (!this.options.autoprefixer) {
      return;
    }

    for (const result of results) {
      if (result.success && fs.existsSync(result.outputFile)) {
        try {
          // autoprefixer가 설치되어 있는지 확인
          execSync('npx autoprefixer --version', { stdio: 'ignore' });
          
          // autoprefixer 실행
          const cmd = `npx autoprefixer "${result.outputFile}" -o "${result.outputFile}"`;
          execSync(cmd);
          
          if (this.options.verbose) {
            this.logger.debug(`🔧 Autoprefixer 적용: ${path.basename(result.outputFile)}`);
          }
        } catch (error) {
          this.logger.warn(`⚠️ Autoprefixer 실행 실패: ${result.outputFile}`, error.message);
        }
      }
    }
  }

  /**
   * 워치 모드 시작
   */
  async startWatchMode(sourceDir, outputDir) {
    if (!fs.existsSync(sourceDir)) {
      throw new Error(`소스 디렉토리를 찾을 수 없습니다: ${sourceDir}`);
    }

    this.logger.info(`👁️  SCSS 워치 모드 시작: ${sourceDir}`);
    
    // 초기 빌드
    await this.buildProject(sourceDir, outputDir);
    
    // 파일 워치 설정
    this.setupFileWatchers(sourceDir, outputDir);
    
    this.logger.info('🔍 파일 변경을 감시 중... (Ctrl+C로 종료)');
  }

  /**
   * 파일 워처 설정
   */
  setupFileWatchers(sourceDir, outputDir) {
    let chokidar;
    try {
      chokidar = require('chokidar');
    } catch (err) {
      this.logger.error('chokidar 모듈을 불러올 수 없습니다. "npm install chokidar"로 설치해 주세요.');
      process.exit(1);
    }

    const watcher = chokidar.watch(sourceDir, {
      ignored: /(^|[\/\\])\../, // 숨김 파일 무시
      persistent: true,
      ignoreInitial: true
    });

    watcher
      .on('add', (filePath) => this.handleFileChange('add', filePath, sourceDir, outputDir))
      .on('change', (filePath) => this.handleFileChange('change', filePath, sourceDir, outputDir))
      .on('unlink', (filePath) => this.handleFileChange('unlink', filePath, sourceDir, outputDir));

    this.watchers.set(sourceDir, watcher);
  }

  /**
   * 파일 변경 처리
   */
  async handleFileChange(event, filePath, sourceDir, outputDir) {
    if (!this.isScssFile(path.basename(filePath))) {
      return;
    }

    const relativePath = path.relative(sourceDir, filePath);
    this.logger.info(`📝 파일 ${event}: ${relativePath}`);

    // 빌드 큐에 추가 (디바운싱)
    this.addToBuildQueue(sourceDir, outputDir);
  }

  /**
   * 빌드 큐 관리
   */
  addToBuildQueue(sourceDir, outputDir) {
    // 중복 제거
    const queueKey = `${sourceDir}->${outputDir}`;
    
    // 기존 빌드 취소
    if (this.buildQueue.includes(queueKey)) {
      return;
    }

    this.buildQueue.push(queueKey);

    // 디바운싱 (500ms)
    setTimeout(async () => {
      const index = this.buildQueue.indexOf(queueKey);
      if (index > -1) {
        this.buildQueue.splice(index, 1);
        
        try {
          await this.buildProject(sourceDir, outputDir);
        } catch (error) {
          this.logger.error('❌ 워치 모드 빌드 실패:', error.message);
        }
      }
    }, 500);
  }

  /**
   * 출력 파일 경로 생성
   */
  getOutputPath(inputPath, sourceDir, outputDir = null) {
    const relativePath = path.relative(sourceDir, inputPath);
    const outputFileName = path.basename(relativePath, '.scss') + '.css';
    const outputRelativePath = path.join(path.dirname(relativePath), outputFileName);
    
    if (outputDir) {
      return path.join(outputDir, outputRelativePath);
    } else {
      // sourceDir와 같은 위치에 출력
      return path.join(sourceDir, path.dirname(relativePath), outputFileName);
    }
  }

  /**
   * SCSS 파일 여부 확인
   */
  isScssFile(fileName) {
    return /\.(scss|sass)$/i.test(fileName);
  }

  /**
   * 건너뛸 디렉토리 확인
   */
  shouldSkipDirectory(dirName) {
    const skipDirs = new Set([
      'node_modules', '.git', '.vscode', '.idea',
      'dist', 'build', '.backup', '.cache'
    ]);
    
    return skipDirs.has(dirName) || dirName.startsWith('.');
  }

  /**
   * 워치 모드 종료
   */
  stopWatchMode() {
    for (const [sourceDir, watcher] of this.watchers) {
      watcher.close();
      this.logger.info(`🛑 워치 모드 종료: ${sourceDir}`);
    }
    this.watchers.clear();
  }

  /**
   * 통계 출력
   */
  printStats() {
    this.logger.info('\n📊 SCSS 빌드 통계:');
    this.logger.info(`🔨 완료된 빌드: ${this.stats.buildsCompleted}회`);
    this.logger.info(`📁 처리된 파일: ${this.stats.filesProcessed}개`);
    this.logger.info(`⚡ 증분 빌드: ${this.stats.incrementalBuilds}회`);
    this.logger.info(`⏱️  총 빌드 시간: ${this.stats.totalBuildTime}ms`);
    this.logger.info(`❌ 에러: ${this.stats.errors}개`);
    
    if (this.stats.buildsCompleted > 0) {
      const avgTime = Math.round(this.stats.totalBuildTime / this.stats.buildsCompleted);
      this.logger.info(`📈 평균 빌드 시간: ${avgTime}ms`);
    }
  }

  /**
   * 정리
   */
  cleanup() {
    this.stopWatchMode();
    this.buildQueue = [];
    this.dependencyGraph.clear();
    this.lastBuildTimes.clear();
  }
}

module.exports = ScssBuildSystem;