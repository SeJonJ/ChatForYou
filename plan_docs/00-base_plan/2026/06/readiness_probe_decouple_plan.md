# [Base Plan] Readiness Probe ↔ 쿠키수집 분리 (안전한 readinessProbe 재도입)

## 0. Prior Knowledge (Vault Scan)

| Type | Note | Key Takeaway |
|------|------|--------------|
| POSTMORTEM | POSTMORTEM - 2026-06-15 무중단배포 readinessProbe 쿠키수집 교착 | readinessProbe가 `cookieCollected`에 게이트되어 전체 장애. 즉시 복구는 probe 제거. 영구 수정은 readiness를 쿠키 수집에서 분리. |
| SPEC | SPEC - ChatForYou 채팅방 무중단 배포 복구 설계 | readiness 503 drain은 무중단 복구의 보조 최적화. probe 없이도 preStop+endpoint 제거+Kafka SERVER_STOPPED로 복구 핵심은 동작. |
| TECH | TECH - ChatForYou K8s Deployment 매니페스트 | 현재 deployment에 readinessProbe 없음(제거 상태), preStop+grace 60만 적용. readiness 재추가는 코드 수정 후에만. |
| TECH | TECH - ChatForYou 채팅방 라우팅 아키텍처 | cookie 수집은 nginx ingress 왕복(자기 자신 라우팅)으로 sticky cookie 값을 학습 → Ready endpoint 전제. |

## 1. Summary (Goal & Scope)

`HealthController.readiness()`가 `cookieCheckEvent.isCookieCollected()`에 게이트되어, readinessProbe 도입 시 "Ready여야 쿠키 수집 가능 / 쿠키 수집돼야 Ready" 순환 교착으로 전체 장애가 발생했다(2026-06-15 POSTMORTEM).

목표는 **readiness 판정을 쿠키 수집에서 분리**해, readinessProbe를 다시 도입해도 부트스트랩 교착이 발생하지 않게 만드는 것이다.

목표(이번 사이클):
- readiness가 쿠키 수집 완료 여부에 의존하지 않는다.
- readiness는 "종료 중(drain)이면 503, 그 외 앱 기동 완료면 READY"만 반영한다.
- 전체 동시 재기동(모든 Pod 동시 NotReady) 상황에서도 교착이 생기지 않음을 설계로 보장한다.

명시적 제외 범위:
- live deployment에 readinessProbe를 실제로 다시 추가하는 작업(probe spec/initialDelay 등). 코드 수정 + L3 two-round review + 검증 이후 별도 배포 단계에서 수행.
- 쿠키 수집(`CookieCheckEvent`) 로직 자체 변경.
- livenessProbe 신설(교착과 무관한 별도 안정성 개선 — 필요 시 후속).
- 무중단 복구 TTL/프론트 재연결 윈도우(이미 설정화 완료).

## 2. Impact Analysis (Critical)

- [Backend]: `HealthController.readiness()` 판정 로직 변경. "앱 기동 완료" 신호를 위해 `InstanceProvider` 또는 신규 ready 플래그(ApplicationReadyEvent 기반, 순환 비의존)가 필요할 수 있다. `CookieCheckEvent.isCookieCollected()`는 진단/내부 용도로 유지하되 readiness 게이트에서는 제거한다.
- [Frontend]: 영향 없음.
- [Desktop]: 영향 없음.
- [Infra/Deploy]: 코드 수정 자체는 무영향. 이후 readinessProbe 재도입 시 deployment(또는 patch)에서 probe spec을 추가하는 별도 작업이 필요하며, 그 전제 조건이 이번 코드 수정이다.

## 3. Technology & Risks

Risk Level: L3

Reason:
- health/readiness 엔드포인트는 K8s 트래픽 라우팅(endpoint 편입/제외)을 직접 좌우하는 차단기이며, 라우팅 부트스트랩(쿠키 수집)·무중단 복구 drain과 얽혀 있다. 한 줄 변경이라도 blast radius가 크다.
- AGENT_GUIDE §3.0 독립 PDCA 규칙에 따라 `chatroom_zero_downtime_recovery` 사이클을 재사용하지 않고 신규 cycle로 작성한다.
- 구현 전 `docs/agent/webrtc-review-protocol.md` 두 라운드 리뷰가 Phase 02에 문서화되어야 한다.

