## 개발 시 유의점 : backend

### 역할 정의
- Claude는 직접 개발하지 않고 **개발 가이드 작성**이 기본 역할
- 유저가 명시적으로 "개발해줘"라고 요청할 때만 코드를 작성
- 가이드 요청 시 순서:
  1. 관련 코드 전체 분석
  2. 필요한 정보 유저에게 문의
  3. `springboot-backend/plan_docs/[기능명]_plan.md` 작성
  4. "개발을 시작할까요?" 확인 요청

---

## 코드 컨벤션

### 1. 패키지 구조
```
webChat/
├── controller/       # REST API 엔드포인트
├── service/          # 비즈니스 로직 (기능별 서브패키지 허용)
│   └── kurento/      # ex) WebRTC 관련 서비스
├── entity/           # JPA 엔티티
├── model/            # DTO / VO
├── repository/       # Spring Data JPA
├── config/           # Spring 설정 클래스
├── security/         # 인증/인가
├── utils/            # 유틸리티
└── mapper/           # Entity ↔ DTO 변환
```

### 2. 네이밍 규칙

**변수명 기본 전략:**
| 범위 | 케이스 | 예시 |
|---|---|---|
| 내부 변수 / 필드 | `camelCase` | `roomTitle`, `maxUserCount` |
| JSON 응답 (프론트 전달) | `camelCase` | `roomTitle`, `maxUserCount` |
| Enum 값 | `UPPER_SNAKE_CASE` | `ROOM_STATUS_OPEN` |
| 상수 | `UPPER_SNAKE_CASE` | `MAX_USER_COUNT = 48` |

> **JSON camelCase**: Jackson 기본값이 camelCase이므로 별도 설정 불필요.
> jQuery 기반 프론트에서 JS 네이티브 스타일(`data.roomTitle`)로 바로 사용 가능.

**클래스명 네이밍:**
| 유형 | 패턴 | 예시 |
|---|---|---|
| Controller | `{기능}Controller` | `ChatController`, `FileController` |
| Service 인터페이스 | `{기능}Service` | `ChatRoomService` |
| Service 구현체 | `{기능}ServiceImpl` | `UserServiceImpl` |
| Entity | `{도메인명}` (접미사 없음) | `ChatUser`, `SocialUser` |
| DTO | `{도메인}Dto` | `ChatDto`, `UserDto` |
| VO (입력) | `{도메인}InVo` | `ChatRoomInVo` |
| VO (출력) | `{도메인}OutVo` | `ChatRoomOutVo` |
| Repository | `{Entity}Repository` | `ChatUserRepository` |
| Config | `{기능}Config` | `SecurityConfig`, `WebSocketConfig` |
| 메서드 | `camelCase` 동사 시작 | `createChatRoom()`, `getUserInfo()` |

### 3. 클래스 어노테이션 규칙

```java
// Service 기본 템플릿
@Slf4j
@Service
@RequiredArgsConstructor  // 생성자 주입 (field에 final 사용)
public class ExampleService {
    private final ExampleRepository exampleRepository;
}

// Controller 기본 템플릿
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/example")
public class ExampleController {
    private final ExampleService exampleService;
}

// Entity 기본 템플릿
@Entity
@Table(name = "example")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Example { }
```

### 4. 의존성 주입
- **생성자 주입** 방식 사용 (`@RequiredArgsConstructor` + `final` 필드)
- `@Autowired` 필드 주입 금지

### 5. 예외 처리
- **전역 예외**: `@RestControllerAdvice` + `@ExceptionHandler` 방식 — `GlobalExceptionHandler` 단일 클래스로 통합
- **커스텀 예외**: `ChatForYouException` (`RuntimeException` 상속) + `ErrorCode` enum 조합
- **응답 포맷**: `ErrorResponse` (Builder 패턴, `@JsonInclude(NON_NULL)`) 사용 — 스택 트레이스 응답 노출 금지
- 비즈니스 로직에서 `try-catch` 남용 금지 — 예외는 위로 전파

**패키지 구조:**
```
webChat/
├── controller/GlobalExceptionHandler.java  # @RestControllerAdvice 전역 핸들러
├── exception/
│   ├── ChatForYouException.java            # 프로젝트 공통 커스텀 예외
│   └── ErrorCode.java                      # 에러 코드 enum (HttpStatus + code + message)
└── model/response/common/
    └── ErrorResponse.java                  # 에러 응답 DTO
```

