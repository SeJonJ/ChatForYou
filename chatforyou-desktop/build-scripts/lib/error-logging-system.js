const fs = require('fs');
const path = require('path');
const util = require('util');
const { EventEmitter } = require('events');

/**
 * 고급 에러 핸들링 및 로깅 시스템
 * 구조화된 로깅, 에러 분류, 성능 모니터링, 디버그 정보 제공
 */
class ErrorLoggingSystem extends EventEmitter {
  constructor(options = {}) {
    super();
    
    this.options = {
      logLevel: options.logLevel || 'info',
      enableFileLogging: options.enableFileLogging !== false,
      enableConsoleLogging: options.enableConsoleLogging !== false,
      logDir: options.logDir || path.join(__dirname, '../.logs'),
      maxLogFiles: options.maxLogFiles || 10,
      maxLogFileSize: options.maxLogFileSize || 10 * 1024 * 1024, // 10MB
      enablePerformanceTracking: options.enablePerformanceTracking !== false,
      enableErrorReporting: options.enableErrorReporting !== false,
      ...options
    };

    // 로그 레벨 매핑
    this.logLevels = {
      error: 0,
      warn: 1,
      info: 2,
      verbose: 3,
      debug: 4,
      trace: 5
    };

    this.currentLogLevel = this.logLevels[this.options.logLevel] || 2;
    
    // 로그 저장소
    this.logHistory = [];
    this.errorHistory = [];
    this.performanceMetrics = new Map();
    
    // 파일 로거
    this.fileStreams = new Map();
    
    // 통계
    this.stats = {
      totalLogs: 0,
      logsByLevel: new Map(),
      errors: new Map(),
      warnings: 0,
      startTime: Date.now()
    };

    // 초기화
    this.initialize();
  }

  /**
   * 시스템 초기화
   */
  initialize() {
    // 로그 디렉토리 생성
    if (this.options.enableFileLogging) {
      this.ensureLogDirectory();
      this.setupFileStreams();
      this.setupLogRotation();
    }

    // 글로벌 에러 핸들러 설정
    if (this.options.enableErrorReporting) {
      this.setupGlobalErrorHandlers();
    }

    // 성능 모니터링 초기화
    if (this.options.enablePerformanceTracking) {
      this.initializePerformanceTracking();
    }

    this.info('ErrorLoggingSystem 초기화 완료', {
      logLevel: this.options.logLevel,
      fileLogging: this.options.enableFileLogging,
      performanceTracking: this.options.enablePerformanceTracking
    });
  }

  /**
   * 로그 디렉토리 보장
   */
  ensureLogDirectory() {
    if (!fs.existsSync(this.options.logDir)) {
      fs.mkdirSync(this.options.logDir, { recursive: true });
    }
  }

  /**
   * 파일 스트림 설정
   */
  setupFileStreams() {
    const logTypes = ['error', 'warn', 'info', 'debug', 'performance'];
    
    for (const type of logTypes) {
      const logFile = path.join(this.options.logDir, `${type}.log`);
      const stream = fs.createWriteStream(logFile, { flags: 'a' });
      this.fileStreams.set(type, stream);
    }

    // 메인 로그 파일
    const mainLogFile = path.join(this.options.logDir, 'main.log');
    const mainStream = fs.createWriteStream(mainLogFile, { flags: 'a' });
    this.fileStreams.set('main', mainStream);
  }

  /**
   * 로그 로테이션 설정
   */
  setupLogRotation() {
    // 일정 간격으로 로그 파일 크기 확인
    setInterval(() => {
      this.checkAndRotateLogs();
    }, 60000); // 1분마다 확인
  }

  /**
   * 로그 로테이션 실행
   */
  checkAndRotateLogs() {
    for (const [type, stream] of this.fileStreams) {
      const logFile = path.join(this.options.logDir, `${type}.log`);
      
      if (fs.existsSync(logFile)) {
        const stats = fs.statSync(logFile);
        
        if (stats.size > this.options.maxLogFileSize) {
          this.rotateLogFile(type, logFile, stream);
        }
      }
    }
  }

