"""hook 쪽 복구 순서 — 엔진 없이도 도는 독립 한 벌.

## 왜 엔진 것을 import 하지 않는가

설치된 hook runtime 은 main `sage` package 없이 돌아야 한다. 그게 이 저장소가 세운 계약이고,
공통 진단 계약을 이유로 그 경계를 열면 소비 프로젝트의 hook 이 엔진 설치에 묶인다.

그래서 여기는 `sage/diagnostic_contract.py` 의 **사본이 아니라 대응물**이다. 공통인 것은 code 와
recovery id 의 **형태와 의미**이고, 두 벌이 어긋나지 않는지는 build-time oracle 이 대조한다.
같은 id 는 양쪽에서 같은 명령을 내야 한다.

## 왜 `sage explain --path` 가 대부분의 첫 단계인가

게이트가 막은 자리에서 사용자가 가장 먼저 알아야 하는 것은 "이 경로가 왜 이런 요구를 받는가"
다. 그건 상태를 바꾸지 않고 답할 수 있는 질문이고, 답을 보기 전에 뭔가를 고치게 하면 엉뚱한
것을 고친다. 안전한 직접 복구가 없을 때 읽기 전용 진단으로 시작하는 규칙의 실제 적용이다.
"""

# (id, command, description_key, mutating)
# command 가 None 이면 사람이 해야 하는 일이고 렌더에서 `Action:` 으로 갈린다.
EXPLAIN = ("explain", "sage explain --path {path}", "recovery.explain", False)
STATUS = ("status", "sage status", "recovery.status", False)
VALIDATE = ("validate", "sage validate --kind all --check --schema", "recovery.validate", False)
CYCLE_SHOW = ("cycle-show", "sage cycle show", "recovery.cycle_show", False)
CYCLE_SET = ("cycle-set", "sage cycle set <stem>", "recovery.cycle_set", True)
CYCLE_CLEAR = ("cycle-clear", "sage cycle clear", "recovery.cycle_clear", True)
WRITE_PHASES = ("write-phases", None, "recovery.write_phases", False)
FIX_CANONICAL_SPEC = ("fix-canonical-spec", None, "recovery.fix_canonical_spec", False)
REGENERATE = ("regenerate", "sage generate --kind <kind> --write", "recovery.regenerate", True)
REINSTALL_HOST = ("reinstall-host", "sage install --host {host} --force --dest .",
                  "recovery.reinstall_host", True)
FIX_RISK_DECLARATION = ("fix-risk-declaration", None, "recovery.fix_risk_declaration", False)
RESOLVE_FEEDBACK = ("resolve-feedback", None, "recovery.resolve_feedback", False)
WRITE_PLAN = ("write-plan", None, "recovery.write_plan", False)
SELECT_L3_STRATEGY = ("select-l3-strategy", None, "recovery.select_l3_strategy", False)
RUN_REVIEW = ("run-review", "sage review", "recovery.run_review", True)
FAST_OPEN = ("fast-open", "sage fast-cycle open", "recovery.fast_open", True)
FIX_DOCUMENT = ("fix-document", None, "recovery.fix_document", False)
FIX_SHARED_PROFILE = ("fix-shared-profile", None, "recovery.fix_shared_profile", False)
FIX_REPORT = ("fix-report", None, "recovery.fix_report", False)
MOVE_OFF_DESKTOP = ("move-off-desktop", None, "recovery.move_off_desktop", False)

