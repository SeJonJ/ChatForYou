# [Base Plan] 04월 exception-handling

## 1. Summary
- **목표**: 프로젝트 전반에 파편화된 예외 처리를 `ChatForYouException` 하나로 통합 규격화하고, 추적 가능한 로그 시스템(MDC+TraceID)을 도입하여 시스템의 관측 가능성(Observability)을 극대화합니다.
- **범위**: Spring Boot 백엔드의 전역 예외 처리 로직 재구축 및 Node.js/Electron 프론트엔드의 공통 에러 핸들러 연동.

## 2. Impact Analysis (Critical) - 사이드 이펙트 분석
이 리팩토링은 시스템의 코어를 건드리기 때문에, 다음 항목들에 대한 강력한 영향도 분석이 선행되었습니다.

- **[Backend] Kurento Media Server (KMS) & WebSocket (raw WebSocket)**:
  - *위험도: High*
  - 기존 `KurentoHandler` 및 `WebSocket` 세션에서 발생하는 예외는 일반적인 HTTP 컨트롤러 예외와 흐름이 다릅니다. 현재 구현은 `TextWebSocketHandler` 내부 try-catch와 `KurentoMessageSender` 표준 에러 payload 전송으로 관리합니다.
- **[Backend] ExceptionController 의존성**:
  - *위험도: Medium*
  - 기존 `ExceptionController.InvalidRoomAccessException` 등에 의존하는 모든 서비스 로직(`ChatService` 등)이 전수 수정되어야 합니다.
- **[Frontend] Fetch/Ajax Catch 블록**:
  - *위험도: Medium*
  - 백엔드가 200 OK 대신 4xx/5xx HTTP 상태 코드를 반환하게 되므로, 기존에 상태 코드 상관없이 JSON 파싱을 시도하던 로직들이 깨질 수 있습니다. 전역 인터셉터 도입이 시급합니다.
- **[Desktop] Electron IPC 통신**:
  - *위험도: Low*
  - 백엔드 예외 응답을 공통 포맷으로 받아, 메인 프로세스에서 사용자 다이얼로그로 표시해야 합니다.

## 3. Discussion Trace
- **문제 인식**: 에러 발생 시 로그가 제각각이고 추적 ID가 없어 장애 원인 파악에 과도한 시간 소요.
- **최종 결론**: `ChatForYouException` + `ErrorCode` Enum + `MDC/TraceID` 통합 체계 구축.
- **UX 가이드**: 500 에러 시 "현재 서버가 알 수 없는 이유로 동작하지 않습니다. 금방 고쳐놓을게요!" 메시지 고정.

## 4. Final Conclusion

> 최종 상태 업데이트: 2026-04-30 (STEP 5 취합 반영)
> 판정: **Conditional Pass** — 핵심 설계 목표 및 후속 GAP 수정 완료, 남은 항목은 런타임 QA 및 기술부채 정리

### 4-1. 달성된 목표

| 목표 | 달성 여부 |
|---|---|
| `ChatForYouException` + `ErrorCode` 단일 예외 체계 통합 | 달성 |
| `ExceptionController` 제거 및 `GlobalExceptionHandler`로 대체 | 달성 |
| MDC TraceID 주입 (`TraceIdFilter`, `req-` prefix UUID) | 달성 |
| `X-Trace-Id` 응답 헤더로 프론트 제보 편의성 확보 | 달성 |
| 비즈니스 예외 WARN / 시스템 예외 ERROR 분리 로깅 | 달성 |
| 500 응답 스택트레이스 미포함 | 달성 |
| `handleApiError` 전역 함수 + 6개 ajax 함수 연동 | 달성 |
| `wsMessageHandlers.error` 핸들러 추가 | 달성 |
| `KurentoMessageSender` WebSocket 에러 표준 인프라 신설 | 달성 (설계 초과) |
| `MethodArgumentNotValidException`, `MissingRequestHeaderException` 등 추가 핸들러 | 달성 (설계 초과) |

### 4-2. 필수 수정 항목 처리 결과

