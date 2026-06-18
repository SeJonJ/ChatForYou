# ChatForYou v2 — Spring Boot Backend

WebRTC/Kurento 기반 N:M 화상채팅 플랫폼의 Spring Boot 백엔드 모듈입니다.
WebSocket(STOMP) 시그널링, Redis 분산 상태 관리, Kafka 인스턴스 간 이벤트 전파,
Kurento Media Server 연동, MinIO 녹화 파일 저장을 담당합니다.

---

## 로컬 실행

```bash
# 빌드
./gradlew clean build

# 실행
java -jar build/libs/springboot-backend-*.jar
```

기본 포트: `8443` (SSL). 로컬 테스트 시 `application.properties`의 SSL 설정을 확인하세요.

---

## application.properties 설정 가이드

> **주의:** 아래 표에서 값이 `xxxx`로 표시된 항목은 민감 정보입니다.
> 실제 값은 아래 K8s Secret 안내를 참고하세요.

> [!NOTE] 운영 환경 보안 설정
> 실 서비스에서는 위 민감 항목을 Kubernetes Secret으로 관리합니다.
> application.properties에 직접 기입하지 않고,
> 환경변수 주입 또는 K8s Secret 마운트 방식으로 적용합니다.
> 로컬 개발 시에는 .gitignore에 포함된 별도 로컬 설정 파일을 사용하세요.

### DB / JPA

| 키 | 값 | 비고 |
|----|-----|------|
| `spring.datasource.url` | `jdbc:mariadb://xxxx:xxxx/chatforyou` | 실제 IP/포트 — 민감 |
| `spring.datasource.username` | `xxxx` | DB 계정 — 민감 |
| `spring.datasource.password` | `xxxx` | DB 비밀번호 — 민감 |
| `spring.jpa.hibernate.ddl-auto` | `none` | 스키마 자동 변경 비활성 |
| `spring.jpa.open-in-view` | `false` | |

### 서버 / SSL

| 키 | 값 | 비고 |
|----|-----|------|
| `server.port` | `8443` | SSL 포트 |
| `server.ssl.enabled` | `true` | |
| `server.ssl.key-store` | `classpath:chatforyou.jks` | JKS 파일명 |
| `server.ssl.key-store-type` | `JKS` | |
| `server.ssl.keyAlias` | `chatforyou` | |
| `server.ssl.key-store-password` | `xxxx` | JKS 비밀번호 — 민감 |
| `server.servlet.session.tracking-modes` | `cookie` | jsessionid URL 노출 방지 |
| `server.forward-headers-strategy` | `native` | X-Forwarded 헤더 처리 |

### WebSocket / WebRTC

| 키 | 값 | 비고 |
|----|-----|------|
| `server.socket.async-timeout` | `25000` | WebSocket async timeout (ms) |
| `server.rtc.async-timeout` | `3600000` | WebRTC async timeout (ms) |
| `server.rtc.session-idle-timeout` | `3600000` | 세션 유휴 timeout (ms) |
| `kms.url` | `ws://xxxx:38088/kurento` | Kurento Media Server — 민감 |

### TURN 서버

| 키 | 값 | 비고 |
|----|-----|------|
| `turn.server.urls` | `turn:xxxx:33488` | TURN 서버 IP — 민감 |
| `turn.server.username` | `xxxx` | TURN 자격증명 — 민감 |
| `turn.server.credential` | `xxxx` | TURN 자격증명 — 민감 |

### JWT

| 키 | 값 | 비고 |
|----|-----|------|
| `jwt.token_secret_key` | `xxxx` | JWT 서명 키 — 민감 |
| `jwt.room.secret` | `xxxx` | 방 토큰 서명 키 — 민감 |
| `jwt.room.expire-ms` | `3600000` | 방 토큰 만료 시간 (ms) |

### 채팅방

| 키 | 값 | 비고 |
|----|-----|------|
| `chatforyou.room.max_user_count` | `6` | 방 최대 인원 |

### 녹화

