# [Base Plan] 04월 grafana-loki-logging

## Executive Summary

| 관점 | 내용 |
|------|------|
| **Problem** | 장애 발생 시 로그가 각 서버 콘솔에만 출력되어 검색·필터링이 불가하며, `traceId`가 있음에도 이를 횡단 조회할 수단이 없어 원인 파악에 과도한 시간 소요 |
| **Solution** | Grafana Loki를 도입하여 Spring Boot 로그를 중앙 집중 수집하고, 기존 Prometheus 메트릭과 traceId로 연계한 단일 Grafana 대시보드 구성 |
| **UX Effect** | 개발·운영 모두 Grafana 한 화면에서 "에러 메트릭 급등 → traceId 클릭 → 해당 요청 전체 로그 조회"가 가능해져 MTTR(평균 복구 시간) 단축 |
| **Core Value** | 이미 존재하는 `TraceIdFilter` + `micrometer-prometheus` 인프라를 최소 코드 변경으로 활용하여 관측 가능성(Observability) 완성 |

---

## 1. Summary

- **목표**: Spring Boot 백엔드의 로그를 Grafana Loki로 중앙 집중화하여, 기존 Prometheus 메트릭과 traceId 기반으로 연계 조회 가능한 단일 Grafana 옵저버빌리티 대시보드를 구축합니다.
- **범위**: Spring Boot 백엔드 logback 설정 변경 + Loki 인프라 구성. 프론트엔드·데스크탑 앱 변경 없음.
- **핵심 원칙**: 기존 `TraceIdFilter` / `MDC traceId` / `micrometer-prometheus` 자산을 최대한 재활용하며, Promtail 에이전트 없이 앱에서 직접 Loki로 push.

---

## 2. 현재 상태 (As-Is)

### 2-1. 기존 인프라 자산 (재활용 가능)

| 자산 | 위치 | 활용 방안 |
|------|------|-----------|
| `TraceIdFilter` | `webChat.filter.TraceIdFilter` | `MDC.put("traceId", "req-" + UUID)` 이미 구현됨. Loki 라벨로 즉시 활용 가능 |
| `logback-spring.xml` | `src/main/resources/logback-spring.xml` | `%X{traceId}` 패턴 이미 포함. JSON 포맷 + LokiAppender만 추가 |
| Prometheus | `build.gradle` micrometer-registry-prometheus:1.11.3 | 메트릭 수집 가동 중. Grafana traceId 연계 기반 |
| Actuator | `spring-boot-starter-actuator` | 헬스체크 / 메트릭 엔드포인트 노출 중 |
| `DownloadLogService` + `LogBatchJob` | `webChat.service.monitoring` | 기존 DB 기반 로그 → Loki로 점진적 전환 대상 |

### 2-2. 현재 logback 설정

```xml
<!-- 현재: Console only, 기본 패턴 -->
<appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
        <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{traceId}] %-5level %logger{36} - %msg%n</pattern>
    </encoder>
</appender>
```

**문제점**: 텍스트 형식이라 Loki에서 필드 추출이 어렵고, 파일로도 저장되지 않아 히스토리 조회 불가.

---

## 3. 목표 상태 (To-Be)

### 3-1. 아키텍처

```
Spring Boot (logback-spring.xml)
  ├── ConsoleAppender (개발용 유지)
  └── LokiAppender (loki4j:loki-logback-appender)
        └── HTTP Push → Loki
                        └── Grafana
                              ├── Logs (LogQL: {app="chatforyou"} |= "traceId")
                              └── Metrics (Prometheus) ──── traceId 연계
```

**핵심 결정**: Promtail 에이전트 없이 `loki-logback-appender`로 앱에서 직접 Loki로 push
- 컴포넌트 수 최소화
- 파일 저장 불필요 (Loki가 스토리지)
- 네트워크 장애 시 비동기 큐 버퍼 내장

### 3-2. 로그 포맷 변경 (JSON 구조화)

