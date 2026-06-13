# [Base Plan] 녹화 다운로드 참가자 권한 검증 (email-keyed admitted ledger)

> Plan stem: `recording_participant_auth`
> Risk Level: **L2** (Redis 구조 추가 + Auth/authorization 로직 — WebRTC/WebSocket/Signaling/Kurento 코어 무변경)
> 단, 유저가 본 사이클을 **L3 독립 PDCA(Phase 00~06)** 로 명시 지시 → Phase 00~06 전 구간 + STEP 5 external/cross-model 검토 루프를 L3 기준으로 수행한다. (리스크 등급 판정과 워크플로 적용 범위 구분 — 아래 §3.6 참조)
> Independent PDCA cycle (AGENT_GUIDE §3.0) — `recording_download_token_refresh`(feat/126, APPROVED) 사이클에서 DEFERRED 된 PRE-EXISTING 보안 항목을 별도 독립 사이클로 보완. 동일 파일(`FileController`/`RecordingFileService`) 인접이나 사이클 재사용 금지.

## 0. Prior Knowledge (Vault Scan)

| Type | Note | Key Takeaway |
|------|------|--------------|
| BRAINSTORM | BRAINSTORM - 녹화 다운로드 참가자 권한 검증 | 본 사이클의 설계 분석 원본. 후보 A(email ledger, 제거 없음) 권장 / 후보 B(roomUUID, WS 신원 변경 → L3 2-round) 차선 / nickname 키잉 기각(전역 비유일). 다운로드 신원=Firebase email, WS 참가자 키=nickName(disjoint), email hook=`ChatRoomController.joinRoom`. |
| SPEC | — | 해당 없음 |
| TECH | TECH - ChatForYou Redis 키 구조 | 모든 키는 `RedisKeyPrefix` 상수 경유 원칙. `room:members:` 신규 프리픽스 추가 위치. 방 삭제 시 키 정리 규칙. |
| TECH | TECH - ChatForYou 채팅방 라우팅 아키텍처 | 공개/비밀방 입장 + room token 발급 경로. `joinRoom` 의 redirect 분기 존재. |
| BUG | BUG - 토큰 refresh 마이그레이션 누락 (file 경로) | 직전 사이클. expiresAt 차단 제거가 본 보안 창을 명시적으로 확대시켰음. |
| POSTMORTEM | — | 해당 없음 |

### Vault Scan 결론
- 직전 사이클 05-expert-review 에서 Codex P1-A("`/file/download` 방 참가자 권한 미검증")가 PRE-EXISTING·Out of Scope 로 기각되며 별도 사이클로 deferred 됨. 동시에 expiresAt 차단 제거로 "방 생존 중(최대 30분 배치 잔존 포함) 다운로드 허용" 창이 명시적으로 넓어졌고 "허용된 트레이드오프"로 문서화됨.
- 본 사이클은 그 deferred 항목을 닫는다: **로그인했으나 방에 입장한 적 없는 임의 사용자가 roomId/fileName/filePath 만으로 타인 방 녹화를 받는 것을 차단**한다.
- BRAINSTORM 노트가 코드 근거와 함께 후보 A를 권장하며, 코드 재검증 결과 후보 A가 WebRTC 코어 무변경으로 보안 목표를 달성함을 확인.

## 1. Summary (Goal & Scope)

### 문제 (코드 확정)
`POST /chatforyou/api/file/download` 의 `bucket=RECORDING` 경로는 다음만 검증한다:
1. Firebase ID 토큰 유효성 (`TokenUtils.checkGoogleOAuthToken`)
2. 로그인 Redis 존재 (`UserService.getValidatedOauthUser(email)`)
3. 방(`KurentoRoom`) Redis 생존 + 녹화 메타 존재 (`RecordingFileService.getObject` L110-118)

→ **요청자가 해당 방의 참가자였는지(authorization)는 어디서도 검증하지 않는다.** 로그인한 임의 사용자가 `roomId`/`fileName`/`filePath` 를 알면 미참여 방의 녹화를 받을 수 있다(IDOR 성격).

### 근본 원인 (코드 확정)
- `expiresAt` 은 "다운로드 유효 시간 제한(60분)" 정책이었지 "참가자 권한 증명"이 아니었음 → 기존 코드도 비참가자를 막지 못함 (PRE-EXISTING).
- 직전 사이클이 expiresAt 차단을 완화 + `RoomBatchJob`(cron `0 0,30`, 최대 30분 잔존) 결합 → 노출 표면 확대.
- 다운로드 신원은 Firebase **email** 인데, 어떤 멤버십 기록도 email 로 키잉되어 있지 않음.

