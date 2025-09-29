# ChatForYou Desktop - Electron 데스크톱 애플리케이션

[![License](https://img.shields.io/badge/License-CC%20BY--NC--SA%204.0-green)]([LICENSE](https://creativecommons.org/licenses/by-nc-sa/4.0/) )
[![Electron](https://img.shields.io/badge/Electron-28.0.0-47848F.svg?logo=electron)](https://electronjs.org/)
[![Node.js](https://img.shields.io/badge/Node.js-16+-339933.svg?logo=node.js)](https://nodejs.org/)

**ChatForYou Desktop** - 데스크톱에서 만나는 새로운 소통의 경험 🚀

WebRTC 기반 화상채팅 및 게임 플랫폼의 Electron 데스크톱 버전으로, Node.js 웹 애플리케이션을 기반으로 자동 변환되어 네이티브 데스크톱 경험을 제공합니다.

## 📋 프로젝트 개요

### 🏗️ 아키텍처

```
ChatForYou_v2/
├── nodejs-frontend/           # 웹 버전 소스
└── chatforyou-desktop/        # 데스크톱 버전 (이 프로젝트)
    ├── src/
    │   ├── main/              # Electron Main Process
    │   │   ├── electron-main.js   # 메인 프로세스 (창 관리, 업데이트)
    │   │   └── preload.js         # 보안 브리지 스크립트
    │   ├── static/            # 정적 파일 (동기화됨)
    │   ├── templates/         # HTML 템플릿 (동기화됨)
    │   └── config/            # 환경별 설정 (동기화됨)
    ├── scripts/               # 빌드 자동화 스크립트
    ├── dist/                  # 빌드 출력
    └── app-update.yml         # 자동 업데이트 설정
```

## ✨ 주요 기능

- **N:M 화상채팅**: WebRTC 기반 멀티미디어 통신
- **실시간 채팅**: DataChannel 기반 메시징
- **CatchMind 게임**: 그림 맞추기 게임 (ChatGPT 기반 동적 주제)
- **파일 전송**: 이미지 파일 공유 (최대 10MB)
- **텍스트 오버레이**: 비디오 위 문자 표시
- **실시간 자막**: 음성 → 텍스트 변환
- **네이티브 창 관리**: 최소화, 최대화, 닫기
- **자동 업데이트**: GitHub Releases 기반 (플랫폼별 차이 존재)
- **시스템 알림**: 네이티브 데스크톱 알림

## 🛠️ 기술 스택

### Core Technologies
- **Electron 28.0.0**: 크로스플랫폼 데스크톱 앱 프레임워크
- **Node.js 16+**: JavaScript 런타임
- **electron-builder**: 멀티플랫폼 빌드 도구
- **electron-updater**: 자동 업데이트 시스템

### Frontend
- **jQuery**: DOM 조작 및 이벤트 처리
- **Bootstrap 5**: 반응형 UI 프레임워크
- **SCSS**: CSS 전처리기
- **WebRTC**: 실시간 미디어 통신
- **Canvas API**: 그림 그리기 및 게임

### Build & DevOps
- **GitHub Actions**: CI/CD 파이프라인
- **Sass**: SCSS 컴파일러
- **Rimraf**: 크로스플랫폼 파일 삭제

## 🚀 설치 및 실행

### 1. 사전 요구사항
```bash
# Node.js 16+ 설치 확인
node --version

# npm 설치 확인  
npm --version

# Sass 설치 (SCSS 컴파일용)
npm install -g sass
```

### 2. 개발 환경 설정
```bash
# 프로젝트 클론
git clone https://github.com/SeJonJ/ChatForYou.git
cd ChatForYou_v2/chatforyou-desktop

# 의존성 설치
npm install
```

### 3. 개발 모드 실행
```bash
# 로컬 환경으로 앱 실행
npm start

# 개발 모드 (DevTools 자동 열림)
npm run dev

# 실시간 개발 모드 (파일 변경 감지)
npm run dev:watch
```

## 🏗️ 빌드 및 배포

### 빌드 명령어
```bash
# 현재 플랫폼용 빌드
npm run build

# 특정 플랫폼 빌드
npm run build:mac     # macOS (.dmg)
npm run build:win     # Windows (.exe)
npm run build:linux   # Linux (.AppImage)

# 모든 플랫폼 빌드
npm run build:all
```

### 빌드 결과물
- **macOS**: `dist/ChatForYou-1.0.0-arm64.dmg`
- **Windows**: `dist/ChatForYou-Setup-1.0.0.exe`  

### 자동 배포 (GitHub Actions)
```bash
# 릴리스 태그 생성 및 푸시
git tag v1.0.1
git push origin v1.0.1

# GitHub Actions가 자동으로:
# 1. 멀티플랫폼 빌드 실행
# 2. GitHub Releases 생성
# 3. 업데이트 메타데이터 생성
```

## 🔧 개발 가이드

### 파일 구조
```
src/
├── main/
│   ├── electron-main.js      # 메인 프로세스
│   └── preload.js            # 렌더러-메인 브리지
├── static/                   # 웹 앱 정적 파일 (자동 동기화)
├── templates/                # HTML 템플릿 (자동 동기화)
└── config/                   # 환경별 설정 (자동 동기화)
```

### 동기화 시스템
데스크톱 앱은 `nodejs-frontend`에서 자동으로 파일을 동기화합니다:

```bash
# 수동 동기화 실행
npm run sync              # 로컬 환경
npm run sync:prod         # 프로덕션 환경
npm run sync:watch        # 실시간 동기화
npm run sync:verbose      # 상세 로그
```

**동기화 과정:**
1. 파일 백업 생성
2. Static 파일 복사 (JS, CSS, 이미지)
3. Template 파일 복사 및 경로 변환
4. Config 파일 환경별 변환
5. SCSS → CSS 컴파일
6. 무결성 검증

### 환경 설정

#### 로컬 개발 환경
```javascript
// src/config/config.local.js (자동 생성됨)
window.__CONFIG__ = {
  API_BASE_URL: 'http://localhost:8080/chatforyou/api',
  PLATFORM: 'electron',
  DEV_MODE: true,
  AUTO_UPDATER: false
};
```

#### 프로덕션 환경
```javascript
// src/config/config.prod.js (자동 생성됨)
window.__CONFIG__ = {
  API_BASE_URL: 'https://hjproject.kro.kr/chatforyou/api',
  PLATFORM: 'electron', 
  DEV_MODE: false,
  AUTO_UPDATER: true
};
```

## 🔄 자동 업데이트 시스템

### 플랫폼별 업데이트 방식

#### 🖥️ Windows - 완전 자동 업데이트
- 앱 시작 시 GitHub Releases API를 통해 새 버전 확인
- 새 버전 발견 시 백그라운드에서 자동 다운로드
- 다운로드 완료 후 사용자에게 설치 여부 확인
- 사용자 승인 시 자동 설치 후 앱 재시작
- 다운로드 진행률 실시간 표시 및 업데이트 실패 시 자동 롤백

#### 🍎 macOS - 수동 다운로드 방식
- 앱 시작 시 새 버전 확인
- 새 버전 발견 시 GitHub Releases 페이지로 안내
- 사용자가 직접 DMG 파일을 다운로드하여 설치
- macOS Gatekeeper 보안 정책으로 인한 수동 설치 방식

### 업데이트 API (Windows 전용)
```javascript
// 업데이트 확인
const result = await window.electronAPI.update.checkForUpdates();

// 다운로드 시작
await window.electronAPI.update.startDownload();

// 업데이트 설치
await window.electronAPI.update.install();

// 이벤트 리스너
window.electronAPI.update.onProgress((event, data) => {
  console.log(`진행률: ${data.percent}%`);
});
```

### 수동 업데이트 확인
앱 메뉴 **도움말 → 업데이트 확인**을 통해 언제든지 업데이트 확인 가능

## 🛡️ 보안

Electron의 보안 모범 사례를 준수합니다:
- **Node.js Integration**: 비활성화
- **Context Isolation**: 활성화
- **Preload Scripts**: 안전한 API 노출
- **CSP**: Content Security Policy 적용

## 📦 배포 패키지

### macOS (.dmg)
- **Apple Silicon**: M1/M2 Mac 전용 빌드
- **Intel**: x86_64 Mac 호환
- **설치**: 드래그 앤 드롭으로 Applications 폴더에 설치

#### ⚠️ macOS "손상된 파일" 에러 해결

macOS에서 "손상된 파일" 또는 "확인되지 않은 개발자" 에러가 발생할 경우:

**방법 1: 열어서 실행하기! **
1. ChatForYou 앱을 우클릭 → "열기" 선택
2. 경고 대화상자에서 "열기" 클릭
3. 한 번 허용하면 이후 정상 실행됩니다

**방법 2: 터미널 명령 사용하기! **
```bash
# 다운로드한 DMG 파일의 quarantine 속성 제거
xattr -r -d com.apple.quarantine ~/Downloads/ChatForYou-*.dmg

# 또는 설치된 앱의 quarantine 속성 제거  
xattr -r -d com.apple.quarantine /Applications/ChatForYou.app
```

**왜 이런 에러가 발생하나요?**
- macOS Gatekeeper는 Apple 개발자 인증서로 서명되지 않은 앱을 자동으로 차단합니다
- ChatForYou는 개인 프로젝트로 Apple Developer Program에 등록되지 않았습니다
- 이는 macOS만의 보안 정책으로, Windows에서는 발생하지 않는 문제입니다

### Windows (.exe)
- **NSIS 설치관리자**: GUI 기반 설치 과정
- **아키텍처**: x64, ia32 지원
- **바탕화면 바로가기**: 자동 생성

Windows Defender SmartScreen에서 경고가 나타날 수 있지만, "추가 정보" → "실행"을 클릭하여 진행할 수 있습니다.

## 🔍 문제 해결

### 일반적인 문제

```bash
# 빌드 실패 시
npm run clean:all && npm install
npm run sync:verbose

# 업데이트 실패 시
npm run validate
npm run update:check

# SCSS 컴파일 오류 시
npm run scss:build
npm run scss:watch
```

## 🌐 지원 플랫폼

| 플랫폼 | 버전 | 아키텍처 | 자동 업데이트 | 상태 |
|--------|------|----------|---------------|------|
| **macOS** | 10.12+ | x64, arm64 | 🔄 버전 체크 + 수동 설치 | ✅ 지원 |
| **Windows** | 10, 11 | x64, ia32 | ✅ 완전 자동 | ✅ 지원 |

## 👥 개발팀
| 역할 | 이름 | 담당 업무 |
|------|------|-----------|
| 🚀 **프로젝트 리더** | 장세존 | Electron 앱 개발, DevOps |
| ⚙️ **백엔드 개발** | 김동현 | 백엔드 · 프론트엔드 기능 개발 |
| 💻 **풀스택 개발** | 박태식 | 백엔드 · 프론트엔드 기능 개발 |
| 🎨 **디자인 및 웹 퍼블리싱 총괄** | 임가현 | 웹 퍼블리싱 · UI/UX 디자인 |


## 📞 지원 및 피드백

- **Issues**: [GitHub Issues](https://github.com/SeJonJ/ChatForYou/issues)
- **Discussions**: [GitHub Discussions](https://github.com/SeJonJ/ChatForYou/discussions)
- **Email**: wkdtpwhs@gmail.com

## 📄 라이선스

Copyright 2024 SejonJang (wkdtpwhs@gmail.com)

이 프로젝트는 Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International License 하에 라이선스됩니다.

**비상업적 사용만 허용됩니다:**
- ✅ 개인적, 교육적, 연구 목적의 사용
- ✅ 오픈소스 기여 및 개선
- ❌ 상업적 목적의 사용 및 배포
- ❌ 수익 창출을 위한 활용

자세한 내용은 [LICENSE](https://creativecommons.org/licenses/by-nc-sa/4.0/) 파일을 참조하세요.