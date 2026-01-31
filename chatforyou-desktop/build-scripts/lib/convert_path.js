const fs = require('fs');
const path = require('path');

/**
 * 통합 경로 변환 엔진
 * 웹 환경 -> Electron 환경 경로 자동 변환
 * JSON 설정 기반으로 동적 규칙 생성
 */
class PathConverter {
  constructor(options = {}) {
    this.options = {
      verbose: options.verbose || false,
      dryRun: options.dryRun || false,
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

    // JSON 설정 로드
    this.config = this.loadConfig();

    // JSON 외부화가 불가능한 기본 규칙 (함수형 replacement 등)
    this.baseRules = this.initializeBaseRules();

    // 정적 변환 규칙 빌드 (JSON pathMappings + 기본 규칙)
    this.conversionRules = this.buildConversionRules();
  }

  /**
   * JSON 설정 로드
   */
  loadConfig() {
    const configPath = path.join(__dirname, '../convert_path.json');
    try {
      if (fs.existsSync(configPath)) {
        return JSON.parse(fs.readFileSync(configPath, 'utf8'));
      }
    } catch (error) {
      if (this.options.verbose) {
        this.logger.warn(`⚠️ convert_path.json 로드 실패: ${error.message}`);
      }
    }
    return { basePaths: {}, pathMappings: {} };
  }

  /**
   * HTML 속성 규칙 생성
   * @private
   */
  _createAttrRule(attr, from, to, name) {
    return {
      name: name || `${attr}-${from.replace(/\//g, '-')}`,
      description: `${attr}="${from}" -> ${attr}="${to}"`,
      pattern: new RegExp(`${attr}=["']${from.replace(/\//g, '\\/')}`, 'g'),
      replacement: `${attr}="${to}`
    };
  }

  /**
   * Base href 규칙 생성
   * @private
   */
  _createBaseHrefRule(from, to, name) {
    return {
      name,
      description: `base href="${from}" -> base href="${to}"`,
      pattern: new RegExp(`<base\\s+href=["']${from.replace(/\//g, '\\/')}["']\\s*\\/?>`, 'gi'),
      replacement: `<base href="${to}">`
    };
  }

  /**
   * 다중 속성 규칙 생성 (캡처 그룹 사용)
   * @private
   */
  _createMultiAttrRule(pattern, replacement, name, description) {
    return { name, description, pattern, replacement };
  }

  /**
   * JSON 외부화가 불가능한 기본 규칙 초기화
   */
  initializeBaseRules() {
    return {
      HTML: [
        this._createBaseHrefRule('/chatforyou/', '../', 'base-href-electron'),
        this._createBaseHrefRule('./', '../', 'base-href-current-removal'),
        this._createAttrRule('href', 'static/', 'static/', 'static-href-conversion'),
        this._createAttrRule('src', 'static/', 'static/', 'static-src-conversion'),
        this._createAttrRule('href', '/static/', 'static/', 'absolute-static-conversion'),
        this._createAttrRule('src', '/static/', 'static/', 'absolute-static-src-conversion'),
        this._createAttrRule('src', 'config/', 'config/', 'script-config-conversion'),
        this._createAttrRule('href', './', '', 'relative-static-fix'),
        this._createAttrRule('src', './', '', 'relative-src-fix'),
        this._createMultiAttrRule(
          /href=["'](styles|fonts|libs|assets)\//g,
          'href="static/$1/',
          'generic-static-directory-conversion',
          'href="styles/" -> href="static/styles/"'
        ),
        this._createMultiAttrRule(
          /src=["'](styles|fonts|libs|assets)\//g,
          'src="static/$1/',
          'generic-static-src-conversion',
          'src="styles/" -> src="static/styles/"'
        ),
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
            if (filePath && filePath.includes('/static/css/')) {
              return 'url("../../static/';
            } else if (filePath && filePath.includes('/css/')) {
              return 'url("../static/';
            }
            return 'url("../static/';
          }
        },
        {
          name: 'url-relative-conversion',
          description: 'url("static/") -> 동적 상대 경로',
          pattern: /url\(["']?static\//g,
          replacement: (match, offset, string, filePath) => {
            if (filePath && filePath.includes('/static/css/')) {
              return 'url("../../static/';
            } else if (filePath && filePath.includes('/css/')) {
              return 'url("../static/';
            }
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
        // /static/ 절대경로 처리
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
        // loadScript 특수 패턴 (${popupName} 변수)
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
        // 템플릿 경로
        {
          name: 'fetch-templates-conversion',
          description: 'fetch(`templates/`) -> fetch(`templates/`)',
          pattern: /fetch\(\s*["']templates\//g,
          replacement: 'fetch(`templates/'
        },
        // 템플릿 리터럴 (이중 변환 방지 - 함수형 replacement)
        // 개행 문자 제외([^\`\n])하여 단일 줄에서만 매칭
        {
          name: 'template-literal-webrtc-conversion',
          description: 'Template literal: `images/webrtc/...` -> `static/images/webrtc/...`',
          pattern: /`([^`\n]*?)images\/webrtc\//g,
          replacement: (match, p1) => {
            // 이중 변환 방지: p1에 'static'이 포함되면 건너뜀
            if (p1.includes('static')) {
              return match;
            }
            return '`' + p1 + 'static/images/webrtc/';
          }
        },
        {
          name: 'template-literal-images-general',
          description: 'Template literal general images: `...images/...` -> `...static/images/...`',
          pattern: /`([^`\n]*?)images\/((?!webrtc)[^`\n\/]*)\//g,
          replacement: (match, p1, p2) => {
            // 이중 변환 방지: p1에 'static'이 포함되면 건너뜀
            if (p1.includes('static')) {
              return match;
            }
            return '`' + p1 + 'static/images/' + p2 + '/';
          }
        },
        // 후처리 따옴표 수정
        {
          name: 'quote-fix-webrtc-conversion',
          description: 'Fix mismatched quotes in webrtc image paths',
          pattern: /"static\/images\/webrtc\/([^'"]+)\.svg'/g,
          replacement: '"static/images/webrtc/$1.svg"'
        }
      ]
    };
  }

  /**
   * 변환 규칙 빌드 (기본 규칙 + JSON 매핑)
   */
  buildConversionRules() {
    const rules = {
      HTML: [...this.baseRules.HTML],
      CSS: [...this.baseRules.CSS],
      JS: [...this.baseRules.JS]
    };

    const mappings = this.config.pathMappings || {};

    // HTML 매핑 처리
    if (mappings.html) {
      for (const [from, to] of Object.entries(mappings.html)) {
        rules.HTML.push(...this.createHtmlRules(from, to));
      }
    }

    // JS 매핑 처리 (JSON 기반 동적 규칙 생성)
    if (mappings.js) {
      for (const [from, to] of Object.entries(mappings.js)) {
        rules.JS.push(...this.createJsRules(from, to));
      }
    }

    return rules;
  }

  /**
   * JS 파일용 변환 규칙 생성 (따옴표 보존)
   * 절대 경로(/images/)와 상대 경로(images/)를 분리하여 처리
   */
  createJsRules(from, to) {
    const escaped = from.replace(/\//g, '\\/');
    const safeName = from.replace(/\//g, '-').replace(/-$/, '');

    return [
      // 1. 절대 경로 처리 (선 처리): '/images/webrtc/' -> 'static/images/webrtc/'
      {
        name: `absolute-path-${safeName}`,
        description: `'/${from}' -> '${to}'`,
        pattern: new RegExp(`(['"])\/${escaped}`, 'g'),
        replacement: `$1${to}`
      },
      // 2. 상대 경로 처리: 'images/webrtc/' -> 'static/images/webrtc/'
      {
        name: `relative-path-${safeName}`,
        description: `'${from}' -> '${to}'`,
        pattern: new RegExp(`(['"])${escaped}`, 'g'),
        replacement: `$1${to}`
      },
      // 3. 템플릿 리터럴 (절대/상대 모두 처리, 이중 변환 방지)
      // 개행 문자 제외([^\`\n])하여 단일 줄에서만 매칭
      {
        name: `template-${safeName}`,
        description: `Template literal: \`${from}\` -> \`${to}\``,
        pattern: new RegExp(`\`([^\`\\n]*?)\\/?${escaped}`, 'g'),
        replacement: (match, p1) => {
          // 이중 변환 방지: p1에 'static'이 포함되면 건너뜀
          // (패턴의 \/?가 슬래시를 소비하여 p1이 'static'이 될 수 있음)
          if (p1.includes('static')) return match;
          return '`' + p1 + to;
        }
      }
    ];
  }

  /**
   * HTML 파일용 변환 규칙 생성
   */
  createHtmlRules(from, to) {
    const escaped = from.replace(/\//g, '\\/');
    const rules = [];

    // src="path" 패턴
    rules.push({
      name: `html-src-${from}`,
      description: `src="${from}" -> src="${to}"`,
      pattern: new RegExp(`src=["']${escaped}`, 'g'),
      replacement: `src="${to}`
    });

    // href="path" 패턴
    rules.push({
      name: `html-href-${from}`,
      description: `href="${from}" -> href="${to}"`,
      pattern: new RegExp(`href=["']${escaped}`, 'g'),
      replacement: `href="${to}`
    });

    // 하위 디렉토리 패턴 (css/rtc/, css/common/ 등)
    if (from === 'css/') {
      rules.push({
        name: `html-href-css-rtc`,
        description: `href="css/rtc/" -> href="static/css/rtc/"`,
        pattern: /href=["']css\/rtc\//g,
        replacement: 'href="static/css/rtc/'
      });
      rules.push({
        name: `html-href-css-common`,
        description: `href="css/common/" -> href="static/css/common/"`,
        pattern: /href=["']css\/common\//g,
        replacement: 'href="static/css/common/'
      });
    }

    return rules;
  }

  /**
   * basePaths에서 파일의 매핑 경로 찾기
   */
  findMappedPath(fileName) {
    const basePaths = this.config.basePaths || {};
    for (const [dirPath, fileList] of Object.entries(basePaths)) {
      if (fileList.includes(fileName)) {
        return dirPath;
      }
    }
    return null;
  }

  /**
   * 디렉토리 전체 변환
   */
  async convertDirectory(dirPath) {
    this.logger.info(`🔄 경로 변환 시작: ${dirPath}`);
    this.stats = { filesProcessed: 0, filesModified: 0, pathsConverted: 0, errors: 0 };

    try {
      await this.processDirectory(dirPath);
      this.printConversionSummary();
      return this.stats;
    } catch (error) {
      this.logger.error(`❌ 경로 변환 실패:`, error.message);
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
    return this.convertWithBasicMode(content, fileType, filePath);
  }

  /**
   * 기본 모드 변환
   */
  async convertWithBasicMode(content, fileType, filePath) {
    let convertedContent = content;
    const rules = this.conversionRules[fileType] || [];
    let filePathsConverted = 0;

    // 동적 base href 처리 (basePaths 활용)
    if (fileType === 'HTML' && filePath) {
      const fileName = path.basename(filePath);
      const mappedPath = this.findMappedPath(fileName);

      if (mappedPath) {
        // 매핑된 경로의 깊이에 따라 base href 결정
        const depth = mappedPath.split('/').length - 1;
        const baseHref = '../'.repeat(depth);

        convertedContent = convertedContent.replace(
          /<base\s+href=["'][^"']*["']\s*\/?>/gi,
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

    for (const rule of rules) {
      // 동적으로 처리된 파일의 경우 base href 변환 건너뛰기
      if (rule.name === 'base-href-normalization' && filePath) {
        const fileName = path.basename(filePath);
        const mappedPath = this.findMappedPath(fileName);
        if (mappedPath) {
          continue;
        }
      }

      const matches = convertedContent.match(rule.pattern);
      if (matches) {
        const beforeConversion = convertedContent;

        if (typeof rule.replacement === 'function') {
          convertedContent = convertedContent.replace(rule.pattern, (match, ...args) => {
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
   * 파일 확장자로 파일 타입 결정
   */
  getFileType(extension) {
    const typeMap = {
      '.html': 'HTML', '.htm': 'HTML', '.vue': 'HTML',
      '.css': 'CSS', '.scss': 'CSS', '.sass': 'CSS',
      '.js': 'JS', '.jsx': 'JS', '.ts': 'JS', '.tsx': 'JS'
    };
    return typeMap[extension] || null;
  }

  /**
   * 건너뛸 디렉토리 판단
   */
  shouldSkipDirectory(dirName) {
    const skipDirs = [
      'node_modules', '.git', '.vscode', '.idea',
      'dist', 'build', '.backup', '.cache', 'coverage',
      '.next', '.nuxt', '.output'
    ];

    return skipDirs.includes(dirName) || dirName.startsWith('.');
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
   * 변환 통계 출력
   */
  printConversionSummary() {
    this.logger.info(`\n📊 경로 변환 완료 요약:`);
    this.logger.info(`📁 처리된 파일: ${this.stats.filesProcessed}개`);
    this.logger.info(`✏️  수정된 파일: ${this.stats.filesModified}개`);
    this.logger.info(`🔄 변환된 경로: ${this.stats.pathsConverted}개`);
    this.logger.info(`❌ 오류: ${this.stats.errors}개`);

    if (this.stats.errors === 0) {
      this.logger.info(`🎉 경로 변환이 성공적으로 완료되었습니다!`);
    }
  }
}

module.exports = PathConverter;
