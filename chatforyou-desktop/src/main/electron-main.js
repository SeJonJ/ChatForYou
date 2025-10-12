const { app, BrowserWindow, Menu, ipcMain, globalShortcut, shell, dialog } = require('electron');
const { autoUpdater } = require('electron-updater');
const path = require('path');
const crypto = require('crypto');
const fs = require('fs');
const AutoUpdateManager = require('../../auto-update/auto-updater');

// 개발 모드 감지
const isDev = process.env.NODE_ENV === 'development' || process.argv.includes('--dev');
const forceDevUpdate = process.argv.includes('--force-dev-update');

let mainWindow;
let updateManager = null; // AutoUpdateManager 인스턴스

/**
 * 보안: file:// URL의 경로가 허용되는 디렉토리인지 검증
 * Directory Traversal 공격 방지
 */
function isValidLocalPath(fileUrl) {
    try {
        // file:// 프로토콜 제거 및 Windows 경로 형식 처리
        let urlPath = fileUrl.replace('file://', '');
        
        // Windows에서 슬래시 3개 (//) 처리
        if (process.platform === 'win32' && urlPath.startsWith('/')) {
            urlPath = urlPath.substring(1);
        }
        
        // URL 디코딩 (..%2F 등의 인코딩된 경로 탐색 시도 방지)
        const decodedPath = decodeURIComponent(urlPath);
        
        // 쿼리 파라미터와 해시 제거
        const cleanPath = decodedPath.split('?')[0].split('#')[0];
        
        // 정규화된 절대 경로로 변환 - Windows/Unix 호환
        const normalizedPath = path.resolve(cleanPath);
        
        // 허용되는 디렉토리 목록 (앱 디렉토리 내부만 허용)
        const appDir = path.resolve(__dirname, '..');
        const allowedDirs = [
            path.join(appDir, 'templates'),
            path.join(appDir, 'static'),
            path.join(appDir, 'config'),
            path.join(appDir, 'assets'),
            path.join(appDir, 'resources')
        ];
        
        // 경로가 허용된 디렉터리 내부에 있는지 확인 - Windows 대소문자 무시
        const isInAllowedDir = allowedDirs.some(allowedDir => {
            const resolvedAllowedDir = path.resolve(allowedDir);
            if (process.platform === 'win32') {
                return normalizedPath.toLowerCase().startsWith(resolvedAllowedDir.toLowerCase());
            }
            return normalizedPath.startsWith(resolvedAllowedDir);
        });
        
        if (!isInAllowedDir) {
            log.warn(`보안: 허용되지 않은 디렉토리 접근 시도: ${normalizedPath}`);
            return false;
        }
        
        // Directory traversal 공격 차단
        const relativePath = path.relative(appDir, normalizedPath);
        if (relativePath.startsWith('..') || path.isAbsolute(relativePath)) {
            log.warn(`보안: 경로 탐색 시도 차단: ${relativePath}`);
            return false;
        }
        
        // 파일 확장자 검증
        const allowedExtensions = [
            '.html', '.htm', '.css', '.js', '.json', '.txt',
            '.svg', '.png', '.jpg', '.jpeg', '.gif', '.ico', '.webp',
            '.woff', '.woff2', '.ttf', '.eot' // 웹폰트 지원
        ];
        
        const fileExtension = path.extname(normalizedPath).toLowerCase();
        
        // 확장자가 없는 경우나 허용된 확장자인 경우만 통과
        if (fileExtension && !allowedExtensions.includes(fileExtension)) {
            log.warn(`보안: 허용되지 않은 파일 확장자: ${fileExtension} (${normalizedPath})`);
            return false;
        }
        
        log.debug(`보안: 유효한 로컬 경로 검증 통과: ${normalizedPath}`);
        return true;
        
    } catch (error) {
        log.error(`보안: 경로 검증 중 오류 발생: ${error.message}`);
        return false;
    }
}

const log = {
    info: (message) => {
        const timestamp = new Date().toISOString();
        console.log(`[${timestamp}] [INFO] ${message}`);
    },
    warn: (message) => {
        const timestamp = new Date().toISOString();
        console.warn(`[${timestamp}] [WARN] ${message}`);
    },
    error: (message) => {
        const timestamp = new Date().toISOString();
        console.error(`[${timestamp}] [ERROR] ${message}`);
    },
    debug: (message) => {
        if (isDev) {
            const timestamp = new Date().toISOString();
            console.log(`[${timestamp}] [DEBUG] ${message}`);
        }
    }
};

