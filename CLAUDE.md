# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

ChatForYou v2 is a WebRTC-based video conferencing and gaming platform with three main components:
- `nodejs-frontend/`: Node.js frontend server (port 3000)
- `springboot-backend/`: Spring Boot backend API (port 8080) 
- `chatforyou-desktop/`: Electron desktop application

The platform supports N:M video conferencing, real-time chat, file sharing, CatchMind game, and features like text overlay, subtitles, and screen sharing.

## Development Commands

### Frontend (Node.js)
```bash
cd nodejs-frontend
npm run local      # Build for local environment
npm run prod       # Build for production environment  
npm run start      # Start server on port 3000
npm run sass       # Watch SCSS compilation
npm run dev        # Run with SCSS watching
```

### Backend (Spring Boot)
```bash
cd springboot-backend
./gradlew clean build                    # Build JAR file
./gradlew test                          # Run tests
java -Dkms.url=ws://[KMS_IP]:[PORT]/kurento -jar build/libs/*.jar  # Run with Kurento Media Server
```

### Desktop (Electron)
```bash
cd chatforyou-desktop
npm run sync:frontend          # Sync from nodejs-frontend
npm run start                  # Start Electron app
npm run dev                    # Development mode with auto-sync
npm run build:win             # Build Windows installer
npm run build:mac             # Build macOS DMG
npm run build:linux           # Build Linux AppImage
npm run scss:build            # Compile SCSS
npm run scss:watch            # Watch SCSS changes
```

## Architecture & Key Technologies

### WebRTC Communication
- **Kurento Media Server**: Handles N:M video conferencing using kurento-client library
- **WebSocket Signaling**: Custom signaling server at `/signal` endpoint using Spring WebSocket/STOMP
- **DataChannel**: Real-time chat and file transfer implementation
- **ICE/TURN Server**: Uses coturn for NAT traversal

### Backend Architecture (Spring Boot)
- **Controllers**: REST API endpoints in `controller/` package
  - `ChatRoomController`: Room management (CRUD operations)
  - `KurentoHandler`: WebSocket handler for WebRTC signaling
  - `FileController`: MinIO-based file upload/download
- **Services**: Business logic in `service/` package
  - `KurentoRoomManager`: Room lifecycle management
  - `ChatRoomService`: Room state management with Redis
  - `ParticipantService`: User session management
- **Redis Integration**: Room persistence, user counting, search functionality
- **JPA Entities**: `ChatUser`, `DailyInfo` for persistent data

### Frontend Architecture
- **jQuery-based**: Vanilla JavaScript with jQuery for DOM manipulation
- **Bootstrap 5**: UI framework for responsive design
- **SCSS Compilation**: `src/static/scss/` compiled to CSS
- **WebRTC Client**: `kurento-service.js` handles WebRTC peer connections
- **Real-time Features**:
  - DataChannel chat implementation
  - Canvas-based CatchMind game
  - Speech recognition for subtitles
  - Screen sharing capabilities

### Electron Desktop App
- **Sync System**: Automated sync from `nodejs-frontend` to `chatforyou-desktop/src`
- **Build Process**: Multi-platform builds with electron-builder
- **Config Conversion**: Web configs automatically converted for Electron environment

## Key Configuration Files

### Frontend Configuration
- `nodejs-frontend/config/config.local.js`: Local development settings
- `nodejs-frontend/config/config.prod.js`: Production settings  
- `chatforyou-desktop/src/config/config.js`: Auto-generated Electron config

### Backend Configuration  
- `springboot-backend/src/main/resources/application.properties`: Main Spring Boot config
- Environment variables for Kurento Media Server URL, Redis, database connections

## Development Workflow

### Working with WebRTC Features
1. Backend WebSocket handling in `KurentoHandler.java:54` (`handleTextMessage`)
2. Frontend WebRTC logic in `kurento-service.js:34` (WebSocket connection)
3. Participant management through `KurentoUserSession` and `KurentoRoomManager`

### Adding New Game Features
- Game logic in `catchMind.js` and `CatchMindService.java`
- Canvas drawing events handled via DataChannel
- Dynamic topic generation via external Python API server

### File Management
- MinIO object storage integration for file sharing
- File size limit: 10MB, supported formats: jpg, jpeg, png, gif
- Upload/download through DataChannel with progress tracking

## Testing & Deployment

### Prerequisites
- Node.js 16+, Java 17, Kurento Media Server, Redis, TURN server (coturn)
- For Electron builds: Platform-specific build tools (Windows: NSIS, macOS: DMG)

### Environment Setup
```bash
# Start required services
redis-server
# Start Kurento Media Server on configured port
# Configure TURN server for WebRTC NAT traversal
```

