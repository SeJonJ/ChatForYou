# 2026-06 coturn · Kurento 배포 개선 + TURN 자격증명 보안 (Issue #139)

> 기본 계획 (Path B — 외부 설계 파일 없음 / 팀 리더가 ARCHITECT_GUIDE 표준으로 작성)
> 본 문서는 **설계/분석 단계 산출물**이다. 소스 코드는 수정하지 않는다.

---

## 0. 개요

| 항목 | 내용 |
|---|---|
| Issue | #139 — feat :: coturn · Kurento 배포 개선 |
| 작성 | chatforyou-lead (2026-06-18) |
| Risk Level | **L3** (WebRTC / TURN / Kurento 미디어 경로 직결) |
| Compound rule | yes — 인프라(K8s) + 백엔드(API/config) + 프론트(ICE consumer) 복합 |
| 필수 Phase | 00 ~ 06 |
| L3 리뷰 | webrtc-review-protocol 2라운드 (R1 flow / R2 failure·lifecycle) 필수 — 구현 차단 게이트 |
| Vault 근거 | `BRAINSTORM - coturn 배포 개선`, `BRAINSTORM - Kurento 배포 개선`, `TECH - Kurento TURN 서버 설정`, `TECH - ChatForYou coturn K8s 배포 매니페스트`, `TECH - Kurento K8s 매니페스트` |

### 0.1 인프라 영향 경고 (MANDATORY)

- **K8s 매니페스트는 본 레포에 없다** — 클러스터(coturn ns StatefulSet, kurento-media-server ns Deployment)에서 별도 관리된다. 본 사이클에서 매니페스트 변경은 **설계 산출물(apply-ready YAML 가이드)** 로만 다루고, 실제 apply는 유저가 클러스터에서 수행한다.
- **coturn `hostNetwork` 전환은 보안그룹/방화벽 변경을 동반한다**: relay 포트 범위(`min-port`~`max-port`, 예 49152–65535 또는 좁힌 범위)를 노드 공인 IP(210.220.67.85)에서 UDP로 외부 개방해야 한다. 이는 인프라 담당자 승인이 필요한 변경이며, 본 PLAN은 그 범위와 근거만 명시한다.
- coturn은 WebRTC 연결 성공률의 **단일 장애점(SPOF)** 이다. 변경 시 카나리 통화 검증을 동반한다.

---

## 1. 기능 목적 및 범위

### 1.1 목적
1. **coturn 배포 정상화**: relay 포트 도달성(P0) 검증·해결, 운영 하드닝(resources/probe/이미지 고정/로그).
2. **Kurento 배포 정상화**: `replicas:2`→`replicas:1` 확정으로 모순 해소(P0), KMS 제어 경로 정리(공인 IP→내부 DNS, P1), 리소스/probe(P1). 수평 확장(KMS 풀)은 추후 작업(10절).
3. **TURN 자격증명 보안 개선**: 정적 공유 평문 자격증명 → coturn TURN REST API(`use-auth-secret`) 기반 세션별 HMAC 시간제한 자격증명 발급.

### 1.2 범위 분류 (작업 묶음)

| 묶음 | 항목 | 위험도 | 컴포넌트 | 본 사이클 포함 |
|---|---|---|---|---|
| **A. 인프라 빠른 하드닝** | coturn/Kurento resources·probe·imagePullPolicy·로그 stdout | 낮음(인프라) | K8s YAML 가이드 | 포함 (설계만) |
| **B. coturn relay 도달성(P0)** | `hostNetwork` 전환 + relay port range + 방화벽 | L3 | K8s YAML 가이드 + 인프라 | 포함 (설계 + 검증 절차) |
| **C. Kurento 구조(P0)** | `replicas:1` 확정 (KMS 풀 분리는 추후 작업 — 10절) | L3 | K8s | **확정됨 — `replicas:1` 단순화** |
| **D. KMS 제어 경로(P1)** | 공인 IP → ClusterIP 내부 DNS + 포트 불일치(38088↔30888) 정리 | L3 | 백엔드 properties + K8s | 포함 (설계) |
| **E. TURN 자격증명 보안** | `use-auth-secret` HMAC 세션 자격증명 발급 API + 프론트/Kurento 연동 | L3 | 백엔드 + 프론트 + K8s | **포함 (핵심 설계)** |

