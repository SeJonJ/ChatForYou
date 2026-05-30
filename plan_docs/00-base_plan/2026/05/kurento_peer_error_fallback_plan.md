# [Base Plan] Kurento Peer 에러 Fallback 누락 수정

**Risk Level**: L3 (WebRTC/Signaling 변경 — AGENT_GUIDE.md §3, §4.2)
**Rule 8 적용**: 2-round 설계 리뷰 완료 (Round 1/2 결과 → `plan_docs/02-design/kurento_peer_error_fallback.md`)
**작업 분류**: WebRTC/Signaling 변경 (프론트엔드 + 백엔드 동반)
**관련 이슈**: #126
**작성일**: 2026-05-27 / **슬림화**: 2026-05-28

---

## 0. Prior Knowledge (Vault Scan)

| Type | Note | Key Takeaway |
|------|------|--------------|
| BUG | `[BUG] kurento-service.js Peer 에러 Fallback 누락` | Round 1/2 설계 리뷰 완료. P0 없음. APPROVED. 4개 호출처, Category A/B 분리, handlePeerSetupError 헬퍼 설계 확정 |
| BUG | `[BUG] ChatForYou 예외 전파 미흡 패턴 수정` | 원본 잔여 항목 출처. 백엔드 패턴 정리 완료. 본 항목은 WebRTC Rule 8 적용 대상으로 분리됨 |
| TECH | `[TECH] Kurento Room Session 객체 모델` | Participant.dispose() 내부 stream track stop 구현 확인 (participant.js:389-402) |
| TECH | `[TECH] WebRTC 시그널링과 WebRtcEndpoint` | KurentoHandler switch 패턴, 백엔드 라우팅 확인 |

**선행 조사 4건 결과 (모두 완료)**:
1. `Participant.dispose()` 내 stream track stop → 이미 안전하게 구현됨 (participant.js:389-402). 추가 wrap 불필요.
2. `Participant.localStream` 보관 흐름 → kurento-service.js:784 setLocalStream 호출 후 dispose() 자동 stop.
3. 백엔드 KurentoHandler 라우팅 패턴 → switch 문 + enum 추가 패턴 확인. 인증 컨텍스트 자동 확보.
4. `connectionFailed` 모달 dismissible 동작 → `dismissible: false` + `onConfirm: leaveRoom('error')` 재사용 가능.

---

## 1. Summary (Goal & Scope)

`kurento-service.js`의 WebRTC peer 생성·SDP 처리 콜백 4곳이 에러 발생 시 `console.error(error)`만 호출하고 종료한다.
결과적으로 사용자가 무력화된 상태로 방치되고, getUserMedia 스트림 트랙이 stop되지 않아 OS 레벨 디바이스 잠금 위험이 발생한다.

**목표**: 공통 헬퍼 `handlePeerSetupError(ctx)`를 신설하여 4개 호출처를 통일하고,
Category A(본인 peer 실패 → 강제 퇴장)와 Category B(상대 peer 실패 → Toast + 재연결 버튼)를 분리 처리한다.

**자동 재시도 정책**: 하이브리드 채택 (즉시 Toast + "다시 연결" 버튼 + 5분 후 새로고침 안내).
옵션 B(1회 자동 재시도)는 구현하지 않으며 "보류된 대안"으로 각 phase 파일에 기록한다.

**이번 작업 범위 제외**:
- Prometheus 메트릭 (`webrtc_peer_setup_failures_total`) — 추후 개선 사항으로 기록

---

## 2. Impact Analysis (Critical)

- **[Backend]**: `KurentoEvent.java` enum에 `PARTICIPANT_RECEIVE_FAILED` 추가, `ErrorCode.java`에 K007 추가, `KurentoHandler.java` switch case + 핸들러 메서드 + Rate Limit 가드 추가, `KurentoRTCMessage.java` 필드 추가 여부 백엔드 expert 결정
- **[Frontend]**: `kurento-service.js` — `handlePeerSetupError(ctx)` 헬퍼 신설, 4개 콜백 수정, iceCandidate 로깅 개선, Category B 재연결 버튼 placeholder UI. 필요 시 `participant.js` 수정.
- **[Desktop]**: `nodejs-frontend/` 변경 후 `chatforyou-desktop/` 에서 `npm run sync` 실행 필요. `chatforyou-desktop/src` 직접 수정 금지.

---

## 3. Before / After UX

