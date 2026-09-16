---
id: "chatforyou-dual-implementation-doc-gate"
kind: hook
runtime_bindings:
  claude: { event: PreToolUse, matcher: "Write|Edit|MultiEdit", timeout: 10 }
  codex: { event: PreToolUse, matcher: "apply_patch", timeout: 10 }
---
## intent

Phase 04 진입 전에 ChatForYou 컴포넌트(backend/frontend)별 구현 문서 계약을 결정론적으로 검사한다.
영향받는 컴포넌트는 같은 cycle-stem 의 구현 문서가 존재하고 완료돼야 하며, 영향이 없는 컴포넌트는
같은 stem 의 00 base-plan 에 비어 있지 않은 N/A 사유를 남겨야 한다.

## canonical
# form=core_adapter: scripts/sage_harness/hooks/chatforyou_dual_implementation_doc_gate_core.py (pure decide)

## enforcement

- 트리거: `plan_docs/04-analyze/<stem>.md` 변경. 그 외 경로는 skip.
- 경로 비교는 case 를 구분하지 않는 정규화 키로 한다. case 를 구분하지 않는 볼륨에서는
  `PLAN_DOCS/04-ANALYZE/…` 가 같은 실제 경로인데 문자열 그대로 비교하면 관할 밖으로 읽힌다.
  구분하는 볼륨에서는 반대로 별개 경로를 관할로 끌어들이지만 그쪽은 차단 방향이라 안전하다.
- Phase 04 markdown 인데 파일명에서 stem 을 읽을 수 없으면 차단한다(`invalid_phase4_stem`).
  "Phase 04 를 안 건드림" 과 묶어 skip 하면 파일명만으로 게이트를 지나간다.
- 판정 대상 문서(00 · 컴포넌트 구현 문서 · legacy allowlist)를 Phase 04 와 **같은 이벤트에서**
  변경하면 차단한다(`mixed_change_scope`). snapshot 은 쓰기 전 상태라 그 조합은 판정할 수 없다.
- rollout 스냅샷 `sage/chatforyou-legacy-cycles.txt` 에 있는 stem 은 소급 검사하지 않는다(skip).
  파일이 없으면 전면 skip 이 아니라 전면 검사다 — 파일 삭제가 곧 게이트 해제가 되면 안 된다.
- 스냅샷은 얼어 있다. 면제를 적용하려는 순간 파싱된 stem 집합의 digest 를 core 에 고정된 값과
  대조하고 다르면 차단한다(`legacy_allowlist_changed`). 고정하지 않으면 목록에 stem 을 먼저
  추가하고(그 변경 자체는 Phase 04 가 아니라 이 게이트를 지나간다) 다음 변경에서 그 Phase 04 를
  쓰는 두-단계 우회가 성립한다. 대조를 목록 읽는 시점이 아니라 면제 적용 시점에 두는 이유는,
  읽는 시점에 두면 위의 "파일 없음 = 전면 검사" 가 "전면 차단" 으로 뒤집히기 때문이다.
- 신규 stem 은 `plan_docs/00-base_plan/` 아래 임의 깊이(`<stem>.md` 또는
  `<YYYY>/<MM>/<stem>.md` 등 월 폴더 포함)에 문서가 정확히 하나 있어야 한다. 0건이면
  `missing_base_plan`, 동일 stem 이 두 곳 이상이면 `ambiguous_base_plan` 으로 차단한다.
- 00 은 `ChatForYou-Component-Doc-Gate: v1` 과 `Component-Backend:` · `Component-Frontend:` 를
  각각 정확히 한 번 선언해야 한다. 선언은 fenced code block 밖에서만 인정한다(CommonMark 규칙:
  닫는 fence 는 info string 을 못 갖고, backtick fence 의 info string 은 backtick 을 못 가진다).
- 그 둘 외의 `Component-*` 선언이 있으면 차단한다(`unknown_component_declaration`).
  무시하면 작성자는 선언했다고 믿는데 아무것도 검사되지 않는다.
- 상태는 `REQUIRED` 또는 `N/A: <사유>` 만 허용한다. N/A 사유는 NFKC 정규화 후 가시 문자가
  하나라도 남아야 한다 — zero-width 나 방향 제어 문자만 적힌 사유는 `strip()` 을 통과하므로
  공백 검사만으로는 "사유를 적었다" 가 성립해 버린다.
- `REQUIRED` 컴포넌트는 대응 구현 문서가 존재해야 하고, 미완료 체크박스(`- [ ]`)가 하나라도 있으면
  컴포넌트·파일·줄번호 증거와 함께 차단한다.
- root 03 문서 검사는 CORE `pre-phase4-checklist-gate` 가 계속 소유한다 — 여기서 중복하지 않는다.

## tests
scripts/sage_harness/hooks/tests/test_chatforyou_dual_implementation_doc_gate.py
