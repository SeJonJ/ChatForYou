// ChatForYou Electron Configuration
// Auto-generated from web config on 2025-09-13T11:49:56.143Z

window.__CONFIG__ = {
  "API_BASE_URL": "http://localhost:8080/chatforyou/api",
  "BASE_URL": "./templates/roomlist.html",
  "PLATFORM": "electron",
  "FILE_PROTOCOL": true,
  "DEV_MODE": true,
  "AUTO_UPDATER": false,
  "WEB_BASE_URL": "http://localhost:3000/chatforyou",
  "ELECTRON_VERSION": "unknown",
  "APP_VERSION": "1.0.0",
  "PLATFORM_TYPE": "desktop",
  "CONVERTED_FROM": "web",
  "CONVERSION_DATE": "2025-09-13T11:49:56.143Z"
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