> 본 PLAN은 위 전 항목을 **설계·분석**한다. 실제 구현 착수 범위·순서는 02/03 설계 후 유저 승인 게이트에서 확정한다.

#### C 묶음 결정 — `replicas:1` 확정 (2026-06-18 유저 승인)
- 현행 `replicas:2`는 HA도 LB도 아니다: Kurento는 인스턴스 간 클러스터링이 없고, 백엔드는 단일 `KurentoClient` WebSocket 1개만 사용하며, k8s ClusterIP LB는 방 단위 affinity를 제공하지 못한다 → `replicas:2`는 구조적 모순.
- **코드 근거**: `KurentoConfig`=단일 `KurentoClient` 빈, `KurentoHandler:329/333`이 그 단일 client로 `createMediaPipeline`, `KurentoPipelineMap`=roomId→MediaPipeline을 백엔드 JVM 메모리에 보관, `ShutdownConfig:115`=단일 destroy.
- 멀티 KMS(수평 확장)로 가려면 위 전부 + 방→KMS 매핑 + 인스턴스 장애처리가 필요 = 큰 별도 L3 → **추후 작업으로 등록**(10절), 본 사이클은 `replicas:1` 단순화로 모순만 제거.

---

## 2. TURN 자격증명 보안 분석 (핵심)

### 2.1 현황 (코드 근거)

| 위치 | 현황 |
|---|---|
| `application.properties:111-114` | `turn.server.urls/username/credential` 평문 (`chatforyou` / `chatforservice`) |
| `AdminController.java:125-135` `/chatforyou/api/admin/turnconfig` | 위 3개 값을 **인증 없이** JSON으로 그대로 반환 (`url`, `username`, `credential`) |
| `kurento-service.js:1004-1023` `initTurnServer()` | `POST /admin/turnconfig` → `turnUrl/turnUser/turnPwd` 전역 변수에 저장 |
| `kurento-service.js:1586-1594, 1666-1674` | `WebRtcPeer` `iceServers`에 `{urls, username, credential}` 주입 (송신·수신 peer 각각) |
| Kurento ConfigMap (`WebRtcEndpoint.conf.ini`) | `turnURL=chatforyou:chatforservice@192.168.0.165:30479` 평문 — **Kurento도 동일 자격증명 사용** |

### 2.2 위험성

| 등급 | 위험 |
|---|---|
| **High** | `/admin/turnconfig`가 **인증 게이트 없음** — `@PostMapping`만 있고 토큰 검증 없음(같은 컨트롤러의 `/allrooms`·`/{roomId}`는 Bearer 검증하나 turnconfig는 누락). 누구나 호출해 TURN 자격증명 취득 가능. |
| **High** | 자격증명이 **정적·무기한·공유** — 한 번 유출되면 무효화 수단이 없고, 제3자가 우리 coturn을 무료 relay(대역폭 도용)·사설망 스캔 relay로 악용 가능. |
| **Medium** | 동일 비밀이 ConfigMap·properties·프론트 전달 JSON·문서 등 **여러 곳에 평문** 산재 → 순환(rotation) 불가, 노출면 넓음. |
| **Medium** | 프론트로 평문 전달은 WebRTC 구조상 **불가피한 측면**(브라우저가 TURN에 직접 인증)이나, 정적 비밀을 그대로 주는 것과 단기 HMAC을 주는 것은 위험도가 다르다. |