| 상황 | Before | After |
|:---|:---|:---|
| 본인 peer 생성 실패 | 화면 멈춤. 카메라/마이크 OS 잠금 위험 | 즉시 모달(강제 퇴장). 스트림 track stop 보장 |
| 상대 peer 연결 실패 | 빈 슬롯 방치. 사용자 인지 불가 | Toast + 재연결 버튼 표시. 5분 후 새로고침 안내 |
| 여러 peer 연속 실패 | 동일하게 방치 | Rate Limit 적용 (10초당 3회). 백엔드 로그 기록 |

---

## 4. Technical Risks (High-level)

| 리스크 | 수준 | 완화 전략 |
|:---|:---:|:---|
| peer dispose 후 ICE candidate race | Medium | disposeParticipantEntry() 즉시 호출로 participant 제거 |
| leaveRoom 직후 비동기 에러 콜백 재진입 | Medium | `roomTeardownStarted` / `forcedSessionExitInProgress` 가드 |
| KurentoRTCMessage DTO 변경 시 기존 호환성 | Low | 기존 필드 재사용 또는 별도 DTO 분리 — 백엔드 expert 결정 |
| Rate Limit map 메모리 누수 | Low | LRU 또는 주기 TTL 정리 — 백엔드 expert 결정 |
| 옵션 B 자동 재시도 채택 시 peer 재생성 race | High | 하이브리드(수동 재연결) 채택으로 회피 |

---

## 5. Component plan_docs 책임 분배

| 파일 | 담당 | 내용 |
|:---|:---|:---|
| `springboot-backend/plan_docs/kurento_peer_error_fallback.md` | **chatforyou-backend-expert** | 코드 레벨 상세: handler 구현 흐름, Rate Limit 데이터 구조 선택 근거, DTO 결정, 인증 검증 위치, 단위 테스트 시나리오 |
| `nodejs-frontend/plan_docs/kurento_peer_error_fallback.md` | **chatforyou-frontend-expert** | 코드 레벨 상세: handlePeerSetupError 전체 구현, 4개 호출처 변경 diff, DOM 구조 + CSS, setTimeout cleanup, 메모리 누수 방지 |
| 루트 00-06 phase 파일 | **chatforyou-lead** | 기능 단위 PDCA 표준 산출물 (cross-component contract) |

상세 설계 내용(함수 시그니처, API 스키마, 호출처별 인자 매핑 등)은 `plan_docs/01-plan/kurento_peer_error_fallback.md` 를 참조한다.

---

## 6. 보류된 대안

**옵션 B (1회 자동 재시도)**: 유저 제안이었으나 아래 리스크로 보류.
- peer dispose 직후 재호출 시 백엔드 WebRtcEndpoint 정리와 race
- 구조적 오류 시 N명 × 1회 자동 재시도 storm
- 에러 분류 부재로 "재시도 가치 있는 오류" 판단 불가
- 백엔드 멱등성 검증 완료 후 별도 태스크로 검토 가능

---

## 7. 추후 개선 사항

- **Prometheus 메트릭**: `webrtc_peer_setup_failures_total{role, phase}` Counter — 이번 범위 제외
- **자동 재시도 옵션 B**: 백엔드 WebRtcEndpoint 멱등성 검증 후 별도 태스크
- **TURN 서버 진단 도구**: 재연결 실패 시 TURN 연결 상태 진단 기능

---

## 8. Document Mapping (Checklist)

| Phase | 파일 | 상태 |
|:---:|:---|:---|
| 00 | `plan_docs/00-base_plan/2026/05/kurento_peer_error_fallback_plan.md` | 완료 (슬림화됨) |
| 01 | `plan_docs/01-plan/kurento_peer_error_fallback.md` | 완료 |
| 02 | `plan_docs/02-design/kurento_peer_error_fallback.md` | 완료 (Round 1/2 포함) |
| 03 | `plan_docs/03-implementation/kurento_peer_error_fallback.md` | 완료 |
| 04 | `plan_docs/04-analyze/kurento_peer_error_fallback.md` | 예정 (chatforyou-external-expert) |
| 05 | `plan_docs/05-expert-review/kurento_peer_error_fallback.md` | 예정 (chatforyou-external-expert) |
| 06 | `plan_docs/06-report/kurento_peer_error_fallback.md` | 예정 (작업 완료 후) |
| Backend 컴포넌트 | `springboot-backend/plan_docs/kurento_peer_error_fallback.md` | 예정 (chatforyou-backend-expert) |
| Frontend 컴포넌트 | `nodejs-frontend/plan_docs/kurento_peer_error_fallback.md` | 예정 (chatforyou-frontend-expert) |
| Vault | `wiki/[BUG] kurento-service.js Peer 에러 Fallback 누락.md` | planned → done 예정 |

- [ ] vault knowledge capture 완료 (Phase H에서 수행)