```json
{
  "timestamp": "2026-04-01T10:00:00.000Z",
  "level": "ERROR",
  "thread": "http-nio-8080-exec-1",
  "logger": "webChat.service.chat.ChatService",
  "message": "채팅방 입장 실패",
  "traceId": "req-550e8400-e29b-41d4-a716-446655440000",
  "app": "chatforyou",
  "env": "production"
}
```

**이점**: Loki에서 `{traceId="req-xxx"}` 로 즉시 필터링 가능

---

## 4. User Intent Discovery (Phase 1 결과)

| 항목 | 답변 |
|------|------|
| **핵심 목적** | 장애 원인 추적 — 에러 발생 시 traceId 기반으로 실패 의심 API를 실시간 조회 |
| **주 사용자** | 개발자 + 운영자 모두 (공유 Grafana 대시보드) |
| **성공 기준** | 에러 발생 후 5분 이내에 traceId로 해당 요청의 전체 로그 흐름을 Grafana에서 조회 가능 |

---

## 5. Alternatives Explored (Phase 2 결과)

| 접근 방식 | Pros | Cons | 결론 |
|-----------|------|------|------|
| **Grafana Loki** ✅ | Prometheus 통합, 라벨 기반 저장으로 저비용, loki-logback-appender 한 줄 연동, traceId MDC 즉시 활용 | Full-text 검색 약함 | **선택** |
| ELK Stack | 강력한 full-text 역인덱스, 복잡한 집계 | JVM 4GB+ 메모리 소모, Grafana와 별도 스택, 운영 복잡도 높음 | 제외 |
| Graylog | 로그 전용 대시보드, Alert 내장 | 내부적으로 Elasticsearch 의존, Prometheus 연동 어색 | 제외 |

**선택 이유**: 이미 `micrometer-prometheus` + Actuator가 있어 Grafana 스택이 자연스럽고, traceId MDC 인프라가 이미 완비되어 있음.

---

## 6. YAGNI 검토 결과 (Phase 3 결과)

### 6-1. 1차 버전 포함 (필수)

| 항목 | 설명 |
|------|------|
| `loki-logback-appender` 연동 | Spring Boot → Loki HTTP push. `com.github.loki4j:loki-logback-appender` |
| JSON 구조화 로그 전환 | `logstash-logback-encoder` 또는 Loki4J 내장 JSON formatter로 logback 패턴 변경 |
| traceId 기반 로그-메트릭 연계 | Grafana Explore에서 traceId로 Loki 로그 + Prometheus 메트릭 동시 조회 설정 |

### 6-2. 2차 버전 이후 (선택적 추가)

| 항목 | 설명 |
|------|------|
| ERROR/WARN 알림 규칙 | Grafana Alert Rule: 1분간 ERROR 로그 5건 이상 → Slack/이메일 알림 |
| 로그 보존 정책 설정 | Loki 설정에서 30일 retention 적용 |

### 6-3. 제외 (Out of Scope)

| 항목 | 이유 |
|------|------|
| Promtail 에이전트 | logback appender 직접 push로 대체. 불필요한 컴포넌트 추가 없음 |
| Kubernetes Helm Chart 배포 | 1차 버전에서는 로컬/개발 환경 검증 후 결정 |
| Docker Compose 로컬 구성 | 개발 환경 별도 논의 |

---

## 7. 구현 계획 (Implementation Plan)

### 7-1. 변경 파일 목록

| 파일 | 변경 내용 | 우선순위 |
|------|-----------|---------|
| `springboot-backend/build.gradle` | `loki-logback-appender` 의존성 추가 | P0 |
| `src/main/resources/logback-spring.xml` | JSON format + LokiAppender 추가 | P0 |
| `src/main/resources/application.properties` | Loki URL 외부 설정 추가 (`loki.url`) | P0 |
| Grafana 설정 | Loki datasource 추가, 기본 대시보드 구성 | P1 |

### 7-2. 의존성 추가 (예상)

```gradle
// Loki logback appender
implementation 'com.github.loki4j:loki-logback-appender:1.5.2'

// JSON 구조화 로그 (선택: Loki4J 내장 JSON formatter 사용 시 불필요)
// implementation 'net.logstash.logback:logstash-logback-encoder:7.4'
```