> 핵심 인식: "프론트에 자격증명이 노출된다"는 것 자체는 WebRTC에서 제거 불가능하다(브라우저가 TURN 서버에 직접 long-term credential로 인증). **개선의 본질은 "정적·무기한·공유" → "세션별·시간제한(TTL)·HMAC 파생"으로 바꿔 유출 시 폭발 반경을 TTL 이내로 한정**하는 것이다.

> **P0(relay 조용한 실패) 근거 보강 (실측)**: 현행 프로덕션 coturn ConfigMap은 `lt-cred-mech`가 **주석 처리(`#lt-cred-mech`)** 된 채 정적 `user=`를 사용한다. 로컬 검증상 lt-cred-mech 꺼진 상태에서는 정적 user 인증 자체가 성립하지 않아 **relay 인증이 실패**한다. 이는 wiki의 "relay 조용한 실패" P0 가설과 정합 — 현재 브라우저는 relay 없이 srflx/host로만 연결되고 있을 가능성이 높다. 보안 개선(`use-auth-secret`)이 동시에 P0 relay 인증 정상화로도 이어진다.

### 2.3 개선 설계: coturn TURN REST API (`use-auth-secret`)

coturn 표준 ephemeral credential 방식. RFC 7635 스타일.

```
# coturn turnserver.conf / ConfigMap
use-auth-secret
static-auth-secret=<공유 시크릿 — K8s Secret 로 주입>
realm=chatforyou
# 정적 user= 라인 제거 (use-auth-secret 와 상호 배타 — 2.4 검증)
# lt-cred-mech 관련 라인 정리: use-auth-secret 사용 시 정적 user 인증 경로 불필요
```

**자격증명 생성 알고리즘 (백엔드가 수행)** — 소비자별 username 포맷 분리 (2.4 검증 결과 반영)
```
# 브라우저용 (단기 TTL, userId 식별 포함)
username   = "<expiryUnixTs>:<userId>"        # 예: "1718800000:user-abc"
credential = base64( HMAC-SHA1( static-auth-secret, username ) )
ttl        = 설정값 (예: 1~2시간) — 통화 길이 + 재연결 여유 고려

# Kurento용 (장기 만료 = now + 50년, timestamp-only — 콜론 없음으로 turnURL 파서 안전)
username   = "<longExpiryUnixTs>"             # +50년 ≈ 3.36e9 (2076년), userId 없음, ':' 없음
credential = base64( HMAC-SHA1( static-auth-secret, username ) )
# turnURL 임베드: turnURL=<longExpiryUnixTs>:<base64cred>@192.168.0.165:30479
```

> **⚠️ coturn 4.6.2 만료값 상한 (실측 — 2026-06-18)**: 만료 timestamp가 **2^32(4,294,967,295 = 2106년)를 초과하면 인증 실패**(401 "Cannot find credentials"). 100년(≈4.94e9)은 실패 확인, **50년(≈3.36e9)은 PASS**(REFRESH success + CHANNEL_BIND 403 peer = 인증 통과). → Kurento용 만료값은 **반드시 2^32 미만**. +50년으로 확정.

> **Kurento 재시작 동작**: Kurento는 `turnURL`을 ConfigMap에서 **기동 시 1회만 읽고** 백엔드에 재요청하는 경로가 없다 → 50년 정적값을 재시작마다 재사용한다. **자동 재발급 불필요.** `static-auth-secret` 로테이션 시에만 Kurento용 자격증명 수동 재생성 + ConfigMap 갱신 + Kurento 재시작이 필요하다(이때 통화 블랙아웃 동반 — P2 무중단 항목과 연계).

**백엔드 API 엔드포인트 (신규/대체)**
```
POST /chatforyou/api/turn/credential        # 또는 기존 /admin/turnconfig 재설계
Auth: 룸 입장 인증 토큰 검증 (현재 turnconfig의 인증 누락을 정상화)
Response (ChatForYouResponse<T>):
{
  "result": "SUCCESS",
  "data": {
    "urls": ["turn:210.220.67.85:3478?transport=udp", "turn:...?transport=tcp"],
    "username": "1718800000:user-abc",
    "credential": "<HMAC base64>",
    "ttl": 3600,
    "peerReconnectTimeoutMs": 300000
  }
}
```