# message_key → 안정 진단 code.
#
# **기계 변환하지 않는다.** `block_` 접두를 떼는 변환은 무엇이 키인지조차 스스로 판단하지
# 못한다 — 같은 패턴으로 잡히는 이름 중에는 함수명·파라미터·profile 설정 키·다른 키의
# prefix 가 섞여 있고, 그것들에서 파생된 안정 식별자는 한 번 나가면 되돌릴 수 없다.
CODE_OF = {
    "block_cycle_binding": "cycle.binding_missing",
    "block_cycle_closed": "cycle.closed",
    "block_cycle_risk_declaration": "cycle.risk_declaration_invalid",
    "block_cycle_risk_reconciliation": "cycle.risk_reconciliation_failed",
    "block_cycle_stem_audit_failure": "cycle.stem_audit_failure",
    "block_fast_cycle_audit": "cycle.fast_audit_missing",
    "block_desktop": "guard.desktop_path",
    "block_document_language_conflict": "document.language_conflict",
    "block_document_post_image": "document.post_image",
    "block_document_prose_language": "document.prose_language",
    "block_document_prose_structure": "document.prose_structure",
    "block_document_unclosed_fence": "document.unclosed_fence",
    "block_prose_scanner_unavailable": "document.prose_scanner_unavailable",
    "block_feedback_unresolved": "feedback.unresolved",
    "block_gate_runtime_error": "gate.runtime_error",
    "block_phase_incomplete": "gate.phase_incomplete",
    "block_l3_no_plan": "gate.l3_plan_missing",
    "block_l3_review_evidence": "gate.l3_review_evidence_missing",
    "block_l3_strategy_unresolved": "gate.l3_strategy_unresolved",
    "block_invalid_done_criteria": "phase00.done_criteria_invalid",
    "block_phase00_mixed_evidence": "phase00.mixed_evidence",
    "block_report_mixed_evidence": "report.mixed_evidence",
    "block_report_waiver_audit_failure": "report.waiver_audit_failure",
    "block_report_without_acceptance": "report.acceptance_missing",
    "block_report_without_approval": "report.approval_missing",
    "block_report_without_audit": "report.audit_missing",
    "block_report_without_done_criteria": "report.done_criteria_missing",
    "block_stale_done_criteria_approval": "report.done_criteria_approval_stale",
    "block_stale_done_criteria_revision": "report.done_criteria_revision_stale",
}

# write guard 의 5 분기. 이전에는 여기 문장이 한국어로 직접 조립돼 있었고, catalog 키가 아니라
# 어떤 한영 대조에도 걸리지 않았다.
GUARD_CODES = ("guard.cycle_declaration", "guard.framework_doc", "guard.core_render",
               "guard.core_render_blocked", "guard.generated_asset")