  /**
   * 개별 로그 파일 로테이션
   */
  rotateLogFile(type, logFile, stream) {
    try {
      // 스트림 닫기
      stream.end();
      
      // 백업 파일 생성
      const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
      const backupFile = path.join(
        this.options.logDir, 
        `${type}.${timestamp}.log`
      );
      
      fs.renameSync(logFile, backupFile);
      
      // 새 스트림 생성
      const newStream = fs.createWriteStream(logFile, { flags: 'a' });
      this.fileStreams.set(type, newStream);
      
      // 오래된 백업 파일 정리
      this.cleanupOldLogFiles(type);
      
      this.debug(`로그 파일 로테이션 완료: ${type}`);
      
    } catch (error) {
      console.error(`로그 파일 로테이션 실패: ${type}`, error);
    }
  }

  /**
   * 오래된 로그 파일 정리
   */
  cleanupOldLogFiles(type) {
    try {
      const files = fs.readdirSync(this.options.logDir)
        .filter(file => file.startsWith(`${type}.`) && file.endsWith('.log'))
        .map(file => ({
          name: file,
          path: path.join(this.options.logDir, file),
          stat: fs.statSync(path.join(this.options.logDir, file))
        }))
        .sort((a, b) => b.stat.mtime - a.stat.mtime);

      // 최대 개수를 초과하는 파일 삭제
      if (files.length > this.options.maxLogFiles) {
        const filesToDelete = files.slice(this.options.maxLogFiles);
        
        for (const file of filesToDelete) {
          fs.unlinkSync(file.path);
          this.debug(`오래된 로그 파일 삭제: ${file.name}`);
        }
      }
    } catch (error) {
      console.error(`로그 파일 정리 실패: ${type}`, error);
    }
  }

  /**
   * 글로벌 에러 핸들러 설정
   */
  setupGlobalErrorHandlers() {
    // 처리되지 않은 예외
    process.on('uncaughtException', (error) => {
      this.error('처리되지 않은 예외', {
        error: this.serializeError(error),
        stack: error.stack,
        fatal: true
      });
      
      // 프로세스 종료 지연 (로그 기록 시간 확보)
      setTimeout(() => {
        process.exit(1);
      }, 1000);
    });

    // 처리되지 않은 Promise 거부
    process.on('unhandledRejection', (reason, promise) => {
      this.error('처리되지 않은 Promise 거부', {
        reason: reason instanceof Error ? this.serializeError(reason) : reason,
        promise: util.inspect(promise),
        stack: reason?.stack
      });
    });

    // 경고 이벤트
    process.on('warning', (warning) => {
      this.warn('Node.js 경고', {
        name: warning.name,
        message: warning.message,
        stack: warning.stack
      });
    });
  }

  /**
   * 성능 추적 초기화
   */
  initializePerformanceTracking() {
    // 메모리 사용량 모니터링
    setInterval(() => {
      const memoryUsage = process.memoryUsage();
      this.recordPerformanceMetric('memory', {
        rss: memoryUsage.rss,
        heapTotal: memoryUsage.heapTotal,
        heapUsed: memoryUsage.heapUsed,
        external: memoryUsage.external,
        timestamp: Date.now()
      });
    }, 30000); // 30초마다

    // CPU 사용량 추적 (사용 가능한 경우)
    this.trackCpuUsage();
  }

  /**
   * CPU 사용량 추적
   */
  trackCpuUsage() {
    let lastCpuUsage = process.cpuUsage();
    
    setInterval(() => {
      const currentCpuUsage = process.cpuUsage(lastCpuUsage);
      
      this.recordPerformanceMetric('cpu', {
        user: currentCpuUsage.user,
        system: currentCpuUsage.system,
        timestamp: Date.now()
      });
      
      lastCpuUsage = process.cpuUsage();
    }, 30000); // 30초마다
  }