**프론트 연동** (`kurento-service.js`)
- `initTurnServer()`가 신규 엔드포인트 호출 → `urls`(배열)·`username`·`credential` 수신.
- `iceServers` 구성을 배열 `urls` 지원하도록 갱신(현재 단일 `urls: turnUrl`).
- TTL 만료 대비: 재연결/재입장 시 자격증명 재발급(현 `initTurnServer` 재호출 경로 확인 필요).

### 2.4 Kurento 동시 소비자 문제 — **로컬 격리 실측으로 확정 (옵션 1)**

> coturn을 `use-auth-secret`로 전환하면 **정적 long-term credential(`chatforyou:chatforservice`)이 더 이상 동작하지 않는다.** coturn 자격증명 소비자는 브라우저뿐 아니라 **Kurento(`WebRtcEndpoint.conf.ini`의 `turnURL`)도 포함**된다(Kurento도 자신의 relay candidate를 위해 coturn에 인증). 따라서 두 소비자를 어떻게 인증시킬지 확정이 필요했고, 아래와 같이 **실측으로 결정**했다.

**검증 환경**: `coturn/coturn:4.6.2` throwaway 컨테이너, 로컬 격리. 스크립트 보존: `/tmp/coturn-verify/`.

**검증 결과 (확정 사실)**

| 구성 | 결과 |
|---|---|
| 정적 `user=`만 (use-auth-secret 없음) | `ALLOCATE processed, success` ✅ |
| 정적 `user=` + `use-auth-secret` 병행 | `check_stun_auth: Cannot find credentials of user <chatforyou>` → 401 ❌ |
| HMAC ephemeral (브라우저용, username=`만료ts:userId`) under use-auth-secret | 인증 통과 ✅ |
| Kurento 전용 long-expiry HMAC (만료 < 2^32) | `user <...>: ALLOCATE processed, success` ✅ |
| Kurento long-expiry 만료 100년(≈4.94e9, > 2^32) | 401 "Cannot find credentials" ❌ — coturn 4.6.2 상한 |
| Kurento long-expiry 만료 50년(≈3.36e9, < 2^32) | REFRESH success + CHANNEL_BIND 403 peer = 인증 통과 ✅ |

**결론**
- **옵션 2(병행) 폐기 — 검증으로 불가 확정**: coturn에서 `use-auth-secret`와 정적 `user=`(lt-cred-mech)는 **상호 배타적**. use-auth-secret을 켜면 정적 user 인증이 완전히 무효화된다.
- **옵션 1 확정**: Kurento도 백엔드가 생성한 **HMAC 자격증명을 사용**한다.
  - 브라우저용: 단기 TTL HMAC (username = `만료ts:userId`).
  - Kurento용: 장기 만료 **+50년**(≈3.36e9, 2076년) HMAC. ConfigMap `turnURL`은 정적이라 TTL 갱신 메커니즘이 없으므로 만료를 길게 둔다. **단 coturn 4.6.2 상한(2^32 = 2106년) 미만 필수** — 100년은 인증 실패하므로 50년으로 확정(2.3절 실측). Kurento는 기동 시 turnURL 1회만 읽으므로 자동 재발급 불필요, secret 로테이션 시에만 수동 재생성+재시작.
- **옵션 3(Kurento relay 미사용)은 P0(relay 도달성)와 충돌**하므로 채택 안 함.

