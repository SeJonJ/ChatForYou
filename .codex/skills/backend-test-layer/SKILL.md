---
name: backend-test-layer
description: >
  Spring Boot 백엔드 테스트 레이어 가이드 및 검증 스킬.
  "테스트 작성해줘", "테스트 코드 검토해줘", "테스트 레이어 확인", "test convention 체크",
  "테스트 코드 컨벤션", "테스트 검증" 등의 표현을 사용할 때 반드시 이 스킬을 사용한다.
  레이어별 어노테이션, 네이밍, given/when/then, Mockito/MockMvc 패턴, Fixture 관리를 포함.
  PLAN 파일의 테스트 검증 단계에서도 반드시 사용한다.
---

# 백엔드 테스트 레이어 가이드 & 검증

이 스킬은 두 가지 모드로 동작한다.

- **가이드 모드**: 테스트 코드 작성 시 패턴과 예시 제공
- **체커 모드**: `git diff` 기준 변경된 테스트 파일을 자동 검증

---

## 1. 레이어별 테스트 전략

| 레이어 | 어노테이션 | 범위 | 특징 |
|---|---|---|---|
| Controller | `@WebMvcTest` | HTTP 계층 | MockMvc, Bean 슬라이스 |
| Service | `@ExtendWith(MockitoExtension.class)` | 순수 단위 | Mockito, 스프링 컨텍스트 없음 |
| Repository | `@DataJpaTest` | JPA + H2 | 트랜잭션 롤백 자동 |
| 통합 | `@SpringBootTest` | 전체 컨텍스트 | 최소화, 꼭 필요할 때만 |

> 통합 테스트(`@SpringBootTest`)는 슬라이스 테스트로 대체 불가한 시나리오에만 사용한다.

---

## 2. 테스트 네이밍 규칙

```
메서드명_조건_기대결과
```

```java
// Good
@Test
void createChatRoom_whenValidRequest_returnsCreatedRoom() { }

@Test
void getChatRoom_whenRoomNotFound_throwsNotFoundException() { }

// Bad
@Test
void testCreateChatRoom() { }

@Test
void test1() { }
```

---

## 3. Given / When / Then 구조

모든 테스트는 반드시 given/when/then 주석으로 구조를 명시한다.

```java
@Test
void createChatRoom_whenValidRequest_returnsCreatedRoom() {
    // given
    ChatRoomInVo request = ChatRoomFixture.validRequest();
    given(chatRoomRepository.save(any())).willReturn(ChatRoomFixture.saved());

    // when
    ChatRoomOutVo result = chatRoomService.createChatRoom(request);

    // then
    assertThat(result.getRoomId()).isNotNull();
    assertThat(result.getTitle()).isEqualTo(request.getTitle());
}
```

---

## 4. Controller 테스트 (`@WebMvcTest`)

```java
@WebMvcTest(ChatRoomController.class)
class ChatRoomControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    ChatRoomService chatRoomService;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void createChatRoom_whenValidRequest_returns200() throws Exception {
        // given
        ChatRoomInVo request = ChatRoomFixture.validRequest();
        ChatRoomOutVo response = ChatRoomFixture.outVo();
        given(chatRoomService.createChatRoom(any())).willReturn(response);

        // when & then
        mockMvc.perform(post("/api/v1/chat-rooms")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.roomId").exists());
    }
}
```

**Controller 테스트 규칙:**
- 응답 검증: `$.success`, `$.data.*` 경로로 `ChatForYouResponse<T>` 구조 확인
- HTTP 상태 코드 반드시 검증
- `@MockBean`으로 Service 레이어 목킹, 실제 비즈니스 로직 관여 금지

---

## 5. Service 테스트 (`@ExtendWith(MockitoExtension.class)`)

```java
@ExtendWith(MockitoExtension.class)
class ChatRoomServiceTest {

    @InjectMocks
    ChatRoomServiceImpl chatRoomService;

    @Mock
    ChatRoomRepository chatRoomRepository;

    @Test
    void getChatRoom_whenRoomNotFound_throwsNotFoundException() {
        // given
        given(chatRoomRepository.findById("invalid-id")).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> chatRoomService.getChatRoom("invalid-id"))
            .isInstanceOf(ExceptionController.NotFoundException.class);
    }

    @Test
    void deleteChatRoom_whenCalled_verifiesRepositoryDelete() {
        // given
        String roomId = "room-1";

        // when
        chatRoomService.deleteChatRoom(roomId);

        // then
        verify(chatRoomRepository).deleteById(roomId);
    }
}
```