**ErrorCode — HttpStatus와 코드·메시지를 함께 보유하는 enum:**
```java
@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "R001", "해당 채팅방을 찾을 수 없습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "A002", "인증이 필요합니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
```

**ChatForYouException — ErrorCode를 필드로 보유하는 커스텀 예외:**
```java
@Getter
public class ChatForYouException extends RuntimeException {
    private final ErrorCode errorCode;
    private final String detail;

    public ChatForYouException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.detail = null;
    }

    public ChatForYouException(ErrorCode errorCode, String detail) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.detail = detail;
    }
}
```

**GlobalExceptionHandler — 전역 핸들러:**
```java
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ChatForYouException.class)
    public ResponseEntity<ErrorResponse> handleChatForYouException(ChatForYouException e) {
        final ErrorCode errorCode = e.getErrorCode();
        log.warn("비즈니스 예외 발생: code={}, message={}", errorCode.getCode(), e.getMessage());
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(buildErrorResponse(errorCode, e.getDetail(), null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException e) {
        // 필드 오류 목록을 ErrorResponse.FieldError 리스트로 변환
        List<ErrorResponse.FieldError> fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> ErrorResponse.FieldError.builder()
                        .field(fe.getField())
                        .value(String.valueOf(fe.getRejectedValue()))
                        .reason(fe.getDefaultMessage())
                        .build())
                .collect(Collectors.toList());
        return ResponseEntity
                .status(ErrorCode.INVALID_INPUT_VALUE.getStatus())
                .body(buildErrorResponse(ErrorCode.INVALID_INPUT_VALUE, null, fieldErrors));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        // 스택 트레이스는 로그에만 기록, 응답에 노출 금지
        log.error("시스템 예외 발생: {}", e.getMessage(), e);
        return ResponseEntity
                .status(ErrorCode.INTERNAL_SERVER_ERROR.getStatus())
                .body(buildErrorResponse(ErrorCode.INTERNAL_SERVER_ERROR, null, null));
    }
}
```

**서비스 레이어에서의 예외 발생 패턴:**
```java
// 비즈니스 예외 — ErrorCode만 전달
throw new ChatForYouException(ErrorCode.ROOM_NOT_FOUND);

// 상세 컨텍스트가 필요한 경우 detail 추가
throw new ChatForYouException(ErrorCode.ROOM_NOT_FOUND, "roomId=" + roomId);
```

### 6. 로깅 규칙
- **`@Slf4j`** (Lombok) 사용 — `LoggerFactory.getLogger()` 직접 선언 금지
- 레벨 기준:

| 레벨 | 사용 상황 |
|---|---|
| `log.debug()` | 개발 중 상세 추적 |
| `log.info()` | 정상 흐름 주요 이벤트 |
| `log.warn()` | 예상 가능한 비정상 상태 |
| `log.error()` | 처리 불가 오류 |

- 로그 메시지: 한글 허용, 변수 값은 `{}` 플레이스홀더 사용
```java
log.info("채팅방 생성 완료: roomId={}", roomId);   // Good
log.error(">>>>>>> " + e.getMessage());             // Bad (문자열 연산 금지)
```

### 7. 주석 규칙

#### 7.1 기본 형식 (간결 우선)
- **인라인 주석**: WHY가 명확한 한 줄만 허용 (WHAT 설명 금지)
- **JavaDoc 형식**: "무엇을 하는지 **1줄** + 순서/정책/예외가 중요할 때 **WHY 1~2줄**". 길게 쓰지 않는다. 핵심 기능이라도 JavaDoc 본문은 간결하게(what) 유지하고, 상세한 "왜 이 순서/조건인가"는 코드 내부 인라인 WHY로 분리한다.
- **파라미터/반환값/예외**: `@param`, `@return`, `@throws`는 이름을 반복하면 생략. 호출자가 실제로 알아야 할 제약·예외가 있을 때만 작성한다.
- **HTML 태그 금지**: `<p>`, `<ol>`, `<li>` 같은 태그형 JavaDoc 금지. 단계 설명이 필요하면 평문으로.
- **TODO 주석**: 리팩토링 예정 코드에만 허용, 담당자와 이유 명시.