### 확정 범위 (설계 후보 A — email-keyed admitted ledger, 제거 없음)
- **멤버십 기록 hook**: `ChatRoomController.joinRoom` 의 **실제 입장 성공 분기에서만** `SADD room:members:{roomId} {email}` (redirect/early-return 분기 제외 — §2 참조).
- **다운로드 검증**: `FileController.download` 의 `bucket=RECORDING` 경로에서 `SISMEMBER room:members:{roomId} {email}` → 아니면 `ACCESS_DENIED(A001, 403)` 차단. `bucket=FILE` 경로는 무영향.
- **정리(cleanup)**: 방 영구 삭제(`ChatRoomService.delChatRoom(KurentoRoom)`)에서 명시적 `DEL room:members:{roomId}`. (배치 `deleteAllChatRoomData` 의 `*{roomId}*` SCAN 이 동일 키를 자동 포착하지만, 키 누수 클래스 방지를 위해 명시 삭제를 1차로 둔다 — §3.4 참조.)
- **공개방·비밀방 공통** 적용 (ledger 가 공개방까지 커버 — room token 은 비밀방 전용이라 단독으로는 불충분).

### 핵심 설계 결정: 검증 위치 (Controller vs Service)
- authorization 검증은 **`FileController.download` 컨트롤러 레이어 또는 전용 서비스 메서드**에 둔다. `RecordingFileService.getObject` 는 email 신원을 모르므로(현재 `roomId/fileName/fileDir` 만 받음), email 을 인자로 전달하거나 컨트롤러에서 선검증하는 방식 중 하나를 backend-expert component plan 에서 확정한다. 02-design 에서 두 옵션의 계약·시퀀스를 제시한다.

### Out of Scope (의도적 제외 — 사유 명시, 유저 확인 필요)
| 제외 항목 | 사유 |
|---|---|
| 후보 B (roomUUID, WS senderId 를 nickName→uuid 전환) | WebRTC 시그널링 참가자 신원 변경 = `webrtc-review-protocol.md` 2-round review 필수 + leaveRoom 루프 민감. "떠난 참가자 즉시 차단"이 명시적 제품 요구가 아닌 한 비용·위험 과다. 본 사이클은 "입장 이력" 정확도로 충분. |
| "현재 접속 중" 정밀 멤버십 (떠난 참가자 즉시 차단) | 후보 A 는 입장 이력 ledger → 떠난 참가자도 방 삭제 전까지 다운로드 가능. 단 **정당 참가자였으므로 보안 위협 아님**. 즉시 차단이 필요하면 별도 사이클(후보 B). |
| `bucket=FILE`(일반 채팅 파일) 경로에 참가자 검증 추가 | 본 사이클은 녹화 경로 한정. 일반 파일 IDOR 도 동일 구조 결함이나 범위 확대 방지. (유저 확인: 녹화만 vs 둘 다?) |
| `RecordingFile.expiresAt` / `recording.url-expire.minutes` 잔여 정리 | 직전 사이클 S-3 으로 등록된 별도 cleanup. 본 사이클 무관. |
| WebSocket `/signal` 토큰화 / WS 레이어 email 도입 | 무변경. 후보 A 는 WS 무변경. |

## 2. Impact Analysis (Critical)

### [Backend] — 변경 있음
| 컴포넌트 | 파일 | 변경 |
|---|---|---|
| 멤버십 기록 | `webChat/controller/ChatRoomController.java` `joinRoom` (L96-143) | 실제 RTC 입장 성공 분기(L139-142, `ChatType.RTC` + 비redirect)에서 ledger SADD. **redirect 3분기(L74 REDIRECT / L118-122 비정상 인스턴스 REDIRECT_DASHBOARD / L125-135 인스턴스 불일치 REDIRECT_ROOM)·MSG 타입(L137-138)에서는 기록 금지.** 입장 hook 의 정확한 시점·메서드 위임은 02-design + backend component plan 에서 확정. |
| ledger 추상화 | `webChat/service/redis/RedisService.java` + `impl/RedisServiceImpl.java` | Set 연산(`addRoomMember`/`isRoomMember`/`deleteRoomMembers`) 추가. 기존 `RedisKeyPrefix` 에 `ROOM_MEMBERS_PREFIX("room:members:")` 추가. |
| 다운로드 검증 | `webChat/controller/FileController.java` `download` (L68-124) | `bucket=RECORDING` 분기에서 `SISMEMBER` 검증 → 실패 시 `ACCESS_DENIED`. `bucket=FILE` 무영향. DownloadLog 흐름 유지(실패 시 FAIL 로그 정합성 검토). |
| (옵션) 서비스 위임 | `webChat/service/file/impl/RecordingFileService.java` `getObject` | email 인자 추가 검증 방식을 택할 경우 시그니처 변경. 컨트롤러 선검증 방식이면 무변경. 02-design 에서 택1. |
| 정리 | `webChat/service/chatroom/ChatRoomService.java` `delChatRoom(KurentoRoom)` (L183-200) | 방 영구 삭제 시 `DEL room:members:{roomId}` 추가. |