// 경로 매핑 설정 로드
let pathMapping = {};
try {
    const mappingPath = path.join(__dirname, '../../build-scripts/convert_path.json');
    if (fs.existsSync(mappingPath)) {
        pathMapping = JSON.parse(fs.readFileSync(mappingPath, 'utf8'));
        log.info(`경로 매핑 설정 로드됨: ${Object.keys(pathMapping).length}개 파일`);
    }
} catch (error) {
    log.warn(`경로 매핑 설정 로드 실패: ${error.message}`);
}

// 자동 업데이트 설정 - 플랫폼별 처리
function setupAutoUpdater() {
    // Windows에서만 AutoUpdateManager 사용
    if (process.platform !== 'darwin') {
        updateManager = new AutoUpdateManager({
            mainWindow: mainWindow,
            isDev: isDev && !forceDevUpdate,
            logger: log
        });
        log.info('Windows 플랫폼: 자동업데이트 활성화');
    } else {
        log.info('Mac 플랫폼: 수동 업데이트 안내 시스템');
        
        // Mac에서 autoUpdater 이벤트 처리 - 수동 다운로드로 유도
        autoUpdater.on('update-available', (info) => {
            log.info(`Mac 업데이트 발견: v${info.version}`);
            
            if (mainWindow) {
                mainWindow.hide();
                log.info('Mac 강제 업데이트: 메인 창 숨김');
            }
            
            dialog.showMessageBox(mainWindow, {
                type: 'info',
                title: '필수 업데이트 안내',
                message: `ChatForYou v${info.version} 필수 업데이트가 있습니다!`,
                detail: `서비스 사용을 위해 업데이트가 필요합니다.\nGitHub에서 최신 DMG 파일을 다운로드해주세요.`,
                buttons: ['GitHub에서 다운로드', '나중에 업데이트(앱 종료)'],
                defaultId: 0,
                cancelId: 1,
                noLink: true  // 링크 스타일 방지
            }).then(result => {
                if (result.response === 0) {
                    shell.openExternal('https://github.com/SeJonJ/ChatForYou/releases/latest');
                    log.info('Mac 강제 업데이트: GitHub Releases 페이지 열기');
                    // GitHub 페이지 열기 후에도 앱 종료
                    setTimeout(() => {
                        log.info('Mac 강제 업데이트: GitHub 페이지 열기 후 앱 종료');
                        app.quit();
                    }, 1000);
                } else {
                    // "앱 종료" 선택 시 즉시 종료
                    log.info('Mac 강제 업데이트: 사용자가 앱 종료 선택');
                    app.quit();
                }
            });
        });

        autoUpdater.on('update-not-available', () => {
            log.info('Mac: 최신 버전 사용 중');
        });

        autoUpdater.on('error', (error) => {
            log.error(`Mac 업데이트 확인 오류: ${error.message}`);
        });
    }
}

function registerGlobalShortcuts() {
    if (!isDev) return;
    try {
        // F12: DevTools 토글
        globalShortcut.register('F12', () => {
            if (mainWindow) {
                if (mainWindow.webContents.isDevToolsOpened()) {
                    mainWindow.webContents.closeDevTools();
                    log.debug('개발자 도구 닫기 (F12)');
                } else {
                    mainWindow.webContents.openDevTools();
                    log.debug('개발자 도구 열기 (F12)');
                }
            }
        });
        const devToolsShortcut = process.platform === 'darwin' ? 'CommandOrControl+Option+I' : 'CommandOrControl+Shift+I';
        globalShortcut.register(devToolsShortcut, () => {
            if (mainWindow) {
                if (mainWindow.webContents.isDevToolsOpened()) {
                    mainWindow.webContents.closeDevTools();
                    log.debug('개발자 도구 닫기 (단축키)');
                } else {
                    mainWindow.webContents.openDevTools();
                    log.debug('개발자 도구 열기 (단축키)');
                }
            }
        });
        log.debug('전역 단축키 등록 완료');
    } catch (error) {
        log.error(`전역 단축키 등록 실패: ${error.message}`);
    }
}