### 7-3. logback-spring.xml 변경 방향 (예상)

```xml
<!-- 추가할 LokiAppender -->
<appender name="LOKI" class="com.github.loki4j.logback.Loki4jAppender">
    <http>
        <url>${loki.url:-http://localhost:3100}/loki/api/v1/push</url>
    </http>
    <format>
        <label>
            <pattern>app=chatforyou,env=${spring.profiles.active:-local},level=%level,traceId=%X{traceId}</pattern>
        </label>
        <message>
            <!-- JSON 구조화 -->
            <pattern>{"timestamp":"%d{yyyy-MM-dd'T'HH:mm:ss.SSS}","level":"%level","thread":"%thread","logger":"%logger{36}","message":"%message","traceId":"%X{traceId}"}</pattern>
        </message>
    </format>
</appender>
```

---

## 8. Impact Analysis (사이드 이펙트 분석)

| 영역 | 위험도 | 내용 |
|------|--------|------|
| **Spring Boot 로그 성능** | Low | LokiAppender는 비동기 배치 전송. 앱 처리 경로와 분리 |
| **Loki 서버 장애 시** | Low | 비동기 큐에 버퍼링 후 재시도. 앱 동작에 영향 없음 |
| **기존 DownloadLog DB 기록** | None | 별도 DB 로그는 독립 동작. 충돌 없음 |
| **logback 패턴 변경** | Low | JSON 포맷 전환 시 기존 콘솔 로그 가독성 저하 → ConsoleAppender는 기존 패턴 유지 권장 |
| **traceId 라벨 카디널리티** | Medium | Loki에서 고유값(UUID)을 라벨로 사용 시 성능 저하 가능 → 라벨에는 `level`, `app`, `env`만, traceId는 메시지 필드(LogQL `|=` 검색)로 처리 권장 |

> **중요**: `traceId`를 Loki **라벨(label)**이 아닌 **로그 메시지 필드**에 포함해야 함. 라벨은 저카디널리티 값(level, app, env)만 사용. 이는 Loki 성능의 핵심 설계 원칙.

---

## 9. 전문가 검토 요청 포인트

이 문서를 기반으로 다음 항목에 대해 전문가 의견을 구합니다:

1. **loki-logback-appender vs logstash-logback-encoder 조합**: JSON 직렬화를 Loki4J 내장 formatter로 할지, logstash-logback-encoder를 별도 사용할지
2. **traceId Loki 라벨 vs 메시지 필드**: 카디널리티 문제 상세 검토
3. **Grafana Explore traceId 연계 설정**: Prometheus `exemplars`와 Loki 연계 방식 (Tempo 없이 가능한지)
4. **Kubernetes 환경 배포 전략**: 1차 로컬 검증 후 k8s Loki Stack(Helm) 도입 여부
5. **기존 `LogBatchJob` + `DownloadLogService` DB 로그와의 역할 분리**: Loki 도입 후 DB 로그를 유지할지 / 어떤 로그를 DB에 남기고 어떤 것을 Loki로 보낼지

---

## 10. Discussion Trace

| 날짜 | 결정 사항 |
|------|-----------|
| 2026-05-03 | 로그 시스템 도입 방향 논의 시작. 핵심 목적: 장애 원인 추적 |
| 2026-05-03 | ELK, Graylog, Grafana Loki 비교 후 Loki 선택 — Prometheus 기존 인프라 재활용 |
| 2026-05-03 | YAGNI 검토: 1차 버전은 appender 연동 + JSON 포맷 + traceId 연계 3가지로 최소화 |
| 2026-05-03 | traceId를 Loki 라벨이 아닌 메시지 필드로 처리해야 함 (카디널리티 이슈) — 전문가 검토 필요 |

---

## 11. Next Steps

1. 이 문서를 기반으로 전문가와 상세 설계 논의
2. 전문가 검토 후 `/pdca design grafana-loki-logging` 으로 설계 단계 진행
3. 인프라 담당자와 Loki 서버 환경 결정 (로컬 Docker / k8s Helm)
