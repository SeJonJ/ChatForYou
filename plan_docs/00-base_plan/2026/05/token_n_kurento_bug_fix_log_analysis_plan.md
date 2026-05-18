# [Base Plan] 로그 분석 버그 수정

**작성일:** 2026-05-04
**브랜치:** feat/104
**Risk Level:** L3 (Critical #1 Kurento WebRTC 포함) + L2 (Auth, JWT 설정)
**Required Phases:** 00 → 01 → 02 → 03 → 04 → 05 → 06

---

## 1. Summary (Goal & Scope)

로그 분석에서 식별된 7개 버그를 우선순위 기반으로 수정한다.
가장 높은 위험도는 Kurento WebRTC 세션 재사용으로 인한 `SDP_END_POINT_ALREADY_NEGOTIATED` 오류이며,
Firebase ID Token 자동 갱신 부재, Room Token TTL 설정, 예외 세분화, Kafka null 발행,
JPA 설정 최적화 순으로 수정 범위를 정의한다.

### 버그 목록 및 구현 순서

**의존성 맵:**
```
ErrorCode.java (A006, R005 추가)
    └─ Bug #3 TokenUtils (A006 사용)
    └─ Bug #4 JwtRoomProvider (R005 사용)
             └─ Bug #2-1 Room Token 재발급 엔드포인트 (R005 반환 + 프론트 연동)
                          └─ Bug #2 Frontend Firebase 자동갱신 (R005/A003 처리 분기)

완전 독립:
    Bug #1 Kurento endpoint release
    Bug #5 Kafka null guard
    Bug #6 open-in-view=false
    Bug #7 dialect 제거
```

권장 구현 순서:
```
Phase 1 (병렬, 백엔드): Bug #1 Kurento endpoint release
                      + Bug #5 Kafka null guard
                      + Bug #6 open-in-view=false
                      + Bug #7 dialect 제거

Phase 2 (순차, 백엔드): ErrorCode.java — A006, R005 추가
                      → Bug #3 TokenUtils 예외 세분화 (A006 사용)
                      → Bug #4 JwtRoomProvider 예외 세분화 (R005 사용)

Phase 3 (순차, 백엔드): Bug #2-1 Room Token 재발급 엔드포인트 추가
                        (Phase 2 완료 후 — R005 코드 필요)

Phase 4 (순차, 프론트): Bug #2 Firebase 자동갱신 + ajaxUtil A003/R005 재시도 연동
                        (Phase 2/3 완료 후 — 백엔드 에러코드 + 재발급 엔드포인트 필요)
```

**순서 근거:**
- Phase 1은 ErrorCode/서비스 레이어와 무관 → 즉시 병렬 처리 가능
- Phase 2에서 A006/R005 코드 먼저 추가 후 각 예외 핸들러에서 사용
- Phase 3 재발급 엔드포인트는 R005 코드가 있어야 의미 있는 응답 반환 가능
- Phase 4 프론트는 R005 분기 처리가 있어야 Room Token 재발급 연동 가능
- **단, Kurento 재연결 시 HTTP `/room/{roomId}` 재호출 없음** → Room Token 만료는 페이지 새로고침 시에만 발생, Bug #1과 직접 연계 없음

---

## 2. Impact Analysis (Critical)

### [Backend]: `springboot-backend/src/main/`

| # | 버그 | 영향 파일 | 변경 유형 |
|---|---|---|---|
| 1 | Kurento endpoint release 누락 | `repository/kurento/participant/InMemoryParticipantRepository.java` | 세션 교체 시 기존 session.close() 호출 로직 추가 |
| 2-1 | Room Token 재발급 엔드포인트 | `controller/ChatRoomController.java`, `service/chatroom/ChatRoomService.java` | `POST /room/token/refresh/{roomId}` 추가, 방 입장 여부 검증 후 재발급 (Fallback: `jwt.room.expire-ms=14400000`) |
| 3 | TokenUtils FirebaseAuthException 구분 | `utils/TokenUtils.java` | `FirebaseAuthException.getErrorCode()` 기반 분기 추가 |
| 4 | JwtRoomProvider R004 세분화 | `security/jwt/JwtRoomProvider.java`, `exception/ErrorCode.java` | R005 신규 코드 추가 및 만료 분기 처리 |
| 5 | Kafka SERVER_STOPPED null guard | `service/routing/InstanceProvider.java` | `shutdown()` 메서드에 instanceId null 체크 추가 |
| 6 | open-in-view=false 명시 | `src/main/resources/application.properties` | `spring.jpa.open-in-view=false` 추가 |
| 7 | hibernate.dialect 제거 | `src/main/resources/application.properties` | `spring.jpa.database-platform` 제거 |

### [Frontend]: `nodejs-frontend/static/js/`

| # | 버그 | 영향 파일 | 변경 유형 |
|---|---|---|---|
| 2 | Firebase ID Token 자동 갱신 | `login/main.js`, `common/ajaxUtil.js` | `onIdTokenChanged` 리스너 등록 + A003 재시도 + R005 Room Token 재발급 연동 |

### [Desktop]: `chatforyou-desktop/src/`

- 직접 수정 금지. `nodejs-frontend` 변경 후 `npm run sync` 실행 필요.
- 영향 파일: `chatforyou-desktop/src/static/js/login/main.js` (sync 결과물)

### [Test]: `springboot-backend/src/test/`

| # | 버그 | 영향 파일 | 변경 유형 |
|---|---|---|---|
| 1 | Kurento endpoint release | 신규 테스트 작성 필요 (`InMemoryParticipantRepositoryTest.java`) | 세션 교체 시 새 세션 유지, 기존 Closeable.close() 후행 호출, close 실패 시 롤백 없음 검증 |
| 3 | TokenUtils 예외 구분 | 신규 테스트 작성 필요 (`TokenUtilsTest.java`) | 각 FirebaseAuthException 오류 코드별 ErrorCode 매핑 검증 |
| 4 | JwtRoomProvider 예외 세분화 | 기존 `JwtRoomProviderTest.java` 존재 시 추가, 없으면 신규 작성 | 만료/무효/불일치 케이스별 예외 코드 검증 |
| 5 | Kafka null guard | 기존 `RoutingBootstrapCoordinatorTest.java` 또는 `InstanceProvider` 관련 테스트 추가 | instanceId=null 상태 publish 차단 + 정상 shutdown cleanup 유지 검증 |
| 2-1 | Room Token 재발급 엔드포인트 | `ChatRoomController` 계층 테스트 추가 필요 | 성공, `A003`, `A006`, `R004`, `R001`, 헤더 누락, 1회 재시도 연계 검증 |

---

## 3. Technology & Risks

### 3.1 버그 상세 분석

#### Bug #1: Kurento SDP_END_POINT_ALREADY_NEGOTIATED (Critical / L3)

**현재 코드 위치 및 문제:**

`InMemoryParticipantRepository.java:29-32`
```java
V existing = participants.putIfAbsent(userId, participant);
if (existing != null) {
    log.warn("사용자 {}가 이미 방 {}에 존재합니다. 기존 세션을 새 세션으로 교체합니다.", userId, roomId);
    participants.put(userId, participant); // 새 세션으로 교체 — 기존 세션 release 없음
}
```

사용자 재연결 시 기존 `KurentoUserSession`이 `close()`되지 않고 Map에서 참조만 제거된다.
기존 세션의 `outgoingMedia(WebRtcEndpoint)`와 `incomingMedia` Map이 Kurento Media Server에 살아있는 상태에서
새 세션이 같은 userId로 SDP Offer를 보내면 `SDP_END_POINT_ALREADY_NEGOTIATED` 예외가 발생한다.

추가로 로그 정황상 duplicate replacement 직전에 `newParticipantArrived`가 먼저 전파되는 흐름과
`Disconnected session had no participant mapping` WARN 반복이 함께 보이므로,
이번 수정은 endpoint release 누락 해결이 1순위이되 join/disconnect ordering 부작용도 인접 코드에서 같이 점검해야 한다.

`KurentoUserSession.close()`는 `Closeable` 구현이며 다음을 해제한다:
- `compositeHubPort`, `compositeScaler` (녹화 중인 경우)
- `incomingMedia` Map의 모든 `WebRtcEndpoint`
- `textOverlayFilter`
- `outgoingMedia`

**수정 방향:**
`InMemoryParticipantRepository.addParticipant()`에서 기존 세션 교체 시
1. `participants.put(userId, participant)`로 새 세션을 먼저 source of truth로 등록하고
2. `existing`이 `Closeable`이면 후행으로 `close()` 호출한다.

단, `close()`는 `IOException`을 발생시킬 수 있으므로 try-catch로 감싸고 실패 시 로그만 남긴다.
제네릭 타입 V가 항상 `KurentoUserSession`임을 런타임에서 확인할 수 없으므로 `instanceof Closeable` 체크 필요.

> WebRTC/Signaling 코드 변경이므로 AGENT_GUIDE.md §8.1 Rule 8에 따라 **최소 2라운드 설계 검토** 필수.
> 구현 전 Phase 02(Design)에서 race condition, resource lifecycle, 보안 관점 독립 검토 2회 문서화 필요.

#### Bug #2: Firebase ID Token 자동 갱신 없음 (High / L2)

**현재 코드 위치 및 문제 (탐색 확인):**

- `login/main.js:94-102`: Firebase SDK 초기화가 `#googleOauth` 버튼 클릭 시 **조건부**로 실행됨 → 이후 리스너 등록 가능 시점 늦음
- `login/main.js:110`: `user.getIdToken()` **일회성** 호출 — 이후 갱신 메커니즘 없음
- `login/main.js:112`: `refresh_token` localStorage 저장 — **전혀 미사용**
- `ajaxUtil.js:5`: `AUTH_REQUIRED_ERROR_CODES = ['A002', 'A003', 'A004', 'U001']`
- `ajaxUtil.js:217`: `localStorage.getItem('access_token')` — 저장된 값을 매 요청마다 그대로 사용
- A003 수신 시: `isAuthRequiredErrorCode()` → 각 페이지 errorCallback에서 `redirectToLogin()` 호출 — **재시도 없음**
- `onIdTokenChanged` 리스너: 프로젝트 전체에서 **미등록**

Firebase ID Token의 TTL은 1시간이다. 1시간 경과 시 A003 수신 → 강제 재로그인이 발생하며
실제로 토큰 갱신이 가능함에도 재시도 로직이 없어 사용자 경험이 저하된다.

다만 여기서의 프론트 원인 분석(`onIdTokenChanged` 미등록, 재시도 없음)은 로그 직접 증거가 아니라
현재 코드 탐색 기반 가설이다. 로그가 직접 보여주는 사실은 백엔드에서 A003이 반복적으로 발생한다는 점까지다.

**수정 방향:**

**(a) `login/main.js` — `onIdTokenChanged` 리스너 등록**
- Firebase 초기화 후 즉시 `auth.onIdTokenChanged(user => ...)` 등록
- 콜백 내 `user.getIdToken()` 호출 → `localStorage.setItem('access_token', token)` 자동 갱신
- `user === null` (로그아웃 상태)이면 `localStorage.removeItem('access_token')`

**(b) `ajaxUtil.js` — A003 처리 강화 (재시도 메커니즘 추가)**
- 현재: A003 → `redirectToLogin()` (즉시 리다이렉트)
- 수정: A003 수신 → Firebase REST Token Refresh API 호출
  (`POST https://securetoken.googleapis.com/v1/token?key={FIREBASE_API_KEY}`)
  → `localStorage.access_token` 업데이트 → 원 요청 **1회 재시도**
  → 재시도도 A003이면 `redirectToLogin()`
- `roomlist.html`, `kurentoroom.html`에는 Firebase SDK가 없으므로 SDK `currentUser.getIdToken(true)` 방식은 사용하지 않는다.

**(c) `ajaxUtil.js` — R005 처리 추가 (Bug #2-1 재발급 엔드포인트 연동)**
- R005 수신 → `POST /chatforyou/api/chat/room/token/refresh/{roomId}` 호출
- 성공 시 `sessionStorage.setItem('roomAccessToken', newToken)` → 원 요청 재시도
- 실패(R004 등) 시 `redirectToRoomList()` (방 목록으로 이동)

**Electron 고려:**
- `onIdTokenChanged`는 Electron 환경에서도 동작 → 별도 분기 불필요
- Firebase 초기화 코드 변경 시 QR 로그인 흐름(`qrscan.js`) 영향 없는지 확인 필요
- 단, 현재 `roomlist.html`, `kurentoroom.html`은 Firebase SDK를 로드하지 않으므로
  공통 `ajaxUtil.js`에서 Firebase 런타임 접근이 가능하도록 bootstrap 또는 대체 경로 설계가 선행되어야 한다.

#### Bug #2-1: Room Token 재발급 엔드포인트 추가 (High / L2)

**현재 상황 분석:**

- Room Token TTL: `application.properties:113` → `jwt.room.expire-ms=1800000` (30분)
- Room Token 검증 위치: `ChatRoomController.joinRoom()` `GET /room/{roomId}` — **페이지 진입 및 새로고침 시에만**
- **Kurento WebSocket 재연결 시**: `KurentoHandler.joinRoom()` → Redis 직접 조회로 처리, HTTP 재호출 없음
- 따라서 Room Token 만료 문제는 **페이지 새로고침/재진입 시**에 발생 (Kurento 재연결과 무관)

주의할 점은, 현재 로그가 직접 보여주는 증상은 `R004`류 invalid room access이며
`ExpiredJwtException` 또는 `R005`는 아직 직접 확인되지 않았다.
즉, 본 항목은 로그 단독 입증이 아니라 현재 코드의 예외 뭉개짐을 분해하기 위한 설계 보강안이다.

**1순위 설계안(현재 보류): 재발급 엔드포인트 추가**

신규 엔드포인트: `POST /chatforyou/api/chat/room/token/refresh/{roomId}`

구현 흐름:
1. Firebase `Authorization` 토큰 검증 (기존 `TokenUtils.checkGoogleOAuthToken()` 재사용)
2. Redis에서 `ChatRoom` 존재 확인 (기존 `chatRoomService.findRoomById()` 재사용)
3. authoritative entitlement source로 room 접근 권한 확인
4. entitlement 실패 시 `ErrorCode.INVALID_ROOM_ACCESS` (R004) 반환
5. entitlement 성공 시 `jwtRoomProvider.create(roomId, email)` 호출 → 새 토큰 반환

구현 파일:
- `controller/ChatRoomController.java` — 엔드포인트 추가
- `service/chatroom/ChatRoomService.java` — `refreshRoomToken(email, roomId)` 메서드 추가
- `service/chatroom/participant/KurentoParticipantService.java` — `getParticipant()` 재사용 (수정 없음)

프론트 연동 (Bug #2 ajaxUtil.js R005 처리와 함께):
- ajaxUtil에서 R005 수신 시 재발급 엔드포인트 호출 → `sessionStorage.roomAccessToken` 갱신 → 원 요청 재시도

**주의사항:**
- `kurentoParticipantService` 기반 현재 인스턴스 participant 확인만으로는 pre-join page refresh 주 경로를 살리지 못할 수 있다.
- 따라서 본 엔드포인트는 entitlement source가 재정의되기 전까지 구현 보류가 맞다.
- 이미 Routing Cookie 기반 sticky session 구조가 있더라도, sticky routing만으로 이 문제를 해결한다고 단정하지 않는다.

**2순위 Fallback (재발급 엔드포인트 구현 시 사이드 이펙트 과대 판단 시):**
- `application.properties` → `jwt.room.expire-ms=14400000` (4시간)으로 변경
- JwtRoomProvider 코드 변경 없음

#### Bug #3: TokenUtils FirebaseAuthException 구분 없음 (Medium / L2)

**현재 코드:**

`TokenUtils.java:14-21`
```java
public static FirebaseToken checkGoogleOAuthToken(String token) {
    if(StringUtil.isNullOrEmpty(token)) throw new ChatForYouException(ErrorCode.TOKEN_NOT_FOUND);
    try {
        return FirebaseAuth.getInstance().verifyIdToken(token);
    } catch (FirebaseAuthException firebaseAuthException) {
        throw new ChatForYouException(ErrorCode.TOKEN_EXPIRED); // 모든 Firebase 예외를 A003으로 처리
    }
}
```

`FirebaseAuthException.getErrorCode()`로 구분 가능한 케이스:
- `ID_TOKEN_EXPIRED`: 만료 → A003 (TOKEN_EXPIRED) — 현재와 동일
- `ID_TOKEN_REVOKED`: 강제 무효화 → A003 또는 신규 코드 검토 필요
- `INVALID_ID_TOKEN`, `CERTIFICATE_FETCH_FAILED` 등: 위조/네트워크 오류 → A006(TOKEN_INVALID) 신규 코드 필요

**수정 방향:**
- `ErrorCode`에 `TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "A006", "유효하지 않은 토큰입니다.")` 추가
- `FirebaseAuthException.getAuthErrorCode().name()` 또는 `getErrorCode()` 기반으로 `switch` 분기
- 만료/취소는 A003, 위조/파싱오류는 A006, 네트워크/서비스 오류는 C003으로 처리

> Firebase SDK 버전에 따라 `getErrorCode()` vs `getAuthErrorCode()` API가 다를 수 있으므로
> 구현 전 현재 사용 중인 firebase-admin SDK 버전 확인 필요.

#### Bug #4: JwtRoomProvider R004 만료/무효 미구분 (Medium / L2)

**현재 코드:**

`JwtRoomProvider.java:42-62` — `validate()` 메서드:
```java
} catch (ChatForYouException e) {
    throw e;
} catch (Exception e) {
    throw new ChatForYouException(ErrorCode.INVALID_ROOM_ACCESS); // 모든 예외를 R004로 처리
}
```

JJWT `parseClaimsJws()` 예외 타입별 구분:
- `ExpiredJwtException`: 만료 → R005(ROOM_TOKEN_EXPIRED) 신규 코드 필요
- `MalformedJwtException`, `UnsupportedJwtException`, `SignatureException`: 위조/파싱 → R004 유지
- `IllegalArgumentException`: null/빈 토큰 → R004 또는 A004와 정렬 검토

**수정 방향:**
- `ErrorCode`에 `ROOM_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "R005", "방 입장 토큰이 만료되었습니다.")` 추가
- `JwtCoreProvider.parse()` 또는 `JwtRoomProvider.validate()` 내에서 `ExpiredJwtException` 분리 catch
- R005를 반환하면 프론트가 재발급 로직을 트리거할 수 있음

**ErrorCode 추가 목록 (Bug #3, #4 통합):**

현재 ErrorCode enum에 없는 신규 코드:
```
A006 - TOKEN_INVALID: 유효하지 않은 토큰 (위조, 파싱 실패 등)
R005 - ROOM_TOKEN_EXPIRED: 방 입장 토큰이 만료됨
```

#### Bug #5: Kafka SERVER_STOPPED instanceId=null 발행 방지 (Medium / L2)

**현재 코드:**

`InstanceProvider.java:155-168` — `shutdown()` 메서드:
```java
public synchronized void shutdown() {
    publishServerEvent(ServerEvent.SERVER_STOPPED, instanceId); // instanceId가 null이면 그대로 발행
    redisService.delInstanceInfo(instanceId);
    ...
}
```

`publishServerEvent()`에서 `instanceId`가 null이면 `KafkaServerEvent.of(SERVER_STOPPED, null, ...)` 로 발행된다.
수신 측 `handleServerEvent()`에서는 `StringUtil.isNullOrEmpty(event.getInstanceId())` 체크가 있어 WARN 로그가 발생한다.

서버 기동 초기에 `instanceId`가 null인 상태에서 종료 신호가 발생하는 엣지 케이스에서 기동마다 WARN 20건 발생.

**수정 방향:**
`shutdown()` 메서드 진입 시 `StringUtil.isNullOrEmpty(instanceId)` 체크 추가.
null이면 WARN 로그 출력 후 `publishServerEvent` 호출 스킵, Redis 정리만 수행.

#### Bug #6: spring.jpa.open-in-view=false 명시 (Low / L1)

**현재 상태:**
`application.properties`에 `spring.jpa.open-in-view` 설정 없음 → 기본값 `true`
이 경우 HTTP 요청 전체 라이프사이클 동안 EntityManager가 열려 있어 불필요한 커넥션 점유 및
LazyLoading이 View 계층에서 발생할 수 있다.

**수정 방향:**
`application.properties`에 `spring.jpa.open-in-view=false` 추가.

#### Bug #7: hibernate.dialect 명시 제거 (Low / L1)

**현재 상태:**
`application.properties:11`
```properties
spring.jpa.database-platform=org.hibernate.dialect.MariaDBDialect
```

Hibernate 6.x (Spring Boot 3.x)부터 `dialect` 자동 감지가 안정화되었으므로 명시적 설정은 불필요하며,
특정 Hibernate 버전 업그레이드 시 deprecated Dialect 클래스명으로 인해 오류가 발생할 수 있다.

**수정 방향:**
`spring.jpa.database-platform=org.hibernate.dialect.MariaDBDialect` 제거.
단, MariaDB JDBC URL에서 자동 감지가 올바르게 동작하는지 빌드 후 확인 필요.

---

## 4. Final Conclusion & UX Guide

### UX 개선 효과

| 버그 | 수정 전 UX | 수정 후 UX |
|---|---|---|
| #1 Kurento endpoint release | 재접속 시 다른 참가자 영상 연결 실패 | 재접속 후 정상 영상 연결 |
| #2 Firebase Token 자동 갱신 | 1시간 후 A003 → 수동 재로그인 필요 | 토큰 자동 갱신, 사용자 개입 불필요 |
| #2-1 Room Token 재발급 | 30분 후 새로고침 시 R005 → 방 목록으로 이동 | 재발급 엔드포인트로 자동 갱신, 장시간 세션 유지 가능 |
| #3 TokenUtils | 위조 토큰도 "토큰 만료" 메시지 노출 | 원인에 맞는 정확한 오류 메시지 |
| #4 JwtRoomProvider | 만료된 Room Token도 "잘못된 접근" 표시 | "토큰 만료" 메시지로 재발급 유도 가능 |
| #5 Kafka null guard | 서버 기동 시마다 WARN 로그 20건 | 불필요한 WARN 로그 제거 |
| #6/#7 설정 | open-in-view 기본값 true, dialect 잠재적 오류 | 설정 최적화, 업그레이드 안전성 향상 |

---

## 5. Document Mapping (Checklist)

### Phase 진행 체크리스트

```
[ ] 00-base_plan: 본 문서 (완료)
[ ] 01-plan: plan_docs/01-plan/bug_fix_log_analysis.md
    - 상세 요구사항 (각 버그 수정 범위)
    - 신규 ErrorCode 정의 (A006, R005)
    - API 영향 스펙 (기존 API 응답 코드 변경 포함)
[ ] 02-design: plan_docs/02-design/bug_fix_log_analysis.md
    - Bug #1: WebRtcEndpoint release 시퀀스 다이어그램
    - Bug #1: 2라운드 설계 검토 (WebRTC 관련 MANDATORY)
    - 예외 처리 전략 (A006, R005 추가 설계)
[ ] 03-implementation: plan_docs/03-implementation/bug_fix_log_analysis.md
    - 파일 소유권 배분 (아래 별도 기재)
    - 구현 체크리스트
    - 빌드 및 테스트 결과
[ ] 04-analyze: plan_docs/04-analyze/bug_fix_log_analysis.md
[ ] 05-expert-review: plan_docs/05-expert-review/bug_fix_log_analysis.md
[ ] 06-report: plan_docs/06-report/bug_fix_log_analysis.md
```

### 파일 소유권 배분

**백엔드 전문가 (`springboot-backend/src/main/`)**
- `java/webChat/repository/kurento/participant/InMemoryParticipantRepository.java` — Bug #1
- `java/webChat/utils/TokenUtils.java` — Bug #3
- `java/webChat/security/jwt/JwtRoomProvider.java` — Bug #4
- `java/webChat/exception/ErrorCode.java` — Bug #3/#4 (A006, R005 추가)
- `java/webChat/service/routing/InstanceProvider.java` — Bug #5
- `java/webChat/controller/ChatRoomController.java` — Bug #2-1 재발급 엔드포인트 추가
- `java/webChat/service/chatroom/ChatRoomService.java` — Bug #2-1 서비스 메서드 추가
- `resources/application.properties` — Bug #6, #7 (Bug #2-1 Fallback 시: jwt.room.expire-ms 변경)

**QA 전문가 (`springboot-backend/src/test/`)**
- `java/webChat/repository/kurento/participant/InMemoryParticipantRepositoryTest.java` — Bug #1 (신규 또는 추가)
- `java/webChat/utils/TokenUtilsTest.java` — Bug #3 (신규)
- `java/webChat/security/jwt/JwtRoomProviderTest.java` — Bug #4 (신규 또는 추가)
- `java/webChat/service/routing/` — Bug #5 (기존 RoutingBootstrapCoordinatorTest.java 또는 InstanceProvider 관련 테스트)
- `java/webChat/controller/ChatRoomController*Test.java` — Bug #2-1 refresh API HTTP 계약 검증
- `java/webChat/service/chatroom/ChatRoomServiceTest.java` — Bug #2-1 참가자 검증/토큰 재발급 서비스 로직 검증

**프론트엔드 전문가 (`nodejs-frontend/static/js/`)**
- `login/main.js` — Bug #2 (onIdTokenChanged 리스너 등록)
- `common/ajaxUtil.js` — Bug #2 (A003 재시도 + R005 재발급 엔드포인트 연동)

**Electron sync (npm run sync 실행 주체: 프론트엔드 전문가)**
- `chatforyou-desktop/src/static/js/login/main.js` — sync 결과물이므로 직접 수정 금지

### 주의사항 (MANDATORY)

1. **Bug #1은 WebRTC 코드 변경**이므로 AGENT_GUIDE.md §8.1 Rule 8에 따라
   Phase 02에서 **최소 2라운드 독립 설계 검토**가 완료될 때까지 구현 시작 불가.
2. `application.properties` 변경(#6, #7, Fallback 시 #2-1) 시 로컬/운영 환경 분리 여부 확인 필요.
   현재 프로젝트는 단일 `application.properties`를 사용하므로 적용 즉시 운영에 반영됨.
3. Bug #7 dialect 제거 후 반드시 `./gradlew clean build` 및 DB 연결 테스트 수행.
4. 프론트 수정 완료 후 `chatforyou-desktop/`에서 `npm run sync` 실행하여
   Electron 빌드 에러 없음 확인 전까지 작업 완료로 간주하지 않는다.