function createMainWindow() {
    log.info('메인 윈도우 생성 중...');
    mainWindow = new BrowserWindow({
        width: 1200,
        height: 800,
        minWidth: 800,
        minHeight: 600,
        title: 'ChatForYou',
        icon: path.join(__dirname, '../static/images/logo/chatforyou_logo.png'),
        webPreferences: {
            nodeIntegration: false,
            contextIsolation: true,
            enableRemoteModule: false,
            preload: path.join(__dirname, 'preload.js'),
            webSecurity: true,
            allowRunningInsecureContent: false
        },
        show: false,
        titleBarStyle: process.platform === 'darwin' ? 'hiddenInset' : 'default'
    });

    mainWindow.loadFile(path.join(__dirname, '../templates/roomlist.html'));
    log.info('로컬 파일 로드: roomlist.html');

    if (isDev) {
        mainWindow.webContents.openDevTools();
        log.debug('개발자 도구 자동 열기');
    }

    mainWindow.once('ready-to-show', () => {
        mainWindow.show();
        log.info('메인 윈도우 표시');
    });
    mainWindow.on('closed', () => {
        log.info('메인 윈도우 닫힘');
        mainWindow = null;
    });
    mainWindow.webContents.on('did-finish-load', () => {
        log.info('페이지 로드 완료');
    });
    mainWindow.webContents.on('did-fail-load', (event, errorCode, errorDescription) => {
        log.error(`페이지 로드 실패: ${errorCode} - ${errorDescription}`);
    });
    
    // UpdateManager에 mainWindow 설정
    if (updateManager) {
        updateManager.setMainWindow(mainWindow);
    }
    
    return mainWindow;
}

