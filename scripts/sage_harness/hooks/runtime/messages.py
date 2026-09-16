"""messages — 게이트/컴플라이언스 hook 사용자 메시지 SSOT (5-3).

io_claude/io_codex 가 각자 들고 있던 message_key→문구 테이블을 여기로 합친다.
런타임 차이(emoji vs ASCII, 대시 —/-, 구분자 개행/파이프, 스킬 접두 //$, phase 화살표
→/->) 는 runtime 인자로 분기하고, io_* 는 채널(stdout / stderr / hookSpecific JSON)과
exit code 만 담당한다. 문구는 정보량이 많은 쪽으로 통일 — codex 도 동일 본문·힌트를 받는다
(4차 잔여였던 두 테이블 드리프트 제거, 의도된 출력 변경).
"""

# runtime 모듈은 설치본에서 flat import 로도 로드된다(io_claude 의 `import messages`).
# 상대 import 하나가 그 경로를 끊으므로 두 형태를 모두 받는다.
try:
    from . import i18n as _i18n
except ImportError:
    import i18n as _i18n

try:
    from . import recovery as _recovery
except ImportError:
    import recovery as _recovery

DEFAULT_LANGUAGE = _i18n.DEFAULT_LANGUAGE

_EMOJI = {"BLOCK": "⛔", "WARN": "⚠️", "OK": "✅"}


def _dash(runtime):
    return " - " if runtime == "codex" else " — "


def _review_cmd(runtime):
    # 스킬 호출 접두는 런타임 규약: codex=$, claude=/. 이건 드리프트가 아니라 정당한 치환.
    return "$sage-review" if runtime == "codex" else "/sage-review"


def desktop_hint(profile, language=DEFAULT_LANGUAGE):
    """프로젝트가 지정한 문구가 있으면 그것을 쓴다 — 프로젝트 소유 문자열은 번역 대상이 아니다."""
    configured = (profile.get("risk") or {}).get("desktop_block_hint")
    return configured or _i18n.frag(language, "hint.desktop_default")


def _inferred_stem(decision):
    """브랜치 leaf 추론으로 stem 을 얻었을 때 그 stem, 아니면 None.

    phase 문서를 고칠 때 stem 은 경로에서 오지만(위조 불가), 그 외 편집에서는 브랜치 leaf 에서
    추론한다. 사이클마다 브랜치를 따는 흐름에서는 맞고, 장수 브랜치에서는 영영 맞지 않는다.
    후자에서 "문서를 작성하라" 고만 안내하면 이미 있는 문서를 다시 쓰게 만든다.
    """
    if "branch-leaf" not in (decision.get("cycle_source") or []):
        return None
    return decision.get("cycle_stem") or ""


def _declare_hint(stem, language):
    return _i18n.frag(language, "hint.declare_stem").format(stem=stem)


def _with_stem(decision, language, tail):
    """추론 stem 이 있으면 선언 안내를 앞에 붙인다. 어순은 catalog 조각이 정한다."""
    stem = _inferred_stem(decision)
    if stem is None:
        return tail
    return _i18n.frag(language, "hint.if_this_cycle").format(
        lead=_declare_hint(stem, language), tail=tail)


def _phase_incomplete_hint(decision, language=DEFAULT_LANGUAGE):
    return _with_stem(decision, language, _i18n.frag(language, "hint.phase_doc"))


def _cycle_binding_hint(decision, language=DEFAULT_LANGUAGE):
    # binding 자체가 실패한 경우다. 비-phase 편집이면 브랜치 leaf 가 후보였으므로 선언이 탈출구고,
    # phase 문서 편집이면 경로/선언 불일치라 기존 안내가 정확하다.
    #
    # 어느 쪽이든 해제 통로를 함께 적는다. 선언이 후보에 섞여 후보가 2개가 되면 여기로 오는데,
    # `resolve` 는 문서 오류를 후보 개수보다 **먼저** 반환하므로 source 로 원인을 역추론할 수 없다.
    # 그래서 단정하지 않고 사실만 적는다 — 선언도 후보에 포함된다. 이 줄이 없으면 완결 사이클 차단
    # 안내("새 사이클의 Phase 00 을 쓰라")를 따른 사용자가 여기서 다시 막히고, 두 안내가 서로를
    # 가리키는 고리에 갇힌다(파일 선언은 세션을 넘겨 살아남아 사이클 경계마다 발생한다).
    source = decision.get("cycle_source") or []
    binding = _i18n.frag(language, "hint.binding")
    clear = _i18n.frag(language, "hint.clear_cycle")
    key = "hint.binding_phase" if (source and "branch-leaf" not in source) else "hint.binding_non_phase"
    return _i18n.frag(language, key).format(binding=binding, clear=clear)


