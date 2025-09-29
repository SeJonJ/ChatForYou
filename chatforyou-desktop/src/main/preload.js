const { contextBridge, ipcRenderer } = require('electron');

// Electron API를 renderer 프로세스에 안전하게 노출
contextBridge.exposeInMainWorld('electronAPI', {
    // 앱 정보 관련 API
    getAppVersion: () => ipcRenderer.invoke('get-app-version'),
    getPlatform: () => ipcRenderer.invoke('get-platform'),
    getAppInfo: async () => {
        const version = await ipcRenderer.invoke('get-app-version');
        const platform = await ipcRenderer.invoke('get-platform');
        return { version, platform };
    },

    // 윈도우 컨트롤 API
    minimizeWindow: () => ipcRenderer.invoke('minimize-window'),
    maximizeWindow: () => ipcRenderer.invoke('maximize-window'),
    closeWindow: () => ipcRenderer.invoke('close-window'),

    // === 새로 추가된 업데이트 관련 API ===
    update: {
        // 업데이트 액션 메소드들
        checkForUpdates: async () => {
            try {
                return await ipcRenderer.invoke('manual-update-check');
            } catch (error) {
                console.error('업데이트 확인 중 오류:', error);
                return { success: false, error: String(error) };
            }
        },

        getInfo: async () => {
            try {
                return await ipcRenderer.invoke('get-update-info');
            } catch (error) {
                console.error('업데이트 정보 조회 중 오류:', error);
                return { currentVersion: 'N/A', updateInfo: null, isDev: true, error: String(error) };
            }
        },

        getStatus: async () => {
            try {
                return await ipcRenderer.invoke('get-update-status');
            } catch (error) {
                console.error('업데이트 상태 조회 중 오류:', error);
                return { hasUpdate: false, isChecking: false, isManualCheck: false, updateVersion: null, error: String(error) };
            }
        },

        startDownload: async () => {
            try {
                return await ipcRenderer.invoke('start-update-download');
            } catch (error) {
                console.error('업데이트 다운로드 시작 중 오류:', error);
                return { success: false, error: String(error) };
            }
        },

        install: async () => {
            try {
                return await ipcRenderer.invoke('install-update');
            } catch (error) {
                console.error('업데이트 설치 중 오류:', error);
                return { success: false, error: String(error) };
            }
        },

        // 업데이트 이벤트 리스너들
        onChecking: (callback) => {
            const wrappedCallback = (event, ...args) => {
                console.log('업데이트 확인 중...');
                callback(event, ...args);
            };
            ipcRenderer.on('update:checking', wrappedCallback);
            return () => ipcRenderer.removeListener('update:checking', wrappedCallback);
        },

        onAvailable: (callback) => {
            const wrappedCallback = (event, version) => {
                console.log(`업데이트 있음: ${version}`);
                callback(event, version);
            };
            ipcRenderer.on('update:available', wrappedCallback);
            return () => ipcRenderer.removeListener('update:available', wrappedCallback);
        },

        onNotAvailable: (callback) => {
            const wrappedCallback = (event, ...args) => {
                console.log('업데이트 없음 - 현재 최신 버전');
                callback(event, ...args);
            };
            ipcRenderer.on('update:not-available', wrappedCallback);
            return () => ipcRenderer.removeListener('update:not-available', wrappedCallback);
        },

        onError: (callback) => {
            const wrappedCallback = (event, error) => {
                console.error('업데이트 오류:', error);
                callback(event, error);
            };
            ipcRenderer.on('update:error', wrappedCallback);
            return () => ipcRenderer.removeListener('update:error', wrappedCallback);
        },

        onProgress: (callback) => {
            const wrappedCallback = (event, progressData) => {
                console.log(`업데이트 다운로드 진행률: ${progressData.percent?.toFixed(1) || 0}%`);
                callback(event, progressData);
            };
            ipcRenderer.on('update:progress', wrappedCallback);
            return () => ipcRenderer.removeListener('update:progress', wrappedCallback);
        },

        onDownloaded: (callback) => {
            const wrappedCallback = (event, version) => {
                console.log(`업데이트 다운로드 완료: ${version}`);
                callback(event, version);
            };
            ipcRenderer.on('update:downloaded', wrappedCallback);
            return () => ipcRenderer.removeListener('update:downloaded', wrappedCallback);
        },

        // 다운로드 시작 이벤트
        onDownloadStarted: (callback) => {
            const wrappedCallback = (event, ...args) => {
                console.log('업데이트 다운로드 시작됨');
                callback(event, ...args);
            };
            ipcRenderer.on('update:download-started', wrappedCallback);
            return () => ipcRenderer.removeListener('update:download-started', wrappedCallback);
        },

        // 유틸리티 메소드들
        removeAllListeners: (channel) => {
            if (typeof channel === 'string' && channel.startsWith('update:')) {
                ipcRenderer.removeAllListeners(channel);
                console.log(`모든 ${channel} 이벤트 리스너 제거됨`);
            } else {
                console.warn('유효하지 않은 채널:', channel);
            }
        },

        removeAllUpdateListeners: () => {
            const updateChannels = [
                'update:checking',
                'update:available', 
                'update:not-available',
                'update:error',
                'update:progress',
                'update:downloaded',
                'update:download-started'
            ];
            
            updateChannels.forEach(channel => {
                ipcRenderer.removeAllListeners(channel);
            });
            console.log('모든 업데이트 관련 이벤트 리스너 제거됨');
        }
    },

    // 소셜 로그인 API (웹 환경과의 호환성을 위해 유지)
    openSocialLogin: (provider) => {
        // 데스크톱 앱에서는 새 창에서 소셜 로그인을 처리할 수 있음
        // 하지만 현재는 기존 웹 로직을 그대로 사용
        console.log(`소셜 로그인 요청: ${provider}`);
        return Promise.resolve();
    },

    // 파일 시스템 API (필요시 확장 가능)
    // saveFile: (data, filename) => ipcRenderer.invoke('save-file', data, filename),
    // loadFile: (filename) => ipcRenderer.invoke('load-file', filename),

    // 알림 API (필요시 확장 가능)
    // showNotification: (title, body) => ipcRenderer.invoke('show-notification', title, body),

    // 개발자 도구 관련 (개발 모드에서만 사용)
    isDev: () => {
        return ipcRenderer.invoke('get-is-dev');
    }
});