function createMenu() {
    const template = [
        {
            label: '파일',
            submenu: [
                {
                    label: '새 창',
                    accelerator: 'CmdOrCtrl+N',
                    click: () => {
                        createMainWindow();
                        log.info('새 창 생성');
                    }
                },
                { type: 'separator' },
                {
                    label: '종료',
                    accelerator: process.platform === 'darwin' ? 'Cmd+Q' : 'Ctrl+Q',
                    click: () => {
                        app.quit();
                    }
                }
            ]
        },
        {
            label: '편집',
            submenu: [
                { label: '실행 취소', accelerator: 'CmdOrCtrl+Z', role: 'undo' },
                { label: '다시 실행', accelerator: 'Shift+CmdOrCtrl+Z', role: 'redo' },
                { type: 'separator' },
                { label: '잘라내기', accelerator: 'CmdOrCtrl+X', role: 'cut' },
                { label: '복사', accelerator: 'CmdOrCtrl+C', role: 'copy' },
                { label: '붙여넣기', accelerator: 'CmdOrCtrl+V', role: 'paste' }
            ]
        },
        {
            label: '보기',
            submenu: [
                { label: '새로고침', accelerator: 'CmdOrCtrl+R', role: 'reload' },
                { label: '강제새로고침', accelerator: 'CmdOrCtrl+Shift+R', role: 'forceReload' },
                ...(isDev ? [
                    { type: 'separator' },
                    {
                        label: '개발자 도구 열기',
                        accelerator: 'F12',
                        click: () => {
                            if (mainWindow) {
                                mainWindow.webContents.openDevTools();
                                log.debug('개발자 도구 열기 (메뉴)');
                            }
                        }
                    },
                    {
                        label: '개발자 도구 닫기',
                        click: () => {
                            if (mainWindow) {
                                mainWindow.webContents.closeDevTools();
                                log.debug('개발자 도구 닫기 (메뉴)');
                            }
                        }
                    },
                    {
                        label: '개발자 도구 토글',
                        accelerator: process.platform === 'darwin' ? 'Alt+Cmd+I' : 'Ctrl+Shift+I',
                        click: () => {
                            if (mainWindow) {
                                if (mainWindow.webContents.isDevToolsOpened()) {
                                    mainWindow.webContents.closeDevTools();
                                    log.debug('개발자 도구 닫기 (토글)');
                                } else {
                                    mainWindow.webContents.openDevTools();
                                    log.debug('개발자 도구 열기 (토글)');
                                }
                            }
                        }
                    }
                ] : [
                    { label: '개발자도구', accelerator: process.platform === 'darwin' ? 'Alt+Cmd+I' : 'Ctrl+Shift+I', role: 'toggleDevTools' }
                ]),
                { type: 'separator' },
                { label: '실제 크기', accelerator: 'CmdOrCtrl+0', role: 'resetZoom' },
                { label: '확대', accelerator: 'CmdOrCtrl+Plus', role: 'zoomIn' },
                { label: '축소', accelerator: 'CmdOrCtrl+-', role: 'zoomOut' },
                { type: 'separator' },
                { label: '전체화면', accelerator: process.platform === 'darwin' ? 'Ctrl+Cmd+F' : 'F11', role: 'togglefullscreen' }
            ]
        },
        {
            label: '창',
            submenu: [
                { label: '최소화', accelerator: 'CmdOrCtrl+M', role: 'minimize' },
                { label: '닫기', accelerator: 'CmdOrCtrl+W', role: 'close' }
            ]
        },
        {
            label: '도움말',
            submenu: [
                {
                    label: 'ChatForYou 개발 정보',
                    click: () => {
                        const infoMsg = 'Developed by ChatForYou Team';
                        const detail = 'Copyright 2025 ChatForYou Team && SeJonJ';
                        dialog.showMessageBox(mainWindow, {
                            type: 'info',
                            title: '개발 정보',
                            message: infoMsg,
                            detail,
                            buttons: ['확인', 'GitHub 이동'],
                            defaultId: 0,
                            cancelId: 0
                        }).then(result => {
                            if (result.response === 1) {
                                shell.openExternal('https://github.com/SeJonJ/ChatForYou');
                            }
                        });
                    }
                },
                {
                    label: 'ChatForYou 버전정보',
                    click: async () => {
                        let appVersion = 'N/A';
                        let electronVersion = process.versions.electron || 'N/A';
                        try {
                            const pkg = JSON.parse(fs.readFileSync(path.join(__dirname, '../../package.json'), 'utf8'));
                            appVersion = pkg.version || 'N/A';
                        } catch (e) {}
                        dialog.showMessageBox(mainWindow, {
                            type: 'info',
                            title: '버전 정보',
                            message: `ChatForYou 버전: v${appVersion}\nElectron 버전: v${electronVersion}`,
                            buttons: ['확인']
                        });
                    }
                },
                {
                    label: '이슈 등록',
                    click: () => {
                        shell.openExternal('https://github.com/SeJonJ/ChatForYou/issues');
                    }
                },
                { type: 'separator' },
                {
                    label: '업데이트 확인',
                    click: async () => {
                        if (!isDev) {
                            log.info('수동 업데이트 확인 요청');
                            
                            // 업데이트 확인 중 다이얼로그 표시
                            const checkingDialog = dialog.showMessageBox(mainWindow, {
                                type: 'info',
                                title: '업데이트 확인 중',
                                message: '업데이트를 확인하고 있습니다...',
                                buttons: [],
                                defaultId: -1
                            });
                            
                            try {
                                await autoUpdater.checkForUpdatesAndNotify();
                            } catch (error) {
                                log.error('업데이트 확인 중 오류:', error);
                                dialog.showMessageBox(mainWindow, {
                                    type: 'error',
                                    title: '업데이트 확인 오류',
                                    message: '업데이트 확인 중 오류가 발생했습니다.',
                                    detail: String(error),
                                    buttons: ['확인']
                                });
                            }
                        } else {
                            dialog.showMessageBox(mainWindow, {
                                type: 'info',
                                title: '개발 모드',
                                message: '개발 모드에서는 업데이트를 확인할 수 없습니다.',
                                buttons: ['확인']
                            });
                        }
                    }
                }
            ]
        }
    ];

    if (isDev) {
        template.push({
            label: '개발',
            submenu: [
                {
                    label: '개발자 도구 열기',
                    accelerator: 'F12',
                    click: () => {
                        if (mainWindow) {
                            mainWindow.webContents.openDevTools();
                            log.debug('개발자 도구 열기 (개발 메뉴)');
                        }
                    }
                },
                {
                    label: '개발자 도구 분리',
                    click: () => {
                        if (mainWindow) {
                            mainWindow.webContents.openDevTools({ mode: 'detach' });
                            log.debug('개발자 도구 분리');
                        }
                    }
                },
                { type: 'separator' },
                {
                    label: '콘솔 로그 레벨',
                    submenu: [
                        {
                            label: 'INFO',
                            type: 'radio',
                            checked: true,
                            click: () => {
                                log.info('로그 레벨: INFO');
                            }
                        },
                        {
                            label: 'DEBUG',
                            type: 'radio',
                            click: () => {
                                log.debug('로그 레벨: DEBUG');
                            }
                        }
                    ]
                },
                { type: 'separator' },
                {
                    label: '캐시 지우기',
                    click: () => {
                        if (mainWindow) {
                            mainWindow.webContents.session.clearCache();
                            log.debug('캐시 지우기 완료');
                        }
                    }
                }
            ]
        });
    }
    if (process.platform === 'darwin') {
        template.unshift({
            label: app.getName(),
            submenu: [
                { label: 'ChatForYou 정보', role: 'about' },
                { type: 'separator' },
                { label: '서비스', role: 'services', submenu: [] },
                { type: 'separator' },
                { label: 'ChatForYou 숨기기', accelerator: 'Command+H', role: 'hide' },
                { label: '다른 항목 숨기기', accelerator: 'Command+Shift+H', role: 'hideothers' },
                { label: '모두 표시', role: 'unhide' },
                { type: 'separator' },
                { label: 'ChatForYou 종료', accelerator: 'Command+Q', click: () => app.quit() }
            ]
        });
    }
    const menu = Menu.buildFromTemplate(template);
    Menu.setApplicationMenu(menu);
    log.debug('메뉴 생성 완료');
}