#### 7.2 JavaDoc 작성 범위 (3단계)
| Tier | 대상 | JavaDoc | 코드 내부 WHY |
|---|---|---|---|
| **Tier 1 — 공용 계약** | REST Controller 공개 API / 의미가 코드만으로 불명확한 요청·응답 DTO 필드 / WebSocket·Kurento 메시지 payload / 공통 응답·에러 모델 / 공통 `utils`·공통 서비스 인터페이스 | **필수** (what 1줄) | 정책/예외 있을 때 |
| **Tier 2 — 핵심·복잡 흐름** | WebRTC / Kurento / WebSocket lifecycle / 토큰 재발급·room token recovery / Redis 방 상태·라우팅·동시성·재시도 | **필수** (what 1줄, 간결) | **필수** — "왜 이 순서/조건이 필요한가" |
| **Tier 3 — 자명한 보조** | private 헬퍼 / 단순 getter / 짧은 위임 메서드 | **생략 가능** | WHY 있으면 한 줄 |

- 접근제어자 가이드: **public API/계약 = 필수**, **protected 확장 포인트 = 권장**, protected 내부 구현 보조 = 생략, **private = 원칙적 생략**.
- "JavaDoc을 모두 작성"의 의미는 **Tier 1·2를 빠짐없이**라는 뜻이다. 자명한 Tier 3까지 강제하면 과잉 주석이 되므로 생략을 허용한다.

#### 7.3 자기 완결성 (STRICT)
WHY 주석은 장려하되, 그 WHY는 **코드/도메인 개념에 근거**해야 한다. 그 파일만 보는 개발자가 **개발 맥락(PDCA 사이클·설계 라벨·과거 의사결정 히스토리)을 몰라도 이해되는 수준**으로만 작성한다. 배경·근거·전략은 코드 주석이 아니라 `plan_docs/02-design`·`06-report`·vault 노트에 남긴다.

**금지 — 맥락 의존 주석 (반드시 제거)**
- **사이클/설계 라벨 참조**: `D1`, `A1 계획 수용`, `후보 A`, `패턴 B`, `reactive/proactive 전략` 등 PDCA·설계 문서 내부 용어
- **타 레이어 전략 인용 / 결정 출처 인용**: "WebSocket 채널 유지 우선", "설계에서 결정한 트레이드오프", "codex 가 지적한…" 등
- **구현 히스토리 나열**: "#127 fix가 누락했던…", "직전 사이클에서…"

> 같은 코드/도메인 근거를 적는 WHY 주석(예: "startup race 를 줄이기 위해 listener 준비 이후 announce")은 **허용**된다. 외부 문서를 봐야만 뜻이 통하는 라벨/인용이 금지 대상이다.

```java
// Good (Tier 1): 공개 API — what 1줄
/**
 * 채팅방에 입장하고 입장 성공 시 멤버십을 기록한다.
 */
public ResponseEntity<ChatForYouResponse> joinRoom(...) { ... }

// Good (Tier 2): 핵심 흐름 — JavaDoc은 what 간결, 코드 내부에 "왜 이 순서/조건"
/**
 * 라우팅 bootstrap 순서를 실행한다.
 */
public void bootstrapRoutingLifecycle() {
    // listener 준비 이후에만 announce 를 진행해 startup race 를 방지
    ...
}

// Good (Tier 3): 자명한 private 헬퍼 — JavaDoc 생략
private String cleanKey(String key) { ... }

// Bad: 코드 그대로 설명 (WHAT 주석)
// 채팅방 생성 메서드
public ChatRoom createChatRoom() { ... }

// Bad: 맥락 의존 (사이클 라벨 / 결정 출처 인용)
// 후보 A 설계대로 입장 ledger 기록 — 후보 B(roomUUID)는 별도 사이클
redisService.addRoomMember(roomId, email);
```

### 8. 객체 생성 패턴
- Entity / DTO 생성: **Builder 패턴** 사용 (`@Builder`)
- Null 체크: `Objects.nonNull()` / `Optional` 사용, 삼항 연산자 최소화

### 9. 공통 API 응답 포맷
모든 REST 응답은 `ChatForYouResponse<T>` wrapper로 통일한다.

```java
@Getter
@Builder
public class ChatForYouResponse<T> {
    private final boolean success;
    private final T data;
    private final String message;

    public static <T> ChatForYouResponse<T> ok(T data) {
        return ChatForYouResponse.<T>builder().success(true).data(data).build();
    }
    public static <T> ChatForYouResponse<T> fail(String message) {
        return ChatForYouResponse.<T>builder().success(false).message(message).build();
    }
}

// Controller 사용 예
return ResponseEntity.ok(ChatForYouResponse.ok(chatRoomService.createChatRoom(request)));
```

