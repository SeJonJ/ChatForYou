# ChatForYou Desktop - 빌드 가이드

[![License](https://img.shields.io/badge/License-CC%20BY--NC--SA%204.0-lightgrey.svg)](https://creativecommons.org/licenses/by-nc-sa/4.0/)

## 📋 개요

ChatForYou Desktop은 Node.js 웹 앱을 Electron 데스크톱 앱으로 변환하는 자동화된 빌드 시스템을 제공합니다.

## 🚀 빌드 스크립트 목록

### 📦 개발 스크립트

| 명령어 | 설명 | 용도 |
|--------|------|------|
| `npm start` | 로컬 환경으로 앱 실행 | 일반 개발 |
| `npm run dev` | 개발 모드로 앱 실행 | 디버깅 |
| `npm run dev:watch` | 파일 변경 감지 모드 | 실시간 개발 |

### 🏗️ 통합 빌드 스크립트

| 명령어 | 설명 | 출력 |
|--------|------|------|
| `npm run build` | 통합 빌드 (macOS) | `.dmg` 파일 |
| `npm run build:mac` | macOS 통합 빌드 | `.dmg` 파일 |
| `npm run build:win` | Windows 통합 빌드 | `.exe` 설치 파일 |
| `npm run build:all` | 모든 플랫폼 통합 빌드 | 모든 형식 |

### 🔄 동기화 스크립트

| 명령어 | 설명 | 환경 |
|--------|------|------|
| `npm run sync` | 로컬 환경 동기화 | local |
| `npm run sync:prod` | 프로덕션 환경 동기화 | production |
| `npm run sync:watch` | 실시간 동기화 | local (watch) |
| `npm run sync:verbose` | 상세 로그 동기화 | local (verbose) |

### 🎨 SCSS 스크립트

| 명령어 | 설명 | 출력 |
|--------|------|------|
| `npm run scss:build` | SCSS → CSS 컴파일 | 압축된 CSS |
| `npm run scss:watch` | SCSS 실시간 컴파일 | 소스맵 포함 CSS |

### 🛠️ 유틸리티 스크립트

| 명령어 | 설명 | 동작 |
|--------|------|------|
| `npm run info` | 빌드 정보 확인 | 현재 버전 및 환경 표시 |
| `npm run clean` | 빌드 결과물 정리 | dist, src 폴더 삭제 |
| `npm run clean:all` | 전체 정리 | node_modules 포함 삭제 |
| `npm run validate` | 동기화 결과 검증 | 파일 무결성 확인 |

### 📲 업데이트 스크립트

| 명령어 | 설명 | 기능 |
|--------|------|------|
| `npm run update:version` | 현재 버전 확인 | 버전 출력 |
| `npm run update:build` | 테스트 빌드 생성 | 버전 지정 빌드 |
| `npm run update:check` | 강제 업데이트 체크 | 업데이트 확인 |
| `npm run update:restore` | 버전 복원 | 백업에서 복원 |

## 🔧 통합 빌드 프로세스

### 통합 빌드 시스템 (`build-scripts/build.js`)
```bash
# 통합 빌드 실행 (모든 과정이 자동화됨)
npm run build
```

**자동화된 빌드 과정:**
1. **빌드 정보 표시** - 현재 환경 및 설정 확인
2. **Frontend 동기화** - nodejs-frontend → chatforyou-desktop/src
   - 파일 백업 생성
   - Static 파일 복사 (JS, CSS, 이미지)
   - Template 파일 복사 및 경로 변환
   - Config 파일 환경별 변환
3. **SCSS 컴파일** - src/static/scss → src/static/css
4. **Electron 빌드** - 플랫폼별 실행 파일 생성

**빌드 결과:**
- **macOS**: `dist/ChatForYou-1.0.0-arm64.dmg`
- **Windows**: `dist/ChatForYou-Setup-1.0.0.exe`

## 📁 디렉토리 구조

```
chatforyou-desktop/
├── src/                    # Electron 앱 소스
│   ├── main/              # Main process
│   ├── static/            # 정적 파일 (동기화됨)
│   ├── templates/         # HTML 템플릿 (동기화됨)
│   └── config/            # 환경별 설정 (동기화됨)
├── build-scripts/         # 빌드 자동화 스크립트
│   ├── build.js           # 통합 빌드 스크립트
│   ├── build-log.js       # 빌드 정보 표시 스크립트
│   ├── sync-frontend.js   # 메인 동기화 스크립트
│   ├── test-update.js     # 업데이트 테스트 도구
│   └── lib/               # 라이브러리 모듈
├── auto-update/           # 자동 업데이트 관련 코드
│   └── auto-updater.js    # 자동 업데이트 관리자
├── dist/                  # 빌드 출력
├── package.json           # npm 설정
├── app-update.yml         # 자동 업데이트 설정
└── BUILD-README.md        # 이 파일
```

## ⚙️ 환경 설정

### 로컬 환경 (local)
```javascript
// src/config/config.local.js
window.__CONFIG__ = {
  API_BASE_URL: 'http://localhost:8080/chatforyou/api',
  DEV_MODE: true,
  AUTO_UPDATER: false
};
```

### 프로덕션 환경 (prod)
```javascript
// src/config/config.prod.js  
window.__CONFIG__ = {
  API_BASE_URL: 'https://hjproject.kro.kr/chatforyou/api',
  DEV_MODE: false,
  AUTO_UPDATER: true
};
```

## 🔄 자동 업데이트

### 설정 파일
```yaml
# app-update.yml
provider: github
owner: sejon
repo: ChatForYou
updaterCacheDirName: chatforyou-updater
```

### 릴리스 프로세스
1. **코드 변경 후 태그 생성**
   ```bash
   git tag v1.0.1
   git push origin v1.0.1
   ```

2. **GitHub Actions 자동 실행**
   - 멀티 플랫폼 빌드
   - GitHub Releases 생성
   - 업데이트 메타데이터 생성

3. **자동 업데이트 배포**
   - 사용자 앱에서 자동 감지
   - 웹 기반 업데이트 UI 표시
   - 원클릭 다운로드 및 설치

## 🛠️ 개발 워크플로우

### 일반 개발
```bash
# 1. 의존성 설치
npm install

# 2. 개발 모드 실행
npm run dev

# 3. 실시간 개발 모드
npm run dev:watch
```

### 빌드 및 배포
```bash
# 1. 로컬 테스트 빌드
npm run build

# 2. 프로덕션 빌드
npm run build:all

# 3. 릴리스 태그 & 배포
git tag v1.0.1
git push origin v1.0.1
```

### 업데이트 테스트
```bash
# 1. 버전 변경
npm run update:build 1.0.1

# 2. 업데이트 체크 테스트
npm run update:check

# 3. 버전 복원
npm run update:restore
```

## 🚨 문제 해결

### 빌드 실패 시
1. **node_modules 재설치**
   ```bash
   npm run clean:all
   npm install
   ```

2. **동기화 문제**
   ```bash
   npm run clean
   npm run sync:verbose
   ```

3. **SCSS 컴파일 오류**
   ```bash
   npm run scss:build
   ```

### 업데이트 실패 시
1. **설정 확인**
   ```bash
   npm run validate
   ```

2. **수동 테스트**
   ```bash
   npm run update:check
   ```

## 📊 성능 최적화

### 빌드 시간 단축
- **병렬 처리**: 멀티 플랫폼 동시 빌드
- **캐싱**: npm 및 electron-builder 캐시 활용
- **최적화**: 불필요한 파일 제외

### 용량 최적화
- **SCSS 압축**: 프로덕션 빌드 시 CSS 압축
- **이미지 최적화**: 고해상도 이미지 압축
- **코드 분할**: 불필요한 모듈 제외

## 🔐 [목표] 보안 고려사항

### 코드 서명
- **macOS**: Apple Developer ID 필요
- **Windows**: Code Signing Certificate 필요
- **자동화**: CI/CD에서 보안 키 관리

### 업데이트 보안
- **HTTPS**: 보안 채널을 통한 업데이트
- **체크섬**: 파일 무결성 검증
- **서명 검증**: 디지털 서명 확인

## 📚 참고 자료

- [Electron Builder 문서](https://www.electron.build/)
- [Electron 자동 업데이트](https://www.electronjs.org/docs/latest/tutorial/updates)
- [GitHub Actions for Electron](https://github.com/samuelmeuli/action-electron-builder)
- [ChatForYou v2 프로젝트](https://github.com/SeJonJ/ChatForYou_v2)

## 📄 라이선스

Copyright 2024 SejonJang (wkdtpwhs@gmail.com)

이 프로젝트는 Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International License 하에 라이선스됩니다.

**비상업적 사용만 허용됩니다:**
- ✅ 개인적, 교육적, 연구 목적의 사용
- ✅ 오픈소스 기여 및 개선
- ❌ 상업적 목적의 사용 및 배포
- ❌ 수익 창출을 위한 활용

자세한 내용은 [LICENSE](https://creativecommons.org/licenses/by-nc-sa/4.0/) 파일을 참조하세요.
