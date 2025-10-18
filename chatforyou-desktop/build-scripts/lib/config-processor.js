const fs = require('fs');
const path = require('path');

/**
 * ChatForYou 설정 파일 처리 엔진
 * 웹 환경 config -> Electron 환경 config 자동 변환
 */
class ConfigProcessor {
  constructor(options = {}) {
    this.options = {
      verbose: options.verbose || false,
      createBackup: options.createBackup !== false,
      ...options
    };

    this.logger = options.logger || console;
    
    // 환경별 설정 템플릿
    this.electronConfigTemplate = {
      local: {
        API_BASE_URL: 'http://localhost:8080/chatforyou/api',
        BASE_URL: './templates',
        PLATFORM: 'electron',
        FILE_PROTOCOL: true,
        DEV_MODE: true,
        AUTO_UPDATER: false,
        GOOGLE_OAUTH: {
          API_KEY: 'AIzaSyAEqUJMfNf_mKZFqWShTDvcGFUSWRgotOM',
          AUTH_DOMAIN: 'chatforyou-77681.firebaseapp.com',
          PROJECT_ID: 'chatforyou-77681+'
        }
      },
      prod: {
        API_BASE_URL: 'https://hjproject.kro.kr/chatforyou/api',
        BASE_URL: './templates',
        PLATFORM: 'electron',
        FILE_PROTOCOL: true,
        DEV_MODE: false,
        AUTO_UPDATER: true,
        GOOGLE_OAUTH: {
          API_KEY: 'AIzaSyAEqUJMfNf_mKZFqWShTDvcGFUSWRgotOM',
          AUTH_DOMAIN: 'chatforyou-77681.firebaseapp.com',
          PROJECT_ID: 'chatforyou-77681+'
        }
      }
    };
  }

  /**
   * 웹 설정을 Electron 설정으로 변환
   */
  async convertWebConfigToElectron(sourceConfigDir, targetConfigDir, environment = 'local') {
    this.logger.info('⚙️ 설정 파일 변환 시작...');

    try {
      // 타겟 디렉토리 생성
      if (!fs.existsSync(targetConfigDir)) {
        fs.mkdirSync(targetConfigDir, { recursive: true });
      }

      // 환경별 설정 파일 변환
      await this.processConfigFile('config.local.js', sourceConfigDir, targetConfigDir, 'local');
      await this.processConfigFile('config.prod.js', sourceConfigDir, targetConfigDir, 'prod');
      
      // 현재 활성 config 파일 처리
      await this.processActiveConfigFile(sourceConfigDir, targetConfigDir, environment);

      this.logger.info('✅ 설정 파일 변환 완료');
      return true;

    } catch (error) {
      this.logger.error('❌ 설정 파일 변환 실패:', error.message);
      throw error;
    }
  }

  /**
   * 개별 설정 파일 처리
   */
  async processConfigFile(fileName, sourceDir, targetDir, environment) {
    const sourceFile = path.join(sourceDir, fileName);
    const targetFile = path.join(targetDir, fileName);

    try {
      // 원본 파일이 존재하는지 확인
      if (!fs.existsSync(sourceFile)) {
        this.logger.warn(`⚠️ 원본 설정 파일을 찾을 수 없습니다: ${sourceFile}`);
        // 기본 템플릿으로 생성
        await this.createDefaultConfig(targetFile, environment);
        return;
      }

      // 원본 설정 읽기
      const originalConfig = await this.readWebConfig(sourceFile);
      
      // Electron용 설정으로 변환
      const electronConfig = this.convertToElectronConfig(originalConfig, environment);
      
      // 변환된 설정 저장
      await this.writeElectronConfig(targetFile, electronConfig);
      
      if (this.options.verbose) {
        this.logger.info(`✅ 변환 완료: ${fileName}`);
      }

    } catch (error) {
      this.logger.error(`❌ 설정 파일 처리 실패: ${fileName}`, error.message);
      throw error;
    }
  }