### Docker Deployment
Both frontend and backend include Dockerfiles for containerized deployment with Docker Compose support.

## Security Considerations
- HTTPS/WSS required for WebRTC in production
- JWT tokens for API authentication
- IP blacklisting capabilities for abuse prevention
- File upload restrictions enforced

## Monitoring
- Prometheus metrics integration
- Grafana dashboards for performance monitoring  
- Access logging and error tracking

## 개발 시 유의점 : 공통

### 1. Agent / Plugin 활용 (MANDATORY)
작업 시작 전 반드시 적합한 subagent 또는 skill을 확인하고 사용한다.

| 작업 영역 | 사용 권장 |
|---|---|
| Spring Boot 백엔드 개발 | `backend-development:backend-architect` agent |
| Spring Boot 기능 개발 (전체 흐름) | `backend-development:feature-development` skill |
| Node.js / Electron 프론트 개발 | `javascript-typescript:javascript-pro` agent |
| 풀스택 기능 개발 | `feature-dev:feature-dev` skill |
| 코드 최종 점검 | `code-review:code-review` skill |
| 코드 리뷰 (병렬 다차원) | `superpowers:code-reviewer` agent |
| 백엔드 프로덕션 코드 컨벤션 검증 | `backend-convention-checker` agent |
| 백엔드 테스트 코드 컨벤션 검증 | `backend-test-convention-checker` agent |
| 프론트 코드 컨벤션 검증 | `frontend-convention-checker` agent |
| 설계·분석·코드 외부 독립 검토 | `external-consultant` agent |
| 설계·분석 3단계 검증 (Claude → 외부검토 → 최종) | `design-review` skill |
| 디버깅 / 버그 원인 추적 | `error-debugging:debugger` agent |
| 보안 취약점 검토 | `backend-development:security-auditor` agent |
| 코드 단순화·정리 | `simplify` skill |
| 병렬 팀 기반 기능 개발 | `agent-teams:team-feature` skill |
| **주요 기능 개발 (5인 전담팀)** | **`chatforyou-dev-team` skill** |

- 프론트 테스트 코드가 필요할 경우 `.test-temp/` 디렉토리에 생성 후 테스트 완료 시 삭제

### 2. Git 규칙 (STRICT)

**브랜치 전략**:
| 브랜치 | 용도 |
|---|---|
| `chatforyou_v2` | **메인 브랜치** — 모든 PR의 base |
| `feature/[기능명]` | 기능 개발 |
| `bug/[이슈번호]` | 버그 수정 |

- **commit / push 절대 금지** — 모든 commit·push는 유저가 직접 수행
- commit 메시지 추천은 허용
- PR base 브랜치는 항상 `chatforyou_v2`

### 3. 주석 규칙
- 과도한 주석 금지. WHY가 명확한 한 줄만 허용
- 나쁜 예: `// 녹화 기능 critical bug 수정`
- 좋은 예: `// 녹화 다운로드 url을 datachannel로 전송`

### 4. 설계 / 분석 요청 시 응답 형식 (MANDATORY)
설계·분석을 요청받거나, 유저의 설계를 검증할 때 반드시 아래 구조로 답변:

1. **설계 방향 및 이유** — 왜 이렇게 설계했는지
2. **실무 레퍼런스 비교** — 업계 기준 대비 장점 / 주의점
3. **유저 설계와의 비교** (검증 요청 시) — 차이점, 각각의 trade-off

### 5. 2차 자기 검증 (MANDATORY)
모든 코드·분석·설계 결과물은 스스로 1회 재검토 후 최종 결론을 제공한다.

### 6. PLAN 파일 작성 규칙
기능 개발 시작 전 반드시 `[기능명]_plan.md` 파일을 작성한다.

**작성 전**: 유저에게 개발할 기능의 범위를 먼저 확인한다.

**PLAN 파일 필수 포함 항목** (모두 TODO 체크박스 형식):
```
- [ ] 개발 기능 목록 (어디까지 완료되었는지 추적)
- [ ] 테스트 시나리오 및 검증 방법
- [ ] 코드 컨벤션
```

### 7. PLAN 파일 저장 위치
| 범위 | 저장 경로 |
|---|---|
| 전체 기능 | `ChatForYou_v2/[기능명]_plan.md` |
| 백엔드 | `springboot-backend/[기능명]_plan.md` |
| 프론트 (desktop 포함) | `nodejs-frontend/[기능명]_plan.md` |


## 개발 시 유의점 : backend ##
docs/springboot_backend.md 파일 참고

## 개발 시 유의점 : frontend ##
docs/nodejs_frontend.md 파일 참고

## 개발 시 유의점 : chatforyou-desktop ##
docs/chatforyou_desktop.md 파일 참고