def _cycle_risk_declaration_hint(decision, language=DEFAULT_LANGUAGE):
    return _with_stem(decision, language, _i18n.frag(language, "hint.risk_declaration"))


def _reconciliation_hint(decision, phase00_path, required_risk, language=DEFAULT_LANGUAGE):
    """계산 위험도가 00 선언을 넘었을 때의 안내. 출처에 따라 첫 행동이 갈린다.

    위험도가 세션 선언에서 왔다면 00 상향은 **실제보다 높은 위험도를 기록하는 행동**이다.
    실측: 가정 질문에서 L3 가 잘못 포착돼 L2 사이클의 모든 편집이 막혔고, 안내대로 00 을
    올렸다면 위험도 기록이 허위로 상향됐을 것이다. 그래서 선언 정정을 먼저 제시한다.
    """
    raise_00 = _i18n.frag(language, "hint.raise_phase00").format(
        required_risk=required_risk, phase00_path=phase00_path)
    if not decision.get("risk_from_declaration"):
        return raise_00
    return _i18n.frag(language, "hint.risk_from_declaration").format(
        required_risk=required_risk, raise_00=raise_00)


_DECLARED_ORIGIN = {"env": "origin.env", "cli": "origin.cli"}


def _cycle_origin_label(decision, language=DEFAULT_LANGUAGE):
    """결속을 **어디서 읽었는지**만 말한다.

    선언 통로가 둘이라 "선언" 한 마디로 뭉치면 안 된다 — env 가 파일을 이기는 상태에서 화면이
    "파일 선언" 이라고 적으면 확정적으로 거짓이다. 반대 방향도 같다.
    또 "누가 `sage cycle set` 으로 선언했다" 는 확인 불가능한 단언이라 하지 않는다. 선언 파일은
    프로젝트 안에 있어 무엇이든 직접 쓸 수 있고, 게이트가 아는 것은 읽은 자리뿐이다.
    """
    source = decision.get("cycle_source") or []
    if "event" in source:
        key = _DECLARED_ORIGIN.get(decision.get("cycle_stem_origin") or "", "origin.declared")
    elif "branch-leaf" in source:
        key = "origin.branch_leaf"
    else:
        key = "origin.phase_doc"
    return _i18n.frag(language, key)


def _declaration_notice(decision, runtime, language=DEFAULT_LANGUAGE):
    """선언 파일이 있는데 읽지 못한 사실을 알린다 — degrade 는 하되 조용하지는 않게.

    부재·손상·스키마 위반이 전부 빈 값으로 뭉개지면 파일을 1바이트만 잘라도 완결 사이클 차단이
    사라지고 아무도 모른다. 이 프로젝트가 반복해서 반증한 "부재는 안전 방향" 의 자리다.

    `cycle_state` 는 sage.i18n 을 import 할 수 없어(엔진 없이 단독 실행) code+arguments 진단을
    dict 로 올린다 — 여기서 이 모듈의 catalog(`_i18n.tr`)로 렌더한다.
    """
    diagnostic = decision.get("cycle_declaration_error")
    if not diagnostic:
        return ""
    detail = _i18n.tr(language, diagnostic["code"], **diagnostic["arguments"])
    core = _i18n.tr(language, "cycle_declaration_ignored", detail=detail)
    return core if runtime == "codex" else f"⚠️  {core}"


def _cycle_suffix(decision, language=DEFAULT_LANGUAGE):
    """판정 stem 과 그 출처를 통과 줄(OK·WARN)에 노출한다.

    선언일 때만 보여주면 정작 위험한 쪽이 안 보인다 — 장수 브랜치에서 leaf 로 추론된 stem 은
    이미 끝난 사이클을 가리키기 쉽고, 그 상태로 통과하는 것이 조용한 오결속이다. 출처를 함께
    적어야 사용자가 "이 사이클이 맞나"를 판단할 수 있다.

    WARN 에도 붙이는 이유: plan 없이 통과하는 상태가 결속이 가장 의심스러운 자리다. pdca 비활성이면
    stem 자체가 없어 아무것도 붙지 않는다. L1/L0 통과는 `pdca.cycle_binding_visibility: all` 일 때만
    줄이 생긴다(EH-15/16) — 기본값에서는 여전히 아무것도 출력하지 않는다.
    """
    stem = decision.get("cycle_stem")
    if not stem:
        return ""
    return f" | cycle: {stem} ({_cycle_origin_label(decision, language)})"