  /**
   * 현재 활성 config.js 파일 처리
   */
  async processActiveConfigFile(sourceDir, targetDir, environment = 'local') {
    const sourceFile = path.join(sourceDir, 'config.js');
    const targetFile = path.join(targetDir, 'config.js');

    try {
      // 환경별 전용 설정 파일 먼저 확인
      const envSpecificFile = path.join(path.dirname(sourceFile), `config.${environment}.js`);
      
      if (fs.existsSync(envSpecificFile)) {
        // 환경별 설정 파일 사용 (config.prod.js, config.local.js)
        const originalConfig = await this.readWebConfig(envSpecificFile);
        const electronConfig = this.convertToElectronConfig(originalConfig, environment);
        
        await this.writeElectronConfig(targetFile, electronConfig);
        
        this.logger.info(`✅ 활성 설정 파일 변환 완료: config.js (${environment} 환경, ${path.basename(envSpecificFile)} 사용)`);
      } else if (fs.existsSync(sourceFile)) {
        // 기본 config.js 사용
        const originalConfig = await this.readWebConfig(sourceFile);
        const electronConfig = this.convertToElectronConfig(originalConfig, environment);
        
        await this.writeElectronConfig(targetFile, electronConfig);
        
        this.logger.info(`✅ 활성 설정 파일 변환 완료: config.js (${environment} 환경)`);
      } else {
        // 전달받은 환경으로 기본 설정 생성
        await this.createDefaultConfig(targetFile, environment);
        this.logger.info(`✅ 기본 설정 파일 생성: config.js (${environment} 환경)`);
      }
    } catch (error) {
      this.logger.error('❌ 활성 설정 파일 처리 실패:', error.message);
      throw error;
    }
  }

  /**
   * 웹 설정 파일 읽기
   */
  async readWebConfig(configPath) {
    try {
      const content = fs.readFileSync(configPath, 'utf8');
      
      // window.__CONFIG__ = { ... }; 패턴 파싱
      const configMatch = content.match(/window\.__CONFIG__\s*=\s*({[\s\S]*?});/);
      
      if (!configMatch) {
        throw new Error('올바른 설정 형식을 찾을 수 없습니다');
      }

      // JavaScript 객체 파싱
      const configStr = configMatch[1];
      
      try {
        // Function constructor를 사용한 안전한 파싱
        const parseConfig = new Function('return ' + configStr);
        return parseConfig();
      } catch (functionError) {
        // Function constructor 실패시 JSON.parse 시도
        try {
          return JSON.parse(configStr);
        } catch (jsonError) {
          throw new Error(`설정 파일 파싱 실패: ${functionError.message}`);
        }
      }
      
    } catch (error) {
      this.logger.error(`❌ 설정 파일 읽기 실패: ${configPath}`, error.message);
      throw error;
    }
  }

  /**
   * 웹 설정을 Electron 설정으로 변환
   */
  convertToElectronConfig(webConfig, environment) {
    // 기본 템플릿 가져오기
    const template = { ...this.electronConfigTemplate[environment] };
    
    // 웹 설정에서 API URL 추출
    if (webConfig.API_BASE_URL) {
      template.API_BASE_URL = webConfig.API_BASE_URL;
    }

    // 추가 설정들 병합
    const electronConfig = {
      ...template,
      ...this.extractCompatibleSettings(webConfig),
      
      // Electron 전용 설정 추가
      ELECTRON_VERSION: process.versions.electron || 'unknown',
      APP_VERSION: this.getAppVersion(),
      PLATFORM_TYPE: 'desktop',
      CONVERTED_FROM: 'web',
      CONVERSION_DATE: new Date().toISOString()
    };
    
    return electronConfig;
  }

  /**
   * 웹 설정에서 호환 가능한 설정 추출
   */
  extractCompatibleSettings(webConfig) {
    const compatibleSettings = {};
    
    // API 관련 설정
    if (webConfig.API_BASE_URL) {
      compatibleSettings.API_BASE_URL = webConfig.API_BASE_URL;
    }
    
    if (webConfig.BASE_URL) {
      // 웹의 BASE_URL은 Electron에서는 사용하지 않지만 참조용으로 보관
      compatibleSettings.WEB_BASE_URL = webConfig.BASE_URL;
    }

    // 기타 호환 가능한 설정들
    const compatibleKeys = [
      'TIMEOUT',
      'DEBUG',
      'LOG_LEVEL',
      'MAX_RETRIES',
      'CACHE_SIZE',
      'LANGUAGE',
      'THEME'
    ];

    compatibleKeys.forEach(key => {
      if (webConfig[key] !== undefined) {
        compatibleSettings[key] = webConfig[key];
      }
    });

    return compatibleSettings;
  }

  /**
   * Electron 설정 파일 쓰기
   */
  async writeElectronConfig(configPath, electronConfig) {
    try {
      // Electron용 설정 파일 생성
      const configContent = this.generateElectronConfigContent(electronConfig);
      
      // 백업 생성
      if (this.options.createBackup && fs.existsSync(configPath)) {
        await this.createBackup(configPath);
      }
      
      // 새 설정 파일 쓰기
      fs.writeFileSync(configPath, configContent, 'utf8');
      
      if (this.options.verbose) {
        this.logger.debug(`💾 설정 파일 저장: ${configPath}`);
      }
      
    } catch (error) {
      this.logger.error(`❌ 설정 파일 쓰기 실패: ${configPath}`, error.message);
      throw error;
    }
  }