| 항목 | 상태 | 비고 |
|---|---|---|
| [GAP-02] `ajaxToJsonPromise` 이중 에러 표시 가능성 | 완료 | 내부 `handleApiError()` 제거, Promise 호출부가 UI 책임 유지 |
| [RISK-02] `fileDownloadAjax` 중복 `xhrFields` | 완료 | `withCredentials` + `responseType: 'blob'` 병합 완료 |
| [GAP-01] `SYNC_GAME_ROUND` 정상 DTO 분리 | 완료 | `GameResultResponse` 도입, `ErrorCode.SYNC_GAME_ROUND` 제거 |
| [GAP-03] `broadcastErrorAndThrow()` 실제 `ErrorCode` 보존 | 완료 | Kurento handled error도 비즈니스 코드 보존 |
| [GAP-06] `qrscan.js` 백엔드 인증 실패 표준 메시지 처리 | 완료 | `getApiErrorMessage()` 연동 완료 |

### 4-3. 후속 검토 결과

| 항목 | 현재 판단 |
|---|---|
| [GAP-04] SSE `onError` / Broken pipe | 해소 완료. `SseService` cleanup + `GlobalExceptionHandler` 204 처리 반영 |
| [GAP-05] Bean Validation 최소 제약 | 해소 완료. 핵심 VO에 `@NotBlank`, `@NotNull`, 범위 제약 반영 |
| [RISK-01] WebSocket traceId 로그 연동 | 해소 완료. 세션 attribute `wsTraceId` 재사용 |
| [RISK-03] 운영 로그 File Appender | 운영 결정으로 종료. k8s stdout/stderr 수집 기준 유지 |
| WebSocket 런타임 재현 QA | 미실시. 브라우저/Electron 실제 시나리오 확인 필요 |

### 4-4. 다음 Iteration 항목

| 항목 | 내용 |
|---|---|
| 테스트 기술부채 | `RedisServiceImplTest`, `KafkaTest`의 `ServletServerContainerFactoryBean` mock 유지 사유 문서화 및 Spring Boot 4.x 이전 `test configuration` 전환 계획 추가 |
| 런타임 QA | 브라우저/Electron 기준으로 SSE disconnect, QR 로그인, 파일 다운로드 인증 쿠키, screen-share error path 재검증 |
| 컨벤션 마감 | 백엔드/프론트 구현 가이드의 `Code conventions`, `Error logging cleanup completed` 체크를 닫을지 여부 최종 정리 |

## 5. Development Precautions

### 5-1. 보안 (기존)
- 500 내부 에러 시 스택 트레이스 응답 body 포함 절대 금지 — `GlobalExceptionHandler`에서 `e.getMessage()`만 응답에 포함
- 로그 레벨: 비즈니스 예외 WARN, 시스템 예외 ERROR로 명확히 구분

### 5-2. 구현 운영 주의사항 (GAP 분석 기반 추가)

**`ajaxToJsonPromise` 사용 시 에러 처리 이중화 금지**

현재 구현은 `ajaxToJsonPromise`가 내부에서 `handleApiError`를 호출하지 않는다. Promise 호출부는 `.catch()`에서 화면별 에러 UI를 직접 책임지고, 공통 처리와 중복되지 않도록 유지할 것.

**`SYNC_GAME_ROUND` 예외 구조 재도입 금지**

현재 구현은 `GameResultResponse` 정상 DTO로 전환되었으므로, 동일 흐름을 다시 `ErrorCode(HttpStatus.OK)` 예외 패턴으로 되돌리지 않는다.

**SSE 응답에 JSON `ErrorResponse` 직접 전송 금지**

현재 `Broken pipe`, `connection reset by peer`, `AsyncRequestNotUsableException` 계열은 204 no-content 로 조기 반환한다. 동일 계열 예외를 일반 JSON 응답 경로로 태우지 않도록 유지한다.

**`fileDownloadAjax` 관련 파일 다운로드 기능 검증 필수**

`xhrFields` 병합은 반영됐지만, 인증이 필요한 실제 다운로드 시나리오는 브라우저/Electron 런타임 QA에서 다시 확인할 것.

**`KurentoMessageSender.broadcastErrorAndThrow()` — 실제 `ErrorCode` 유지**

현재 Kurento 관련 handled error는 `ErrorCode`를 그대로 보존한다. 신규 WebSocket 진입점 추가 시에도 같은 규칙을 유지할 것.

## 6. Document Mapping

- [x] Requirements & Data: `plan_docs/01-plan/exception-handling.md`
- [x] Interface & Sequence: `plan_docs/02-design/exception-handling-spec.md`
- [x] Implementation Guide: `plan_docs/03-implementation/exception-handling-guide.md`
- [x] GAP Analysis: `plan_docs/04-analyze/exception-handling-gap.md` (2026-04-28, 외부 전문가)
- [x] Final STEP 5 Report: `plan_docs/05-report/exception-handling.md`