def _gate_fields(decision):
    """catalog template 에 넘길 named argument. 판정이 만든 값을 그대로 싣는다."""
    return {
        "rs": decision.get("reason", ""),
        "miss": ", ".join(decision.get("missing_phases") or []),
        "phase00_risk": decision.get("phase00_risk", ""),
        "required_risk": decision.get("required_risk", ""),
        "cycle_stem": decision.get("cycle_stem") or "(미상)",
    }


def _gate_record(decision, profile, language=DEFAULT_LANGUAGE):
    """message_key → (sev, scope, show_reason, hint). 문구 자체는 catalog 가 소유한다 —
    여기 한 벌 더 두면 같은 문장의 소유자가 둘이 되어 어느 쪽이 나가는지 알 수 없다."""
    rs = decision.get("reason", "")
    risk = decision.get("risk", "")
    miss = ", ".join(decision.get("missing_phases") or [])
    phase00_risk = decision.get("phase00_risk", "")
    required_risk = decision.get("required_risk", "")
    cycle_stem = decision.get("cycle_stem") or "(미상)"
    phase00_path = decision.get("phase00_path") or decision.get("file_short") or "(미상)"
    return {
        "block_desktop": ("BLOCK", "", False, desktop_hint(profile, language)),
        # §10-a-C: 미해결 차단성 마커를 남긴 채 그 파일에 쓰는 것을 막는다. 마커를 걷어내는
        # 편집은 통과하므로(자기차단 방지) 안내는 "해소하라" 가 정확한 탈출 경로다.
        "block_feedback_unresolved": ("BLOCK", "", True, None),
        "block_l3_no_plan": ("BLOCK", "L3", True, None),
        "block_l3_strategy_unresolved": ("BLOCK", "L3", True, None),
        "block_l3_review_evidence": ("BLOCK", "L3", True, None),
        "warn_l3_no_review": ("WARN", "L3", True, None),
        "warn_l2_no_plan": ("WARN", "L2", True, None),
        "warn_l0_l3_content": ("WARN", "L0", False, None),
        "block_phase_incomplete": ("BLOCK", risk, True,
                             _phase_incomplete_hint(decision, language)),
        "warn_phase_incomplete": ("WARN", "L1", False, None),
        "block_cycle_risk_declaration": (
            "BLOCK", "PDCA", True,
            _cycle_risk_declaration_hint(decision, language)),
        "block_cycle_risk_reconciliation": (
            "BLOCK", "PDCA", True,
            _reconciliation_hint(decision, phase00_path, required_risk, language)),
        "block_report_without_approval": ("BLOCK", "PDCA", False, None),
        "block_report_mixed_evidence": ("BLOCK", "PDCA", False, None),
        "block_phase00_mixed_evidence": ("BLOCK", "PDCA", False, None),
        "warn_phase00_mixed_evidence": ("WARN", "PDCA", False, None),
        "block_report_without_audit": ("BLOCK", "PDCA", False, None),
        "warn_report_without_audit": ("WARN", "PDCA", False, None),
        "block_invalid_done_criteria": ("BLOCK", "PDCA", False, None),
        "warn_invalid_done_criteria": ("WARN", "PDCA", False, None),
        "warn_done_criteria_progress": ("WARN", "PDCA", False, None),
        "block_stale_done_criteria_revision": ("BLOCK", "PDCA", False, None),
        "warn_stale_done_criteria_revision": ("WARN", "PDCA", False, None),
        "block_report_without_done_criteria": ("BLOCK", "PDCA", False, None),
        "warn_report_without_done_criteria": ("WARN", "PDCA", False, None),
        "block_stale_done_criteria_approval": ("BLOCK", "PDCA", False, None),
        "warn_stale_done_criteria_approval": ("WARN", "PDCA", False, None),
        "block_cycle_binding": ("BLOCK", "PDCA", False, _cycle_binding_hint(decision, language)),
        # 문서 언어 충돌. 게이트가 어느 쪽도 고르지 않으므로 안내도 한쪽을 지목하지 않는다 —
        # 정본은 Phase 00 이고, 미러를 맞추는 명령을 함께 적어야 탈출 경로가 닫히지 않는다.
        "block_document_language_conflict": ("BLOCK", "PDCA", True, None),
        "warn_document_language_missing": ("WARN", "PDCA", True, None),
        # 본문(prose) 언어 위반 — marker 는 일치하지만 그 아래 실제 글이 선언 언어를 어긴 경우.
        # 셋 다 reason 을 노출하는데, 그 reason 은 판정이 만든 **언어 중립 evidence**(경로·줄번호·
        # 원문 조각)뿐이다. 설명 문장은 여기 catalog 가 소유해야 영어 화면에 한국어가 안 섞인다.
        "block_document_prose_language": ("BLOCK", "PDCA", True, None),
        "block_document_prose_structure": ("BLOCK", "PDCA", True, None),
        "block_document_unclosed_fence": ("BLOCK", "PDCA", True, None),
        # 변경 후 전체 본문을 되짚지 못한 경우와 판정 정본 부재. 둘 다 "무엇인지 모른다" 라서
        # 통과가 아니라 차단이다 — 모르는 채 통과시키면 그 경로가 곧 검사 우회로가 된다.
        "block_document_post_image": ("BLOCK", "PDCA", True, None),
        "block_prose_scanner_unavailable": ("BLOCK", "PDCA", True, None),
        "block_cycle_closed": ("BLOCK", "PDCA", True,
                             _i18n.frag(language, "hint.cycle_closed").format(
                                 clear=_i18n.frag(language, "hint.clear_cycle"))),
        "block_report_without_acceptance": ("BLOCK", "PDCA", False, None),
        "warn_report_without_acceptance": ("WARN", "PDCA", False, None),
        "warn_report_with_l3_waiver": ("WARN", "PDCA", False, None),
        "block_report_waiver_audit_failure": ("BLOCK", "PDCA", False, None),
        "block_fast_cycle_audit": ("BLOCK", "FAST", False, None),
        "warn_fast_cycle": ("WARN", risk, False, None),
        "block_cycle_stem_audit_failure": ("BLOCK", "PDCA", False, None),
        "block_gate_runtime_error": ("BLOCK", "PDCA", False, None),
        "ok_l3": ("OK", "L3", False, None),
        "ok_l2": ("OK", "L2", False, None),
        # EH-15/16: 기본은 이 둘이 선택되지 않는다(core 가 profile opt-in 일 때만 key 를 싣는다).
        # 켜면 편집 빈도가 가장 높은 자리라, 결속이 틀렸을 때 가장 먼저 눈에 띄는 자리이기도 하다.
        "ok_l1": ("OK", "L1", False, None),
        "ok_l0": ("OK", "L0", False, None),
    }.get(decision.get("message_key"))