// 보안을 위한 추가 설정
window.addEventListener('DOMContentLoaded', () => {
    console.log('Preload script loaded successfully');
    
    // 개발 모드 표시
    if (window.electronAPI?.isDev) {
        window.electronAPI.isDev().then(isDev => {
        if (isDev) {
            console.log('🔧 개발 모드에서 실행 중');
            console.log('📢 업데이트 API가 확장되었습니다:');
            console.log('   - window.electronAPI.update.checkForUpdates()');
            console.log('   - window.electronAPI.update.getInfo()');
            console.log('   - window.electronAPI.update.getStatus()');
            console.log('   - window.electronAPI.update.startDownload()');
            console.log('   - window.electronAPI.update.install()');
            console.log('   - window.electronAPI.update.on*() 이벤트 리스너들');
        }
        }).catch(err => console.warn('개발 모드 확인 실패:', err));
    }
    
    // 업데이트 API 사용 예시 (개발 모드에서만 표시)
    if (window.electronAPI?.isDev) {
        window.electronAPI.isDev().then(isDev => {
        if (isDev) {
            console.log(`
🚀 업데이트 API 사용 예시:

// 업데이트 확인
const result = await window.electronAPI.update.checkForUpdates();

// 업데이트 정보 조회
const info = await window.electronAPI.update.getInfo();
console.log('현재 버전:', info.currentVersion);

// 다운로드 진행률 모니터링
const removeProgressListener = window.electronAPI.update.onProgress((event, data) => {
    console.log(\`진행률: \${data.percent}%\`);
    console.log(\`속도: \${(data.bytesPerSecond / 1024 / 1024).toFixed(2)} MB/s\`);
});

// 사용 완료 후 리스너 제거
// removeProgressListener();
        `);
        }
        }).catch(err => console.warn('업데이트 API 예시 출력 실패:', err));
    }
});