  /**
   * 에러 로그
   */
  error(message, meta = {}, error = null) {
    if (this.shouldLog('error')) {
      const logEntry = this.createLogEntry('error', message, meta, error);
      this.processLogEntry(logEntry);
      
      // 에러 히스토리에 추가
      this.errorHistory.push(logEntry);
      this.limitHistory(this.errorHistory, 1000);
      
      // 에러 통계 업데이트
      const errorType = meta.errorType || 'unknown';
      this.stats.errors.set(errorType, (this.stats.errors.get(errorType) || 0) + 1);
      
      // 이벤트 발생
      this.emit('error-logged', logEntry);
    }
  }

  /**
   * 경고 로그
   */
  warn(message, meta = {}) {
    if (this.shouldLog('warn')) {
      const logEntry = this.createLogEntry('warn', message, meta);
      this.processLogEntry(logEntry);
      this.stats.warnings++;
      this.emit('warning-logged', logEntry);
    }
  }

  /**
   * 정보 로그
   */
  info(message, meta = {}) {
    if (this.shouldLog('info')) {
      const logEntry = this.createLogEntry('info', message, meta);
      this.processLogEntry(logEntry);
    }
  }

  /**
   * 상세 로그
   */
  verbose(message, meta = {}) {
    if (this.shouldLog('verbose')) {
      const logEntry = this.createLogEntry('verbose', message, meta);
      this.processLogEntry(logEntry);
    }
  }

  /**
   * 디버그 로그
   */
  debug(message, meta = {}) {
    if (this.shouldLog('debug')) {
      const logEntry = this.createLogEntry('debug', message, meta);
      this.processLogEntry(logEntry);
    }
  }

  /**
   * 추적 로그
   */
  trace(message, meta = {}) {
    if (this.shouldLog('trace')) {
      const logEntry = this.createLogEntry('trace', message, meta);
      this.processLogEntry(logEntry);
    }
  }

  /**
   * 성능 메트릭 기록
   */
  recordPerformanceMetric(name, data) {
    if (!this.options.enablePerformanceTracking) {
      return;
    }

    if (!this.performanceMetrics.has(name)) {
      this.performanceMetrics.set(name, []);
    }

    const metrics = this.performanceMetrics.get(name);
    metrics.push(data);
    
    // 메트릭 히스토리 제한
    this.limitHistory(metrics, 1000);

    // 성능 로그 파일에 기록
    if (this.options.enableFileLogging) {
      const performanceStream = this.fileStreams.get('performance');
      if (performanceStream) {
        const logLine = JSON.stringify({
          timestamp: new Date().toISOString(),
          metric: name,
          data
        }) + '\n';
        
        performanceStream.write(logLine);
      }
    }
  }

  /**
   * 타이밍 시작
   */
  startTiming(label) {
    const startTime = process.hrtime.bigint();
    
    return {
      end: () => {
        const endTime = process.hrtime.bigint();
        const duration = Number(endTime - startTime) / 1000000; // ms
        
        this.recordPerformanceMetric('timing', {
          label,
          duration,
          timestamp: Date.now()
        });
        
        this.debug(`타이밍: ${label}`, { duration: `${duration.toFixed(2)}ms` });
        
        return duration;
      }
    };
  }

  /**
   * 로그 레벨 확인
   */
  shouldLog(level) {
    return this.logLevels[level] <= this.currentLogLevel;
  }

  /**
   * 로그 엔트리 생성
   */
  createLogEntry(level, message, meta = {}, error = null) {
    const timestamp = new Date().toISOString();
    const logEntry = {
      timestamp,
      level,
      message,
      meta: { ...meta },
      pid: process.pid,
      sessionId: this.getSessionId()
    };

    // 에러 정보 추가
    if (error) {
      logEntry.error = this.serializeError(error);
    }

    // 스택 트레이스 추가 (에러 레벨인 경우)
    if (level === 'error' && !error) {
      const { stack } = new Error();
      logEntry.stack = stack?.split('\n').slice(2).join('\n');
    }

    // 컨텍스트 정보 추가
    logEntry.context = this.getContextInfo();

    return logEntry;
  }