- CodeGraph 영향: `getObject` impact → `FileController.download` 단일 호출처 + 테스트 2종(`FileControllerExceptionTest`, `RecordingFileServiceTest`). `joinRoom` callers → HTTP route(`ChatRoomController`) + WS(`KurentoHandler`)·`KurentoRoomManager` (서로 다른 메서드 — HTTP joinRoom 만 본 사이클 대상, WS joinRoom 무변경 확인).
- `/file/download` 계약: 정상 응답·파라미터 유지. **신규 실패 응답 A001(403) 추가** → FE 영향 검토 필요(§Frontend).

### [Frontend] — 변경 가능성 낮음, 확인 필요
- 정상 시나리오(참가자 본인 다운로드)는 UX 무변경. 신규 차단은 "비참가자"에게만 발생.
- 단, FE 의 다운로드 에러 핸들러(`ajaxUtil.js` `fileDownloadAjax` blob 정규화 → `handleApiError`)가 **A001(403)** 코드를 적절한 toast 로 처리하는지 확인 필요. 신규 toast 문구 필요 여부는 frontend-expert 가 판단(없으면 FE 무변경, 사유 기록).
- 신규 API·DTO·WebSocket 이벤트 없음 → 대규모 FE 변경 없음 예상.

### [Desktop]
- `chatforyou-desktop/src` 직접 수정 금지. FE 무변경이면 sync 불요(사유 기록). FE 가 에러 문구만 추가하면 `npm run sync` 검증.
- Electron IPC 다운로드 경로 계약(성공=Blob) 무변경.

## 3. Technology & Risks

### 3.1 아키텍처 제약 (코드 재검증 완료)
| 사실 | 근거 (코드) | 함의 |
|---|---|---|
| 다운로드 신원 = Firebase email | `FileController.download` L82 `oauthRedis.getEmail()` | 권한 검사 키 = email |
| WS 참가자 키 = nickName (email 없음) | `KurentoHandler.joinRoom` L303 `userId = message.getSenderId()`(=client nickName). WS 레이어에 email 부재 | WS hook 으로 ledger 기록 불가 → HTTP hook 필요 |
| email hook = HTTP `joinRoom` | `ChatRoomController.joinRoom` L107 `oauthRedis.getEmail()`. 공개+비밀 공통 | ledger SADD 의 유일한 email 보유 지점 |
| HTTP joinRoom 에 redirect 3분기 존재 | L74/L118-122/L125-135 | redirect 분기는 "이 인스턴스 입장 아님" → SADD 제외 필수 |
| 방 데이터 = Hash `roomId:{roomId}` | `RedisServiceImpl.saveChatRoom` L329 | ledger 는 별도 Set 키 권장(Hash 필드 직렬화 회피) |
| 방 삭제 = `deleteAllChatRoomData` SCAN `*{roomId}*` | `RedisServiceImpl.deleteAllChatRoomData` L292-318 | `room:members:{roomId}` 가 자동 포착됨(safety net) |

### 3.2 리스크 표
| 리스크 | 영향 | 완화 |
|---|---|---|
| joinRoom SADD 시점 오류 (redirect 분기에 기록) | 입장 안 한 사용자가 ledger 등록 → 차단 우회 | redirect/MSG 분기 명시 제외. 02-design 시퀀스에 분기별 SADD 여부 표기. QA 가 redirect 케이스 미기록 검증. |
| email 정규화 불일치 (대소문자 등) | SADD email 과 download email 이 달라 정당 참가자 차단(false negative) | 양쪽 모두 동일 출처(`oauthRedis.getEmail()`) 사용 → 정규화 일관. QA 가 동일 email 왕복 검증. |
| ledger 키 누수 (방 삭제 시 미정리) | Redis 키 누적 | 명시 `DEL` + SCAN 자동 포착 2중. 키에 roomId 포함 보장. |
| 기존 진행 중 방(이미 입장했으나 ledger 없는 사용자) | 배포 직후 기존 참가자가 차단됨 | 배포 시점 기존 방의 멤버십은 비어있음 → 마이그레이션/완화 정책 필요. §3.5 참조 — **유저 확인 필수**. |
| 다운로드 실패 응답 코드 추가(A001) FE 미처리 | 비참가자에게 의도 불명 toast | frontend-expert 가 `handleApiError` A001 처리 확인. |
| WebRTC 코어 영향 | 없음 | WebSocket/Signaling/Kurento/DataChannel/미디어 파이프라인/room lifecycle state machine 무변경 → `webrtc-review-protocol.md` 2-round review **미트리거**(§3.6). |