Current-code findings:
- `HealthController.readiness()`는 `isShuttingDown()`이면 503, 그 외 `isCookieCollected()`면 READY, 아니면 503("Cookie not yet collected")을 반환한다.
- `CookieCheckEvent.collectOwnCookie()`는 nginx ingress 왕복(`/health/cookie`)으로 자기 sticky cookie를 학습하며, 응답 instanceId가 자기 자신일 때만 성공 → Ready endpoint 전제.
- `RoutingBootstrapCoordinator.bootstrapRoutingLifecycle()`는 `WebServerInitializedEvent`(Tomcat 기동 후) `@Async`로 실행되어 cluster announce → `collectOwnCookie()` 순서로 진행한다.
- `cookieCollected`는 수집 성공 시 true, Phase 3 fallback 실패 시 false 후 `scheduleApplicationShutdown()`으로 자가 재시작 → readiness 게이트와 결합 시 크래시 루프.
- 현재 deployment에는 readinessProbe가 없다(제거 상태). 따라서 `/readiness`는 현재 호출되지 않는 사실상 죽은 코드.

Primary risks:
- P0: readiness를 "앱 기동 완료" 신호로 게이트할 때, 그 신호가 ingress 라우팅에 의존하면(쿠키 수집처럼) 교착이 재발한다. ready 신호는 반드시 ingress 비의존(예: ApplicationReadyEvent/컨텍스트 기동)이어야 한다.
- P1: readiness에서 쿠키 게이트를 제거하면 Pod가 "쿠키 미확보 상태"에서도 트래픽을 받는다. 단 이는 probe 도입 이전의 기존 동작과 동일하며(과거에도 즉시 트래픽 수신), 쿠키 수집은 Ready endpoint가 되어야 오히려 성공하므로 정합적이다. 회귀 검증 필요.
- P1: drain(`isShuttingDown` → 503) 동작은 보존되어야 한다. 분리 과정에서 drain 경로가 깨지면 무중단 복구의 traffic stop이 사라진다.
- P2: `/readiness`의 응답 본문/상태코드 계약 변경이 모니터링/대시보드에 영향을 줄 수 있다(현재는 probe만 소비).

## 4. Final Conclusion & UX Guide

- 사용자 직접 UX 변화는 없다(내부 인프라/probe 동작 개선).
- 운영 관점: 이번 수정 후 readinessProbe를 재도입하면 "정상 Pod에만 트래픽, 종료 중 Pod drain"이 의도대로 동작한다. 재도입 자체는 별도 배포 단계.
- 엔지니어링 결론:
  - readiness = `isShuttingDown ? 503 : (앱 기동 완료 ? READY : 503)`. "앱 기동 완료"는 ingress 비의존 신호(권장: ApplicationReadyEvent로 set되는 플래그)로 판정한다.
  - 쿠키 수집은 readiness 게이트에서 분리하되, 진단 목적의 별도 노출은 유지 가능(예: `/health/cookie` 또는 로그).
  - L3 구현은 Phase 01/02와 two-round review 완료 후에만 시작한다.

## 5. Document Mapping (Checklist)

- [x] Phase 00 Base Plan: `plan_docs/00-base_plan/2026/06/readiness_probe_decouple_plan.md`
- [x] Phase 01 Plan: `plan_docs/01-plan/readiness_probe_decouple.md`
- [ ] Phase 02 Design + WebRTC two-round review — 이번 요청 범위 밖(01까지)
- [ ] Phase 03 Implementation — review APPROVED 후
- [ ] Phase 04 Analyze / QA
- [ ] Phase 05 Expert Review
- [ ] Phase 06 Report
- [ ] (별도) readinessProbe 재도입 배포 — 코드 수정·검증 후

> 이번 작업 지시: Phase 00 → 01 까지만 진행. 02 이후는 사용자 재지시 시 진행.