**옵션 1 구현 주의 — Kurento `turnURL` 파서 (필독)**
- Kurento `WebRtcEndpoint.conf.ini`의 `turnURL=user:pass@host:port` 파서는 username 내부 콜론을 잘못 쪼갤 위험이 있다.
- 따라서 **Kurento용 username은 timestamp-only**(userId 없이 만료값만 → 콜론 없음)로 발급한다. → `turnURL` 파싱 안전(검증으로 동작 확인).
- 예: `turnURL=2097149265:7Luqay7jUl/PCKCxqaGMT7gFBzY=@192.168.0.165:30479`
- base64 credential에 `/` `+` `=`가 포함될 수 있으나 `@`·`:`는 없어 `user:pass@host` 분리에는 무해. 단 **02-design에서 실제 Kurento turnURL 파서 동작을 한 번 더 확인**할 것.

> 잘못 설계하면(옵션 2 시도 / Kurento username에 콜론 포함) Kurento relay가 조용히 실패하여 2.2의 P0가 재현된다.

---

## 3. 영향 컴포넌트 목록

### 3.1 백엔드 (`springboot-backend/src/main/`) — 백엔드 전문가 소유
- `controller/AdminController.java` — `turnconfig` 인증 누락 정상화 / 신규 자격증명 엔드포인트 (재설계 대상)
- 신규 `service/` (예: `TurnCredentialService`) — HMAC-SHA1 자격증명 생성 로직 (`@Service`, 생성자 주입). 브라우저용 단기 TTL(username=`만료ts:userId`)과 Kurento용 장기·timestamp-only(콜론 없음) 두 발급 경로. Kurento용은 부트스트랩 시 1회 생성 또는 별도 발급 흐름 — 02-design에서 확정.
- 신규 `controller/TurnController.java` 또는 AdminController 재배치 — 엔드포인트 소유권 결정 필요
- `config/KurentoConfig.java` — `kms.url` 내부 DNS 전환(D 묶음) 시 영향
- `resources/application.properties` — `turn.server.*` 재정의(secret 참조), `kms.url` 변경, `turn.credential.ttl` 등 신규 키
- 신규 `model/` DTO — 자격증명 응답 VO (`ChatForYouResponse<T>` wrapper 준수)

### 3.2 프론트 (`nodejs-frontend/`) — 프론트 전문가 소유
- `static/js/rtc/kurento-service.js`:
  - `initTurnServer()` (L1004) — 엔드포인트/응답 스키마 변경, `urls` 배열 지원, 인증 헤더 추가
  - `iceServers` 구성부 (L1586-1594, L1666-1674) — 배열 `urls`·세션 자격증명 반영
  - TTL 만료/재연결 시 재발급 흐름 (재연결 로직과 연계 — L3 신중 검토)
- **Electron 영향**: `window.__CONFIG__.API_BASE_URL` 사용 유지(하드코딩 금지). 자격증명 발급은 런타임 fetch라 Electron 빌드 자체엔 영향 적으나, 작업 후 `chatforyou-desktop`에서 `npm run sync` 빌드 무에러 확인 필수.

### 3.3 인프라 (K8s — 레포 외부 / 설계 가이드만)
- coturn StatefulSet/ConfigMap/Service: `hostNetwork`, relay port range, `use-auth-secret`+`static-auth-secret`(Secret), resources/probe/이미지 고정/로그 stdout
- Kurento Deployment/Service/ConfigMap: `replicas`, KMS Service ClusterIP 전환, `turnURL` 자격증명 방식, resources/probe
- 방화벽/보안그룹: relay UDP 포트 범위 개방 (인프라 승인 게이트)

### 3.4 테스트 (`springboot-backend/src/test/`) — QA 전문가 소유
- `TurnCredentialService` 단위(HMAC 생성/만료 username 포맷) — 1차는 백엔드 전문가 단위테스트
- 엔드포인트 HTTP 계층(@WebMvcTest): 인증 게이트(미인증 401), 응답 스키마, TTL 경계
- 경계/시나리오: 만료 직전·직후 자격증명, 재발급, Kurento 동시 소비자 옵션 검증 시나리오

### 3.5 Electron 처리 필요 여부
- **필요(확인 수준)**: 직접 코드 변경은 없으나 프론트 변경 후 `npm run sync` 빌드 검증 mandatory. SCSS 변경 없음 예상.

