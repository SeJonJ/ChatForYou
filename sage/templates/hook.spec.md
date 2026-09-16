---
id: ""            # 예: pre-phase4-checklist-gate
kind: hook
runtime_bindings:
  claude: { event: PreToolUse, matcher: "Write|Edit|MultiEdit", timeout: 10 }
  codex: { event: PreToolUse, matcher: "apply_patch", timeout: 10 }
---
## intent
이 hook이 무엇을 결정론적으로 차단/검사하는지 한 문장.

## canonical
# form=core_adapter: sage_harness/hooks/<id>_core.py (pure decide) + adapters/{claude,codex}/<id>.sh
# form=native:       sage_harness/hooks/<id>.sh (단일 정본, legacy/custom POSIX hook)

## enforcement
- 차단 조건과 통과 조건을 명시 (enforcement는 hook 전용)

## tests
sage_harness/hooks/tests/test_<id>.py   # 결정론 회귀 (core/adapter)