### 3.3 동시성
- Redis Set SADD/SISMEMBER 는 원자적. 동시 입장·동시 다운로드 경쟁 없음. nickname 기반 replace 충돌 같은 문제 없음(email 키).

### 3.4 cleanup 이중화 근거
- 1차: `delChatRoom(KurentoRoom)` 명시 `DEL room:members:{roomId}` — 의도 명확·즉시.
- 2차(safety net): 배치 `deleteAllChatRoomData(roomId)` 의 `*{roomId}*` SCAN 이 `room:members:{roomId}` 를 포착. 단 이는 부수효과이므로 설계상 1차에 의존하고 2차는 방어로만 기술. (주의: SCAN 패턴 `*{roomId}*` 가 의도치 않은 키까지 지우지 않는지는 기존 동작이므로 본 사이클이 새 위험 도입 안 함.)

### 3.5 배포 마이그레이션 리스크 (유저 확인 필요)
- ledger 도입 시점에 **이미 입장해 있던 기존 방 참가자**는 ledger 에 없음 → 배포 직후 정당 참가자가 다운로드 차단될 수 있음.
- 완화 후보: (a) 기존 방은 자연 소멸(최대 30분 배치)까지 짧은 호환 창 허용, (b) 다운로드 검증을 "ledger 비어있으면(=구방) 통과" feature-flag 로 점진 적용, (c) 즉시 적용(짧은 불편 수용).
- **이 결정은 보안 vs 가용성 트레이드오프 → 유저 승인 필요.** 01-plan 확정 전 합의.

### 3.6 리스크 등급 vs 워크플로 적용 (명시)
- 순수 변경 분류: Redis 키/구조 추가 + authorization 로직 = **L2** (risk-classification.md: WebRTC/WebSocket 무관 backend state/auth). `webrtc-review-protocol.md` 2-round review 는 **미트리거**(코어 무변경).
- 단 유저가 **L3 독립 PDCA(Phase 00~06)** 를 명시 지시 → Phase 00~06 전 구간 작성 + STEP 5 external/cross-model 검토 루프(최대 3 iteration)를 L3 기준으로 수행한다.
- 즉 "2-round WebRTC review" 는 불요(코어 무변경), "Phase 06 + STEP 5 review 루프" 는 유저 지시에 따라 수행.

## 4. Final Conclusion & UX Guide
- 후보 A(email-keyed admitted ledger)로 비참가자 차단을 WebRTC 무변경으로 달성. "현재 접속" 정밀도는 포기하되 보안 위협 아님(정당 참가자만 잔존 다운로드).
- UX: 정상 참가자는 변화 없음. 비참가자는 A001(403) 차단. 신규 toast 문구는 FE 가 필요 시만 추가.
- 핵심 미해결 결정(유저 확인): (1) Out of Scope 의 `bucket=FILE` 포함 여부, (2) §3.5 배포 마이그레이션 정책, (3) 검증 위치(Controller 선검증 vs Service 인자 전달).

## 5. Document Mapping (Checklist)
- [x] `plan_docs/00-base_plan/2026/06/recording_participant_auth_plan.md` — lead (본 문서)
- [x] `plan_docs/01-plan/recording_participant_auth.md` — lead
- [x] `plan_docs/02-design/recording_participant_auth.md` — lead
- [x] `springboot-backend/plan_docs/recording_participant_auth_plan.md` — backend-expert (STEP 2)
- [x] `nodejs-frontend/plan_docs/recording_participant_auth_plan.md` — frontend-expert → **FE 무변경으로 미생성**(사유: A001이 ajaxUtil.js handleApiError 기존 toast로 처리, 03-implementation §2.3 기록)
- [x] `plan_docs/03-implementation/recording_participant_auth.md` — lead (STEP 3)
- [x] `plan_docs/04-analyze/recording_participant_auth.md` — lead + qa(보조) (STEP 4)
- [x] `plan_docs/05-expert-review/recording_participant_auth.md` — external-expert(+codex) (STEP 5) → **APPROVED ★★★★☆**
- [x] `plan_docs/06-report/recording_participant_auth.md` — lead (STEP 6, 05 APPROVED)
- [x] vault knowledge capture 완료 (BRAINSTORM status deferred→implemented + TECH-Redis ledger 추가, index/log 갱신)
