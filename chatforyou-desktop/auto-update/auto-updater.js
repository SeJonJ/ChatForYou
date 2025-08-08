const { autoUpdater } = require('electron-updater');
const { dialog } = require('electron');
const path = require('path');

/**
 * ChatForYou 자동 업데이트 관리자
 */
class AutoUpdateManager {
  constructor(options = {}) {
    this.mainWindow = options.mainWindow;
    this.isDev = options.isDev || false;
    this.logger = options.logger || console;
    
    this.updateWindow = null;
    this.updateInfo = null;
    
    this.setupAutoUpdater();
  }

  setupAutoUpdater() {
    // 개발 모드에서 업데이트 테스트를 위한 설정
    if (this.isDev) {
      const forceDevUpdate = process.env.FORCE_DEV_UPDATE === 'true' || 
                           process.argv.includes('--force-dev-update') ||
                           process.argv.includes('FORCE_DEV_UPDATE=true');
      
      this.logger.info(`개발 모드 감지. FORCE_DEV_UPDATE=${process.env.FORCE_DEV_UPDATE}, argv=${process.argv.join(' ')}`);
      
      if (!forceDevUpdate) {
        this.logger.info('개발 모드에서는 자동 업데이트를 비활성화합니다. (FORCE_DEV_UPDATE=true로 활성화 가능)');
        return;
      } else {
        this.logger.info('개발 모드에서 강제 업데이트 테스트 활성화');
        // 개발용 업데이트 설정 파일 사용
        autoUpdater.updateConfigPath = path.join(__dirname, 'dev-app-update.yml');
      }
    }
    
    autoUpdater.logger = this.logger;
    autoUpdater.autoDownload = false; // 수동으로 다운로드 확인하도록 변경
    
    autoUpdater.on('checking-for-update', () => {
      this.logger.info('업데이트 확인 중...');
      if (this.mainWindow) this.mainWindow.webContents.send('update:checking');
    });

    autoUpdater.on('update-available', async (info) => {
      this.logger.info(`업데이트 있음. 최신버전: ${info.version}`);
      this.updateInfo = info;
      if (this.mainWindow) this.mainWindow.webContents.send('update:available', info.version);
      
      // 웹 기반 업데이트 팝업 표시
      this.createUpdateWindow(info.version);
    });

    autoUpdater.on('update-not-available', async (info) => {
      this.logger.info('현재 최신버전입니다.');
      if (this.mainWindow) this.mainWindow.webContents.send('update:not-available');
      
      // 수동 업데이트 확인 시에만 다이얼로그 표시
      if (this.updateInfo === 'manual-check') {
        await dialog.showMessageBox(this.mainWindow, {
          type: 'info',
          title: '업데이트 확인',
          message: '현재 최신 버전을 사용 중입니다.',
          detail: `현재 버전: ${require('electron').app.getVersion()}`,
          buttons: ['확인']
        });
        this.updateInfo = null;
      }
    });

    autoUpdater.on('error', async (err) => {
      const errorMessage = String(err);
      this.logger.error('업데이트 오류: ' + errorMessage);
      if (this.mainWindow) this.mainWindow.webContents.send('update:error', errorMessage);
      
      // app-update.yml 없을 때는 조용히 처리
      if (errorMessage.includes('app-update.yml') || errorMessage.includes('ENOENT')) {
        this.logger.info('업데이트 설정 파일이 없습니다. 개발 환경으로 판단됩니다.');
        return;
      }
      
      // 기타 오류 다이얼로그 표시
      await dialog.showMessageBox(this.mainWindow, {
        type: 'error',
        title: '업데이트 오류',
        message: '업데이트 중 오류가 발생했습니다.',
        detail: errorMessage,
        buttons: ['확인']
      });
    });

    autoUpdater.on('download-progress', (progressObj) => {
      this.logger.info(`업데이트 다운로드 중... ${progressObj.percent.toFixed(1)}%`);
      const progressData = {
        percent: progressObj.percent,
        bytesPerSecond: progressObj.bytesPerSecond,
        transferred: progressObj.transferred,
        total: progressObj.total
      };
      
      if (this.mainWindow) {
        this.mainWindow.webContents.send('update:progress', progressData);
      }
      if (this.updateWindow) {
        this.updateWindow.webContents.send('update:progress', progressData);
      }
    });

    autoUpdater.on('update-downloaded', async (info) => {
      this.logger.info('업데이트 다운로드 완료. 설치 확인 대기 중');
      if (this.mainWindow) this.mainWindow.webContents.send('update:downloaded', info.version);
      if (this.updateWindow) this.updateWindow.webContents.send('update:downloaded', info.version);
    });
  }

