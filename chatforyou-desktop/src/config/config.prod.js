// ChatForYou Electron Configuration
// Auto-generated from web config on 2025-08-08T12:05:06.310Z

window.__CONFIG__ = {
  "API_BASE_URL": "https://hjproject.kro.kr/chatforyou/api",
  "BASE_URL": "./templates/roomlist.html",
  "PLATFORM": "electron",
  "FILE_PROTOCOL": true,
  "DEV_MODE": false,
  "AUTO_UPDATER": true,
  "WEB_BASE_URL": "https://hjproject.kro.kr/chatforyou",
  "ELECTRON_VERSION": "unknown",
  "APP_VERSION": "1.0.0",
  "PLATFORM_TYPE": "desktop",
  "CONVERTED_FROM": "web",
  "CONVERSION_DATE": "2025-08-08T12:05:06.310Z"
};

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