| 키 | 값 | 비고 |
|----|-----|------|
| `recording.ext` | `mp4` | 녹화 파일 형식 |
| `recording.url-expire.minutes` | `60` | presigned URL 유효 시간 (분) |
| `recording.upload.max-retries` | `3` | 업로드 최대 재시도 횟수 |
| `recording.upload.retry-delay-ms` | `3000` | 재시도 간격 (ms) |
| `recording.auto-stop-minutes` | `1` | 녹화 자동 종료 시간 (분) |
| `spring.thread.bound.multi` | `2` | I/O bound 스레드 배수 |

### MinIO

| 키 | 값 | 비고 |
|----|-----|------|
| `minio.access.key` | `xxxx` | MinIO 접근 키 — 민감 |
| `minio.access.secret` | `xxxx` | MinIO 시크릿 키 — 민감 |
| `minio.external-url` | `https://xxxx` | MinIO 외부 도메인 — 민감 |
| `minio.bucket-name` | `chatforyou-storage` | 파일 공유 버킷 |
| `minio.recording-bucket-name` | `chatforyou-recording-storage` | 녹화 버킷 |

### 파일 업로드

| 키 | 값 | 비고 |
|----|-----|------|
| `spring.servlet.multipart.enabled` | `true` | |
| `spring.servlet.multipart.maxFileSize` | `10MB` | |
| `spring.servlet.multipart.maxRequestSize` | `10MB` | |
| `allowed.file_extension` | `png, jpg, jpeg, gif` | 허용 파일 확장자 |

### Redis

| 키 | 값 | 비고 |
|----|-----|------|
| `spring.data.redis.host` | `xxxx` | Redis 서버 IP — 민감 |
| `spring.data.redis.master.port` | `30678` | Redis master NodePort |
| `spring.data.redis.slave.port` | `30679` | Redis slave NodePort |
| `spring.data.redis.password` | `xxxx` | Redis 비밀번호 — 민감 |
| `spring.cache.type` | `redis` | |

### Kafka

| 키 | 값 | 비고 |
|----|-----|------|
| `spring.kafka.bootstrap-servers` | `xxxx:30092,xxxx:30093,xxxx:30094` | Kafka broker IP — 민감 |
| `spring.kafka.consumer.group-id` | `chatforyou-consumer-group` | |

### Actuator / 접근 제어

| 키 | 값 | 비고 |
|----|-----|------|
| `management.endpoints.web.exposure.include` | `*` | actuator 노출 (내부망으로 제한됨) |
| `endpoint.allowed_subnet` | `192.168.0.0/24, 10.244.0.0/24, ...` | actuator 허용 서브넷 |
| `endpoint.allowed_ip_addresses` | `127.0.0.1, ::1` | actuator 허용 IP |

### 개발 도구

| 키 | 값 | 비고 |
|----|-----|------|
| `spring.devtools.livereload.enabled` | `true` | 로컬 개발용 Live Reload |
| `spring.thymeleaf.cache` | `false` | 템플릿 캐시 비활성 (개발용) |

### CatchMind Python API

| 키 | 값 | 비고 |
|----|-----|------|
| `catchmind.python.api.url` | `http://xxxx:8000` | Python API 서버 IP — 민감 |
| `catchmind.python.api.titles` | `/game_titles` | 게임 제목 목록 경로 |
| `catchmind.python.api.subjects` | `/game_subjects` | 게임 주제 목록 경로 |

---

## 관련 문서

| 문서 | 설명 |
|------|------|
| [WEBRTC_ROOM_LIFECYCLE.md](./WEBRTC_ROOM_LIFECYCLE.md) | WebRTC/Kurento 방 생명주기 상태 전이 명세 |
| [ROOM_ROUTING_FLOW.md](./ROOM_ROUTING_FLOW.md) | 다중 인스턴스 방 라우팅 플로우 |
| [RECORDING_FLOW.md](./RECORDING_FLOW.md) | 녹화 시스템 아키텍처 및 플로우 |
| [ZERO_DOWNTIME_DEPLOY_FLOW.md](./ZERO_DOWNTIME_DEPLOY_FLOW.md) | K8s Rolling Update 무중단 배포 복구 플로우 |