// IPC 핸들러 설정 - 개선된 버전
function setupIpcHandlers() {
    // 기존 핸들러들
    ipcMain.handle('get-app-version', () => {
        return app.getVersion();
    });
    
    ipcMain.handle('get-platform', () => {
        return process.platform;
    });
    
    ipcMain.handle('get-is-dev', () => {
        return isDev;
    });
    
    ipcMain.handle('minimize-window', () => {
        if (mainWindow) {
            mainWindow.minimize();
            log.debug('윈도우 최소화');
        }
    });
    
    ipcMain.handle('maximize-window', () => {
        if (mainWindow) {
            if (mainWindow.isMaximized()) {
                mainWindow.unmaximize();
                log.debug('윈도우 최대화 해제');
            } else {
                mainWindow.maximize();
                log.debug('윈도우 최대화');
            }
        }
    });
    
    ipcMain.handle('close-window', () => {
        if (mainWindow) {
            mainWindow.close();
            log.debug('윈도우 닫기');
        }
    });

    // === 새로 추가된 업데이트 관련 IPC 핸들러들 ===
    
    // 수동 업데이트 확인 - updateManager 사용
    ipcMain.handle('manual-update-check', async () => {
        if (updateManager) {
            return await updateManager.checkForUpdates();
        }
        return { success: false, error: 'UpdateManager not initialized' };
    });

    // 업데이트 정보 조회
    ipcMain.handle('get-update-info', () => {
        if (updateManager) {
            return updateManager.getUpdateInfo();
        }
        return { currentVersion: app.getVersion(), updateInfo: null, isDev: isDev };
    });

    // 업데이트 다운로드 시작
    ipcMain.handle('start-update-download', async () => {
        if (updateManager) {
            return await updateManager.startDownload();
        }
        return { success: false, error: 'UpdateManager not initialized' };
    });

    // 업데이트 설치
    ipcMain.handle('install-update', async () => {
        if (updateManager) {
            return await updateManager.installUpdate();
        }
        return { success: false, error: 'UpdateManager not initialized' };
    });

    // 업데이트 상태 조회
    ipcMain.handle('get-update-status', () => {
        if (updateManager) {
            return updateManager.getUpdateStatus();
        }
        return { hasUpdate: false, isChecking: false, isManualCheck: false, updateVersion: null };
    });

    log.debug('IPC 핸들러 설정 완료 (업데이트 관련 핸들러 포함)');
}

