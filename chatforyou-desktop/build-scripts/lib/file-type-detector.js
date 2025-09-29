const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

/**
 * 자동 파일 타입 감지 및 처리 시스템
 * 새로운 파일 타입과 패턴을 자동으로 감지하고 적절한 변환 규칙을 제안
 */
class FileTypeDetector {
  constructor(options = {}) {
    this.options = {
      verbose: options.verbose || false,
      enableLearning: options.enableLearning !== false,
      cacheResults: options.cacheResults !== false,
      ...options
    };

    this.logger = options.logger || console;
    
    // 파일 타입 캐시
    this.fileTypeCache = new Map();
    
    // 학습된 패턴 저장소
    this.learnedPatterns = new Map();
    
    // 파일 타입 분류기
    this.fileClassifiers = new Map();
    this.initializeFileClassifiers();
    
    // 내용 기반 패턴 감지기
    this.contentPatterns = new Map();
    this.initializeContentPatterns();
    
    // 통계
    this.stats = {
      filesAnalyzed: 0,
      newPatternsDetected: 0,
      typesPredicted: 0,
      accuracy: 0
    };
  }

  /**
   * 파일 분류기 초기화
   */
  initializeFileClassifiers() {
    // HTML 파일 분류기
    this.fileClassifiers.set('html', {
      extensions: ['.html', '.htm', '.xhtml'],
      contentSignatures: [
        /<!DOCTYPE\s+html/i,
        /<html[^>]*>/i,
        /<head[^>]*>/i,
        /<body[^>]*>/i
      ],
      commonPatterns: [
        /href\s*=\s*["'][^"']*["']/gi,
        /src\s*=\s*["'][^"']*["']/gi,
        /<script[^>]*>/gi,
        /<link[^>]*>/gi
      ],
      pathPatterns: [
        /static\//g,
        /js\//g,
        /css\//g,
        /images\//g,
        /fonts\//g
      ]
    });

    // CSS/SCSS 파일 분류기
    this.fileClassifiers.set('css', {
      extensions: ['.css', '.scss', '.sass', '.less'],
      contentSignatures: [
        /@import/i,
        /@media/i,
        /\{[^}]*\}/,
        /url\s*\(/i
      ],
      commonPatterns: [
        /url\s*\(\s*["']?[^"')]*["']?\s*\)/gi,
        /@import\s+["'][^"']*["']/gi,
        /background(?:-image)?\s*:\s*url/gi
      ],
      pathPatterns: [
        /\.\.\/static\//g,
        /\/static\//g,
        /fonts\//g,
        /images\//g
      ]
    });

    // JavaScript 파일 분류기
    this.fileClassifiers.set('js', {
      extensions: ['.js', '.jsx', '.ts', '.tsx', '.mjs', '.cjs'],
      contentSignatures: [
        /function\s+\w+/,
        /const\s+\w+\s*=/,
        /let\s+\w+\s*=/,
        /var\s+\w+\s*=/,
        /=>\s*{?/,
        /import\s+.*from/,
        /require\s*\(/
      ],
      commonPatterns: [
        /fetch\s*\(\s*["'][^"']*["']/gi,
        /import\s*\(\s*["'][^"']*["']/gi,
        /require\s*\(\s*["'][^"']*["']/gi,
        /\.src\s*=\s*["'][^"']*["']/gi,
        /loadScript\s*\(\s*["'][^"']*["']/gi
      ],
      pathPatterns: [
        /static\/js\//g,
        /templates\//g,
        /config\//g,
        /api\//g
      ]
    });

    // JSON 파일 분류기
    this.fileClassifiers.set('json', {
      extensions: ['.json', '.jsonc'],
      contentSignatures: [
        /^\s*\{/,
        /^\s*\[/
      ],
      commonPatterns: [
        /"[^"]*"\s*:\s*"[^"]*"/g,
        /"[^"]*"\s*:\s*\{/g,
        /"[^"]*"\s*:\s*\[/g
      ],
      pathPatterns: []
    });
  }

  /**
   * 내용 기반 패턴 초기화
   */
  initializeContentPatterns() {
    // 경로 패턴 감지기
    this.contentPatterns.set('path-detection', {
      patterns: [
        {
          name: 'static-resource-paths',
          regex: /(?:href|src|url)\s*[=:]\s*["']?[^"']*\/?(static|js|css|images|fonts)\/[^"'\s)]*["']?/gi,
          handler: (matches, filePath) => {
            return {
              type: 'resource-path',
              paths: matches,
              needsConversion: true,
              targetPattern: 'static/'
            };
          }
        },
        {
          name: 'template-paths',
          regex: /(?:fetch|import|require|loadScript)\s*\(\s*["'][^"']*(?:templates|popup)\/[^"']*["']/gi,
          handler: (matches, filePath) => {
            return {
              type: 'template-path',
              paths: matches,
              needsConversion: true,
              targetPattern: 'templates/'
            };
          }
        },
        {
          name: 'api-endpoints',
          regex: /["'][^"']*(?:api|chatforyou)\/[^"']*["']/gi,
          handler: (matches, filePath) => {
            return {
              type: 'api-endpoint',
              paths: matches,
              needsConversion: true,
              targetPattern: 'API_BASE_URL'
            };
          }
        }
      ]
    });

    // 동적 로딩 패턴 감지기
    this.contentPatterns.set('dynamic-loading', {
      patterns: [
        {
          name: 'template-literals',
          regex: /`[^`]*\$\{[^}]*\}[^`]*`/g,
          handler: (matches, filePath) => {
            const pathMatches = matches.filter(match => 
              match.includes('/') && 
              (match.includes('static') || match.includes('js') || match.includes('templates'))
            );
            return {
              type: 'template-literal',
              paths: pathMatches,
              needsConversion: pathMatches.length > 0,
              targetPattern: 'dynamic'
            };
          }
        },
        {
          name: 'async-imports',
          regex: /import\s*\(\s*["'][^"']*["']\s*\)/gi,
          handler: (matches, filePath) => {
            return {
              type: 'async-import',
              paths: matches,
              needsConversion: true,
              targetPattern: 'import'
            };
          }
        }
      ]
    });
  }

  /**
   * 파일 타입 자동 감지
   */
  async detectFileType(filePath, content = null) {
    const cacheKey = this.generateCacheKey(filePath);
    
    // 캐시에서 확인
    if (this.options.cacheResults && this.fileTypeCache.has(cacheKey)) {
      return this.fileTypeCache.get(cacheKey);
    }

    try {
      // 파일 내용 읽기 (제공되지 않은 경우)
      if (!content && fs.existsSync(filePath)) {
        content = fs.readFileSync(filePath, 'utf8');
      }

      const result = await this.analyzeFile(filePath, content);
      
      // 캐시에 저장
      if (this.options.cacheResults) {
        this.fileTypeCache.set(cacheKey, result);
      }

      this.stats.filesAnalyzed++;
      return result;

    } catch (error) {
      this.logger.error(`❌ 파일 타입 감지 실패: ${filePath}`, error.message);
      return null;
    }
  }

  /**
   * 파일 분석
   */
  async analyzeFile(filePath, content) {
    const fileExt = path.extname(filePath).toLowerCase();
    const fileName = path.basename(filePath);
    
    const analysis = {
      filePath,
      fileName,
      extension: fileExt,
      type: null,
      confidence: 0,
      patterns: [],
      conversionNeeded: false,
      suggestedRules: []
    };

    // 1. 확장자 기반 예측
    const extensionPrediction = this.predictTypeByExtension(fileExt);
    
    // 2. 내용 기반 분석
    const contentAnalysis = await this.analyzeContent(content, filePath);
    
    // 3. 패턴 기반 분석
    const patternAnalysis = await this.analyzePatterns(content, filePath);
    
    // 4. 결과 통합
    analysis.type = extensionPrediction.type || contentAnalysis.type;
    analysis.confidence = Math.max(
      extensionPrediction.confidence,
      contentAnalysis.confidence
    );
    analysis.patterns = [...contentAnalysis.patterns, ...patternAnalysis.patterns];
    analysis.conversionNeeded = analysis.patterns.some(p => p.needsConversion);
    analysis.suggestedRules = this.generateSuggestedRules(analysis);

    if (this.options.verbose) {
      this.logger.debug(`📋 파일 분석 완료: ${fileName}`);
      this.logger.debug(`   타입: ${analysis.type} (신뢰도: ${analysis.confidence}%)`);
      this.logger.debug(`   패턴 수: ${analysis.patterns.length}`);
      this.logger.debug(`   변환 필요: ${analysis.conversionNeeded ? 'Yes' : 'No'}`);
    }

    return analysis;
  }

  /**
   * 확장자 기반 타입 예측
   */
  predictTypeByExtension(extension) {
    for (const [type, classifier] of this.fileClassifiers) {
      if (classifier.extensions.includes(extension)) {
        return { type, confidence: 90 };
      }
    }
    return { type: null, confidence: 0 };
  }

  /**
   * 내용 기반 분석
   */
  async analyzeContent(content, filePath) {
    if (!content) {
      return { type: null, confidence: 0, patterns: [] };
    }

    let bestMatch = { type: null, confidence: 0 };
    const patterns = [];

    for (const [type, classifier] of this.fileClassifiers) {
      let confidence = 0;
      let matchCount = 0;

      // 시그니처 확인
      for (const signature of classifier.contentSignatures) {
        if (signature.test(content)) {
          matchCount++;
          confidence += 20;
        }
      }

      // 일반 패턴 확인
      for (const pattern of classifier.commonPatterns) {
        const matches = content.match(pattern) || [];
        if (matches.length > 0) {
          confidence += Math.min(matches.length * 5, 30);
          patterns.push({
            type: 'common-pattern',
            pattern: pattern.source,
            matches: matches.length,
            needsConversion: false
          });
        }
      }

      // 경로 패턴 확인
      for (const pathPattern of classifier.pathPatterns) {
        const matches = content.match(pathPattern) || [];
        if (matches.length > 0) {
          confidence += Math.min(matches.length * 10, 40);
          patterns.push({
            type: 'path-pattern',
            pattern: pathPattern.source,
            matches: matches.length,
            needsConversion: true
          });
        }
      }

      confidence = Math.min(confidence, 100);

      if (confidence > bestMatch.confidence) {
        bestMatch = { type, confidence };
      }
    }

    return { ...bestMatch, patterns };
  }

  /**
   * 패턴 기반 분석
   */
  async analyzePatterns(content, filePath) {
    const patterns = [];

    if (!content) {
      return { patterns };
    }

    for (const [patternType, patternGroup] of this.contentPatterns) {
      for (const patternConfig of patternGroup.patterns) {
        const matches = content.match(patternConfig.regex) || [];
        
        if (matches.length > 0) {
          const result = patternConfig.handler(matches, filePath);
          patterns.push({
            ...result,
            patternName: patternConfig.name,
            matchCount: matches.length
          });

          if (this.options.verbose) {
            this.logger.debug(`  🎯 패턴 감지: ${patternConfig.name} (${matches.length}개)`);
          }
        }
      }
    }

    return { patterns };
  }

  /**
   * 권장 규칙 생성
   */
  generateSuggestedRules(analysis) {
    const rules = [];

    if (!analysis.patterns.length) {
      return rules;
    }

    // 경로 변환 규칙 생성
    const pathPatterns = analysis.patterns.filter(p => p.needsConversion);
    
    for (const pattern of pathPatterns) {
      switch (pattern.type) {
        case 'resource-path':
          rules.push({
            name: `auto-resource-path-${Date.now()}`,
            description: '자동 감지된 리소스 경로 변환',
            pattern: /(?:href|src)\s*=\s*["'](?:\.\/)?(?:\/)?static\//gi,
            replacement: 'static/',
            priority: 'high',
            auto: true
          });
          break;
          
        case 'template-path':
          rules.push({
            name: `auto-template-path-${Date.now()}`,
            description: '자동 감지된 템플릿 경로 변환',
            pattern: /(?:fetch|import)\s*\(\s*["'](?:\.\/)?(?:\/)?templates\//gi,
            replacement: 'templates/',
            priority: 'high',
            auto: true
          });
          break;
          
        case 'template-literal':
          rules.push({
            name: `auto-template-literal-${Date.now()}`,
            description: '자동 감지된 템플릿 리터럴 변환',
            pattern: /`[^`]*\$\{[^}]*\}[^`]*static\/js\/[^`]*`/g,
            replacement: (match) => match.replace(/(?:\.\/)?(?:\/)?js\//g, 'static/js/'),
            priority: 'medium',
            auto: true
          });
          break;
      }
    }

    return rules;
  }

  /**
   * 새로운 패턴 학습
   */
  async learnNewPattern(filePath, pattern, replacement) {
    if (!this.options.enableLearning) {
      return false;
    }

    const patternKey = crypto.createHash('md5')
      .update(`${pattern.source || pattern}_${replacement}`)
      .digest('hex');

    if (!this.learnedPatterns.has(patternKey)) {
      this.learnedPatterns.set(patternKey, {
        pattern,
        replacement,
        learnedFrom: filePath,
        usageCount: 1,
        createdAt: new Date().toISOString()
      });

      this.stats.newPatternsDetected++;
      
      if (this.options.verbose) {
        this.logger.info(`🧠 새로운 패턴 학습: ${pattern.source || pattern}`);
      }

      return true;
    } else {
      // 사용 횟수 증가
      this.learnedPatterns.get(patternKey).usageCount++;
      return false;
    }
  }

  /**
   * 배치 파일 분석
   */
  async analyzeBatch(filePaths) {
    const results = [];
    const startTime = Date.now();

    this.logger.info(`🔍 배치 파일 분석 시작: ${filePaths.length}개 파일`);

    for (const filePath of filePaths) {
      try {
        const analysis = await this.detectFileType(filePath);
        if (analysis) {
          results.push(analysis);
        }
      } catch (error) {
        this.logger.error(`❌ 파일 분석 실패: ${filePath}`, error.message);
      }
    }

    const duration = Date.now() - startTime;
    
    this.logger.info(`✅ 배치 분석 완료: ${results.length}/${filePaths.length} (${duration}ms)`);
    
    // 통계 생성
    const statistics = this.generateBatchStatistics(results);
    return { results, statistics };
  }

  /**
   * 배치 통계 생성
   */
  generateBatchStatistics(results) {
    const stats = {
      totalFiles: results.length,
      byType: {},
      conversionNeeded: 0,
      averageConfidence: 0,
      topPatterns: []
    };

    let totalConfidence = 0;
    const patternCounts = new Map();

    for (const result of results) {
      // 타입별 통계
      stats.byType[result.type] = (stats.byType[result.type] || 0) + 1;
      
      // 변환 필요 파일 수
      if (result.conversionNeeded) {
        stats.conversionNeeded++;
      }
      
      // 신뢰도 누적
      totalConfidence += result.confidence;
      
      // 패턴 빈도 계산
      for (const pattern of result.patterns) {
        const key = pattern.patternName || pattern.type;
        patternCounts.set(key, (patternCounts.get(key) || 0) + pattern.matchCount);
      }
    }

    // 평균 신뢰도
    stats.averageConfidence = results.length > 0 ? 
      Math.round(totalConfidence / results.length) : 0;

    // 상위 패턴
    stats.topPatterns = Array.from(patternCounts.entries())
      .sort((a, b) => b[1] - a[1])
      .slice(0, 10)
      .map(([pattern, count]) => ({ pattern, count }));

    return stats;
  }

  /**
   * 캐시 키 생성
   */
  generateCacheKey(filePath) {
    const stats = fs.existsSync(filePath) ? fs.statSync(filePath) : {};
    return crypto.createHash('md5')
      .update(`${filePath}_${stats.mtime || Date.now()}_${stats.size || 0}`)
      .digest('hex');
  }

  /**
   * 학습된 패턴 내보내기
   */
  exportLearnedPatterns() {
    return Array.from(this.learnedPatterns.entries()).map(([key, pattern]) => ({
      id: key,
      ...pattern
    }));
  }

  /**
   * 캐시 정리
   */
  clearCache() {
    this.fileTypeCache.clear();
    this.logger.info('🧹 파일 타입 캐시가 정리되었습니다.');
  }

  /**
   * 통계 초기화
   */
  resetStats() {
    this.stats = {
      filesAnalyzed: 0,
      newPatternsDetected: 0,
      typesPredicted: 0,
      accuracy: 0
    };
  }
}

module.exports = FileTypeDetector;