RECOVERY = {
    # --- cycle -------------------------------------------------------------
    "cycle.binding_missing": (EXPLAIN, CYCLE_SHOW, CYCLE_SET),
    "cycle.closed": (CYCLE_SHOW, CYCLE_CLEAR, CYCLE_SET),
    # 이 code 에는 Action 단계를 두지 않는다. `hint.block_cycle_risk_declaration` 이 이미
    # 정확히 어떤 줄을 써야 하는지 리터럴 예시로 말하고 있고, 같은 말을 두 번 하면 사용자는
    # 둘이 다른 지시인지 확인하느라 멈춘다.
    "cycle.risk_declaration_invalid": (EXPLAIN, VALIDATE),
    "cycle.risk_reconciliation_failed": (EXPLAIN, FIX_RISK_DECLARATION, VALIDATE),
    "cycle.stem_audit_failure": (STATUS, CYCLE_SHOW, VALIDATE),
    "cycle.fast_audit_missing": (STATUS, FAST_OPEN, VALIDATE),
    # --- guard -------------------------------------------------------------
    "guard.desktop_path": (EXPLAIN, MOVE_OFF_DESKTOP),
    # 세 갈래를 다 준다. 이 파일을 직접 열려는 사람은 셋 중 하나를 하려던 것이고, 그중
    # 하나만 보여주면 나머지 둘을 하려던 사람은 다시 직접 열게 된다.
    "guard.cycle_declaration": (CYCLE_SHOW, CYCLE_SET, CYCLE_CLEAR),
    "guard.framework_doc": (EXPLAIN, FIX_DOCUMENT, VALIDATE),
    "guard.core_render": (EXPLAIN, FIX_DOCUMENT, VALIDATE),
    "guard.core_render_blocked": (EXPLAIN, REINSTALL_HOST, VALIDATE),
    "guard.generated_asset": (FIX_CANONICAL_SPEC, REGENERATE, VALIDATE),
    # --- document ----------------------------------------------------------
    "document.language_conflict": (EXPLAIN, FIX_DOCUMENT, VALIDATE),
    "document.post_image": (EXPLAIN, FIX_DOCUMENT, VALIDATE),
    "document.prose_language": (EXPLAIN, FIX_DOCUMENT, VALIDATE),
    "document.prose_structure": (EXPLAIN, FIX_DOCUMENT, VALIDATE),
    "document.unclosed_fence": (EXPLAIN, FIX_DOCUMENT, VALIDATE),
    "document.prose_scanner_unavailable": (STATUS, REINSTALL_HOST, VALIDATE),
    # --- feedback / gate ---------------------------------------------------
    "feedback.unresolved": (EXPLAIN, RESOLVE_FEEDBACK),
    "gate.runtime_error": (STATUS, REINSTALL_HOST, VALIDATE),
    # hook 런타임이 자기 core 를 세우지 못한 상태들. 사용자에게 보이는 BLOCK 이므로
    # 다른 차단과 같은 계약을 진다 — code 와 다음 행동이 함께 나간다.
    "runtime.core_failure": (STATUS, REINSTALL_HOST, VALIDATE),
    "runtime.project_hook_contract": (STATUS, FIX_DOCUMENT, VALIDATE),
    "runtime.profile_contract": (STATUS, FIX_SHARED_PROFILE, VALIDATE),
    "gate.phase_incomplete": (EXPLAIN, CYCLE_SHOW, WRITE_PHASES),
    "gate.l3_plan_missing": (EXPLAIN, WRITE_PLAN),
    "gate.l3_review_evidence_missing": (EXPLAIN, RUN_REVIEW),
    "gate.l3_strategy_unresolved": (STATUS, SELECT_L3_STRATEGY, VALIDATE),
    # --- phase 00 / report --------------------------------------------------
    "phase00.done_criteria_invalid": (EXPLAIN, FIX_DOCUMENT, VALIDATE),
    "phase00.mixed_evidence": (EXPLAIN, FIX_DOCUMENT, VALIDATE),
    "report.mixed_evidence": (EXPLAIN, FIX_REPORT, VALIDATE),
    "report.waiver_audit_failure": (STATUS, VALIDATE),
    "report.acceptance_missing": (EXPLAIN, FIX_REPORT, VALIDATE),
    "report.approval_missing": (EXPLAIN, RUN_REVIEW),
    "report.audit_missing": (STATUS, VALIDATE),
    "report.done_criteria_missing": (EXPLAIN, FIX_DOCUMENT, VALIDATE),
    "report.done_criteria_approval_stale": (EXPLAIN, RUN_REVIEW),
    "report.done_criteria_revision_stale": (EXPLAIN, FIX_DOCUMENT, VALIDATE),
}

# 안전한 직접 복구가 없을 때 내려앉는 바닥값. catalog 나 매핑이 깨졌다는 이유로 사용자에게
# 아무 다음 행동도 주지 않는 것이 가장 나쁜 실패다.
FALLBACK = (STATUS,)


def code_for(message_key):
    return CODE_OF.get(message_key)


def steps_for(code):
    return RECOVERY.get(code, ())


def render(code, translate, **context):
    """복구 순서를 사람이 읽는 줄 목록으로.

    `Next:` 와 `Action:` 토큰은 번역하지 않는다 — 번역하면 사용자가 화면에서 검색할 수 없고
    로그에서 자동 수집도 깨진다. 언어를 타는 것은 뒤에 붙는 설명뿐이다.
    """
    steps = steps_for(code) or FALLBACK
    lines = []
    for _id, command, description_key, _mutating in steps:
        if command:
            try:
                rendered = command.format(**context)
            except (KeyError, IndexError, ValueError):
                rendered = command
            lines.append("Next: " + rendered)
        else:
            text = translate(description_key)
            lines.append("Action: " + (text or description_key))
    return lines
