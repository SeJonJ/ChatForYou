const fs = require('fs');
const path = require('path');

/**
 * 통합 경로 변환 엔진
 * 웹 환경 -> Electron 환경 경로 자동 변환
 * 정적 규칙과 동적 전략을 모두 지원하는 통합 시스템
 */
class PathConverter {
  constructor(options = {}) {
    this.options = {
      verbose: options.verbose || false,
      dryRun: options.dryRun || false,
      useAdvancedMode: options.useAdvancedMode || false,
      ...options
    };

    this.logger = options.logger || console;
    
    // 변환 통계
    this.stats = {
      filesProcessed: 0,
      filesModified: 0,
      pathsConverted: 0,
      errors: 0
    };

    // 파일 타입별 변환 전략
    this.conversionStrategies = new Map();
    
    // 동적 패턴 규칙
    this.dynamicPatterns = new Map();
    
    // 정적 변환 규칙
    this.conversionRules = {
      HTML: [
        {
          name: 'base-href-electron',
          description: 'base href="/chatforyou/" -> base href="../"',
          pattern: /<base\s+href=["']\/chatforyou\/["']\s*\/?>/gi,
          replacement: '<base href="../">'
        },
        {
          name: 'base-href-current-removal',
          description: 'base href="./" -> base href="../"',
          pattern: /<base\s+href=["']\.\/">/gi,
          replacement: '<base href="../">'
        },
        {
          name: 'static-href-conversion',
          description: 'href="static/" -> href="static/"',
          pattern: /href=["']static\//g,
          replacement: 'href="static/'
        },
        {
          name: 'static-src-conversion',
          description: 'src="static/" -> src="static/"',
          pattern: /src=["']static\//g,
          replacement: 'src="static/'
        },
        {
          name: 'absolute-static-conversion',
          description: 'href="/static/" -> href="static/"',
          pattern: /href=["']\/static\//g,
          replacement: 'href="static/'
        },
        {
          name: 'absolute-static-src-conversion',
          description: 'src="/static/" -> src="static/"',
          pattern: /src=["']\/static\//g,
          replacement: 'src="static/'
        },
        {
          name: 'script-js-conversion',
          description: 'script src="js/" -> src="static/js/"',
          pattern: /src=["'](?:\.\/)?js\//g,
          replacement: 'src="static/js/'
        },
        {
          name: 'script-config-conversion',
          description: 'script src="config/" -> src="config/"',
          pattern: /src=["']config\//g,
          replacement: 'src="config/'
        },
        {
          name: 'relative-static-fix',
          description: 'href="./static/" -> href="static/"',
          pattern: /href=["']\.\//g,
          replacement: 'href="'
        },
        {
          name: 'relative-src-fix',
          description: 'src="./static/" -> src="static/"',
          pattern: /src=["']\.\//g,
          replacement: 'src="'
        },
        {
          name: 'images-path-conversion',
          description: 'src="images/" -> src="static/images/"',
          pattern: /src=["']images\//g,
          replacement: 'src="static/images/'
        },
        {
          name: 'images-href-conversion',
          description: 'href="images/" -> href="static/images/"',
          pattern: /href=["']images\//g,
          replacement: 'href="static/images/'
        },
        {
          name: 'css-rtc-conversion',
          description: 'href="css/rtc/" -> href="static/css/rtc/"',
          pattern: /href=["']css\/rtc\//g,
          replacement: 'href="static/css/rtc/'
        },
        {
          name: 'css-common-conversion',
          description: 'href="css/common/" -> href="static/css/common/"',
          pattern: /href=["']css\/common\//g,
          replacement: 'href="static/css/common/'
        },
        {
          name: 'css-directory-conversion',
          description: 'href="css/" -> href="static/css/"',
          pattern: /href=["']css\//g,
          replacement: 'href="static/css/'
        },
        {
          name: 'generic-static-directory-conversion',
          description: 'href="styles/" -> href="static/styles/"',
          pattern: /href=["'](styles|fonts|libs|assets)\//g,
          replacement: 'href="static/$1/'
        },
        {
          name: 'generic-static-src-conversion',
          description: 'src="styles/" -> src="static/styles/"',
          pattern: /src=["'](styles|fonts|libs|assets)\//g,
          replacement: 'src="static/$1/'
        },
        {
          name: 'config-script-fix',
          description: 'Fix config.js script tag quotation error',
          pattern: /src=["']config\/config\.js['"]><\/script>/g,
          replacement: 'src="config/config.js"></script>'
        }
      ],

      CSS: [
        {
          name: 'url-static-conversion',
          description: 'url("/static/") -> 동적 상대 경로',
          pattern: /url\(["']?\/static\//g,
          replacement: (match, offset, string, filePath) => {
            // CSS 파일의 위치에 따라 상대 경로 계산
            if (filePath && filePath.includes('/static/css/')) {
              // static/css/ 디렉토리에 있는 경우: ../../static/
              return 'url("../../static/';
            } else if (filePath && filePath.includes('/css/')) {
              // 다른 css/ 디렉토리에 있는 경우: ../static/
              return 'url("../static/';
            }
            // 기본값
            return 'url("../static/';
          }
        },
        {
          name: 'url-relative-conversion',
          description: 'url("static/") -> 동적 상대 경로',
          pattern: /url\(["']?static\//g,
          replacement: (match, offset, string, filePath) => {
            // CSS 파일의 위치에 따라 상대 경로 계산
            if (filePath && filePath.includes('/static/css/')) {
              // static/css/ 디렉토리에 있는 경우: ../../static/
              return 'url("../../static/';
            } else if (filePath && filePath.includes('/css/')) {
              // 다른 css/ 디렉토리에 있는 경우: ../static/
              return 'url("../static/';
            }
            // 기본값
            return 'url("../static/';
          }
        },
        {
          name: 'import-static-conversion',
          description: '@import "/static/" -> @import "../static/"',
          pattern: /@import\s+["']\/static\//g,
          replacement: '@import "../static/'
        }
      ],

      JS: [
        {
          name: 'fetch-static-conversion',
          description: 'fetch("/static/") -> fetch("./static/")',
          pattern: /fetch\(["']\/static\//g,
          replacement: 'fetch("./static/'
        },
        {
          name: 'xhr-static-conversion',
          description: 'XMLHttpRequest URL 변환',
          pattern: /["']\/static\//g,
          replacement: '"./static/'
        },
        {
          name: 'dynamic-script-loading-conversion',
          description: '`js/popup/` -> `static/js/popup/`',
          pattern: /[`'"](?:\.\/)?js\/popup\//g,
          replacement: '`static/js/popup/'
        },
        {
          name: 'loadScript-js-popup-conversion',
          description: 'loadScript(`js/popup/${popupName}_popup.js`) -> loadScript(`static/js/popup/${popupName}_popup.js`)',
          pattern: /loadScript\(\s*[`'"](?:\.\/)?js\/popup\/\$\{[^}]+\}_popup\.js[`'"]\s*\)/g,
          replacement: 'loadScript(`static/js/popup/${popupName}_popup.js`)'
        },
        {
          name: 'fetch-templates-conversion',
          description: 'fetch(`templates/`) -> fetch(`templates/`)',
          pattern: /fetch\(\s*["']templates\//g,
          replacement: 'fetch(`templates/'
        },
        {
          name: 'script-src-js-conversion',
          description: 'script.src = "js/" -> script.src = "static/js/"',
          pattern: /\.src\s*=\s*[`'"](?:\.\/)?js\//g,
          replacement: '.src = "static/js/'
        },
        {
          name: 'image-src-webrtc-conversion',
          description: '.src = "images/webrtc/" -> .src = "static/images/webrtc/"',
          pattern: /\.src\s*=\s*[`'"]images\/webrtc\//g,
          replacement: '.src = "static/images/webrtc/'
        },
        {
          name: 'image-attr-webrtc-conversion',
          description: 'attr("src", "/images/webrtc/") -> attr("src", "static/images/webrtc/")',
          pattern: /attr\(\s*[`'"](src|href)[`'"]\s*,\s*[`'"]\/images\/webrtc\//g,
          replacement: 'attr("$1", "static/images/webrtc/'
        },
        {
          name: 'absolute-path-webrtc-conversion',
          description: '절대 경로 /images/webrtc/ -> static/images/webrtc/ (슬래시 제거)',
          pattern: /(['"])\/images\/webrtc\//g,
          replacement: '$1static/images/webrtc/'
        },
        {
          name: 'image-direct-webrtc-conversion',
          description: '"/images/webrtc/" -> "static/images/webrtc/" (preserving quote type)',
          pattern: /(['"])images\/webrtc\//g,
          replacement: '$1static/images/webrtc/'
        },
        {
          name: 'quote-fix-webrtc-conversion',
          description: 'Fix mismatched quotes in webrtc image paths',
          pattern: /"static\/images\/webrtc\/([^'"]+)\.svg'/g,
          replacement: '"static/images/webrtc/$1.svg"'
        },
        {
          name: 'attr-string-template-webrtc-conversion',
          description: 'attr("src", \'images/webrtc/\' + ...) -> attr("src", \'static/images/webrtc/\' + ...)',
          pattern: /attr\(\s*['"]src['"]\s*,\s*['"]images\/webrtc\//g,
          replacement: 'attr("src", \'static/images/webrtc/'
        },
        {
          name: 'template-literal-webrtc-conversion',
          description: 'Template literal: `images/webrtc/...${...}...` -> `static/images/webrtc/...${...}...`',
          pattern: /`([^`]*?)images\/webrtc\//g,
          replacement: (match, p1) => {
            // 이미 static/으로 끝나면 변환하지 않음 (이중 변환 방지)
            if (p1.endsWith('static/')) {
              return match;
            }
            return '`' + p1 + 'static/images/webrtc/';
          }
        },
        {
          name: 'template-literal-images-general',
          description: 'Template literal general images: `...images/...` -> `...static/images/...` (non-webrtc)',
          pattern: /`([^`]*?)images\/((?!webrtc)[^`\/]*)\//g,
          replacement: (match, p1, p2) => {
            // 이미 static/으로 끝나면 변환하지 않음 (이중 변환 방지)
            if (p1.endsWith('static/')) {
              return match;
            }
            return '`' + p1 + 'static/images/' + p2 + '/';
          }
        },
        {
          name: 'ternary-operator-webrtc-conversion',
          description: '삼항 연산자 내 /images/webrtc/ 경로 변환',
          pattern: /([?:])\s*(['"])\/images\/webrtc\//g,
          replacement: '$1 $2static/images/webrtc/'
        }
      ]
    };

    if (this.options.useAdvancedMode) {
      this.initializeConversionStrategies();
      this.initializeDynamicPatterns();
    }
  }

  /**
   * 변환 전략 초기화
   */
  initializeConversionStrategies() {
    // HTML 파일 변환 전략
    this.conversionStrategies.set('html', {
      patterns: [
        {
          name: 'base-href-normalization',
          description: '일반적인 base href를 ../로 설정 (login 하위 디렉토리 제외)',
          pattern: /<base\s+href=["'][^"']*["']/gi,
          replacement: '<base href="../">'
        },
        {
          name: 'static-resource-paths',
          description: 'static 리소스 경로 정규화',
          pattern: /(href|src)=["'](?:\.\/)?(?:\/)?static\//gi,
          replacement: '$1="static/'
        },
        {
          name: 'script-source-normalization',
          description: 'script src 경로 정규화',
          pattern: /src=["'](?:\.\/)?(?:\/)?(?:js|static\/js)\//gi,
          replacement: 'src="static/js/'
        },
        {
          name: 'config-path-normalization',
          description: 'config 경로 정규화',
          pattern: /src=["'](?:\.\/)?(?:\/)?config\//gi,
          replacement: 'src="config/'
        }
      ],
      postProcess: (content, filePath) => {
        return this.normalizeHtmlPaths(content, filePath);
      }
    });

    // CSS/SCSS 파일 변환 전략
    this.conversionStrategies.set('css', {
      patterns: [
        {
          name: 'url-path-conversion',
          description: 'CSS url() 경로 변환',
          pattern: /url\(\s*["']?(?:\.\/)?(?:\/)?static\//gi,
          replacement: 'url("../static/'
        },
        {
          name: 'import-path-conversion',
          description: '@import 경로 변환',
          pattern: /@import\s+["'](?:\.\/)?(?:\/)?static\//gi,
          replacement: '@import "../static/'
        }
      ],
      postProcess: (content, filePath) => {
        return this.normalizeCssPaths(content, filePath);
      }
    });

    // JavaScript 파일 변환 전략
    this.conversionStrategies.set('js', {
      patterns: [
        {
          name: 'fetch-api-paths',
          description: 'fetch() API 경로 변환',
          pattern: /fetch\(\s*["'](?:\.\/)?(?:\/)?(?=(?:static|templates|config)\/)/gi,
          replacement: 'fetch("'
        },
        {
          name: 'dynamic-import-paths',
          description: '동적 import 경로 변환',
          pattern: /import\(\s*["'](?:\.\/)?(?:\/)?(?=(?:static|templates)\/)/gi,
          replacement: 'import("'
        },
        {
          name: 'script-loading-paths',
          description: '동적 스크립트 로딩 경로 변환',
          pattern: /(?:\.src\s*=\s*[`'"]|loadScript\(\s*[`'"])(?:\.\/)?js\//gi,
          replacement: (match) => {
            if (match.includes('loadScript')) {
              return match.replace(/(?:\.\/)?js\//, 'static/js/');
            }
            return match.replace(/(?:\.\/)?js\//, 'static/js/');
          }
        }
      ],
      postProcess: (content, filePath) => {
        return this.normalizeJavaScriptPaths(content, filePath);
      }
    });
  }

  /**
   * 동적 패턴 초기화
   */
  initializeDynamicPatterns() {
    // 템플릿 리터럴 패턴
    this.dynamicPatterns.set('template-literal', {
      pattern: /`[^`]*\${[^}]*}[^`]*`/g,
      handler: (match, filePath) => {
        // 템플릿 리터럴에서 js/로 시작하는 경로만 변환
        return match.replace(/([`'"]|\$\{[^}]*\})(?:\.\/)?js\//g, 
          (subMatch, prefix) => subMatch.replace(/(?:\.\/)?js\//, 'static/js/'));
      }
    });

    // 상대 경로 패턴
    this.dynamicPatterns.set('relative-paths', {
      pattern: /["']\.{1,2}\/[^"']*["']/g,
      handler: (match, filePath) => {
        return match;
      }
    });
  }

  /**
   * 디렉토리 전체 변환
   */
  async convertDirectory(dirPath) {
    const modeText = this.options.useAdvancedMode ? '고급' : '기본';
    this.logger.info(`🔄 ${modeText} 경로 변환 시작: ${dirPath}`);
    this.stats = { filesProcessed: 0, filesModified: 0, pathsConverted: 0, errors: 0 };

    try {
      await this.processDirectory(dirPath);
      this.printConversionSummary();
      return this.stats;
    } catch (error) {
      this.logger.error(`❌ ${modeText} 경로 변환 실패:`, error.message);
      throw error;
    }
  }

  /**
   * 재귀적으로 디렉토리 처리
   */
  async processDirectory(dirPath) {
    if (!fs.existsSync(dirPath)) {
      this.logger.warn(`⚠️ 디렉토리가 존재하지 않습니다: ${dirPath}`);
      return;
    }

    const entries = fs.readdirSync(dirPath, { withFileTypes: true });

    for (const entry of entries) {
      const fullPath = path.join(dirPath, entry.name);

      if (entry.isDirectory()) {
        if (!this.shouldSkipDirectory(entry.name)) {
          await this.processDirectory(fullPath);
        }
      } else {
        await this.processFile(fullPath);
      }
    }
  }

  /**
   * 개별 파일 처리
   */
  async processFile(filePath) {
    this.stats.filesProcessed++;

    try {
      const fileExt = path.extname(filePath).toLowerCase();
      const fileType = this.getFileType(fileExt);

      if (!fileType) {
        if (this.options.verbose) {
          this.logger.debug(`⏭️ 건너뜀: ${filePath} (지원하지 않는 파일 형식)`);
        }
        return;
      }

      const originalContent = fs.readFileSync(filePath, 'utf8');
      const convertedContent = await this.convertFileContent(originalContent, fileType, filePath);

      if (originalContent !== convertedContent) {
        if (!this.options.dryRun) {
          fs.writeFileSync(filePath, convertedContent, 'utf8');
        }
        
        this.stats.filesModified++;
        
        if (this.options.verbose) {
          this.logger.info(`✅ 변환 완료: ${filePath}`);
        }
      }

    } catch (error) {
      this.stats.errors++;
      this.logger.error(`❌ 파일 변환 실패: ${filePath}`, error.message);
    }
  }

  /**
   * 파일 내용 변환
   */
  async convertFileContent(content, fileType, filePath) {
    if (this.options.useAdvancedMode) {
      return this.convertWithAdvancedMode(content, fileType, filePath);
    } else {
      return this.convertWithBasicMode(content, fileType, filePath);
    }
  }

  /**
   * 기본 모드 변환
   */
  async convertWithBasicMode(content, fileType, filePath) {
    let convertedContent = content;
    const rules = this.conversionRules[fileType] || [];
    let filePathsConverted = 0;

    // 동적 base href 처리 (convert_path.json 활용)
    if (fileType === 'HTML' && filePath) {
      const fileName = path.basename(filePath);
      const convertPathFile = path.join(__dirname, '../convert_path.json');
      
      try {
        if (fs.existsSync(convertPathFile)) {
          const pathMapping = JSON.parse(fs.readFileSync(convertPathFile, 'utf8'));
          
          // 새로운 구조에서 파일명 찾기: {"경로": ["파일명들"]}
          let mappedPath = null;
          for (const [dirPath, fileList] of Object.entries(pathMapping)) {
            if (fileList.includes(fileName)) {
              mappedPath = dirPath;
              break;
            }
          }
          
          if (mappedPath) {
            // 매핑된 경로의 깊이에 따라 base href 결정
            const depth = mappedPath.split('/').length - 1; // /templates/login = 2, /templates/login/qr = 3
            const baseHref = '../'.repeat(depth);
            
            convertedContent = convertedContent.replace(
              /<base\s+href=["'][^"']*["']/gi, 
              `<base href="${baseHref}">`
            );
            
            if (convertedContent !== content) {
              filePathsConverted++;
              if (this.options.verbose) {
                this.logger.debug(`  🔧 동적 base href 변환: ${fileName} -> ${baseHref} (깊이: ${depth})`);
              }
            }
          }
        }
      } catch (error) {
        if (this.options.verbose) {
          this.logger.debug(`  ⚠️ convert_path.json 로드 실패: ${error.message}`);
        }
      }
    }

    for (const rule of rules) {
      // 동적으로 처리된 파일의 경우 base href 변환 건너뛰기
      if (rule.name === 'base-href-normalization' && filePath) {
        const fileName = path.basename(filePath);
        const convertPathFile = path.join(__dirname, '../convert_path.json');
        
        try {
          if (fs.existsSync(convertPathFile)) {
            const pathMapping = JSON.parse(fs.readFileSync(convertPathFile, 'utf8'));
            
            // 새로운 구조에서 파일명 찾기: {"경로": ["파일명들"]}
            let isFileMapped = false;
            for (const [dirPath, fileList] of Object.entries(pathMapping)) {
              if (fileList.includes(fileName)) {
                isFileMapped = true;
                break;
              }
            }
            
            if (isFileMapped) {
              continue; // 이미 동적으로 처리된 파일은 건너뛰기
            }
          }
        } catch (error) {
          // 에러 발생 시 기본 처리 계속
        }
      }
      
      const matches = convertedContent.match(rule.pattern);
      if (matches) {
        const beforeConversion = convertedContent;
        
        if (typeof rule.replacement === 'function') {
          convertedContent = convertedContent.replace(rule.pattern, (match, ...args) => {
            // filePath를 함수에 전달
            return rule.replacement(match, ...args, filePath);
          });
        } else {
          convertedContent = convertedContent.replace(rule.pattern, rule.replacement);
        }
        
        if (beforeConversion !== convertedContent) {
          filePathsConverted += matches.length;
          
          if (this.options.verbose) {
            this.logger.debug(`  🔧 ${rule.name}: ${matches.length}개 경로 변환`);
          }
        }
      }
    }

    this.stats.pathsConverted += filePathsConverted;

    if (filePathsConverted > 0 && this.options.verbose) {
      this.logger.info(`  📝 ${path.basename(filePath)}: ${filePathsConverted}개 경로 변환됨`);
    }

    return convertedContent;
  }

  /**
   * 고급 모드 변환
   */
  async convertWithAdvancedMode(content, fileType, filePath) {
    let convertedContent = content;
    let pathsConverted = 0;

    // 기본 패턴 적용
    const strategy = this.conversionStrategies.get(fileType);
    if (strategy) {
      for (const rule of strategy.patterns) {
        const beforeConversion = convertedContent;
        
        if (typeof rule.replacement === 'function') {
          convertedContent = convertedContent.replace(rule.pattern, (match, ...args) => {
            // filePath를 함수에 전달
            return rule.replacement(match, ...args, filePath);
          });
        } else {
          convertedContent = convertedContent.replace(rule.pattern, rule.replacement);
        }
        
        if (beforeConversion !== convertedContent) {
          const matches = beforeConversion.match(rule.pattern) || [];
          pathsConverted += matches.length;
          
          if (this.options.verbose) {
            this.logger.debug(`  🔧 ${rule.name}: ${matches.length}개 경로 변환`);
          }
        }
      }

      // 후처리 적용
      if (strategy.postProcess) {
        const beforePostProcess = convertedContent;
        convertedContent = strategy.postProcess(convertedContent, filePath);
        
        if (beforePostProcess !== convertedContent && this.options.verbose) {
          this.logger.debug(`  🎯 후처리 적용됨`);
        }
      }
    }

    // 동적 패턴 적용
    for (const [patternName, patternConfig] of this.dynamicPatterns) {
      const beforeDynamic = convertedContent;
      const matches = convertedContent.match(patternConfig.pattern) || [];
      
      for (const match of matches) {
        const converted = patternConfig.handler(match, filePath);
        if (converted !== match) {
          convertedContent = convertedContent.replace(match, converted);
          pathsConverted++;
        }
      }
      
      if (beforeDynamic !== convertedContent && this.options.verbose) {
        this.logger.debug(`  🌟 동적 패턴 ${patternName}: ${matches.length}개 처리`);
      }
    }

    this.stats.pathsConverted += pathsConverted;

    if (pathsConverted > 0 && this.options.verbose) {
      this.logger.info(`  📝 ${path.basename(filePath)}: ${pathsConverted}개 경로 변환됨`);
    }

    return convertedContent;
  }

  /**
   * HTML 경로 정규화
   */
  normalizeHtmlPaths(content, filePath) {
    let normalized = content;
    
    // 이미지 경로 정규화
    normalized = normalized.replace(
      /(src|href)=["'](?:\.\/)?(?:\/)?images\//gi,
      '$1="static/images/'
    );
    
    // 폰트 경로 정규화  
    normalized = normalized.replace(
      /(src|href)=["'](?:\.\/)?(?:\/)?fonts\//gi,
      '$1="static/fonts/'
    );
    
    return normalized;
  }

  /**
   * CSS 경로 정규화
   */
  normalizeCssPaths(content, filePath) {
    let normalized = content;
    
    // 웹폰트 경로 정규화
    normalized = normalized.replace(
      /url\(\s*["']?(?:\.\/)?(?:\/)?fonts\//gi,
      'url("../static/fonts/'
    );
    
    // 이미지 경로 정규화
    normalized = normalized.replace(
      /url\(\s*["']?(?:\.\/)?(?:\/)?images\//gi,
      'url("../images/'
    );
    
    return normalized;
  }

  /**
   * JavaScript 경로 정규화
   */
  normalizeJavaScriptPaths(content, filePath) {
    let normalized = content;
    
    // 템플릿 리터럴의 복잡한 경로 패턴 처리 (경로 시작 부분만)
    normalized = normalized.replace(
      /`([^`]*\$\{[^}]*\}[^`]*)*(?:\.\/)?js\/[^`]*`/g,
      (match) => {
        return match.replace(/(?:\.\/)?js\//, 'static/js/');
      }
    );
    
    return normalized;
  }

  /**
   * 파일 확장자로 파일 타입 결정
   */
  getFileType(extension) {
    const typeMap = this.options.useAdvancedMode ? 
      new Map([
        ['.html', 'html'],
        ['.htm', 'html'],
        ['.css', 'css'],
        ['.scss', 'css'],
        ['.sass', 'css'],
        ['.js', 'js'],
        ['.jsx', 'js'],
        ['.ts', 'js'],
        ['.tsx', 'js'],
        ['.mjs', 'js'],
        ['.cjs', 'js']
      ]) : {
        '.html': 'HTML',
        '.htm': 'HTML',
        '.css': 'CSS',
        '.scss': 'CSS',
        '.sass': 'CSS',
        '.less': 'CSS',
        '.stylus': 'CSS',
        '.js': 'JS',
        '.jsx': 'JS',
        '.ts': 'JS',
        '.tsx': 'JS',
        '.vue': 'HTML',
        '.mjs': 'JS',
        '.cjs': 'JS'
      };

    return this.options.useAdvancedMode ? 
      typeMap.get(extension) || null : 
      typeMap[extension] || null;
  }

  /**
   * 건너뛸 디렉토리 판단
   */
  shouldSkipDirectory(dirName) {
    const skipDirs = this.options.useAdvancedMode ? 
      new Set([
        'node_modules', '.git', '.vscode', '.idea', 
        'dist', 'build', '.backup', '.cache', 'coverage',
        '.next', '.nuxt', '.output'
      ]) : [
        'node_modules',
        '.git',
        '.vscode',
        '.idea',
        'dist',
        'build',
        '.backup',
        '.cache',
        'coverage'
      ];

    return this.options.useAdvancedMode ? 
      skipDirs.has(dirName) || dirName.startsWith('.') :
      skipDirs.includes(dirName) || dirName.startsWith('.');
  }

  /**
   * 단일 파일 변환 (외부 API)
   */
  async convertFile(filePath) {
    if (!fs.existsSync(filePath)) {
      throw new Error(`파일을 찾을 수 없습니다: ${filePath}`);
    }

    await this.processFile(filePath);
    return this.stats;
  }

  /**
   * 변환 규칙 추가
   */
  addConversionRule(fileType, rule) {
    if (this.options.useAdvancedMode) {
      const strategy = this.conversionStrategies.get(fileType);
      if (!strategy) {
        this.conversionStrategies.set(fileType, { patterns: [], postProcess: null });
      }
      
      this.conversionStrategies.get(fileType).patterns.push(rule);
      this.logger.info(`✅ 동적 변환 규칙 추가: ${fileType} - ${rule.name}`);
    } else {
      if (!this.conversionRules[fileType]) {
        this.conversionRules[fileType] = [];
      }

      // 규칙 유효성 검사
      if (!rule.name || !rule.pattern || typeof rule.replacement === 'undefined') {
        throw new Error('변환 규칙에는 name, pattern, replacement가 필요합니다');
      }

      this.conversionRules[fileType].push(rule);
      this.logger.info(`✅ 변환 규칙 추가: ${fileType} - ${rule.name}`);
    }
  }

  /**
   * 동적 패턴 추가
   */
  addDynamicPattern(name, pattern, handler) {
    if (!this.options.useAdvancedMode) {
      this.logger.warn('⚠️ 동적 패턴은 고급 모드에서만 지원됩니다');
      return;
    }

    this.dynamicPatterns.set(name, { pattern, handler });
    this.logger.info(`✅ 동적 패턴 추가: ${name}`);
  }

  /**
   * 변환 미리보기 (dry run)
   */
  async previewConversion(filePath) {
    const originalDryRun = this.options.dryRun;
    const originalVerbose = this.options.verbose;
    
    this.options.dryRun = true;
    this.options.verbose = true;

    try {
      if (fs.statSync(filePath).isDirectory()) {
        await this.convertDirectory(filePath);
      } else {
        await this.convertFile(filePath);
      }
    } finally {
      this.options.dryRun = originalDryRun;
      this.options.verbose = originalVerbose;
    }

    return this.stats;
  }

  /**
   * 변환 통계 출력
   */
  printConversionSummary() {
    const modeText = this.options.useAdvancedMode ? '고급' : '기본';
    this.logger.info(`\n📊 ${modeText} 경로 변환 완료 요약:`);
    this.logger.info(`📁 처리된 파일: ${this.stats.filesProcessed}개`);
    this.logger.info(`✏️  수정된 파일: ${this.stats.filesModified}개`);
    this.logger.info(`🔄 변환된 경로: ${this.stats.pathsConverted}개`);
    this.logger.info(`❌ 오류: ${this.stats.errors}개`);

    if (this.stats.errors === 0) {
      this.logger.info(`🎉 ${modeText} 경로 변환이 성공적으로 완료되었습니다!`);
    }
  }

  /**
   * 변환 가능한 파일 목록 조회
   */
  getConvertibleFiles(dirPath) {
    const convertibleFiles = [];
    
    const walkDirectory = (currentPath) => {
      const entries = fs.readdirSync(currentPath, { withFileTypes: true });
      
      for (const entry of entries) {
        const fullPath = path.join(currentPath, entry.name);
        
        if (entry.isDirectory() && !this.shouldSkipDirectory(entry.name)) {
          walkDirectory(fullPath);
        } else if (entry.isFile()) {
          const fileExt = path.extname(entry.name).toLowerCase();
          if (this.getFileType(fileExt)) {
            convertibleFiles.push({
              path: fullPath,
              type: this.getFileType(fileExt),
              size: fs.statSync(fullPath).size
            });
          }
        }
      }
    };

    if (fs.existsSync(dirPath)) {
      walkDirectory(dirPath);
    }

    return convertibleFiles;
  }

  /**
   * 백업 생성
   */
  async createBackup(filePath, backupDir) {
    const fileName = path.basename(filePath);
    const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
    const backupFileName = `${fileName}.backup-${timestamp}`;
    const backupPath = path.join(backupDir, backupFileName);

    // 백업 디렉토리 생성
    if (!fs.existsSync(backupDir)) {
      fs.mkdirSync(backupDir, { recursive: true });
    }

    // 파일 백업
    fs.copyFileSync(filePath, backupPath);
    
    this.logger.info(`💾 백업 생성: ${backupPath}`);
    return backupPath;
  }

  /**
   * 모드 전환
   */
  setAdvancedMode(enabled) {
    this.options.useAdvancedMode = enabled;
    
    if (enabled && this.conversionStrategies.size === 0) {
      this.initializeConversionStrategies();
      this.initializeDynamicPatterns();
    }

    this.logger.info(`🔧 ${enabled ? '고급' : '기본'} 모드로 전환되었습니다`);
  }
}

module.exports = PathConverter;