  /**
   * 로그 엔트리 처리
   */
  processLogEntry(logEntry) {
    // 통계 업데이트
    this.stats.totalLogs++;
    this.stats.logsByLevel.set(
      logEntry.level, 
      (this.stats.logsByLevel.get(logEntry.level) || 0) + 1
    );

    // 히스토리에 추가
    this.logHistory.push(logEntry);
    this.limitHistory(this.logHistory, 5000);

    // 콘솔 출력
    if (this.options.enableConsoleLogging) {
      this.outputToConsole(logEntry);
    }

    // 파일 출력
    if (this.options.enableFileLogging) {
      this.outputToFile(logEntry);
    }
  }

  /**
   * 콘솔 출력
   */
  outputToConsole(logEntry) {
    const { timestamp, level, message, meta } = logEntry;
    const timeStr = new Date(timestamp).toISOString().split('T')[1].split('.')[0];
    
    // 레벨별 색상 및 이모지
    const levelInfo = {
      error: { emoji: '❌', color: '\x1b[31m' },
      warn: { emoji: '⚠️', color: '\x1b[33m' },
      info: { emoji: 'ℹ️', color: '\x1b[36m' },
      verbose: { emoji: '📝', color: '\x1b[37m' },
      debug: { emoji: '🔍', color: '\x1b[90m' },
      trace: { emoji: '🔬', color: '\x1b[90m' }
    };

    const info = levelInfo[level] || { emoji: '📄', color: '\x1b[37m' };
    const reset = '\x1b[0m';
    
    let output = `[${timeStr}] ${info.color}${info.emoji} ${message}${reset}`;
    
    // 메타데이터 출력 (비어있지 않은 경우)
    if (Object.keys(meta).length > 0) {
      output += `\n${util.inspect(meta, { colors: true, depth: 3 })}`;
    }

    // 에러 스택 출력
    if (logEntry.error?.stack) {
      output += `\n${info.color}스택:${reset}\n${logEntry.error.stack}`;
    }

    console.log(output);
  }

  /**
   * 파일 출력
   */
  outputToFile(logEntry) {
    const logLine = JSON.stringify(logEntry) + '\n';
    
    // 메인 로그 파일에 기록
    const mainStream = this.fileStreams.get('main');
    if (mainStream) {
      mainStream.write(logLine);
    }

    // 레벨별 로그 파일에 기록
    const levelStream = this.fileStreams.get(logEntry.level);
    if (levelStream) {
      levelStream.write(logLine);
    }
  }

  /**
   * 에러 직렬화
   */
  serializeError(error) {
    return {
      name: error.name,
      message: error.message,
      stack: error.stack,
      code: error.code,
      errno: error.errno,
      syscall: error.syscall,
      path: error.path
    };
  }

  /**
   * 세션 ID 생성/반환
   */
  getSessionId() {
    if (!this._sessionId) {
      this._sessionId = Math.random().toString(36).substring(2, 15);
    }
    return this._sessionId;
  }

  /**
   * 컨텍스트 정보 수집
   */
  getContextInfo() {
    return {
      nodeVersion: process.version,
      platform: process.platform,
      arch: process.arch,
      cwd: process.cwd(),
      uptime: process.uptime(),
      memoryUsage: process.memoryUsage()
    };
  }

  /**
   * 히스토리 제한
   */
  limitHistory(history, maxSize) {
    if (history.length > maxSize) {
      history.splice(0, history.length - maxSize);
    }
  }

  /**
   * 로그 레벨 변경
   */
  setLogLevel(level) {
    if (this.logLevels.hasOwnProperty(level)) {
      this.currentLogLevel = this.logLevels[level];
      this.options.logLevel = level;
      this.info(`로그 레벨 변경: ${level}`);
    } else {
      this.warn(`잘못된 로그 레벨: ${level}`);
    }
  }

