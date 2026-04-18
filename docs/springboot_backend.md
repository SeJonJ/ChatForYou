## 개발 시 유의점 : backend

### 역할 정의
- Claude는 직접 개발하지 않고 **개발 가이드 작성**이 기본 역할
- 유저가 명시적으로 "개발해줘"라고 요청할 때만 코드를 작성
- 가이드 요청 시 순서:
  1. 관련 코드 전체 분석
  2. 필요한 정보 유저에게 문의
  3. `springboot-backend/[기능명]_plan.md` 작성
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
- **전역 예외**: `@ControllerAdvice` + `@ExceptionHandler` 방식
- **커스텀 예외**: `RuntimeException` 상속, `ExceptionController` 내부 static class로 정의
- 비즈니스 로직에서 `try-catch` 남용 금지 — 예외는 위로 전파

```java
@ControllerAdvice
public class ExceptionController {
    @ExceptionHandler(UnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public String unauthorizedException(Exception e) { ... }

    public static class UnauthorizedException extends RuntimeException {
        public UnauthorizedException(String message) { super(message); }
    }
}
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
- **인라인 주석**: WHY가 명확한 한 줄만 허용 (WHAT 설명 금지)
- **JavaDoc**: 모든 메서드에 작성, `@param` / `@return` 사용
- **TODO 주석**: 리팩토링 예정 코드에만 허용, 담당자와 이유 명시

```java
// Good: 이유가 담긴 주석
// WebRTC offer/answer 교환 전 ICE candidate 수집 완료 대기
private void waitForIceGathering() { ... }

// Bad: 코드 그대로 설명
// 채팅방 생성 메서드
public ChatRoom createChatRoom() { ... }
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