---

## 4. 컨벤션 기준 요약 (docs 기반)

**백엔드** (`docs/springboot_backend.md`)
- 생성자 주입(`@RequiredArgsConstructor` + final), `@Slf4j` 플레이스홀더 로깅
- `ChatForYouResponse<T>` wrapper, 전역 예외(`ChatForYouException` + `ErrorCode`)
- 인증은 WebSocket/Controller 레벨에서 — turnconfig 인증 누락은 **버그이자 컨벤션 위반** 정상화
- JavaDoc Tier 1(공개 API)·Tier 2(WebRTC/TURN/토큰 핵심 흐름) 필수, HTML 태그 금지, 자기완결 WHY
- TURN credential 등 비밀은 **로그/응답에 평문 노출 금지** (이미 kurento-service.js L105/1437이 credential 로깅 회피 중 — 백엔드도 동일 원칙)

**프론트** (`docs/nodejs_frontend.md`)
- jQuery 유지, `var` 금지, `ajaxUtil`/`fetchJson` 래퍼(현 `initTurnServer`는 `fetchJson` 사용 — 유지)
- `window.__CONFIG__.API_BASE_URL` 사용, `isElectron()` 분기, ES6 async/await·class는 유저 승인 후
- 주석 자기완결성: 사이클/설계 라벨 인용 금지, 비밀값 로깅 금지
- WebSocket 핸들러는 `wsMessageHandlers` 맵 (자격증명은 fetch 경로라 해당 적을 수 있음)

---

## 5. 기존 구현 가이드 존재 여부

| 경로 | 상태 |
|---|---|
| `springboot-backend/plan_docs/[turn_credential].md` | **없음** → 백엔드 전문가가 신규 작성 |
| `nodejs-frontend/plan_docs/kurento_peer_error_fallback.md` | 존재(인접 주제, 재사용 아님) |
| `nodejs-frontend/plan_docs/[turn_credential].md` | **없음** → 프론트 전문가가 신규 작성 |
| 루트 `plan_docs/01~06/coturn_kurento_*` | 본 사이클에서 신규 생성 (Independent PDCA) |

> Independent PDCA Cycle Rule: #134(무중단 배포)와 인접하나 별도 사이클로 진행. 기존 사이클 문서 재사용 금지.

---

## 6. 파일 소유권 배분

| 팀원 | 소유 범위 |
|---|---|
| **백엔드 전문가** | `springboot-backend/src/main/**` (테스트 제외) + `springboot-backend/plan_docs/turn_credential.md` |
| **프론트 전문가** | `nodejs-frontend/**` (chatforyou-desktop/src 직접수정 금지) + `nodejs-frontend/plan_docs/turn_credential.md` |
| **QA 전문가** | `springboot-backend/src/test/**` + 02-design 검증 시나리오 기반 테스트 |
| **팀 리더** | 루트 `plan_docs/00~06`, 인프라 YAML 설계 가이드(레포 외부 대상), 04-analyze, fix 라우팅 |
| **외부 전문가 + /codex** | 05-expert-review 2라운드(+L3 cross-model) |

> 동일 파일 중복 배분 없음. 인프라 YAML은 레포 외부라 코드 소유권과 분리.

---

## 7. CodeGraph / 영향 범위 메모

- 단순 config 노출 + 신규 API라 핵심 심볼 변경은 제한적. `turnconfig` 소비자는 프론트 `initTurnServer` 단일 경로(grep 확인).
- WebRTC 코어 게이트 해당: coturn relay 경로·Kurento 자격증명·KMS 접속 경로 변경은 **승인된 설계 외 자동 rework 금지**. 02-design 후 유저 승인 게이트 필수.

---

## 8. Document Mapping