def display_language(root=None):
    """대상 프로젝트의 표시 언어. 결정할 수 없으면 기본값이며 절대 예외를 올리지 않는다.

    렌더 직전에만 부른다. 여기서 실패해 게이트가 죽으면 언어 설정 하나가 차단을 통째로 꺼버리고,
    그건 표시 계층이 판정 계층을 무너뜨리는 방향이다.
    """
    try:
        return _i18n.context.resolve(root)[0]
    except Exception:
        return DEFAULT_LANGUAGE


def gate_text(decision, profile, runtime, language=DEFAULT_LANGUAGE):
    """게이트 결정 → 런타임별 렌더 문자열(매칭 없으면 ''). 채널/exit 은 io_* 가 처리.

    언어는 마지막에만 닿는다. `decision` 은 이미 언어 중립으로 확정돼 있고 여기서 바꾸는 것은
    사람이 읽는 문장 하나뿐이다 — status·exit_code·message_key·evidence 는 건드리지 않는다.
    """
    rec = _gate_record(decision, profile, language)
    # 선언 손상 알림은 판정과 독립이다 — 기본 설정의 L1/L0 통과는 message_key 가 없어 줄 자체가
    # 안 생기는데, 거기가 바로 깨진 선언이 조용히 무시되는 자리다. 판정 줄이 없으면 이 알림만 내보낸다.
    notice = _declaration_notice(decision, runtime, language)
    if not rec:
        return notice
    sev, scope, show_reason, hint = rec
    text = _i18n.tr(language, decision.get("message_key"), **_gate_fields(decision))
    fs = decision.get("file_short", "")
    rs = decision.get("reason", "")
    tag = f"[GATE {sev}{_dash(runtime)}{scope}]" if scope else f"[GATE {sev}]"
    prefix = "" if runtime == "codex" else f"{_EMOJI[sev]} "
    if sev == "OK":
        # fs 가 빈 경우(경로가 판정에 안 쓰인 통과)까지 구분자를 붙이면 꼬리만 남은 줄이 된다.
        line = f"{prefix}{tag} {text}" + (f" | {fs}" if fs else "") + _cycle_suffix(decision, language)
    else:
        line = f"{prefix}{tag} {text} {_i18n.frag(language, 'gate.frame.file')}: {fs}"
        if show_reason and rs:
            line += f" | {_i18n.frag(language, 'gate.frame.reason')}: {rs}"
        if sev == "WARN":
            line += _cycle_suffix(decision, language)
    # 계산 hint(출처에 따라 문장이 갈리는 것)가 우선이고, 그 외에는 `hint.<message_key>` 규약으로
    # catalog 에서 찾는다. 표에 문구를 남겨두면 같은 문장의 소유자가 둘이 된다.
    hint = hint or _i18n.frag(language, f"hint.{decision.get('message_key')}")
    if hint:
        hint = hint.replace("{rv}", _review_cmd(runtime))
        line += (" | " if runtime == "codex" else "\n  → ") + hint
    if sev == "BLOCK":
        # 차단 뒤에는 항상 다음에 칠 것이 있어야 한다. hint 는 "무엇이 잘못됐나" 를 설명하고
        # `Next:` 는 "지금 무엇을 치면 되나" 를 준다 — 둘은 다른 물건이다. code 도 함께 낸다:
        # 번역된 문장은 언어마다 다르지만 code 는 같아서, 사용자가 검색하고 CI 가 수집할 수
        # 있는 조각은 그것뿐이다.
        code = _recovery.code_for(decision.get("message_key"))
        if code:
            # 줄바꿈은 런타임 규약이다. Codex 는 한 줄 파이프 구분을 요구하므로 여기서
            # 개행을 쓰면 wire 계약이 깨진다 — hint 가 이미 같은 이유로 분기하고 있다.
            joiner = " | " if runtime == "codex" else "\n  "
            parts = [f"[{code}]"]
            parts.extend(_recovery.render(code, lambda key: _i18n.frag(language, key),
                                          path=fs or "<path>", host=runtime))
            line += joiner + joiner.join(parts)
    return f"{notice}\n{line}" if notice else line