### 10. @Transactional 전략
- **조회 메서드**: `@Transactional(readOnly = true)` — dirty checking 비활성화로 성능 개선
- **쓰기 메서드**: `@Transactional` — 기본값
- Service 클래스 레벨에 `@Transactional(readOnly = true)` 선언 후, 쓰기 메서드에만 `@Transactional` 오버라이드 권장

```java
@Service
@Transactional(readOnly = true)   // 클래스 기본: 조회
@RequiredArgsConstructor
public class ChatRoomService {

    public ChatRoom getChatRoom(String roomId) { ... }   // readOnly 상속

    @Transactional                                        // 쓰기만 오버라이드
    public ChatRoom createChatRoom(ChatRoomInVo request) { ... }
}
```

### 11. Bean Validation
- **Controller**: `@Valid`로 InVo/DTO 검증
- **VO/DTO 필드**: `@NotBlank`, `@NotNull`, `@Max` 등 선언
- 검증 실패 시 `@ExceptionHandler(MethodArgumentNotValidException.class)`로 전역 처리

```java
// Controller
public ResponseEntity<?> create(@Valid @RequestBody ChatRoomInVo request) { ... }

// InVo
public class ChatRoomInVo {
    @NotBlank
    private String title;
    @Max(48)
    private int maxUsers;
}
```

### 12. N+1 방지
- 연관관계 기본값: `FetchType.LAZY`
- 컬렉션 조회: `fetch join` 또는 `@EntityGraph` 명시
- `application.properties`에 배치 사이즈 설정 권장

```properties
spring.jpa.properties.hibernate.default_batch_fetch_size=100
```

```java
// fetch join 예시
@Query("SELECT r FROM ChatRoom r JOIN FETCH r.participants WHERE r.roomId = :roomId")
Optional<ChatRoom> findWithParticipants(@Param("roomId") String roomId);
```

### 13. 테스트 레이어 구분

| 레이어 | 어노테이션 | 범위 |
|---|---|---|
| Controller | `@WebMvcTest` | HTTP 계층, MockMvc 사용 |
| Service | `@ExtendWith(MockitoExtension.class)` | 순수 단위 테스트, Mockito |
| Repository | `@DataJpaTest` | JPA + H2 인메모리 |
| 통합 | `@SpringBootTest` | 전체 컨텍스트 (최소화) |

### 14. PLAN 파일의 코드 컨벤션 검증 항목 (lint 체크리스트)

**프로덕션 코드** 검증: `backend-convention-checker` agent 사용
```
- [ ] 생성자 주입 사용 여부 (@RequiredArgsConstructor + final)
- [ ] @Slf4j 사용 / 문자열 연결 로그 없음
- [ ] 전역 예외처리 적용 (@ControllerAdvice)
- [ ] 네이밍 컨벤션 준수 (camelCase / UPPER_SNAKE_CASE Enum)
- [ ] ChatForYouResponse<T> wrapper 적용
- [ ] @Transactional(readOnly=true) 조회 메서드 적용
- [ ] @Valid 입력 검증 적용
- [ ] FetchType.LAZY + N+1 방지 확인
- [ ] 불필요한 주석 없음 (WHAT 설명 주석 제거)
- [ ] JavaDoc 작성 범위: Tier 1(공용 계약: 공개 API/의미불명 DTO 필드/공통 응답·에러·유틸)·Tier 2(핵심 흐름: WebRTC/Kurento/토큰/Redis) 메서드에 JavaDoc 존재 (§7.2). Tier 2는 코드 내부 WHY("왜 이 순서/조건") 동반
- [ ] JavaDoc 형식: what 1줄 + 필요시 WHY 1~2줄, 장황하지 않음. `@param`/`@return`/`@throws`는 제약 있을 때만
- [ ] 주석 자기 완결성: 사이클/설계 라벨(`D1`, `A1 계획 수용`, `후보 A` 등)·결정 출처 인용·구현 히스토리 주석 없음 (§7.3 — 배경은 plan_docs/vault에)
- [ ] Builder 패턴으로 객체 생성
```

**테스트 코드** 검증: `backend-test-convention-checker` agent 사용
```
- [ ] 레이어 어노테이션 올바르게 사용
- [ ] 테스트 네이밍: 메서드명_조건_기대결과 형식
- [ ] given/when/then 구조 명시
- [ ] BDDMockito.given() 사용
- [ ] Fixture 클래스로 테스트 데이터 관리
- [ ] @SpringBootTest 사용 최소화
```
