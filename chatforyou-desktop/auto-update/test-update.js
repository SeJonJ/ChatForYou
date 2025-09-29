#!/usr/bin/env node

/**
 * 업데이트 테스트용 스크립트
 * 
 * 사용법:
 * 1. 현재 버전을 GitHub Release 보다 낮은 버전으로 변경 : node test-update.js build [버전]
 * 2. 앱 실행
 * 3. 다운로드 확인
 * 4. 테스트 버전 복원 : node test-update.js restore
 */

const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const log = {
    info: (msg) => console.log(`[INFO] ${msg}`),
    warn: (msg) => console.warn(`[WARN] ${msg}`),
    error: (msg) => console.error(`[ERROR] ${msg}`),
    success: (msg) => console.log(`[SUCCESS] ${msg}`)
};

class UpdateTester {
    constructor() {
        this.projectRoot = path.join(__dirname, '..');
        this.packageJsonPath = path.join(this.projectRoot, 'package.json');
        this.backupPath = path.join(this.projectRoot, 'package.json.backup');
    }

    /**
     * 현재 버전 정보 표시
     */
    getCurrentVersion() {
        try {
            const packageJson = JSON.parse(fs.readFileSync(this.packageJsonPath, 'utf8'));
            return packageJson.version;
        } catch (error) {
            log.error(`버전 정보 읽기 실패: ${error.message}`);
            return null;
        }
    }

    /**
     * 버전 임시 변경 (테스트용)
     */
    changeVersion(newVersion) {
        try {
            // 백업 생성
            if (!fs.existsSync(this.backupPath)) {
                fs.copyFileSync(this.packageJsonPath, this.backupPath);
                log.info('package.json 백업 생성됨');
            }

            // 버전 변경
            const packageJson = JSON.parse(fs.readFileSync(this.packageJsonPath, 'utf8'));
            const oldVersion = packageJson.version;
            packageJson.version = newVersion;
            
            fs.writeFileSync(this.packageJsonPath, JSON.stringify(packageJson, null, 2));
            log.success(`버전 변경: ${oldVersion} → ${newVersion}`);
            
            return true;
        } catch (error) {
            log.error(`버전 변경 실패: ${error.message}`);
            return false;
        }
    }

    /**
     * 백업에서 버전 복원
     */
    restoreVersion() {
        try {
            if (fs.existsSync(this.backupPath)) {
                fs.copyFileSync(this.backupPath, this.packageJsonPath);
                fs.unlinkSync(this.backupPath);
                log.success('✅ package.json 복원 완료');
                return true;
            } else {
                log.warn('백업 파일이 없습니다');
                return false;
            }
        } catch (error) {
            log.error(`버전 복원 실패: ${error.message}`);
            return false;
        }
    }

    /**
     * 테스트용 릴리스 생성
     */
    createTestRelease(version) {
        try {
            log.info('테스트용 릴리스 빌드 시작...');
            
            // 버전 변경
            if (!this.changeVersion(version)) {
                return false;
            }

            // 빌드 실행
            execSync('npm run build:mac', { 
                stdio: 'inherit',
                cwd: this.projectRoot 
            });

            log.success(`테스트 릴리스 생성 완료: v${version}`);
            return true;
        } catch (error) {
            log.error(`테스트 릴리스 생성 실패: ${error.message}`);
            return false;
        }
    }

    /**
     * 업데이트 강제 체크 (개발용)
     */
    forceUpdateCheck() {
        try {
            log.info('강제 업데이트 체크 실행...');
            
            // dev-app-update.yml 생성 (개발용)
            const devUpdateConfig = `provider: github
owner: sejon
repo: ChatForYou
updaterCacheDirName: chatforyou-updater-dev`;
            
            const devConfigPath = path.join(this.projectRoot, 'dev-app-update.yml');
            fs.writeFileSync(devConfigPath, devUpdateConfig);
            
            // Electron 앱 실행 (개발 모드에서 업데이트 강제 활성화)
            const env = { 
                ...process.env, 
                ELECTRON_ENABLE_UPDATE_CHECK: 'true',
                NODE_ENV: 'production' // 업데이트 체크 활성화
            };
            
            execSync('electron .', { 
                stdio: 'inherit',
                cwd: this.projectRoot,
                env
            });
            
        } catch (error) {
            log.error(`강제 업데이트 체크 실패: ${error.message}`);
        }
    }

    /**
     * 업데이트 서버 시뮬레이션
     */
    simulateUpdateServer() {
        const express = require('express');
        const app = express();
        const port = 3001;

        app.use(express.static(path.join(this.projectRoot, 'dist')));

        // 버전 정보 엔드포인트
        app.get('/latest', (req, res) => {
            const currentVersion = this.getCurrentVersion();
            const latestVersion = this.incrementVersion(currentVersion);
            
            res.json({
                version: latestVersion,
                files: [
                    {
                        url: `http://localhost:${port}/ChatForYou-${latestVersion}-arm64.dmg`,
                        sha512: 'mock-sha512-hash',
                        size: 12345678
                    }
                ],
                path: `ChatForYou-${latestVersion}-arm64.dmg`,
                sha512: 'mock-sha512-hash',
                releaseDate: new Date().toISOString()
            });
        });

        app.listen(port, () => {
            log.success(`업데이트 서버 시뮬레이션 시작: http://localhost:${port}`);
            log.info('Ctrl+C로 서버를 중지할 수 있습니다');
        });
    }

    /**
     * 버전 번호 자동 증가
     */
    incrementVersion(version) {
        const parts = version.split('.');
        parts[2] = (parseInt(parts[2]) + 1).toString();
        return parts.join('.');
    }

    /**
     * 도움말 표시
     */
    showHelp() {
        console.log(`
ChatForYou 업데이트 테스터

사용법:
  node test-update.js <command> [options]

명령어:
  version                현재 버전 표시
  change <version>       버전 임시 변경
  restore               버전 복원
  build <version>       테스트 릴리스 빌드
  check                 강제 업데이트 체크
  server                업데이트 서버 시뮬레이션
  help                  도움말 표시

예시:
  node test-update.js version
  node test-update.js change 1.0.1
  node test-update.js build 1.0.1
  node test-update.js check
  node test-update.js restore
        `);
    }
}

// 메인 실행 부분
function main() {
    const tester = new UpdateTester();
    const command = process.argv[2];
    const arg = process.argv[3];

    switch (command) {
        case 'version':
            const version = tester.getCurrentVersion();
            if (version) {
                log.info(`현재 버전: ${version}`);
            }
            break;

        case 'change':
            if (!arg) {
                log.error('버전을 지정해주세요. 예: node test-update.js change 1.0.1');
                return;
            }
            tester.changeVersion(arg);
            break;

        case 'restore':
            tester.restoreVersion();
            break;

        case 'build':
            if (!arg) {
                log.error('버전을 지정해주세요. 예: node test-update.js build 1.0.1');
                return;
            }
            tester.createTestRelease(arg);
            break;

        case 'check':
            tester.forceUpdateCheck();
            break;

        case 'server':
            try {
                tester.simulateUpdateServer();
            } catch (error) {
                log.error('express 모듈이 필요합니다: npm install express --save-dev');
            }
            break;

        case 'help':
        case '--help':
        case '-h':
            tester.showHelp();
            break;

        default:
            log.error('알 수 없는 명령어입니다.');
            tester.showHelp();
            break;
    }
}

// 스크립트가 직접 실행되는 경우에만 main 함수 호출
if (require.main === module) {
    main();
}

module.exports = UpdateTester;