**Service 테스트 규칙:**
- `BDDMockito.given()` 사용 (`Mockito.when()` 지양)
- 예외 검증: `assertThatThrownBy()` 사용
- 상호작용 검증: `verify()` 사용
- `ArgumentCaptor`로 전달 인자 상세 검증 가능

---

## 6. Repository 테스트 (`@DataJpaTest`)

```java
@DataJpaTest
class ChatUserRepositoryTest {

    @Autowired
    ChatUserRepository chatUserRepository;

    @Test
    void findByEmail_whenExists_returnsUser() {
        // given
        ChatUser user = ChatUser.builder()
            .email("test@test.com")
            .nickname("tester")
            .build();
        chatUserRepository.save(user);

        // when
        Optional<ChatUser> result = chatUserRepository.findByEmail("test@test.com");

        // then
        assertThat(result).isPresent();
        assertThat(result.get().getNickname()).isEqualTo("tester");
    }
}
```

**Repository 테스트 규칙:**
- H2 인메모리 DB 자동 사용, 외부 인프라 불필요
- 각 테스트는 `@Transactional`로 자동 롤백 (데이터 오염 방지)
- 커스텀 JPQL / `@Query` 메서드에 집중해서 테스트

---

## 7. Fixture 관리 패턴

테스트 전용 데이터는 `Fixture` 클래스로 중앙 관리한다.

```java
// test/java/.../fixture/ChatRoomFixture.java
public class ChatRoomFixture {

    public static ChatRoomInVo validRequest() {
        return ChatRoomInVo.builder()
            .title("테스트방")
            .maxUsers(10)
            .build();
    }

    public static ChatRoom entity() {
        return ChatRoom.builder()
            .roomId("test-room-1")
            .title("테스트방")
            .maxUsers(10)
            .build();
    }

    public static ChatRoomOutVo outVo() {
        return ChatRoomOutVo.builder()
            .roomId("test-room-1")
            .title("테스트방")
            .build();
    }
}
```

**Fixture 규칙:**
- 파일 위치: `src/test/java/.../fixture/` 패키지
- 네이밍: `{도메인}Fixture`
- 각 메서드는 상태를 명확히 표현: `validRequest()`, `invalidRequest()`, `saved()` 등
- 매직 리터럴 직접 사용 금지 — 반드시 Fixture 통해 생성

---

## 8. 체커 모드 실행 방법

`backend-test-convention-checker` agent를 사용해 아래 작업을 수행한다:

1. **변경 파일 감지**: `git diff --name-only HEAD` 실행
2. **테스트 파일 필터링**: `src/test/java/` 경로 하위 파일만 선별
3. **컨벤션 검증**: 아래 체크리스트 기준으로 각 파일 검토
4. **결과 리포트**: 위반 항목, 위치(file:line), 수정 가이드 출력
5. **PLAN 업데이트**: 작업 중인 PLAN 파일의 테스트 체크박스 `[x]`로 업데이트

---

## 9. 테스트 컨벤션 체크리스트

```
- [ ] 레이어 어노테이션 올바르게 사용 (@WebMvcTest / @ExtendWith(MockitoExtension) / @DataJpaTest)
- [ ] 테스트 네이밍: 메서드명_조건_기대결과 형식
- [ ] given/when/then 구조 명시
- [ ] BDDMockito.given() 사용 (Mockito.when() 지양)
- [ ] Controller: MockMvc + ChatForYouResponse<T> 구조 검증
- [ ] Service: verify() 또는 assertThatThrownBy()로 상호작용/예외 검증
- [ ] Repository: 커스텀 쿼리 메서드 중심 테스트
- [ ] Fixture 클래스 사용 (매직 리터럴 직접 생성 금지)
- [ ] @SpringBootTest 사용 최소화 (슬라이스 테스트 우선)
- [ ] 불필요한 주석 없음 (given/when/then 외 WHAT 설명 주석 제거)
```

---

## 10. 리포트 형식 (체커 모드)

```
## 백엔드 테스트 레이어 검증 결과

### 변경된 테스트 파일
- src/test/java/.../ChatRoomServiceTest.java

### 위반 사항
| 파일 | 라인 | 규칙 | 내용 | 수정 방법 |
|------|------|------|------|-----------|
| ChatRoomServiceTest.java | 25 | 네이밍 | testCreate() | createChatRoom_whenValid_returnsRoom()으로 변경 |

### 통과 항목
- given/when/then 구조: 정상
- Mockito 패턴: 정상

### 결론
[통과 / 수정 필요] — 위반 N건
```