  createUpdateWindow(updateVersion) {
    if (this.updateWindow) {
      this.updateWindow.focus();
      return this.updateWindow;
    }

    this.logger.info('업데이트 팝업 창 생성 중...');
    const { BrowserWindow } = require('electron');
    
    this.updateWindow = new BrowserWindow({
      width: 550,
      height: 650,
      minWidth: 500,
      minHeight: 600,
      title: '업데이트 알림',
      icon: path.join(__dirname, '../src/static/images/logo/chatforyou_logo.png'),
      webPreferences: {
        nodeIntegration: false,
        contextIsolation: true,
        enableRemoteModule: false,
        preload: path.join(__dirname, '../src/main/preload.js'),
        webSecurity: true,
        allowRunningInsecureContent: false
      },
      show: false,
      resizable: false,
      modal: true,
      parent: this.mainWindow,
      titleBarStyle: process.platform === 'darwin' ? 'hiddenInset' : 'default',
      closable: false,        // X 버튼 비활성화
      minimizable: false,     // 최소화 방지
      maximizable: false      // 최대화 방지
    });

    const updateUrl = `file://${path.join(__dirname, 'update_popup.html')}?version=${updateVersion}`;
    this.updateWindow.loadURL(updateUrl);
    this.logger.info(`업데이트 팝업 로드: ${updateUrl}`);

    if (this.isDev) {
      this.updateWindow.webContents.openDevTools();
      this.logger.debug('업데이트 팝업 개발자 도구 열기');
    }

    this.updateWindow.once('ready-to-show', () => {
      this.updateWindow.show();
      this.logger.info('업데이트 팝업 표시');
    });

    this.updateWindow.on('closed', () => {
      this.logger.info('업데이트 팝업 닫힘');
      this.updateWindow = null;
    });

    this.updateWindow.webContents.on('did-finish-load', () => {
      this.logger.info('업데이트 팝업 로드 완료');
    });

    return this.updateWindow;
  }

  closeUpdateWindow() {
    if (this.updateWindow) {
      this.updateWindow.close();
      this.updateWindow = null;
    }
    
    // 강제 업데이트: 업데이트 창을 닫으면 앱 종료
    if (this.updateInfo && typeof this.updateInfo === 'object') {
      this.logger.info('강제 업데이트: 사용자가 업데이트를 거부하여 앱 종료');
      const { app } = require('electron');
      app.quit();
    }
  }

  async checkForUpdates() {
    if (!this.isDev) {
      this.logger.info('수동 업데이트 확인 요청');
      this.updateInfo = 'manual-check'; // 수동 확인 표시
      
      try {
        await autoUpdater.checkForUpdatesAndNotify();
        return { success: true };
      } catch (error) {
        this.logger.error('수동 업데이트 확인 오류:', error);
        return { success: false, error: String(error) };
      }
    } else {
      return { success: false, error: '개발 모드에서는 업데이트를 확인할 수 없습니다.' };
    }
  }

  async startDownload() {
    if (this.updateInfo && typeof this.updateInfo === 'object') {
      this.logger.info('업데이트 다운로드 시작 요청');
      autoUpdater.downloadUpdate();
      return { success: true };
    } else {
      return { success: false, error: '사용 가능한 업데이트가 없습니다.' };
    }
  }

  async installUpdate() {
    this.logger.info('업데이트 설치 요청');
    // 업데이트 설치 전 1초 대기
    setTimeout(() => {
      autoUpdater.quitAndInstall();
    }, 1000);
    return { success: true };
  }

  getUpdateStatus() {
    return {
      hasUpdate: this.updateInfo && typeof this.updateInfo === 'object',
      isChecking: this.updateInfo === 'checking',
      isManualCheck: this.updateInfo === 'manual-check',
      updateVersion: this.updateInfo && typeof this.updateInfo === 'object' ? this.updateInfo.version : null
    };
  }

  getUpdateInfo() {
    const { app } = require('electron');
    return {
      currentVersion: app.getVersion(),
      updateInfo: this.updateInfo,
      isDev: this.isDev
    };
  }

  setMainWindow(mainWindow) {
    this.mainWindow = mainWindow;
  }
}

module.exports = AutoUpdateManager;