def declared_capture_text(level, runtime):
    core = f"[Risk 선언 포착] 이번 세션 작업 레벨: {level} — 소스 수정 시 해당 레벨 게이트가 적용됩니다."
    return core if runtime == "codex" else f"ℹ️  {core}"


def declared_ambiguous_text(runtime):
    core = ("[Risk 선언 미포착] 여러 레벨이 함께 언급돼 선언으로 보지 않았습니다 — "
            "적용하려면 레벨 하나만 적어주세요.")
    return core if runtime == "codex" else f"ℹ️  {core}"


def declared_clear_text(runtime, existed=True):
    # 지울 것이 없었는데 "지웠습니다" 로 안내하면 사용자가 원인 파악에 헤맨다.
    core = ("[Risk 선언 해제] 이번 세션의 위험도 선언을 지웠습니다"
            if existed else "[Risk 선언 해제] 이번 세션에는 위험도 선언이 없었습니다")
    core += " — 이후 판정은 경로·내용 계산만 씁니다."
    return core if runtime == "codex" else f"ℹ️  {core}"


def report_saved_text(host_dir, today, runtime):
    core = f"Compliance report saved: {host_dir}/logs/compliance-{today}.md"
    return core if runtime == "codex" else f"📋 {core}"


# --- pre-phase4-checklist-gate 공유 문구 ---
# (block 의 evidence 리스트 본문은 런타임별 구조라 io_* 가 조립; 여기선 한 줄 문구만 통일)
def _phase4_arrow(runtime):
    return "3->4" if runtime == "codex" else "3→4"


def phase4_block_header(total_unchecked, base, runtime):
    prefix = "" if runtime == "codex" else "⛔ "
    return (f"{prefix}[GATE BLOCK{_dash(runtime)}Phase {_phase4_arrow(runtime)}] "
            f"체크리스트 미완료 {total_unchecked}건 (기능: {base})")


def phase4_warn(base, runtime):
    prefix = "" if runtime == "codex" else "⚠️  "
    return (f"{prefix}[GATE WARN{_dash(runtime)}Phase {_phase4_arrow(runtime)}] "
            f"'{base}' 의 03-implementation 문서를 찾지 못했습니다.")


def phase4_ok(base, runtime):
    prefix = "" if runtime == "codex" else "✅ "
    return (f"{prefix}[GATE OK{_dash(runtime)}Phase {_phase4_arrow(runtime)}] "
            f"'{base}' 체크리스트 완료 확인")