app.whenReady().then(() => {
    log.info(`ChatForYou Desktop 시작 (${isDev ? '개발' : '운영'} 모드)`);
    createMainWindow();
    createMenu();
    setupIpcHandlers();
    setupAutoUpdater();
    
    if (!isDev) {
        if (process.platform === 'darwin') {
            log.info('Mac 앱 시작: 강제 업데이트 체크 수행 (재시작 시에도 동일하게 적용)');
        }
        autoUpdater.checkForUpdatesAndNotify();
        log.info('자동 업데이트 확인 시작');
    }
    
    if (isDev) {
        registerGlobalShortcuts();
    }
    app.on('activate', () => {
        if (BrowserWindow.getAllWindows().length === 0) {
            createMainWindow();
        }
    });
});

app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') {
        app.quit();
    }
});
app.on('will-quit', () => {
    globalShortcut.unregisterAll();
    log.info('ChatForYou Desktop 종료');
});

// 보안 관련 이벤트
app.on('web-contents-created', (event, contents) => {
    contents.setWindowOpenHandler(({ url }) => {
        if (url.startsWith('file://')) {
            // 보안: file:// URL의 경로 유효성 검사
            if (isValidLocalPath(url)) {
                log.debug(`로컬 파일 네비게이션 허용: ${url}`);
                return { action: 'allow' };
            } else {
                log.warn(`보안: 허용되지 않은 파일 경로 차단: ${url}`);
                return { action: 'deny' };
            }
        }
        shell.openExternal(url);
        log.debug(`외부 링크를 기본 브라우저에서 열기: ${url}`);
        return { action: 'deny' };
    });
    contents.on('will-navigate', (event, url) => {
        if (url.startsWith('file://')) {
            // 보안: 경로 유효성 검사
            if (!isValidLocalPath(url)) {
                log.warn(`보안: will-navigate에서 허용되지 않은 경로 차단: ${url}`);
                event.preventDefault();
                return;
            }
            
            // 로컬 파일 네비게이션 처리
            const urlPath = url.replace('file://', '');
            
            // HTML 파일로의 네비게이션인지 확인
            if (urlPath.includes('.html')) {
                event.preventDefault();
                
                // URL에서 쿼리 파라미터 추출
                const urlObj = new URL(url);
                const queryString = urlObj.search;
                
                // URL에서 파일명 추출
                const fileName = path.basename(urlPath.split('?')[0]);
                
                // 동적 경로 매핑으로 올바른 경로 결정
                let correctPath;
                let mappedPath = null;
                
                // 새로운 구조에서 파일명 찾기: {"경로": ["파일명들"]}
                for (const [dirPath, fileList] of Object.entries(pathMapping)) {
                    if (fileList.includes(fileName)) {
                        mappedPath = dirPath;
                        break;
                    }
                }
                
                if (mappedPath) {
                    // 설정 파일에서 매핑된 경로 사용
                    correctPath = path.join(__dirname, '..', mappedPath, fileName);
                    log.debug(`동적 경로 매핑: ${fileName} -> ${mappedPath}`);
                } else {
                    // 기본 경로 사용
                    correctPath = path.join(__dirname, '../templates', fileName);
                    log.debug(`기본 경로 사용: ${fileName} -> templates`);
                }
                const correctUrl = `file://${correctPath}${queryString}`;
                
                // 수정된 URL도 보안 검증
                if (!isValidLocalPath(correctUrl)) {
                    log.warn(`보안: 수정된 URL도 허용되지 않음: ${correctUrl}`);
                    return;
                }
                
                log.debug(`HTML 네비게이션 수정: ${url} -> ${correctUrl}`);
                contents.loadURL(correctUrl);
                return;
            }
            
            log.debug(`로컬 네비게이션 허용: ${url}`);
            return;
        }
        if (!url.startsWith('file://')) {
            event.preventDefault();
            shell.openExternal(url);
            log.warn(`외부 네비게이션 차단 후 브라우저에서 열기: ${url}`);
        }
    });
});

// 예외 처리
process.on('uncaughtException', (error) => {
    log.error(`처리되지 않은 예외: ${error.message}`);
    log.error(error.stack);
});
process.on('unhandledRejection', (reason, promise) => {
    log.error(`처리되지 않은 Promise 거부: ${reason}`);
});