  /**
   * 통계 출력
   */
  printStats() {
    const uptime = Math.round((Date.now() - this.stats.startTime) / 1000);
    
    this.info('\n📊 로깅 시스템 통계:');
    this.info(`⏱️  실행 시간: ${uptime}초`);
    this.info(`📝 총 로그: ${this.stats.totalLogs}개`);
    this.info(`⚠️  경고: ${this.stats.warnings}개`);
    
    // 레벨별 통계
    this.info('📋 레벨별 로그:');
    for (const [level, count] of this.stats.logsByLevel) {
      this.info(`   ${level}: ${count}개`);
    }
    
    // 에러 통계
    if (this.stats.errors.size > 0) {
      this.info('❌ 에러 유형별:');
      for (const [type, count] of this.stats.errors) {
        this.info(`   ${type}: ${count}개`);
      }
    }
    
    // 성능 메트릭 요약
    if (this.options.enablePerformanceTracking) {
      this.printPerformanceMetrics();
    }
  }

  /**
   * 성능 메트릭 출력
   */
  printPerformanceMetrics() {
    this.info('📈 성능 메트릭:');
    
    for (const [name, metrics] of this.performanceMetrics) {
      if (metrics.length === 0) continue;
      
      const latest = metrics[metrics.length - 1];
      
      switch (name) {
        case 'memory':
          this.info(`   메모리 (RSS): ${Math.round(latest.rss / 1024 / 1024)}MB`);
          this.info(`   힙 사용량: ${Math.round(latest.heapUsed / 1024 / 1024)}MB`);
          break;
          
        case 'cpu':
          this.info(`   CPU (사용자): ${(latest.user / 1000).toFixed(2)}ms`);
          this.info(`   CPU (시스템): ${(latest.system / 1000).toFixed(2)}ms`);
          break;
          
        case 'timing':
          const timings = metrics.filter(m => m.label);
          if (timings.length > 0) {
            const avgTiming = timings.reduce((sum, t) => sum + t.duration, 0) / timings.length;
            this.info(`   평균 처리 시간: ${avgTiming.toFixed(2)}ms`);
          }
          break;
      }
    }
  }

  /**
   * 로그 검색
   */
  searchLogs(query, options = {}) {
    const {
      level = null,
      startTime = null,
      endTime = null,
      limit = 100
    } = options;

    let results = this.logHistory;

    // 레벨 필터
    if (level) {
      results = results.filter(log => log.level === level);
    }

    // 시간 범위 필터
    if (startTime) {
      results = results.filter(log => new Date(log.timestamp) >= startTime);
    }
    if (endTime) {
      results = results.filter(log => new Date(log.timestamp) <= endTime);
    }

    // 텍스트 검색
    if (query) {
      const regex = new RegExp(query, 'i');
      results = results.filter(log => 
        regex.test(log.message) || 
        regex.test(JSON.stringify(log.meta))
      );
    }

    // 제한
    return results.slice(-limit);
  }

  /**
   * 에러 보고서 생성
   */
  generateErrorReport() {
    const report = {
      generatedAt: new Date().toISOString(),
      summary: {
        totalErrors: this.errorHistory.length,
        errorsByType: Object.fromEntries(this.stats.errors),
        recentErrors: this.errorHistory.slice(-10)
      },
      systemInfo: this.getContextInfo(),
      performanceMetrics: this.getPerformanceSnapshot()
    };

    return report;
  }

  /**
   * 성능 스냅샷 생성
   */
  getPerformanceSnapshot() {
    const snapshot = {};
    
    for (const [name, metrics] of this.performanceMetrics) {
      if (metrics.length > 0) {
        snapshot[name] = {
          latest: metrics[metrics.length - 1],
          count: metrics.length,
          recent: metrics.slice(-10)
        };
      }
    }
    
    return snapshot;
  }

  /**
   * 정리
   */
  cleanup() {
    // 파일 스트림 닫기
    for (const [type, stream] of this.fileStreams) {
      stream.end();
    }
    this.fileStreams.clear();
    
    // 이벤트 리스너 제거
    this.removeAllListeners();
    
    this.info('ErrorLoggingSystem 정리 완료');
  }
}

module.exports = ErrorLoggingSystem;