- [x] 요구사항 및 데이터: `plan_docs/01-plan/coturn_kurento_improvement.md`
- [x] 인터페이스 및 시퀀스: `plan_docs/02-design/coturn_kurento_improvement-spec.md`
- [x] 구현 가이드: `plan_docs/03-implementation/coturn_kurento_improvement-guide.md`
- [x] 백엔드 구현 가이드: `springboot-backend/plan_docs/turn_credential.md`
- [x] 프론트 구현 가이드: `nodejs-frontend/plan_docs/turn_credential.md`
- [x] 갭 분석: `plan_docs/04-analyze/coturn_kurento_improvement.md`
- [x] 전문가 리뷰: `plan_docs/05-expert-review/coturn_kurento_improvement-review.md` (설계 라운드 — 코드 리뷰/codex는 구현 후)
- [x] 최종 보고서: `plan_docs/06-report/coturn_kurento_improvement.md`

---

## 9. 유저 승인 게이트 (구현 착수 전 필수 확인)

다음은 자동 진행 금지 — 유저 결정 필요:
1. ~~**C 묶음(Kurento `replicas` 구조)**~~ → **해결됨 (2026-06-18 유저 승인): `replicas:1` 확정.** 앱레벨 KMS 풀(수평 확장)은 추후 작업으로 등록(10절).
2. ~~**2.4 Kurento 동시 소비자 옵션(1/2/3)**: TURN 자격증명 방식 확정.~~ → **해결됨 (2026-06-18 로컬 격리 실측): 옵션 1 확정, 옵션 2 폐기.** 브라우저 단기 HMAC + Kurento 장기 timestamp-only HMAC.
3. **B 묶음 방화벽 변경**: relay UDP 포트 범위 개방 — 인프라 담당 승인. — **미결**
4. **본 사이클 구현 범위**: 전체(A~E) vs 보안(E) 우선 vs 인프라 하드닝(A) 우선 분리. — **미결**
5. **엔드포인트 설계**: 기존 `/admin/turnconfig` 재설계 vs 신규 `/turn/credential` 분리. — **미결**

---

## 10. 추후 작업 (Deferred — 본 사이클 범위 아님, 트래킹만)

본 사이클에서 구현하지 않으나, **추후 vault capture 단계에서 아래를 반영**한다:

1. **앱레벨 KMS 풀(수평 확장)** — `replicas:1`로 단일 KMS 모순은 제거했으나, 단일 KMS 부하 한계 도달 시 수평 확장이 필요하다. 방→KMS 인스턴스 매핑, `KurentoClient` 풀, 인스턴스 장애처리가 동반되는 큰 L3 작업.
   - (a) vault `BRAINSTORM - Kurento 배포 개선`(또는 관련 TECH 노트)에 "남은 작업: 앱레벨 KMS 풀 분리(방→KMS 매핑, KurentoClient 풀, 부하 한계 도달 시 L3)" 추가.
   - (b) `SPEC - ChatForYou 기능 개발 우선순위 로드맵`에 "KMS 풀(수평 확장) — 단일 KMS 부하 한계 시" 항목 추가.
   - 현재는 **등록(트래킹)만**, 실제 wiki 반영은 추후 vault capture 단계.

---

## 11. Vault Knowledge Capture
- [ ] 구현/설계 변경 확정 후 vault 갱신 (coturn/Kurento BRAINSTORM → TECH/SPEC 승격, `use-auth-secret` 결정 기록). 현 단계는 설계라 미착수.
- [ ] 10절 deferred 항목(KMS 풀) vault 반영 — 추후 capture 단계.
- [ ] **TECH 노트 후보 (실측 근거 확보됨)**: "coturn use-auth-secret vs 정적 user 상호배타 — 실측" — 다음 실측 사실 포함: ① `use-auth-secret`/정적 `user=` 상호배타, ② Kurento timestamp-only username(콜론 회피), ③ `lt-cred-mech` 주석처리 = relay 인증 실패, ④ **coturn 4.6.2 만료값 상한 = 2^32(2106년); 100년 실패, 50년 PASS**. 검증 스크립트 `/tmp/coturn-verify/`. 구현 확정 후 캡처.
