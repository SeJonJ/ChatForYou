#!/usr/bin/env node

/**
 * ChatForYou 동기화 검증 스크립트
 * 동기화된 파일들의 무결성과 경로 변환 결과를 검증
 */

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

class SyncValidator {
  constructor() {
    this.projectRoot = path.join(__dirname, '../..');
    this.nodejsFrontendPath = path.join(this.projectRoot, 'nodejs-frontend');
    this.desktopSrcPath = path.join(__dirname, '../src');
    
    this.results = {
      totalChecks: 0,
      passedChecks: 0,
      failedChecks: 0,
      warnings: 0,
      errors: []
    };
  }

  /**
   * 메인 검증 실행
   */
  async validate() {
    console.log('\n🔍 ChatForYou 동기화 검증 시작...\n');

    try {
      // 1. 파일 존재 여부 검증
      await this.validateFileExistence();
      
      // 2. 파일 크기 검증
      await this.validateFileSizes();
      
      // 3. 경로 변환 검증
      await this.validatePathConversion();
      
      // 4. 설정 파일 검증
      await this.validateConfigFiles();
      
      // 5. HTML 구문 검증
      await this.validateHtmlSyntax();
      
      // 6. CSS 구문 검증
      await this.validateCssSyntax();
      
      // 결과 출력
      this.printResults();
      
      return this.results.failedChecks === 0;
      
    } catch (error) {
      console.error('❌ 검증 중 오류 발생:', error.message);
      return false;
    }
  }

  /**
   * 파일 존재 여부 검증
   */
  async validateFileExistence() {
    console.log('📁 파일 존재 여부 검증 중...');
    
    const criticalFiles = [
      'src/static/css',
      'src/static/js', 
      'src/static/images',
      'src/static/fonts',
      'src/templates',
      'src/config'
    ];

    for (const file of criticalFiles) {
      const fullPath = path.join(__dirname, '..', file);
      this.checkFile(fullPath, `필수 디렉토리: ${file}`);
    }

    // HTML 템플릿 파일 확인
    const templateFiles = [
      'src/templates/roomlist.html',
      'src/templates/chatlogin.html',
      'src/templates/kurentoroom.html'
    ];

    for (const template of templateFiles) {
      const fullPath = path.join(__dirname, '..', template);
      this.checkFile(fullPath, `템플릿 파일: ${template}`);
    }
  }

  /**
   * 파일 크기 검증
   */
  async validateFileSizes() {
    console.log('📏 파일 크기 검증 중...');
    
    const sourceStatic = path.join(this.nodejsFrontendPath, 'static');
    const targetStatic = path.join(this.desktopSrcPath, 'static');
    
    if (fs.existsSync(sourceStatic) && fs.existsSync(targetStatic)) {
      await this.compareDirectorySizes(sourceStatic, targetStatic, 'static');
    }

    const sourceTemplates = path.join(this.nodejsFrontendPath, 'templates');
    const targetTemplates = path.join(this.desktopSrcPath, 'templates');
    
    if (fs.existsSync(sourceTemplates) && fs.existsSync(targetTemplates)) {
      await this.compareDirectorySizes(sourceTemplates, targetTemplates, 'templates');
    }
  }

  /**
   * 경로 변환 검증
   */
  async validatePathConversion() {
    console.log('🔄 경로 변환 검증 중...');
    
    const templateDir = path.join(this.desktopSrcPath, 'templates');
    if (!fs.existsSync(templateDir)) {
      this.addError('templates 디렉토리가 존재하지 않습니다');
      return;
    }

    const htmlFiles = this.getHtmlFiles(templateDir);
    
    for (const htmlFile of htmlFiles) {
      await this.validateHtmlPathConversion(htmlFile);
    }

    const cssDir = path.join(this.desktopSrcPath, 'static/css');
    if (fs.existsSync(cssDir)) {
      const cssFiles = this.getCssFiles(cssDir);
      
      for (const cssFile of cssFiles) {
        await this.validateCssPathConversion(cssFile);
      }
    }
  }