  /**
   * Electron 설정 파일 내용 생성
   */
  generateElectronConfigContent(config) {
    const configStr = JSON.stringify(config, null, 2);
    
    return `// ChatForYou Electron Configuration
// Auto-generated from web config on ${new Date().toISOString()}

window.__CONFIG__ = ${configStr};

// Electron specific utilities
if (typeof require !== 'undefined') {
  try {
    const { ipcRenderer } = require('electron');
    
    // IPC 통신 헬퍼
    window.__ELECTRON__ = {
      ipc: ipcRenderer,
      platform: process.platform,
      version: process.versions.electron,
      
      // 앱 정보 가져오기
      getAppInfo: () => ipcRenderer.invoke('get-app-version'),
      
      // 윈도우 제어
      minimizeWindow: () => ipcRenderer.invoke('minimize-window'),
      maximizeWindow: () => ipcRenderer.invoke('maximize-window'),
      closeWindow: () => ipcRenderer.invoke('close-window')
    };
  } catch (error) {
    console.warn('Electron IPC not available:', error.message);
  }
}
`;
  }

  /**
   * 환경 감지 (설정 내용 기반)
   */
  detectEnvironment(configContent) {
    if (configContent.includes('localhost') || configContent.includes('127.0.0.1')) {
      return 'local';
    } else if (configContent.includes('hjproject.kro.kr') || configContent.includes('production')) {
      return 'prod';
    }
    
    // 기본값
    return 'local';
  }

  /**
   * 기본 설정 파일 생성
   */
  async createDefaultConfig(configPath, environment) {
    const defaultConfig = this.electronConfigTemplate[environment];
    await this.writeElectronConfig(configPath, defaultConfig);
    
    this.logger.info(`✅ 기본 설정 파일 생성: ${path.basename(configPath)} (${environment} 환경)`);
  }

  /**
   * 앱 버전 가져오기
   */
  getAppVersion() {
    try {
      const packagePath = path.join(__dirname, '../package.json');
      if (fs.existsSync(packagePath)) {
        const pkg = JSON.parse(fs.readFileSync(packagePath, 'utf8'));
        return pkg.version || '1.0.0';
      }
    } catch (error) {
      this.logger.warn('⚠️ package.json에서 버전 정보를 읽을 수 없습니다');
    }
    return '1.0.0';
  }

  /**
   * 백업 생성
   */
  async createBackup(configPath) {
    const backupDir = path.join(path.dirname(configPath), '.backup');
    const fileName = path.basename(configPath);
    const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
    const backupPath = path.join(backupDir, `${fileName}.backup-${timestamp}`);

    // 백업 디렉토리 생성
    if (!fs.existsSync(backupDir)) {
      fs.mkdirSync(backupDir, { recursive: true });
    }

    // 파일 백업
    fs.copyFileSync(configPath, backupPath);
    
    if (this.options.verbose) {
      this.logger.debug(`💾 설정 백업: ${backupPath}`);
    }
  }

  /**
   * 설정 검증
   */
  validateConfig(config) {
    const requiredFields = ['API_BASE_URL', 'PLATFORM'];
    const missingFields = [];

    for (const field of requiredFields) {
      if (!config[field]) {
        missingFields.push(field);
      }
    }

    if (missingFields.length > 0) {
      throw new Error(`필수 설정 필드가 없습니다: ${missingFields.join(', ')}`);
    }

    // API URL 형식 검증
    if (config.API_BASE_URL && !config.API_BASE_URL.match(/^https?:\/\/.+/)) {
      throw new Error('API_BASE_URL이 올바른 URL 형식이 아닙니다');
    }

    return true;
  }

  /**
   * 설정 비교
   */
  compareConfigs(config1, config2) {
    const differences = [];
    const allKeys = new Set([...Object.keys(config1), ...Object.keys(config2)]);

    for (const key of allKeys) {
      if (config1[key] !== config2[key]) {
        differences.push({
          key,
          value1: config1[key],
          value2: config2[key]
        });
      }
    }

    return differences;
  }

  /**
   * 설정 정보 출력
   */
  printConfigInfo(configPath) {
    try {
      const config = this.readWebConfig(configPath);
      
      this.logger.info(`\n📋 설정 정보: ${path.basename(configPath)}`);
      this.logger.info(`🌐 API URL: ${config.API_BASE_URL || 'N/A'}`);
      this.logger.info(`🖥️  플랫폼: ${config.PLATFORM || 'web'}`);
      this.logger.info(`🔧 개발 모드: ${config.DEV_MODE ? 'Yes' : 'No'}`);
      
      if (config.CONVERSION_DATE) {
        this.logger.info(`🔄 변환 일시: ${config.CONVERSION_DATE}`);
      }
      
    } catch (error) {
      this.logger.error(`❌ 설정 정보 출력 실패: ${error.message}`);
    }
  }
}

module.exports = ConfigProcessor;