  /**
   * HTML 파일 경로 변환 검증
   */
  async validateHtmlPathConversion(htmlFile) {
    try {
      const content = fs.readFileSync(htmlFile, 'utf8');
      const fileName = path.basename(htmlFile);
      
      // 1. base href 검증
      if (content.includes('<base href="/chatforyou/"')) {
        this.addError(`${fileName}: base href가 변환되지 않았습니다`);
      } else if (content.includes('<base href="./">')) {
        this.addPass(`${fileName}: base href 변환 완료`);
      }
      
      // 2. static 경로 검증
      const staticPatterns = [
        /href=["']static\//g,
        /src=["']static\//g,
        /href=["']\/static\//g,
        /src=["']\/static\//g
      ];
      
      for (const pattern of staticPatterns) {
        if (pattern.test(content)) {
          this.addError(`${fileName}: 변환되지 않은 static 경로 발견`);
        }
      }
      
      // 3. 상대 경로 확인
      const relativePatterns = [
        /href=["']\.\//g,
        /src=["']\.\//g
      ];
      
      let hasRelativePaths = false;
      for (const pattern of relativePatterns) {
        if (pattern.test(content)) {
          hasRelativePaths = true;
          break;
        }
      }
      
      if (hasRelativePaths) {
        this.addPass(`${fileName}: 상대 경로 변환 확인됨`);
      }
      
    } catch (error) {
      this.addError(`${path.basename(htmlFile)} 읽기 실패: ${error.message}`);
    }
  }

  /**
   * CSS 파일 경로 변환 검증
   */
  async validateCssPathConversion(cssFile) {
    try {
      const content = fs.readFileSync(cssFile, 'utf8');
      const fileName = path.basename(cssFile);
      
      // 절대 경로 확인
      if (content.includes('url("/static/') || content.includes("url('/static/")) {
        this.addError(`${fileName}: 변환되지 않은 절대 경로 발견`);
      }
      
      // 상대 경로 확인
      if (content.includes('url("../') || content.includes("url('../")) {
        this.addPass(`${fileName}: 상대 경로 변환 확인됨`);
      }
      
    } catch (error) {
      this.addError(`${path.basename(cssFile)} 읽기 실패: ${error.message}`);
    }
  }

  /**
   * 설정 파일 검증
   */
  async validateConfigFiles() {
    console.log('⚙️ 설정 파일 검증 중...');
    
    const configDir = path.join(this.desktopSrcPath, 'config');
    const configFiles = ['config.js', 'config.local.js', 'config.prod.js'];
    
    for (const configFile of configFiles) {
      const configPath = path.join(configDir, configFile);
      
      if (fs.existsSync(configPath)) {
        await this.validateConfigFile(configPath);
      } else if (configFile === 'config.js') {
        this.addError(`필수 설정 파일이 없습니다: ${configFile}`);
      } else {
        this.addWarning(`선택적 설정 파일이 없습니다: ${configFile}`);
      }
    }
  }

  /**
   * 개별 설정 파일 검증
   */
  async validateConfigFile(configPath) {
    try {
      const content = fs.readFileSync(configPath, 'utf8');
      const fileName = path.basename(configPath);
      
      // 1. window.__CONFIG__ 형식 확인
      if (!content.includes('window.__CONFIG__')) {
        this.addError(`${fileName}: window.__CONFIG__ 형식이 아닙니다`);
        return;
      }
      
      // 2. API_BASE_URL 확인
      if (!content.includes('API_BASE_URL')) {
        this.addError(`${fileName}: API_BASE_URL이 없습니다`);
      } else {
        this.addPass(`${fileName}: API_BASE_URL 확인됨`);
      }
      
      // 3. JavaScript 구문 검증
      try {
        const configMatch = content.match(/window\.__CONFIG__\s*=\s*({[\s\S]*?});/);
        if (configMatch) {
          const configStr = configMatch[1];
          new Function('return (' + configStr + ')')(); // 구문 검증
          this.addPass(`${fileName}: JavaScript 구문 유효함`);
        }
      } catch (error) {
        this.addError(`${fileName}: JavaScript 구문 오류: ${error.message}`);
      }
      
    } catch (error) {
      this.addError(`${path.basename(configPath)} 읽기 실패: ${error.message}`);
    }
  }

  /**
   * HTML 구문 검증
   */
  async validateHtmlSyntax() {
    console.log('📄 HTML 구문 검증 중...');
    
    const templateDir = path.join(this.desktopSrcPath, 'templates');
    if (!fs.existsSync(templateDir)) return;
    
    const htmlFiles = this.getHtmlFiles(templateDir);
    
    for (const htmlFile of htmlFiles) {
      await this.validateHtmlFile(htmlFile);
    }
  }

  /**
   * HTML 파일 구문 검증
   */
  async validateHtmlFile(htmlFile) {
    try {
      const content = fs.readFileSync(htmlFile, 'utf8');
      const fileName = path.basename(htmlFile);
      
      // 기본적인 HTML 구조 확인
      const hasDoctype = content.includes('<!DOCTYPE html>');
      const hasHtml = content.includes('<html');
      const hasHead = content.includes('<head>');
      const hasBody = content.includes('<body');
      
      if (hasDoctype && hasHtml && hasHead && hasBody) {
        this.addPass(`${fileName}: HTML 구조 유효함`);
      } else {
        this.addWarning(`${fileName}: HTML 구조가 불완전할 수 있습니다`);
      }
      
      // 닫히지 않은 태그 검사 (간단한 검증)
      const openTags = (content.match(/<[^\/][^>]*>/g) || []).length;
      const closeTags = (content.match(/<\/[^>]*>/g) || []).length;
      const selfClosingTags = (content.match(/<[^>]*\/>/g) || []).length;
      
      if (Math.abs(openTags - closeTags - selfClosingTags) > 5) { // 5개 이상 차이나면 경고
        this.addWarning(`${fileName}: 태그 불균형 의심됨`);
      }
      
    } catch (error) {
      this.addError(`${path.basename(htmlFile)} HTML 검증 실패: ${error.message}`);
    }
  }

  /**
   * CSS 구문 검증
   */
  async validateCssSyntax() {
    console.log('🎨 CSS 구문 검증 중...');
    
    const cssDir = path.join(this.desktopSrcPath, 'static/css');
    if (!fs.existsSync(cssDir)) return;
    
    const cssFiles = this.getCssFiles(cssDir);
    
    for (const cssFile of cssFiles) {
      await this.validateCssFile(cssFile);
    }
  }

  /**
   * CSS 파일 구문 검증
   */
  async validateCssFile(cssFile) {
    try {
      const content = fs.readFileSync(cssFile, 'utf8');
      const fileName = path.basename(cssFile);
      
      // 기본적인 CSS 구문 오류 검사
      const openBraces = (content.match(/{/g) || []).length;
      const closeBraces = (content.match(/}/g) || []).length;
      
      if (openBraces === closeBraces) {
        this.addPass(`${fileName}: CSS 중괄호 균형 유지됨`);
      } else {
        this.addError(`${fileName}: CSS 중괄호 불균형 (열림: ${openBraces}, 닫힘: ${closeBraces})`);
      }
      
      // 문법 오류 패턴 검사
      const errorPatterns = [
        /;;+/g,  // 세미콜론 중복
        /\{\s*\}/g,  // 빈 규칙
        /[^}]\s*$/g  // 닫히지 않은 규칙
      ];
      
      let hasErrors = false;
      for (const pattern of errorPatterns) {
        if (pattern.test(content)) {
          hasErrors = true;
          break;
        }
      }
      
      if (!hasErrors) {
        this.addPass(`${fileName}: 기본 CSS 구문 검증 통과`);
      }
      
    } catch (error) {
      this.addError(`${path.basename(cssFile)} CSS 검증 실패: ${error.message}`);
    }
  }

  /**
   * 디렉토리 크기 비교
   */
  async compareDirectorySizes(sourceDir, targetDir, dirName) {
    try {
      const sourceSize = await this.getDirectorySize(sourceDir);
      const targetSize = await this.getDirectorySize(targetDir);
      
      const sizeDiff = Math.abs(sourceSize - targetSize);
      const sizeRatio = sizeDiff / sourceSize;
      
      if (sizeRatio < 0.1) { // 10% 이내 차이는 허용
        this.addPass(`${dirName}: 크기 검증 통과 (원본: ${this.formatSize(sourceSize)}, 복사본: ${this.formatSize(targetSize)})`);
      } else {
        this.addWarning(`${dirName}: 크기 차이 발견 (원본: ${this.formatSize(sourceSize)}, 복사본: ${this.formatSize(targetSize)})`);
      }
      
    } catch (error) {
      this.addError(`${dirName} 크기 비교 실패: ${error.message}`);
    }
  }

  /**
   * 디렉토리 크기 계산
   */
  async getDirectorySize(dirPath) {
    let totalSize = 0;
    
    const walk = (currentPath) => {
      const stat = fs.statSync(currentPath);
      
      if (stat.isFile()) {
        totalSize += stat.size;
      } else if (stat.isDirectory()) {
        const files = fs.readdirSync(currentPath);
        files.forEach(file => {
          walk(path.join(currentPath, file));
        });
      }
    };
    
    walk(dirPath);
    return totalSize;
  }

  /**
   * HTML 파일 목록 가져오기
   */
  getHtmlFiles(dir) {
    const htmlFiles = [];
    
    const walk = (currentDir) => {
      const entries = fs.readdirSync(currentDir, { withFileTypes: true });
      
      for (const entry of entries) {
        const fullPath = path.join(currentDir, entry.name);
        
        if (entry.isDirectory()) {
          walk(fullPath);
        } else if (entry.isFile() && path.extname(entry.name).toLowerCase() === '.html') {
          htmlFiles.push(fullPath);
        }
      }
    };
    
    walk(dir);
    return htmlFiles;
  }

  /**
   * CSS 파일 목록 가져오기
   */
  getCssFiles(dir) {
    const cssFiles = [];
    
    const walk = (currentDir) => {
      const entries = fs.readdirSync(currentDir, { withFileTypes: true });
      
      for (const entry of entries) {
        const fullPath = path.join(currentDir, entry.name);
        
        if (entry.isDirectory()) {
          walk(fullPath);
        } else if (entry.isFile() && path.extname(entry.name).toLowerCase() === '.css') {
          cssFiles.push(fullPath);
        }
      }
    };
    
    walk(dir);
    return cssFiles;
  }

  /**
   * 파일 존재 확인
   */
  checkFile(filePath, description) {
    this.results.totalChecks++;
    
    if (fs.existsSync(filePath)) {
      this.results.passedChecks++;
      console.log(`✅ ${description}`);
    } else {
      this.results.failedChecks++;
      this.results.errors.push(`❌ ${description} - 파일/디렉토리를 찾을 수 없습니다: ${filePath}`);
      console.log(`❌ ${description}`);
    }
  }

  /**
   * 성공 추가
   */
  addPass(message) {
    this.results.totalChecks++;
    this.results.passedChecks++;
    console.log(`✅ ${message}`);
  }

  /**
   * 오류 추가
   */
  addError(message) {
    this.results.totalChecks++;
    this.results.failedChecks++;
    this.results.errors.push(message);
    console.log(`❌ ${message}`);
  }

  /**
   * 경고 추가
   */
  addWarning(message) {
    this.results.warnings++;
    console.log(`⚠️ ${message}`);
  }

  /**
   * 크기 포맷팅
   */
  formatSize(bytes) {
    const units = ['B', 'KB', 'MB', 'GB'];
    let size = bytes;
    let unitIndex = 0;
    
    while (size >= 1024 && unitIndex < units.length - 1) {
      size /= 1024;
      unitIndex++;
    }
    
    return `${size.toFixed(1)} ${units[unitIndex]}`;
  }

  /**
   * 결과 출력
   */
  printResults() {
    console.log('\n' + '='.repeat(60));
    console.log('📊 검증 결과');
    console.log('='.repeat(60));
    
    console.log(`총 검증 항목: ${this.results.totalChecks}개`);
    console.log(`통과: ${this.results.passedChecks}개`);
    console.log(`실패: ${this.results.failedChecks}개`);
    console.log(`경고: ${this.results.warnings}개`);
    
    if (this.results.failedChecks > 0) {
      console.log('\n❌ 실패한 검증 항목:');
      this.results.errors.forEach(error => {
        console.log(`   ${error}`);
      });
    }
    
    console.log('='.repeat(60));
    
    if (this.results.failedChecks === 0) {
      console.log('🎉 모든 검증을 통과했습니다!');
    } else {
      console.log(`⚠️ ${this.results.failedChecks}개의 문제를 해결해야 합니다.`);
    }
    
    console.log('');
  }
}

// 스크립트 실행
if (require.main === module) {
  const validator = new SyncValidator();
  validator.validate().then(success => {
    process.exit(success ? 0 : 1);
  }).catch(error => {
    console.error('❌ 검증 실행 실패:', error.message);
    process.exit(1);
  });
}

module.exports = SyncValidator;