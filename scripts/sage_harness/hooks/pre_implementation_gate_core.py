"""pre-implementation-gate — canonical core (pure, IO-bound gate, 부분추출).

Codex 2R 합의: 공유 risk-gate 만 canonical 추출. "L3 review doc 매칭"은 algorithm_delta(병합금지)
→ find_l3_review 전략 슬롯(claude_grep_first / codex_feature_signal 둘 다 보존, v1 미선택).

계약(2단계 pure core):
  classify_risk(event, profile)                              -> {risk, reason, trigger_sources, file_short}
  decide(event, profile, snapshot, strategy_result)          -> {status, exit_code, risk, message_key, safety_degraded?}
- core 는 fs/time 의존 0. plan 후보(내용)·strategy 실행결과는 adapter 가 snapshot/strategy_result 로 주입.

안전 합의(G1): strategy 미선택(unresolved)이면 L3 review 확인 불가 → BLOCK + override-required + safety_degraded.
키워드/파일패턴 매칭은 case-insensitive(G2 canonical, 더 많은 L3 포착 = 안전 방향).
risk trigger(글롭/키워드)는 profile_bound(G3) — core 에 도메인값 0.
"""

import fnmatch
import os
import re
import sys

import cycle_binding
import risk_declaration
from path_risk import path_risk_floor
from done_criteria_contract import (
    document_revision,
    parse_done_criteria,
    phase00_text_hash,
)

CONTRACT_VERSION = "2"   # EH-7: decide() 가 cycle_stem/cycle_source/cycle_stem_declared 를 싣고,
                         # 어댑터가 선언 사용을 감사해야 한다. 낡은 어댑터 + 새 core 조합은 스탬프만
                         # 하고 기록을 못 해 무감사 통과가 되므로 STALE 로 잡는다.
_RANK = {"none": -1, "L0": 0, "L1": 1, "L2": 2, "L3": 3}
_STRUCTURED_LABEL_EMPHASIS_RE = re.compile(
    r"(?P<mark>\*{1,3}|_{1,3})(?P<label>Final\s+Status|Loop-Run"
    r"|Review-Assurance|Review-Close-Reason|Review-Rounds|Residual-Findings)"
    r"(?P<colon>\s*[:：]?)(?P=mark)",
    re.IGNORECASE,
)


def _imatch(path: str, glob: str) -> bool:
    return fnmatch.fnmatch(path.lower(), glob.lower())


def _has_kw(content: str, keywords: list) -> bool:
    c = (content or "").lower()
    return any(kw.lower() in c for kw in keywords)


def _classify_one(path: str, content: str, profile: dict) -> tuple:
    """단일 변경의 (risk, reason, trigger_sources) — desktop 은 별도(여기선 분류만).

    경로 기준 하한은 `path_risk.path_risk_floor` 가 소유한다. 이 모듈이 자기 사본을 갖지
    않는 이유는 `sage explain --path` 가 같은 판정을 보여줘야 하기 때문이다 — 두 벌을 두면
    언젠가 갈리고, 갈린 뒤에는 어느 쪽이 진짜 게이트 판정인지 알 수 없다.
    """
    r = profile.get("risk", {})
    floor = path_risk_floor(path, profile)
    if floor.risk == "L0":
        return floor.as_tuple()
    if floor.risk == "none":
        return ("none", "", [])
    if "invalid_profile" in floor.trigger_sources:
        # validate/compiler가 orphan exclusion을 차단하지만, pure core도 malformed runtime
        # profile을 L0/none으로 하향하지 않는다.
        return floor.as_tuple()
    risk, reason, trigger_sources = floor.as_tuple()

    # 내용 escalation (L1/L2 → L3, L1 → L2). Filename으로 이미 L3인
    # change도 content provenance는 보존해 감사/compound gate 입력이 정확해야 한다.
    if _has_kw(content, r.get("l3_content_keywords", [])):
        risk = "L3"
        reason += " + 내용 L3 키워드"
        trigger_sources.append("content_l3")
    elif risk == "L1" and _has_kw(content, r.get("l2_content_keywords", [])):
        risk, reason = "L2", reason + " + 내용 L2 키워드"
        trigger_sources.append("content_l2")
    return (risk, reason, trigger_sources)


def classify_risk(event: dict, profile: dict) -> dict:
    """changes 중 최고 위험 분류. desktop 직접수정은 risk='DESKTOP_BLOCK'."""
    r = profile.get("risk", {})
    desktop_glob = r.get("desktop_block_glob", "")
    changes = event.get("changes") or []

    l3_kw = r.get("l3_content_keywords", [])
    l0_l3_file = ""   # P2-9: L0 즉시통과 파일이 L3 내용 키워드를 담은 경우(비차단 WARN — 민감정보 점검)

    best = {"risk": "none", "reason": "", "is_l3_filename": False,
            "trigger_sources": [], "file_short": ""}
    for ch in changes:
        path = ch.get("path") or ""
        if desktop_glob and _imatch(path, desktop_glob):
            return {"risk": "DESKTOP_BLOCK", "reason": f"동기화 산출물/금지 경로 직접수정 금지: {path}",
                    "is_l3_filename": False, "declared_l3": False,
                    "trigger_sources": ["desktop_block"], "file_short": path}
        content = ch.get("content") or ""
        risk, reason, sources = _classify_one(path, content, profile)
        # L0 는 내용 escalation 을 안 거치므로(즉시통과) 문서에 숨은 L3 키워드를 놓친다 → 별도 비차단 스캔.
        if risk == "L0" and not l0_l3_file and _has_kw(content, l3_kw):
            l0_l3_file = path
        if _RANK.get(risk, -1) > _RANK.get(best["risk"], -1):
            best = {"risk": risk, "reason": reason,
                    "is_l3_filename": "filename_l3" in sources,
                    "trigger_sources": sources, "file_short": path}
        elif risk != "none" and risk == best["risk"]:
            # Compound changes can reach the same rank through different controls.
            # Keep every security-relevant provenance instead of letting the first
            # same-rank change mask a later filename/content L3 trigger.
            best["trigger_sources"] = list(dict.fromkeys([*best["trigger_sources"], *sources]))
            if "filename_l3" in sources and not best["is_l3_filename"]:
                best["is_l3_filename"] = True
                best["file_short"] = path
                best["reason"] = reason
    best["l0_l3_file"] = l0_l3_file

    # 유저 선언 레벨 반영 (effective = max(감지, 선언), 상향만)
    declared = event.get("declared_max")  # "L0".."L3" or None
    declared_l3 = declared == "L3"
    if declared and _RANK.get(declared, -1) > _RANK.get(best["risk"], -1):
        best["risk"] = declared
        best["reason"] = (best["reason"] + " + " if best["reason"] else "") + f"유저 선언 {declared}"
        best["trigger_sources"] = list(best.get("trigger_sources") or []) + [f"declared_{declared.lower()}"]
    best["declared_l3"] = declared_l3
    return best


def _doc_match(docs: list, event: dict) -> str:
    """문서 목록에서 ticket(브랜치 숫자) 매칭 → 없으면 최근(recent=7일 이내) fallback.

    plan/phase 문서 존재 판정의 공통 규칙. docs = [{path, content, recent}].
    (원본 Claude 충실성: ticket 매칭은 전체 대상, fallback 은 -mtime -7 제한.)
    """
    import re
    branch = event.get("branch") or ""
    m = re.search(r"[0-9]+", branch)
    ticket = m.group(0) if m else ""
    if ticket:
        for d in docs:
            if ticket in (d.get("content") or ""):
                return d.get("path", "")
    for d in docs:
        if d.get("recent"):
            return d.get("path", "")
    return ""


def _cycle_binding(event, profile, snapshot):
    cfg = _pdca_cfg(profile)
    if cfg is None:
        return None
    return cycle_binding.resolve(event, snapshot, cfg)


def _cycle_doc(docs, binding):
    if not binding or binding.get("error"):
        return None, (binding or {}).get("error") or "cycle binding unavailable"
    return cycle_binding.select_document(docs, binding["stem"])


def _is_phase_write(event, cfg):
    patterns = [item.get("glob") or "" for item in (cfg.get("phases") or [])]
    return any(cycle_binding.matches_glob(change.get("path") or "", pattern)
               for change in (event.get("changes") or []) for pattern in patterns if pattern)


def _phase_only_change(event, cfg):
    """변경이 1건 이상이고 **전부** phase 문서인가 — 완결 사이클 차단의 유일한 면제 조건.

    `_is_phase_write` 를 쓰면 안 된다. 그건 `any()` 라 소스 파일 열 개에 문서 한 줄만 섞어도
    참이 되고, 면제 조건에 넣는 순간 차단 전체가 꺼진다(실측: rc 0, 출력 0바이트).
    유일한 호출부인 PDCA 진입 조건에서는 `any` 가 **넓게 잡는** 안전한 방향이라 그대로 옳다 —
    같은 술어를 면제에 쓰면 방향이 뒤집힌다.

    변경 0건도 면제가 아니다. 어댑터가 경로를 못 뽑은 상태가 차단을 사면하면 안 된다.
    """
    patterns = [item.get("glob") or "" for item in (cfg.get("phases") or [])]
    changes = event.get("changes") or []
    return bool(changes) and all(
        any(cycle_binding.matches_glob(change.get("path") or "", pattern)
            for pattern in patterns if pattern)
        for change in changes)


def _changed_phase_ids(event, cfg):
    changed = set()
    for phase in cfg.get("phases") or []:
        pid, pattern = str(phase.get("id") or ""), phase.get("glob") or ""
        if pid and pattern and any(cycle_binding.matches_glob(change.get("path") or "", pattern)
                                   for change in (event.get("changes") or [])):
            changed.add(pid)
    return changed


def _phase_glob(cfg, phase_id):
    for phase in cfg.get("phases") or []:
        if str(phase.get("id") or "") == str(phase_id):
            return phase.get("glob") or ""
    return ""


def _is_phase00_repair_only(event, cfg):
    """Phase 00 자체를 만들거나 고치는 쓰기는 잘못된 기존 선언으로 자기차단하지 않는다."""
    changes = event.get("changes") or []
    phase00_glob = _phase_glob(cfg, "00")
    other_globs = [
        phase.get("glob") or ""
        for phase in (cfg.get("phases") or [])
        if str(phase.get("id") or "") != "00" and phase.get("glob")
    ]
    return bool(changes and phase00_glob) and all(
        cycle_binding.matches_glob(change.get("path") or "", phase00_glob)
        and not any(cycle_binding.matches_glob(change.get("path") or "", glob)
                    for glob in other_globs)
        for change in changes
    )


def _plan_exists(event: dict, snapshot: dict) -> str:
    """snapshot.plan_files 에서 ticket→recent 매칭(기존 계약 유지)."""
    return _doc_match(snapshot.get("plan_files") or [], event)


def _bound_plan_exists(event, profile, snapshot):
    cfg = _pdca_cfg(profile)
    if cfg is None:
        return _plan_exists(event, snapshot)
    binding = cycle_binding.resolve(event, snapshot, cfg)
    if binding.get("error"):
        return ""
    return binding["stem"] if cycle_binding.any_document(snapshot.get("plan_files") or [], binding["stem"]) else ""


def _pdca_cfg(profile: dict):
    """PDCA phase 강제 설정. 비활성(enabled=false 또는 phases 없음)이면 None → 게이트는 기존 동작(하위호환)."""
    p = profile.get("pdca") or {}
    if not p.get("enabled"):
        return None
    if not p.get("phases"):
        return None
    return p


def _discloses_low_risk_binding(profile: dict) -> bool:
    """EH-15/16: L1·L0 통과 줄에도 결속 stem 을 노출할지.

    기본은 기존 동작(노출 안 함)이다. 통과 줄은 양 host 모두 비차단 컨텍스트 채널로 나가므로
    (EH-12) 항상 켜면 편집 빈도가 가장 높은 tier 에서 매번 모델 컨텍스트에 한 줄이 쌓인다.
    결속 증거 자체는 `.sage/override.jsonl` 에 위험도와 무관하게 남으므로 이건 손실 복구가
    아니라 감시 편의이며, 필요한 프로젝트만 켜는 것이 맞다.

    `pdca.enabled=false` 는 대상이 아니다 — 사이클 개념 자체가 없어 노출할 stem 이 없다.
    """
    return ((profile.get("pdca") or {}).get("cycle_binding_visibility") or "gated") == "all"


def _fast_covers_required(fast_state, required):
    """Fast 면제를 줘도 되는가 — 그 run 이 요구 phase 를 실제로 담고 있는가.

    fresh Fast 는 composite 00 하나가 00~04 를 담고, `parse_fast_plan` 이 phase heading 과 Document
    Mapping 체크리스트로 그걸 확인한 뒤에만 state 가 만들어진다. 그래서 면제가 성립한다.

    전환 run 에는 composite 문서가 없다. 담보는 전환 시점에 실재하던 Standard 문서들이고, 그 목록이
    `source_phases_open` 이다. 이걸 확인하지 않으면 Phase 00 하나만 쓴 상태에서 전환해 계획 00~03
    요구를 통째로 면제받을 수 있다 — 전환이 계획을 건너뛰는 통로가 된다.

    **key 집합만 보지 않는다.** `{"00": {}, "01": {}}` 는 key 는 맞지만 그 문서가 실재했다는
    어떤 근거도 싣지 않는다. 이 함수는 `_fast_cycle_state` 뒤에만 불리는 것이 아니라 따로도
    불릴 수 있으므로, 여기서도 계약을 확인한다 — 호출 순서에 기대는 방어는 순서가 바뀌면
    사라진다.
    """
    if fast_state.get("entry_mode") != CONVERTED_ENTRY_MODE:
        return True
    recorded = fast_state.get("source_phases_open")
    if not isinstance(recorded, dict):
        return False
    try:
        from fast_cycle_contract import converted_provenance_issue
    except Exception:
        return False           # 계약을 부르지 못하면 면제하지 않는다
    if converted_provenance_issue(fast_state.get("current_phase"), recorded):
        return False
    return set(required).issubset(set(recorded))


def _missing_pre_impl_phases(event: dict, profile: dict, snapshot: dict, risk: str,
                             fast_state=None):
    """구현 전 의무 phase 중 문서가 없는 것 목록. pdca 비활성이면 None(=강제 안 함).

    빈 리스트 = 강제 활성이나 결핍 없음(또는 해당 레벨 요구 phase 없음). 비어있지 않으면 결핍.
    phase 문서 존재는 path basename + Cycle-Stem exact binding으로 판정한다.

    결핍 판정은 "문서가 없다" 와 "다른 사이클을 보고 있다" 를 구분하지 못한다. 어느 쪽인지는
    stem 의 출처가 알려주므로, 안내는 decide() 가 스탬프한 cycle_source 로 분기한다.
    """
    cfg = _pdca_cfg(profile)
    if cfg is None:
        return None
    required = (cfg.get("pre_implementation_required") or {}).get(risk) or []
    if not required:
        return []
    if (fast_state is not None and set(required).issubset({"00", "01", "02", "03"})
            and _fast_covers_required(fast_state, required)):
        return []
    phase_docs = snapshot.get("phase_docs") or {}
    binding = cycle_binding.resolve(event, snapshot, cfg)
    if binding.get("error"):
        return ["cycle-binding"]
    # 게이트가 요구하는 문서를 게이트가 막는 교착을 푼다 — L2/L3 를 선언하면 계획 문서 편집도 그
    # 위험도로 올라가므로, 01 을 만들려는 쓰기가 02·03 부재로 차단된다. 면제 조건은 둘 다 성립할
    # 때뿐이다: 변경이 **전부** phase 문서이고(`_phase_only_change` 는 `all()` 이라 소스가 한 줄
    # 이라도 섞이면 거짓 — `_is_phase_write` 의 `any()` 를 쓰면 방향이 뒤집혀 차단 전체가 꺼진다),
    # 그 phase 들이 **요구 목록 안**일 것. 04~06 은 자기 자신을 요구하지 않아 교착이 없으므로
    # 면제 근거도 없다 — 면제하면 01·02·03 이 하나도 없는 채로 승인 문서를 먼저 쓸 수 있다.
    # stem 검사를 따로 하지 않는 이유는 위 결속이 이미 그것이기 때문이다 — 다른 stem 문서가 섞이면
    # resolve 가 후보 두 개로 실패해 여기 도달하지 못한다.
    if _phase_only_change(event, cfg) and _changed_phase_ids(event, cfg) <= set(required):
        return []
    missing = []
    for pid in required:
        _doc, error = cycle_binding.select_document(phase_docs.get(pid) or [], binding["stem"])
        if error:
            missing.append(pid)
    return missing


CONVERTED_ENTRY_MODE = "FAST-CONVERTED"


def _opener_contract_issue(state):
    """이 run 이 Fast 계약을 실제로 세웠는가 — 조회·무결성과 **같은** 판정을 쓴다.

    이전에는 게이트가 `clean`/`chain_ok`/`seq_ok`/`terminal` 네 조건을 직접 세었다. 그 넷은
    감사 파일이 말이 되는가만 보고 opener 가 담보를 실었는가는 보지 않는다. 그래서 `ts` 를
    지우고 해시를 다시 계산한 감사가 조회에서는 손상, 게이트에서는 정상 Fast 였고 — 느슨한
    쪽이 게이트였으므로 그 상태가 Standard 01·02 면제를 받았다.

    계약 모듈을 부르지 못하면 통과시키지 않는다. 판정할 근거가 없는 것은 통과가 아니다.
    """
    try:
        from fast_cycle_contract import opener_run_issues
    except Exception as exc:
        return f"Fast opener contract unavailable: {type(exc).__name__}: {exc}"
    issues = opener_run_issues(state)
    if not issues:
        return None
    detail = "; ".join(f"{code}({', '.join(f'{k}={v}' for k, v in args.items())})" if args
                       else code for code, args in issues[:3])
    return "active Fast run opener contract is invalid: " + detail


def _fast_policy_floor_issue(policy, state):
    """감사에 고정된 Fast 계약이 profile 정책의 **하한**을 실제로 만족하는가.

    `open_issues` 는 Fast Plan 문서와 감사 open snapshot 이 **서로** 일치하는지만 본다. 둘이
    같은 값을 담고 있으면 그 값이 정책 하한 아래여도 통과한다 — 리뷰를 하나도 요구하지 않는
    0 round Fast 계약이 Standard 01·02 면제를 받는 통로가 여기였다. 문서끼리의 일치와 정책
    충족은 다른 질문이고, 앞의 것만 물으면 뒤의 것은 아무도 묻지 않는다.

    level 이 정책에 없으면 하한을 알 수 없다 — 그 상태를 통과로 읽지 않는다.
    """
    level = state.get("fast_review_level")
    rounds_by_level = policy.get("minimum_rounds")
    lenses_by_level = policy.get("minimum_lenses")
    if not isinstance(rounds_by_level, dict) or not isinstance(lenses_by_level, dict):
        return "pdca.fast_cycle policy is missing minimum_rounds/minimum_lenses"
    if level not in rounds_by_level or level not in lenses_by_level:
        return f"Fast review level {level!r} is not declared in pdca.fast_cycle policy"
    rounds = state.get("minimum_rounds")
    if type(rounds) is not int or rounds < rounds_by_level[level]:
        return (f"Fast run minimum_rounds={rounds!r} is below the policy floor "
                f"{rounds_by_level[level]!r} for level {level!r}")
    lenses = state.get("lenses")
    if not isinstance(lenses, (list, tuple)):
        return f"Fast run lenses must be a list (found {type(lenses).__name__})"
    candidates = (policy.get("lenses") or {}).get(level)
    if not isinstance(candidates, (list, tuple)):
        return f"pdca.fast_cycle.lenses is missing level {level!r}"
    # 개수만 세면 `['anything', 'goes']` 나 같은 렌즈를 두 번 적은 기록이 하한을 만족한다.
    # 하한이 말하는 것은 "서로 다른, 정책이 선언한 렌즈 몇 개로 봤는가" 다. 실제 CLI 는 언제나
    # `candidates[:n]` 을 싣는다 — 중복도 미선언 렌즈도 만들 수 없다.
    declared = {lens for lens in lenses if lens in candidates}
    if len(declared) < lenses_by_level[level]:
        return (f"Fast run has {len(declared)} distinct declared lenses, below the policy floor "
                f"{lenses_by_level[level]!r} for level {level!r}")
    unknown = sorted({str(lens) for lens in lenses if lens not in candidates})
    if unknown:
        return (f"Fast run lenses are not declared in pdca.fast_cycle.lenses[{level!r}]: "
                f"{unknown[:3]}")
    return None


def _converted_fast_state(audit, converted, stem, content, cfg):
    """Standard→Fast 로 전환된 run 의 상태. Fast Plan 문서를 요구하지 않는다.

    전환은 문서를 쓰지 않기로 한 상태 전이라(설계 §6.5) Phase 00 은 그대로 Standard 문서다. 이 경로를
    fresh Fast 의 `parse_fast_plan()` 에 통과시키려 하면 전환 직후 그 사이클의 모든 governed 쓰기가
    막히고, 유일한 해소책이 설계가 금지한 composite 00 재작성이 된다.

    결속은 문서 안 marker 가 아니라 **exact cycle stem + 유일한 active converted run** 이다. 대신
    문서에서 읽을 수 있는 하나 — Phase 00 의 실제 위험도 — 는 감사 open snapshot 과 대조한다.
    """
    # 비교 대상은 **같은 stem 의** active run 이다. 전역 active 와 비교하면 다른 사이클이 남긴
    # run 하나가 이 사이클의 모든 쓰기를 막고, 진단문은 관계없는 run 을 가리킨다.
    stem_active = [rid for rid in (audit.get("active") or [])
                   if (audit.get("runs") or {}).get(rid, {}).get("cycle_stem") == stem]
    if len(converted) != 1 or len(converted) != len(stem_active):
        return None, (f"converted Fast run binding is ambiguous: converted={converted}, "
                      f"active={audit.get('active')}")
    policy = cfg.get("fast_cycle")
    if not isinstance(policy, dict) or policy.get("enabled") is not True:
        return None, "pdca.fast_cycle.enabled=true is required for a converted Fast run"
    if audit.get("snapshot_error"):
        return None, f"Fast audit snapshot failed: {audit['snapshot_error']}"
    if audit.get("file_ok") is not True:
        details = "; ".join(str(item) for item in (audit.get("file_issues") or [])[:3])
        return None, f"Fast audit file integrity failed: {details or 'cause unavailable'}"
    state = (audit.get("runs") or {}).get(converted[0]) or {}
    if state.get("terminal"):
        return None, "active converted Fast run integrity/state is invalid"
    opener_issue = _opener_contract_issue(state)
    if opener_issue:
        return None, opener_issue
    if state.get("cycle_stem") != stem:
        return None, "converted Fast run is bound to another cycle stem"
    declaration = risk_declaration.parse(content)
    if declaration.status != "valid":
        return None, ("converted Fast run requires a readable Phase 00 Risk Level: "
                      + _risk_declaration_detail(declaration))
    if state.get("actual_risk") != declaration.tier:
        return None, (f"actual risk differs between Phase 00 ({declaration.tier}) and the "
                      f"converted audit snapshot ({state.get('actual_risk')})")
    floor_issue = _fast_policy_floor_issue(policy, state)
    if floor_issue:
        return None, floor_issue
    return state, None


def _fast_cycle_state(event, profile, snapshot, cfg):
    """Return (active state or None, blocking detail or None)."""
    phase_docs = snapshot.get("phase_docs") or {}
    binding = cycle_binding.resolve(event, snapshot, cfg)
    if binding.get("error"):
        return None, None
    doc, selection_error = cycle_binding.select_document(phase_docs.get("00") or [], binding["stem"])
    if selection_error or not doc:
        return None, None
    content = doc.get("content") or ""
    try:
        from fast_cycle_contract import open_issues, parse_fast_plan
        plan, parse_issues = parse_fast_plan(content)
    except Exception as exc:
        return None, f"Fast Plan parser failure: {type(exc).__name__}: {exc}"
    audit = snapshot.get("fast_cycle_audit") or {}
    matching_active = [run_id for run_id in (audit.get("active") or [])
                       if (audit.get("runs") or {}).get(run_id, {}).get("cycle_stem") == binding["stem"]]
    runs = audit.get("runs") or {}
    converted = [run_id for run_id in matching_active
                 if runs.get(run_id, {}).get("entry_mode") == CONVERTED_ENTRY_MODE]
    if converted:
        return _converted_fast_state(audit, converted, binding["stem"], content, cfg)
    mode = plan.metadata.get("Cycle-Mode") if plan is not None else None
    if mode != "FAST" and not matching_active:
        return None, None
    if plan is None or parse_issues:
        return None, "composite Fast Plan invalid: " + "; ".join(parse_issues[:3])
    policy = cfg.get("fast_cycle")
    if not isinstance(policy, dict) or policy.get("enabled") is not True:
        return None, "pdca.fast_cycle.enabled=true is required for Cycle-Mode FAST"
    if audit.get("snapshot_error"):
        return None, f"Fast audit snapshot failed: {audit['snapshot_error']}"
    if audit.get("file_ok") is not True:
        details = "; ".join(str(item) for item in (audit.get("file_issues") or [])[:3])
        return None, f"Fast audit file integrity failed: {details or 'cause unavailable'}"
    run_id = plan.metadata.get("Fast-Audit-Run")
    if len(matching_active) != 1 or matching_active[0] != run_id:
        return None, f"Fast Plan run binding mismatch: plan={run_id!r}, active={matching_active}"
    state = (audit.get("runs") or {}).get(run_id) or {}
    if state.get("terminal"):
        return None, "active Fast run integrity/state is invalid"
    opener_issue = _opener_contract_issue(state)
    if opener_issue:
        return None, opener_issue
    level = state.get("fast_review_level")
    lenses = state.get("lenses") or []
    issues = open_issues(
        plan, stem=binding["stem"], level=level, lens_count=len(lenses),
        reason=state.get("reason"), minimum_rounds=state.get("minimum_rounds"),
        lenses=lenses, require_pending_phase4=False)
    if state.get("actual_risk") != plan.metadata.get("Risk Level"):
        issues.append("actual risk differs between Fast Plan and audit open snapshot")
    floor_issue = _fast_policy_floor_issue(policy, state)
    if floor_issue:
        issues.append(floor_issue)
    if issues:
        return None, "; ".join(issues[:5])
    return state, None


def _final_status(content):
    """Read one anchored status declaration outside Markdown code fences."""
    statuses = []
    for raw in _non_fenced_lines(content):
        line = _structured_declaration_line(raw)
        match = re.fullmatch(r"Final\s+Status\s*:\s*([A-Za-z][A-Za-z0-9_-]*)", line, re.IGNORECASE)
        if match:
            statuses.append(match.group(1).upper())
    if len(statuses) != 1:
        return None, f"Final Status declaration must appear exactly once (found {len(statuses)})"
    return statuses[0], None


def _non_fenced_lines(content):
    yield from cycle_binding.non_fenced_lines(content)


def _structured_line(raw):
    """Remove Markdown emphasis around a governance label, preserving its value."""
    return _STRUCTURED_LABEL_EMPHASIS_RE.sub(
        lambda match: f"{match.group('label')}{match.group('colon')}", raw or "")


def _structured_declaration_line(raw):
    """Normalize label emphasis and one whole-declaration emphasis wrapper."""
    line = _structured_line(raw).strip()
    for marker in ("***", "___", "**", "__", "*", "_"):
        if len(line) > len(marker) * 2 and line.startswith(marker) and line.endswith(marker):
            return line[len(marker):-len(marker)].strip()
    return line


def _risk_declaration_detail(declaration):
    """무엇이 왜 틀렸는지와 몇 번째 줄인지 — 선언을 고칠 수 있는 최소 정보."""
    reason = {
        "missing": "Phase 00 헤더에 Risk Level 선언 없음",
        "duplicate": "Risk Level 선언이 헤더에 둘 이상",
        "placeholder": "Risk Level 이 선택지 나열(placeholder)",
        "malformed": "Risk Level 선언 문법 오류",
    }.get(declaration.status, "Risk Level 선언이 L1~L3 이 아님")
    if declaration.line is None:
        return reason
    return f"{reason} (line {declaration.line}: {declaration.excerpt})"


def _bound_phase00_risk(event, profile, snapshot):
    """현재 cycle의 authoritative Phase 00 risk 선언을 구조화한다."""
    cfg = _pdca_cfg(profile)
    if cfg is None:
        return {"status": "missing", "risk": None, "path": "", "detail": "PDCA 비활성", "cycle_stem": ""}
    binding = cycle_binding.resolve(event, snapshot, cfg)
    if binding.get("error"):
        return {"status": "ambiguous", "risk": None, "path": "",
                "detail": binding["error"], "cycle_stem": ""}
    docs = (snapshot.get("phase_docs") or {}).get("00") or []
    unreadable = next(
        (doc for doc in docs if not isinstance((doc or {}).get("content"), str)),
        None,
    )
    if unreadable is not None:
        return {"status": "invalid", "risk": None, "path": unreadable.get("path") or "",
                "detail": "읽을 수 없는 Phase 00 snapshot content",
                "cycle_stem": binding["stem"]}
    doc, error = cycle_binding.select_document(docs, binding["stem"])
    if error:
        status = "ambiguous" if "ambiguous" in error.lower() else "missing"
        return {"status": status, "risk": None, "path": "",
                "detail": error, "cycle_stem": binding["stem"]}
    path = (doc or {}).get("path") or ""
    declaration = risk_declaration.parse((doc or {}).get("content") or "")
    # tier 정책은 소비자 몫이다 — 파서는 L0 도 인식해 돌려주지만, 사이클 결속의 authoritative
    # 선언은 L1~L3 다. L0 선언은 여기서 잘못된 선언과 같게 막는다.
    if declaration.status != "valid" or declaration.tier not in ("L1", "L2", "L3"):
        return {"status": "invalid", "risk": None, "path": path,
                "detail": _risk_declaration_detail(declaration), "cycle_stem": binding["stem"]}
    return {"status": "valid", "risk": declaration.tier, "path": path,
            "detail": "", "cycle_stem": binding["stem"]}


def _leaves_the_path(change):
    """이 변경 뒤 그 경로가 비는가 — 삭제이거나, 다른 경로로 옮겨 나가는 원본이거나.

    move 는 source(`Update File`) + destination(`Move to`) 두 변경으로 표현된다. source 를
    일반 수정으로 두면 사라질 문서의 선언과 본문이 최종 문서처럼 검사돼, 잘못 선언된 문서를
    사이클 밖으로 빼내는 정리 작업이 영구히 막힌다 — phase 문서 집합 관점에서 move-out 은
    그냥 삭제다. 목적지가 여전히 사이클 문서면 그쪽이 같은 stem 으로 잡히므로 검사는
    목적지에서 계속된다.

    **경로가 아니라 변경 단위로 묻는다.** 같은 패치가 비운 경로에 새 문서를 다시 만들 수 있고
    (`Move to` 뒤의 `Add File`), 경로로 기억한 배제는 그 새 문서까지 검사에서 빼버린다.
    변경 순서대로 적용하면 나중 변경이 최종 상태를 이긴다.
    """
    return (change or {}).get("op") == "delete" or bool((change or {}).get("move_destination"))


def _overlay_changed_documents(event, cfg, stem, documents):
    """디스크 문서 집합 위에 **이번 변경 후**의 문서를 덮어쓴다(제자리 수정).

    snapshot 은 쓰기 **전** 상태다. 그것만 비교하면 이번 쓰기가 바꾸는 선언 자체가 검사에서
    빠져, en 사이클의 문서를 ko 로 되돌리는 쓰기가 통과한다 — 막으려는 바로 그 상태다.
    삭제는 집합에서 뺀다. 지워지는 문서의 선언으로 남은 문서를 막으면 잘못 선언된 문서를
    치우는 일이 영구히 불가능해진다.

    재구성 실패(post_image_error)는 여기서 조각을 문서로 오해하지 말고 그냥 둔다 — 그 실패는
    본문 게이트가 fail-closed 로 잡는다. 여기서 조각을 읽으면 정상 부분 편집이 전부 marker
    미선언으로 떨어진다.
    """
    globs = [item.get("glob") or "" for item in ((cfg or {}).get("phases") or [])]
    for change in (event.get("changes") or []):
        path = (change or {}).get("path") or ""
        if not path or cycle_binding.path_stem(path) != stem:
            continue
        if not any(cycle_binding.matches_glob(path, glob) for glob in globs if glob):
            continue
        if _leaves_the_path(change):
            documents.pop(path, None)
            continue
        image, error = _post_image(change)
        if error is None and image is not None:
            documents[path] = image


def _document_language_gate(event, profile, snapshot, stem):
    """같은 사이클 문서들의 `Document-Language` 일관성 판정.

    Phase 00 마커가 정본이고 `.sage/cycle.json` 은 재개 편의를 위한 미러다. 둘이 다르면 파일이
    이기는 게 아니라 hard conflict 이고, 게이트는 어느 쪽도 자동으로 고치지 않는다 — 고르는
    순간 사용자가 선언하지 않은 언어로 사이클 절반이 계속 쓰인다.

    **부재는 차단하지 않는다.** 마커 이전에 시작한 사이클이 전부 즉시 막히는 과차단이 되고,
    그건 이 기능이 만든 결함이다. 대신 **일부만 선언한 상태**는 WARN 으로 드러낸다. 부분 이관과
    완전 이관이 똑같이 조용하면 마이그레이션이 언제 끝났는지 셀 수 없다.

    손상(중복 선언·미지원 값)과 불일치는 차단한다. 이건 부재가 아니라 선언이 서로 다른 답을
    내는 상태이고, 그 위에서 쓴 문서는 어느 언어로 검토돼야 하는지 정할 수 없다.

    반환: None(해당없음) | {"status": "block"|"warn", "reason": str}
    """
    if _pdca_cfg(profile) is None or not stem:
        return None
    try:
        import document_language
    except Exception:
        return None                     # 모듈 부재 → 판정 불가, 다른 게이트에 맡김

    documents = {}
    for docs in (snapshot.get("phase_docs") or {}).values():
        for doc in docs or []:
            path = (doc or {}).get("path") or ""
            if path and cycle_binding.path_stem(path) == stem:
                documents.setdefault(path, doc.get("content") or "")
    _overlay_changed_documents(event, _pdca_cfg(profile), stem, documents)
    if not documents:
        return None

    declared = event.get("cycle_document_language")
    issues = document_language.consistency_issues(documents, declared)
    if not issues:
        return None

    missing = sorted(path for path, reason in issues if reason == document_language.MISSING)
    if len(missing) == len(issues):
        # 전부 미선언이고 미러도 비었으면 마커 도입 이전의 사이클이다 — legacy 로 통과시킨다.
        if len(missing) == len(documents) and declared is None:
            return None
        return {"status": "warn",
                "reason": (f"문서 언어 선언 누락 {len(missing)}건 — {', '.join(missing[:3])}"
                           f"{' 외' if len(missing) > 3 else ''}")}

    hard = sorted((path, reason) for path, reason in issues
                  if reason != document_language.MISSING)
    detail = "; ".join(f"{path}: {reason}" for path, reason in hard[:3])
    return {"status": "block", "reason": f"문서 언어 선언 충돌 {len(hard)}건 — {detail}"}


def _resolved_document_language(event, snapshot, stem):
    """이 stem 의 판정된 문서 언어. 마커가 없거나 손상됐으면 None(무엇에 맞춰 볼지 미정의).

    declared 미러가 유효하면 그것을 쓴다. 없으면 이 stem 의 Phase 00 자기 marker 를 읽는다 —
    Phase 00 이 정본이라는 계약(§7.6)과 같은 우선순위를 여기서도 지킨다. 위쪽 `_document_language_gate`
    가 이미 hard conflict 를 차단했으므로, 여기 도달했다면 두 값이 있어도 서로 다르지 않다.
    """
    declared = event.get("cycle_document_language")
    if declared in ("ko", "en"):
        return declared
    try:
        import document_language
    except Exception:
        return None
    for doc in (snapshot.get("phase_docs") or {}).get("00") or []:
        path = (doc or {}).get("path") or ""
        if path and cycle_binding.path_stem(path) == stem:
            language, problem = document_language.scan(doc.get("content") or "")
            if not problem:
                return language
    return None


def _post_image(change):
    """이 변경 후의 **전체 본문**. (image, error) — 둘 중 하나만 채워진다.

    Write / apply_patch Add File 은 content 가 곧 전체 본문이고(`full_content`), 부분 diff
    (Claude Edit/MultiEdit, apply_patch Update File)는 오케스트레이터가 디스크 원본에 적용해
    되짚어 싣는다. 되짚기가 실패했으면 `post_image_error` 가 실려 오고, 그건 조용히 넘길 일이
    아니다 — 최종 문서를 모르는 채 통과시키면 그 경로가 곧 검사 우회로가 된다.
    """
    if change.get("full_content"):
        return change.get("content"), None
    return change.get("post_image"), change.get("post_image_error")


def _prose_diagnostic(key, path, line=0, evidence=""):
    """언어 중립 진단. 문장이 아니라 위치와 원문 조각뿐이다 — 사람이 읽는 설명은 표시 계층
    catalog 가 소유한다. 여기서 한국어 문장을 만들면 표시 언어가 en 인 화면에 그대로 실려 나간다."""
    return {"key": key, "path": path, "line": line, "evidence": evidence}


def _document_prose_gate(event, snapshot, stem):
    """이번 write 로 새로 생기거나 바뀌는 이 stem 의 본문이 선언 언어를 어겼는가.

    두 질문의 전제가 다르므로 입력도 갈라 쓴다.
    - en 문서에 **새로 들어오는** 한국어: 이번에 추가되는 내용만 보면 된다. 디스크의 legacy
      문서까지 소급하면 문서 하나가 이후 모든 편집을 막는 과차단이 된다(§7.7).
    - ko 문서에 한국어가 **아예 없다**: 변경 후 전체 본문이 있어야만 물을 수 있다. 부분 diff 에
      물으면 정상 문서에 영어 조각을 더하는 편집이 곧바로 차단된다.

    Markdown 문서(.md)만 본다. 이 모듈의 규칙은 전부 Markdown 구조(fence·inline code·blockquote)
    이고, 같은 stem 의 소스 파일에 적용하면 주석 한 줄로 차단되거나 되짚기 실패로 막힌다.

    반환: None(해당없음/통과) | 언어 중립 진단 dict
    """
    language = _resolved_document_language(event, snapshot, stem)
    if language not in ("ko", "en"):
        return None
    try:
        import prose_language
    except Exception as exc:
        # 판정 정본이 사라졌는데 통과시키면, 파일 하나를 지우는 것이 이 게이트를 끄는 스위치가 된다.
        # 설명 문장은 catalog 가 소유한다 — 여기서는 원문 예외를 evidence 로만 넘긴다.
        return _prose_diagnostic("block_prose_scanner_unavailable", "",
                                 evidence=f"{type(exc).__name__}: {exc}")
    for change in (event.get("changes") or []):
        path = (change or {}).get("path") or ""
        if not path or not path.endswith(".md") or cycle_binding.path_stem(path) != stem:
            continue
        if _leaves_the_path(change):
            continue           # 최종 상태에 남지 않는다 — 판정은 목적지·후속 변경에서 이어진다
        image, error = _post_image(change)
        if error:
            return _prose_diagnostic("block_document_post_image", path, evidence=error)
        if image is None:
            # 쓰기는 하는데 변경 후 문서를 알 수 없다 — 모르는 채 통과시키면 그 도구가 우회로다.
            if change.get("content"):
                return _prose_diagnostic("block_document_post_image", path,
                                         evidence="no post-image contract")
            continue
        # 변경 전·후 **전체 문서**를 Markdown 문맥째로 판정하고, 그 차이(이번 변경이 늘린
        # 부채)만 차단한다. 새로 추가된 줄만 보면 fence 한 줄을 지워 안에 있던 한국어가 prose 로
        # 드러나는 경우를 놓치고, 변경 후만 보면 legacy 문서 하나가 이후 모든 편집을 막는다.
        before = change.get("pre_image") or ""
        found = prose_language.new_foreign_prose(before, image, language)
        if found:
            line_no, snippet = found[0]
            return _prose_diagnostic("block_document_prose_language", path,
                                     line=line_no, evidence=snippet[:80])
        unclosed = prose_language.new_unclosed_fence(before, image)
        if unclosed:
            return _prose_diagnostic("block_document_unclosed_fence", path, line=unclosed)
        if prose_language.newly_lacks_native_prose(before, image, language):
            return _prose_diagnostic("block_document_prose_structure", path)
    return None


def _report_gate(event: dict, profile: dict, snapshot: dict):
    """report phase 문서를 쓰는 변경이면 approve phase 의 승인 마커 존재 여부 판정.

    반환: None(비활성/해당없음) | {"approved": bool, "report_phase", "approve_phase"}.
    report/approve phase 미설정이면 None. 06(report) 작성 전 05(approve) APPROVED 강제용.
    """
    cfg = _pdca_cfg(profile)
    if cfg is None:
        return None
    report_phase = cfg.get("report_phase") or ""
    approve_phase = cfg.get("approve_phase") or ""
    if not report_phase or not approve_phase:
        return None
    if not _is_writing_report(event, cfg):
        return None
    binding = cycle_binding.resolve(event, snapshot, cfg)
    if binding.get("error"):
        return {"approved": False, "report_phase": report_phase, "approve_phase": approve_phase,
                "detail": binding["error"]}
    approved, detail, selected = _approval_state(binding["stem"], cfg, snapshot)
    if selected is None:
        return {"approved": False, "report_phase": report_phase, "approve_phase": approve_phase,
                "detail": detail}
    return {"approved": approved, "report_phase": report_phase, "approve_phase": approve_phase,
            "detail": detail, "cycle_stem": binding["stem"]}


def _approval_state(stem, cfg, snapshot):
    """approve phase 문서의 승인 상태 → (approved, detail, selected).

    report 게이트와 완결 판정이 같은 규칙을 쓰게 하는 단일소스다. 갈리면 "06 을 쓸 수 있는데
    완결로는 안 보는" 상태가 생긴다. selected 가 None 이면 문서 선택 자체가 실패한 것이다.
    """
    approve_docs = (snapshot.get("phase_docs") or {}).get(str(cfg.get("approve_phase") or "")) or []
    selected, error = cycle_binding.select_document(approve_docs, stem)
    if error:
        return False, error, None
    marker = (cfg.get("approve_marker") or "APPROVED").upper()
    status, status_error = _final_status(selected.get("content") or "")
    approved = status_error is None and status == marker
    detail = (selected.get("path") if approved else
              f"{selected.get('path')} Final Status 오류: {status_error or f'{status!r} != {marker!r}'}")
    return approved, detail, selected


def _is_writing_report(event, cfg):
    report_phase = cfg.get("report_phase") or ""
    if not report_phase:
        return False
    phases = {str(p.get("id") or ""): p for p in (cfg.get("phases") or [])}
    rglob = (phases.get(str(report_phase)) or {}).get("glob") or ""
    return bool(rglob) and any(cycle_binding.matches_glob(ch.get("path") or "", rglob)
                               for ch in (event.get("changes") or []))


def _acceptance_status_match(line, status):
    needle = re.escape(status.upper())
    return re.search(rf"(?<![A-Z0-9]){needle}(?![A-Z0-9])", line.upper()) is not None


def _cycle_risk(event, profile, snapshot, cfg):
    """06 report event 자체는 L0 문서 변경이라, acceptance 대상 risk 는 cycle 문서에서 보수적으로 추정한다.

    명시 risk 를 찾으면 require_for_risk 에 적용하고, 못 찾으면 unknown 으로 둔다. unknown 은 skip 하지 않는다:
    기존 문서가 risk 라벨을 안 썼다는 이유로 acceptance gate 가 조용히 꺼지는 것을 피하기 위해서다.
    """
    rank = {"L1": 1, "L2": 2, "L3": 3}
    risks = []
    for value in (event.get("declared_max"), snapshot.get("cycle_risk")):
        if value is None:
            continue
        normalized = str(value).upper()
        if normalized not in rank:
            return "unknown"
        risks.append(normalized)
    binding = cycle_binding.resolve(event, snapshot, cfg)
    if binding.get("error"):
        return "unknown"
    phase_docs = snapshot.get("phase_docs") or {}
    for phase in ("00", "01", "02", "03", "04", "05"):
        docs = phase_docs.get(phase) or []
        matching = [doc for doc in docs
                    if cycle_binding.path_stem(doc.get("path") or "") == binding["stem"]]
        if not matching:
            continue
        doc, error = cycle_binding.select_document(docs, binding["stem"])
        if error:
            return "unknown"
        content = (doc or {}).get("content") or ""
        # 선언은 헤더 metadata 영역에만 있다 — 본문 산문의 `Risk Level` 언급을 세면 추정치가
        # 위로 튀어 acceptance 요구가 과해진다. 다만 여러 phase 의 최대를 취하는 보수성은 그대로
        # 둔다: 여기서 낮게 읽는 것이 이 게이트에서는 위험한 방향이다.
        found, error = risk_declaration.scan(content)
        if error is not None:
            # risk 를 쓰려다 만 줄은 조용히 넘기지 않는다 — 넘기면 옆의 낮은 선언이 채택돼
            # acceptance 요구가 실제보다 헐거워진다.
            return "unknown"
        for _line, tier in found:
            # 이 층이 아는 사이클 tier 는 L1~L3 다. `L0` 를 조용히 건너뛰면 옆의 낮은 선언이
            # 채택되거나 선언이 하나도 안 남아, acceptance gate 가 통째로 꺼진다(L1 은
            # required_risks 밖이라 게이트가 실행조차 되지 않는다). `_declared_risk` 와 같은 방향.
            if tier not in rank:
                return "unknown"
            risks.append(tier)
    return max(risks, key=rank.get) if risks else "unknown"


def _section_table_lines(content, heading_words):
    """주어진 heading 아래의 markdown table line 만 반환. 다음 heading 에서 종료."""
    lines, in_section = [], False
    for raw in _non_fenced_lines(content):
        stripped = raw.strip()
        if re.match(r"^#{1,6}\s+", stripped):
            title = stripped.lstrip("#").strip().lower()
            if in_section:
                break
            if any(word.lower() in title for word in heading_words):
                in_section = True
            continue
        if in_section and "|" in stripped:
            lines.append(stripped)
    return lines


def _split_md_row(line):
    cells = [c.strip() for c in line.strip().strip("|").split("|")]
    if not cells or all(not c for c in cells):
        return []
    if all(re.fullmatch(r":?-{3,}:?", c.replace(" ", "")) for c in cells):
        return []
    return cells


def _table_dicts(lines):
    header = None
    rows = []
    for line in lines:
        cells = _split_md_row(line)
        if not cells:
            continue
        if header is None:
            header = [c.lower() for c in cells]
            continue
        row = {header[i]: cells[i] for i in range(min(len(header), len(cells)))}
        row["_raw"] = line
        rows.append(row)
    return rows


def _first_cell(row, names):
    def normalized(value):
        return re.sub(r"[^a-z0-9가-힣]", "", (value or "").lower())

    for name in names:
        target = normalized(name)
        for key, val in row.items():
            if key != "_raw" and normalized(key) == target:
                return val
    for name in names:
        for key, val in row.items():
            if key != "_raw" and name in key:
                return val
    return ""


_ACCEPTANCE_ID_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*\Z")


def _acceptance_id(value):
    raw = (value or "").strip()
    if len(raw) >= 2 and raw[0] == raw[-1] == "`":
        raw = raw[1:-1].strip()
    return raw if _ACCEPTANCE_ID_RE.fullmatch(raw) else ""


def _acceptance_matrix(content):
    rows = _table_dicts(_section_table_lines(content, ["acceptance matrix", "수용", "인수"]))
    all_ids, required_ids, invalid_ids = [], [], []
    for row in rows:
        raw_id = _first_cell(row, ["id", "acceptance"])
        rid = _acceptance_id(raw_id)
        required = (_first_cell(row, ["required", "필수"]) or "yes").strip().lower()
        if raw_id and not rid:
            invalid_ids.append(raw_id.strip())
            continue
        if not rid:
            continue
        all_ids.append(rid)
        if required not in ("no", "false", "n", "optional", "n/a"):
            required_ids.append(rid)
    duplicates = sorted({rid for rid in all_ids if all_ids.count(rid) > 1})
    return {"all": all_ids, "required": required_ids,
            "duplicates": duplicates, "invalid": invalid_ids}


def requirement_id_duplicates(content):
    """01 문서의 요구사항 ID 중복. **acceptance 와 같은 자리에서 fail-closed 다.**

    acceptance parser 는 `A-ID` 만 본다. 그래서 `FR-*` 를 두 번 적어도 아무도 잡지 못하고,
    두 행은 서로 다른 요구를 같은 이름으로 갖게 된다 — 04 가 그 이름 하나를 근거로 대면
    어느 요구를 덮었는지 판정할 수 없다. 실제로 rework 하나가 기존 번호를 다시 써서 세 건이
    중복됐고, 검사가 없어 통과했다.

    표 행의 첫 칸만 본다 — 본문에서 다른 요구를 인용하는 것은 중복이 아니다.
    """
    seen = []
    for line in content.splitlines():
        stripped = line.strip()
        if not stripped.startswith("|"):
            continue
        cells = [cell.strip() for cell in stripped.strip("|").split("|")]
        if not cells:
            continue
        match = re.fullmatch(r"(?:\*\*)?([A-Z]{2,4}-[A-Z]?\d+)(?:\*\*)?", cells[0])
        if match:
            seen.append(match.group(1))
    return sorted({rid for rid in seen if seen.count(rid) > 1})


def _acceptance_matrix_ids(content):
    return _acceptance_matrix(content)["required"]


def _acceptance_evidence_rows(content):
    rows = _table_dicts(_section_table_lines(content, ["acceptance evidence", "acceptance evidence review", "수용", "인수"]))
    out = []
    for row in rows:
        raw_id = _first_cell(row, ["id", "acceptance"])
        rid = _acceptance_id(raw_id)
        status = _first_cell(row, ["status", "상태"])
        reason = " ".join(value for value in (
            _first_cell(row, ["reason", "사유"]),
            _first_cell(row, ["evidence", "근거"]),
            _first_cell(row, ["notes", "note", "비고"]),
        ) if value.strip())
        if raw_id or status:
            out.append({"id": rid, "raw_id": raw_id.strip(), "status": status.strip(),
                        "reason": reason.strip(),
                        "raw": row.get("_raw", "")})
    return out


def _has_na_reason(value):
    normalized = re.sub(r"\s+", " ", (value or "").strip()).lower()
    return normalized not in ("", "-", "--", "n/a", "na", "none", "없음", "해당 없음", "해당없음")


def acceptance_policy(profile, cycle_risk):
    """이 위험도에서 acceptance 를 강제하는가, 그리고 어떤 상태값이 미해결인가.

    06 report 게이트와 리뷰 조기 완료가 **같은 정책 한 벌**을 봐야 한다. 두 자리에 따로 적으면
    06 이 막는 04 상태를 조기 완료가 먼저 통과시키고, 그 뒤 06 에서 막히는 순서만 바뀐다 —
    조기 완료는 사용자가 잔여 위험을 인수하는 자리이지 미검증 요구사항을 넘기는 자리가 아니다.

    반환: `None`(이 위험도에서 강제하지 않음) 또는 `mode`·`statuses`·`unresolved`·`acceptance`.
    """
    verification = profile.get("verification") or {}
    ac = verification.get("acceptance") if isinstance(verification, dict) else None
    if not isinstance(ac, dict) or not ac.get("enabled"):
        return None
    configured_risks = ac.get("require_for_risk")
    required_risks = ({risk for risk in configured_risks if isinstance(risk, str)}
                      if isinstance(configured_risks, list)
                      else {"L2", "L3"})
    # L3 acceptance enforcement is an engine invariant. A profile may opt L1/L2 in or out,
    # but cannot bypass the gate entirely by omitting L3 from require_for_risk.
    required_risks.add("L3")
    if cycle_risk != "unknown" and cycle_risk not in required_risks:
        return None
    effective_risk = "L3" if cycle_risk == "unknown" else cycle_risk
    if "report_gate_by_risk" in ac:
        by_risk = ac.get("report_gate_by_risk")
        if not isinstance(by_risk, dict):
            mode = "enforce"
        else:
            expected = "enforce" if effective_risk == "L3" else "advisory"
            configured = by_risk.get(effective_risk) or expected
            mode = configured if configured == expected else "enforce"
    elif "report_gate_enforce" in ac:
        legacy_mode = ac.get("report_gate_enforce") or "off"
        # Legacy advisory/off cannot weaken the new L3 invariant. Explicit enforce remains
        # a safe upward-compatible policy for every tier; other legacy values migrate to
        # the fixed L2 advisory/L3 enforce defaults at runtime.
        mode = "enforce" if legacy_mode == "enforce" or effective_risk == "L3" else "advisory"
    else:
        mode = "enforce" if effective_risk == "L3" else "advisory"
    if mode not in ("advisory", "enforce"):
        return None

    configured_statuses = ac.get("statuses")
    statuses = ({status.strip().upper() for status in configured_statuses
                 if isinstance(status, str) and status.strip()}
                if isinstance(configured_statuses, list) else set())
    statuses.update({"PASS", "FAIL", "NOT TESTED", "N/A"})
    configured_unresolved = ac.get("unresolved_statuses")
    unresolved = ({status.strip().upper() for status in configured_unresolved
                   if isinstance(status, str) and status.strip()}
                  if isinstance(configured_unresolved, list) else set())
    # FAIL/NOT TESTED are engine-level unresolved states; validation-bypassed profiles
    # cannot remove either one to turn a required acceptance into PASS.
    unresolved.update({"FAIL", "NOT TESTED"})
    # Profiles cannot mint new resolved states. Only PASS and reasoned N/A resolve;
    # every configured extension remains an unresolved, non-waivable status.
    unresolved.update(statuses - {"PASS", "N/A"})
    return {"mode": mode, "statuses": statuses, "unresolved": unresolved, "acceptance": ac}


def acceptance_findings(plan_content, report_content, policy, *, plan_path, report_path):
    """01 matrix 와 04 evidence 대조. `(구조 오류, 미해결 행)`.

    구조 오류는 표를 읽을 수 없다는 뜻이라 판정 자체가 불가능하고, 미해결 행은 읽었는데 통과하지
    못한 요구사항이다. 둘을 한 리스트로 합치면 waiver 를 적용할 수 있는 행과 그렇지 않은 상태를
    호출부가 구분하지 못한다.
    """
    statuses = policy["statuses"]
    unresolved = policy["unresolved"]
    matrix = _acceptance_matrix(plan_content)
    required_ids = matrix["required"]
    evidence_rows = _acceptance_evidence_rows(report_content)
    if matrix["invalid"]:
        return [f"선택된 01 문서({plan_path})에 invalid acceptance ID: {matrix['invalid']}"], []
    if matrix["duplicates"]:
        return [f"선택된 01 문서({plan_path})에 duplicate acceptance ID: {matrix['duplicates']}"], []
    requirement_duplicates = requirement_id_duplicates(plan_content)
    if requirement_duplicates:
        # 같은 이름을 가진 두 요구사항은 04 가 근거를 대는 순간 어느 쪽을 덮었는지 알 수
        # 없게 만든다. A-ID 만 보던 검사가 놓쳐 온 자리다.
        return [f"선택된 01 문서({plan_path})에 duplicate requirement ID: "
                f"{requirement_duplicates}"], []
    if not matrix["all"]:
        return [f"선택된 01 문서({plan_path or '미선택'})에 acceptance matrix ID 없음"], []
    if not evidence_rows:
        return [f"선택된 04 문서({report_path})에 acceptance evidence table 없음"
                "(PASS/FAIL/NOT TESTED/N/A 필요)"], []
    known_statuses = set(statuses)
    unresolved_rows = []
    seen_ids = []
    for row in evidence_rows:
        rid = row.get("id") or ""
        raw_id = row.get("raw_id") or ""
        status = (row.get("status") or "").upper()
        if raw_id and not rid:
            unresolved_rows.append({"id": rid, "status": status, "waivable": False,
                                    "detail": f"{row.get('raw')} (acceptance ID 형식 오류: {raw_id!r})"})
        elif not rid:
            unresolved_rows.append({"id": rid, "status": status, "waivable": False,
                                    "detail": f"{row.get('raw')} (acceptance ID 누락)"})
        else:
            seen_ids.append(rid)
        if not status or status not in known_statuses:
            unresolved_rows.append({"id": rid, "status": status, "waivable": False,
                                    "detail": f"{row.get('raw')} (상태값 미인식: {row.get('status')!r})"})
        elif status in unresolved and rid in required_ids:
            unresolved_rows.append({"id": rid, "status": status,
                                    "waivable": status == "NOT TESTED",
                                    "detail": row.get("raw") or f"{rid}: {status}"})
        elif status == "N/A" and not _has_na_reason(row.get("reason")):
            unresolved_rows.append({"id": rid, "status": status, "waivable": False,
                                    "detail": f"{row.get('raw')} (N/A 사유 누락)"})
    duplicate_evidence = sorted({rid for rid in seen_ids if seen_ids.count(rid) > 1})
    if duplicate_evidence:
        return [f"04 acceptance evidence duplicate ID: {duplicate_evidence}"], []
    unknown_ids = sorted(set(seen_ids) - set(matrix["all"]))
    if unknown_ids:
        return [f"04 acceptance evidence 에 01 matrix 미정의 ID: {unknown_ids}"], []
    missing_ids = [rid for rid in required_ids if rid not in set(seen_ids)]
    if missing_ids:
        return [f"04 acceptance evidence 에 01 matrix required ID 누락: {missing_ids}"], []
    return [], unresolved_rows


def acceptance_waiver_split(unresolved_rows, policy, cycle_risk, stem, waiver_summary, *,
                            report_path):
    """미해결 행을 `(여전히 막는 것, exact waiver 로 잔여가 된 것)` 으로 가른다.

    waiver 는 상태를 PASS 로 바꾸지 않는다 — L3 에서 명시 승인된 `NOT TESTED` 하나를 잔여 위험
    표기로 옮길 뿐이다. FAIL·형식 오류·미인식 상태는 어떤 승인으로도 옮겨지지 않는다.
    """
    ac = policy["acceptance"]
    waiver_cfg = ac.get("waiver") if isinstance(ac.get("waiver"), dict) else {}
    can_consider_waiver = cycle_risk == "L3" and waiver_cfg.get("enabled") is True
    waiver_uses, remaining = [], []
    for row in unresolved_rows:
        if not (can_consider_waiver and row["waivable"] and row["id"]):
            remaining.append(row)
            continue
        if not waiver_summary.get("valid"):
            issues = "; ".join((waiver_summary.get("issues") or [])[:3]) or "unknown audit error"
            remaining.append(dict(row, detail=f"{row['detail']} (waiver audit invalid: {issues})"))
            continue
        matches = [grant for grant in (waiver_summary.get("active") or [])
                   if isinstance(grant, dict)
                   and grant.get("cycle_stem") == stem
                   and grant.get("acceptance_id") == row["id"]]
        if len(matches) != 1:
            if len(matches) > 1:
                row = dict(row, detail=f"{row['detail']} (conflicting exact waivers: {len(matches)})")
            remaining.append(row)
            continue
        grant = matches[0]
        required_grant_fields = ("waiver_id", "reason", "scope", "remaining_evidence", "confirmed_by")
        if any(not isinstance(grant.get(field), str) or not grant.get(field).strip()
               for field in required_grant_fields):
            remaining.append(dict(row, detail=f"{row['detail']} (malformed exact waiver)"))
            continue
        waiver_uses.append(dict(grant, report_path=report_path))
    return remaining, waiver_uses


def _acceptance_gate(event, profile, snapshot):
    """06 작성 시 04 acceptance evidence와 exact L3 waiver를 검사한다.

    SAGE 의 실패 모드: build/test/review 는 통과했지만 명시 요구사항이 미검증/미구현. 이 gate 는
    04 문서의 구조화된 상태만 확인한다. Waiver는 exact L3 ``NOT TESTED``를 residual WARN으로
    바꿀 뿐 PASS로 만들지 않으며, FAIL/unknown risk/invalid audit에는 적용되지 않는다.
    """
    cfg = _pdca_cfg(profile)
    if cfg is None or not _is_writing_report(event, cfg):
        return None
    cycle_risk = _cycle_risk(event, profile, snapshot, cfg)
    policy = acceptance_policy(profile, cycle_risk)
    if policy is None:
        return None
    mode = policy["mode"]
    ac = policy["acceptance"]
    phase_docs = snapshot.get("phase_docs") or {}
    docs01 = phase_docs.get("01") or []
    docs04 = phase_docs.get("04") or []
    binding = cycle_binding.resolve(event, snapshot, cfg)

    def fail(detail):
        return {"ok": False, "mode": mode, "detail": detail}

    if binding.get("error"):
        return fail(f"cycle binding 실패: {binding['error']}")
    plan_doc, plan_error = cycle_binding.select_document(docs01, binding["stem"])
    if plan_error:
        return fail(f"01 선택 실패: {plan_error}")
    sel, sel_error = cycle_binding.select_document(docs04, binding["stem"])
    if sel_error:
        return fail(f"04 선택 실패: {sel_error}")
    plan_path = plan_doc.get("path")
    sel_path = sel.get("path")
    structural, unresolved_rows = acceptance_findings(
        (plan_doc or {}).get("content") or "", sel.get("content") or "",
        policy, plan_path=plan_path, report_path=sel_path)
    if structural:
        return fail(structural[0])
    if unresolved_rows:
        waiver_summary = snapshot.get("acceptance_waivers") or {
            "valid": True, "issues": [], "active": []}
        remaining, waiver_uses = acceptance_waiver_split(
            unresolved_rows, policy, cycle_risk, binding["stem"], waiver_summary,
            report_path=sel_path)
        if remaining:
            lines = [row["detail"] for row in remaining]
            preview = "; ".join(lines[:3])
            more = "" if len(lines) <= 3 else f"; ... 외 {len(lines) - 3}건"
            return fail(f"선택된 04 문서({sel_path})에 미해결 acceptance 존재: {preview}{more}")
        if waiver_uses:
            residual = "; ".join(
                f"{grant['acceptance_id']} NOT TESTED, waiver={grant['waiver_id']}, "
                f"reason={grant['reason']}, scope={grant['scope']}, remaining={grant['remaining_evidence']}"
                for grant in waiver_uses)
            return {"ok": False, "mode": "advisory", "waived": True,
                    "detail": f"L3 명시 waiver 적용(상태는 PASS 아님): {residual}",
                    "waiver_uses": waiver_uses}
    return {"ok": True, "mode": mode, "detail": sel_path}


_REDUCED_ASSURANCE_MARKERS = ("Review-Assurance", "Review-Close-Reason", "Review-Rounds",
                              "Residual-Findings")
EARLY_CLOSE_REASON = "USER_AUTHORIZED_EARLY"
REVIEW_ASSURANCE_REDUCED = "REDUCED_BY_USER_AUTHORIZATION"


def _marker_values(content, label):
    """fence 밖에서 `Label: value` 를 전부 모은다. 정확히 1개인지는 호출부가 판정한다."""
    pattern = re.compile(rf"^{re.escape(label)}\s*[:：]\s*(.+?)\s*$", re.IGNORECASE)
    values = []
    for raw in _non_fenced_lines(content or ""):
        match = pattern.match(_structured_declaration_line(raw))
        if match:
            values.append(match.group(1).strip())
    return values


_CONFIGURED_MAX_RE = re.compile(
    r"^\s*\d+\s+\(\s*configured max\s*:\s*([^)]*?)\s*\)\s*$", re.IGNORECASE)


def _configured_max_issues(declared, run):
    """`Review-Rounds` 의 상한 표기가 감사 레코드와 같은가.

    라운드 수만 대조하면 "몇 번 중 몇 번" 의 분모가 검증에서 빠진다. `1 (configured max: 999)` 처럼
    상한을 부풀리거나 `1 (configured max: 1)` 로 낮춰 적으면, 실제로 얼마나 건너뛴 리뷰인지가 문서만
    보는 사람에게 다르게 읽힌다 — 네 표기를 강제하는 이유가 바로 그 판별이다.

    레코드에 상한이 없으면 대조 기준이 없으므로 넘긴다. 조기 종료를 기록하는 경로는 이 값을 항상
    남기지만, 그 사실을 여기서 다시 요구하면 손상된 레코드 하나가 진단을 상한 이야기로 바꾼다 —
    감사 손상은 `integrity_issues` 가 말할 일이다.
    """
    ceiling = run.get("configured_max_iterations")
    if ceiling is None:
        return []
    expected = str(ceiling)
    match = _CONFIGURED_MAX_RE.fullmatch(declared or "")
    if match is None:
        return [f"Review-Rounds 상한 표기가 정확한 형식이 아님"
                f"(감사 기록: configured max: {expected})"]
    if match.group(1) != expected:
        return [f"Review-Rounds 의 상한이 감사 기록과 다름: 문서={match.group(1)!r} 감사={expected!r}"]
    return []


def _reduced_assurance_issues(content, run):
    """05 문서의 보증 저하 표기와 terminal 감사 레코드가 정확히 일치하는가.

    한쪽만 있으면 차단한다. 문서에만 있으면 감사 없이 보증 저하를 자칭한 것이고, 감사에만 있으면
    조기 종료로 닫힌 run 이 일반 승인처럼 06 으로 넘어간다. 둘 다 사후 판별을 불가능하게 만든다.
    """
    early = (run.get("close_reason") or "") == EARLY_CLOSE_REASON
    found = {label: _marker_values(content, label) for label in _REDUCED_ASSURANCE_MARKERS}
    if not early:
        # `Review-Rounds`·`Residual-Findings` 는 일반 리뷰 문서에도 자연스럽게 적히는 중립 표기다.
        # 표기의 존재를 트리거로 쓰면 기존 05 문서가 그 한 줄 때문에 막힌다. 차단해야 하는 것은
        # 문서가 감사와 다르게 보증 저하를 **자칭**하는 경우뿐이다.
        claimed = [f"{label}: {value}" for label, token in (
            ("Review-Assurance", REVIEW_ASSURANCE_REDUCED),
            ("Review-Close-Reason", EARLY_CLOSE_REASON),
        ) for value in found[label] if value.strip().upper() == token]
        if claimed:
            return [f"조기 완료로 닫히지 않은 run 인데 보증 저하를 자칭함: {claimed}"]
        return []
    issues = []
    for label in _REDUCED_ASSURANCE_MARKERS:
        if len(found[label]) != 1:
            issues.append(f"{label} 선언은 fence 밖에 정확히 1개여야 함(found {len(found[label])})")
    if issues:
        return issues
    if found["Review-Assurance"][0].upper() != REVIEW_ASSURANCE_REDUCED:
        issues.append(f"Review-Assurance 는 {REVIEW_ASSURANCE_REDUCED} 여야 함")
    if found["Review-Close-Reason"][0].upper() != EARLY_CLOSE_REASON:
        issues.append(f"Review-Close-Reason 은 {EARLY_CLOSE_REASON} 이어야 함")
    rounds = run.get("completed_rounds")
    if rounds is not None and (type(rounds) is not int or isinstance(rounds, bool)):
        # 손상된 레코드는 대조 기준이 될 수 없다. 여기서 크래시하면 진단이 runtime error 로
        # 뭉개져 무엇이 깨졌는지 안 보인다(판정은 어차피 fail-closed 다).
        issues.append(f"감사 기록의 completed_rounds 가 정수가 아님: {rounds!r}")
    elif rounds is not None and not re.match(rf"^{int(rounds)}\b", found["Review-Rounds"][0]):
        issues.append(f"Review-Rounds 가 감사 기록({rounds})과 다름: {found['Review-Rounds'][0]!r}")
    issues.extend(_configured_max_issues(found["Review-Rounds"][0], run))
    receipt = run.get("survived_by_severity")
    if isinstance(receipt, dict):
        declared = dict(re.findall(r"(P[0-3])\s*=\s*(\d+)", found["Residual-Findings"][0]))
        expected = {key: str(int(receipt.get(key, 0))) for key in ("P0", "P1", "P2", "P3")}
        if declared != expected:
            issues.append(f"Residual-Findings 가 감사 기록과 다름: 문서={declared} 감사={expected}")
    return issues


def _audit_gate(event, profile, snapshot):
    """9.5 — report←approve 에 loop_audit 증거 요건을 더한다(advisory-first, run_id 바인딩).

    반환: None(skip) | {"ok": bool, "mode": "advisory"|"enforce", "detail": str}.
    skip 조건: pdca/review_loop 비활성, flag off/미설정, 또는 06 작성이 아님.
    검사: current Cycle-Stem의 05 문서 1개를 exact 선택 → 그 동일 문서에서 APPROVED 마커 + `Loop-Run: <id>` 를
    함께 읽고, 주입된 loop_audit.runs[id] 가 closed+APPROVED 인지. (codex 설계 R1~R4: stale 결합 차단.)
    """
    cfg = _pdca_cfg(profile)
    if cfg is None:
        return None
    rl = cfg.get("review_loop") or {}
    if not rl.get("enabled"):
        return None   # 루프 미기대 → 현행 마커-only (오차단 방지)
    mode = rl.get("report_gate_enforce") or "advisory"   # 7차 배치3-5: 기본 off→advisory(루프 켠 프로젝트는 최소 WARN)
    if mode not in ("advisory", "enforce"):
        return None   # 명시 off/무효 → skip(하위호환)
    report_phase = cfg.get("report_phase") or ""
    approve_phase = cfg.get("approve_phase") or ""
    if not report_phase or not approve_phase:
        return None
    if not _is_writing_report(event, cfg):
        return None   # 06 작성이 아님

    binding = cycle_binding.resolve(event, snapshot, cfg)
    if binding.get("error"):
        return {"ok": False, "mode": mode, "detail": f"cycle binding 실패: {binding['error']}"}

    # current Cycle-Stem의 05 문서 하나만 선택하고 APPROVED와 Loop-Run을 같은 문서에서 읽는다.
    approve_docs = (snapshot.get("phase_docs") or {}).get(approve_phase) or []
    sel, select_error = cycle_binding.select_document(approve_docs, binding["stem"])
    sel_path = (sel or {}).get("path")
    la = snapshot.get("loop_audit") or {}
    has_any = bool(la.get("has_any_records"))

    def fail(detail):
        return {"ok": False, "mode": mode, "detail": detail}

    if select_error:
        return fail(f"05 선택 실패: {select_error}")
    if la.get("file_ok", True) is False:
        # 10-g: malformed/non-object JSON은 run_id 자체를 신뢰할 수 없어 특정 run으로 격리할 수 없다.
        # 유효 레코드만 골라 통과시키면 손상 줄에 숨은 증거를 skip하는 우회가 되므로 파일 단위로 닫는다.
        snapshot_error = la.get("snapshot_error")
        if snapshot_error:
            return fail(f"loop audit snapshot 생성 실패 — {snapshot_error} "
                        "(감사 증거를 읽지 못해 신뢰 불가)")
        file_issues = la.get("file_issues") or []
        if file_issues:
            detail = "; ".join(str(issue).replace("\n", " ") for issue in file_issues[:3])
            return fail(f"loop audit 로그 무결성 실패 — {detail} (감사 증거 신뢰 불가)")
        return fail("loop audit 로그 원문 무결성 실패(원인 미제공) — 감사 증거 신뢰 불가")
    content = sel.get("content") or ""
    marker = (cfg.get("approve_marker") or "APPROVED").upper()
    status, status_error = _final_status(content)
    if status_error or status != marker:
        return fail(f"선택된 05 문서({sel_path}) Final Status 오류: "
                    f"{status_error or f'{status!r} != {marker!r}'}")
    # run_id 는 `review-loop` 가 verbatim 저장(커스텀 --run-id 포함) → 게이트도 비공백 토큰을 그대로 받는다
    # (codex 코드 R1-P1: 협소 charset 이면 rev:123·run/1 같은 합법 run 을 오차단).
    run_ids = []
    for raw in _non_fenced_lines(content):
        match = re.fullmatch(r"Loop-Run:\s*(\S+)", _structured_declaration_line(raw), re.IGNORECASE)
        if match:
            run_ids.append(match.group(1))
    if len(run_ids) != 1:
        hint = "audit 기록 자체가 없음 — 루프 미실행 의심" if not has_any else "05 문서에 Loop-Run 미기재"
        return fail(f"선택된 05 문서({sel_path})의 Loop-Run 선언은 fence 밖에 정확히 1개여야 함"
                    f"(found {len(run_ids)}; {hint})")
    run_id = run_ids[0]
    runs = la.get("runs") or {}
    run = runs.get(run_id)
    if run is None:
        return fail(f"05 가 가리키는 run {run_id!r} 가 audit 에 없음(loop open/close 미기록)")
    if not run.get("clean", True):
        # 재사용/중복 open·close·고아 → 증거 모호(stale 결과로 통과 차단, codex 코드 R2-P1)
        return fail(f"run {run_id!r} 의 audit 이력이 모호(중복/재사용 open·close) — 증거 신뢰 불가")
    if run.get("seq_ok") is False:
        # 7차 배치3-3: seq 불연속/누락 = CLI/라이브러리 우회한 수기 JSONL append 또는 순서 조작.
        return fail(f"run {run_id!r} 의 라운드 seq 불연속/누락 — 수기 기록 또는 순서 조작 의심(감사 증거 신뢰 불가)")
    if run.get("chain_ok") is False:
        # 10-g: selected run의 immediate-predecessor/self-hash 검증 실패. 키 부재/None은 legacy skip.
        return fail(f"run {run_id!r} 의 strict hash-chain 불일치 — 기록 수정/누락/순서 조작 의심(감사 증거 신뢰 불가)")
    if not run.get("closed"):
        return fail(f"run {run_id!r} 가 닫히지 않음(루프 미종료)")
    if (run.get("result") or "").upper() != "APPROVED":
        return fail(f"run {run_id!r} 가 result={run.get('result')!r} 로 종료(APPROVED 아님)")
    if run.get("degraded"):
        # 7차 배치3-4: 의도한 reviewer(open) ≠ 실제(close) — cross-model 요청이 same-runtime 으로 폴백된 정황.
        return fail(f"run {run_id!r} reviewer 불일치: 의도={run.get('reviewer_requested')!r} "
                    f"실제={run.get('reviewer_actual')!r} (cross-model 의도 검토 미수행 의심)")
    assurance = _reduced_assurance_issues(content, run)
    if assurance:
        return fail(f"run {run_id!r} 보증 수준 표기 불일치: " + "; ".join(assurance[:3]))
    report_issues = _report_assurance_issues(event, cfg, run)
    if report_issues:
        return fail(f"run {run_id!r} 리포트 보증 수준 표기 불일치: " + "; ".join(report_issues[:3]))
    return {"ok": True, "mode": mode, "detail": run_id}


def _report_assurance_issues(event, cfg, run):
    """조기 종료로 닫힌 run 의 리포트도 그 사실을 스스로 밝히는가.

    05 만 강제하면 최종 산출물인 06 은 보증 수준을 말하지 않는다 — 리포트만 읽는 사람에게는
    축약된 리뷰로 닫힌 사이클이 표준 승인과 똑같이 보인다. 05 와 같은 기준으로 06 의 본문을
    본다: 조기 종료가 아니면 자칭만 막고, 조기 종료면 네 표기가 감사와 일치해야 한다.

    검사 대상은 지금 쓰이는 06 내용이다. 여러 조각으로 쓰는 중이면 조각마다 판정하지 않고
    합쳐서 본다 — 한 조각에 표기가 없다는 이유로 작성 중간을 막지 않는다.
    """
    report_phase = str(cfg.get("report_phase") or "")
    rglob = _phase_glob(cfg, report_phase)
    if not rglob:
        return []
    written = [change.get("content") or "" for change in (event.get("changes") or [])
               if cycle_binding.matches_glob(change.get("path") or "", rglob)]
    if not written:
        return []
    return _reduced_assurance_issues("\n".join(written), run)


def _done_criteria_decision(mode, key, reason, file_short, **extra):
    blocked = mode == "enforce" and key.startswith("block_")
    decision = {
        "status": "block" if blocked else "warn",
        "exit_code": 2 if blocked else 0,
        "risk": "PDCA",
        "message_key": key if blocked else key.replace("block_", "warn_", 1),
        "reason": reason,
        "file_short": file_short,
    }
    decision.update(extra)
    return decision


def _done_criteria_gate(event, profile, snapshot):
    """Validate bound Phase 00 progressively and bind final report to fresh approval."""
    cfg = _pdca_cfg(profile)
    if cfg is None:
        return None
    base_plan = cfg.get("base_plan") if isinstance(cfg.get("base_plan"), dict) else {}
    mode = base_plan.get("done_criteria_gate", "off")
    if mode == "off":
        return None
    if mode not in ("advisory", "enforce"):
        return {"status": "block", "exit_code": 2, "risk": "PDCA",
                "message_key": "block_gate_runtime_error",
                "reason": f"invalid pdca.base_plan.done_criteria_gate={mode!r}", "file_short": ""}

    changed = _changed_phase_ids(event, cfg)
    governed = changed & {"01", "02", "03", "04", "05", "06"}
    if "00" in changed and governed:
        return _done_criteria_decision(
            mode, "block_phase00_mixed_evidence",
            "Phase 00과 후속 Phase를 같은 변경에서 쓰면 pre-write snapshot으로 "
            "revision과 영향 Phase 재실행을 검증할 수 없음", "")
    if not governed or _is_phase00_repair_only(event, cfg):
        return None
    binding = cycle_binding.resolve(event, snapshot, cfg)
    if binding.get("error"):
        return _done_criteria_decision(
            mode, "block_invalid_done_criteria",
            f"cycle binding 실패: {binding['error']}", "")
    docs = snapshot.get("phase_docs") or {}
    phase00, selection_error = cycle_binding.select_document(docs.get("00") or [], binding["stem"])
    if selection_error:
        return _done_criteria_decision(
            mode, "block_invalid_done_criteria",
            f"Phase 00 선택 실패: {selection_error}", "")
    content = phase00.get("content")
    parse_mode = "fast" if re.search(r"(?m)^Cycle-Mode:\s*FAST\s*$", content or "") else "standard"
    result = parse_done_criteria(content, mode=parse_mode)
    path = phase00.get("path") or ""
    if result.status != "valid":
        detail = "; ".join(result.issues[:3])
        return _done_criteria_decision(
            mode, "block_invalid_done_criteria",
            f"Phase 00 Done Criteria 구조 오류: {detail}", path,
            revision=result.revision, unresolved_items=[])

    if parse_mode == "standard" and result.latest_revision is not None:
        target_numbers = [int(phase) for phase in governed]
        prior_affected = [phase for phase in result.latest_revision.affected_phases
                          if any(int(phase) < target for target in target_numbers)]
        stale = []
        for phase in prior_affected:
            selected, error = cycle_binding.select_document(docs.get(phase) or [], binding["stem"])
            if error:
                stale.append(f"{phase}: {error}")
                continue
            revision, revision_issues = document_revision(selected.get("content"))
            if revision_issues or revision != result.revision:
                stale.append(f"{phase}: revision={revision!r}, expected={result.revision}")
        if stale:
            return _done_criteria_decision(
                mode, "block_stale_done_criteria_revision",
                "영향 Phase 재실행 미확인: " + "; ".join(stale[:3]), path,
                revision=result.revision, stale_phases=stale)

    report_phase = str(cfg.get("report_phase") or "06")
    is_report = report_phase in governed
    if result.unresolved and is_report:
        unresolved = [f"line {item.line}: {item.text}" for item in result.unresolved[:3]]
        return _done_criteria_decision(
            mode, "block_report_without_done_criteria",
            f"미해결 Done Criteria {len(result.unresolved)}건: " + "; ".join(unresolved), path,
            revision=result.revision, unresolved_items=unresolved,
            resolved=len(result.items) - len(result.unresolved), total=len(result.items))

    if is_report and not result.unresolved:
        approve_phase = str(cfg.get("approve_phase") or "05")
        approval, approval_error = cycle_binding.select_document(
            docs.get(approve_phase) or [], binding["stem"])
        # Missing/unapproved 05 belongs to the older report gate, which gives the established repair hint.
        if not approval_error:
            approval_content = approval.get("content") or ""
            status, status_error = _final_status(approval_content)
            marker = str(cfg.get("approve_marker") or "APPROVED").upper()
            if not status_error and status == marker:
                hash_values = []
                run_ids = []
                for raw in _non_fenced_lines(approval_content):
                    line = _structured_declaration_line(raw)
                    hash_match = re.fullmatch(r"Phase00-Hash:\s*(sha256:[0-9a-f]{64})", line)
                    run_match = re.fullmatch(r"Loop-Run:\s*(\S+)", line, re.IGNORECASE)
                    if hash_match:
                        hash_values.append(hash_match.group(1))
                    if run_match:
                        run_ids.append(run_match.group(1))
                current_hash = phase00_text_hash(content)
                audit = snapshot.get("loop_audit") or {}
                run = (audit.get("runs") or {}).get(
                    run_ids[0] if len(run_ids) == 1 else "")
                loop_hash = run.get("phase00_hash") if isinstance(run, dict) else None
                if (len(hash_values) != 1 or len(run_ids) != 1
                        or audit.get("file_ok") is not True
                        or not isinstance(run, dict)
                        or run.get("clean") is not True
                        or run.get("closed") is not True
                        or (run.get("result") or "").upper() != "APPROVED"
                        or run.get("seq_ok") is not True
                        or run.get("chain_ok") is not True
                        or hash_values[0] != current_hash or loop_hash != current_hash):
                    return _done_criteria_decision(
                        mode, "block_stale_done_criteria_approval",
                        "Phase 00이 최신 Phase 05/Loop 승인에 결속되지 않음 "
                        f"(current={current_hash}, phase05={hash_values}, loop={loop_hash!r}, "
                        f"file_ok={audit.get('file_ok')!r}, clean={run.get('clean') if isinstance(run, dict) else None!r}, "
                        f"seq_ok={run.get('seq_ok') if isinstance(run, dict) else None!r}, "
                        f"chain_ok={run.get('chain_ok') if isinstance(run, dict) else None!r})",
                        path, revision=result.revision)

    if result.unresolved:
        unresolved = [f"line {item.line}: {item.text}" for item in result.unresolved[:3]]
        return {"status": "warn", "exit_code": 0, "risk": "PDCA",
                "message_key": "warn_done_criteria_progress",
                "reason": f"Done Criteria 진행 중: {len(result.unresolved)}건 미해결",
                "file_short": path, "revision": result.revision,
                "unresolved_items": unresolved,
                "resolved": len(result.items) - len(result.unresolved), "total": len(result.items)}
    return None


def _select_pending_gate_decision(decisions):
    blocked = next((decision for decision in decisions if decision["status"] == "block"), None)
    if blocked:
        return blocked
    warnings = [decision for decision in decisions if decision["status"] == "warn"]
    if not warnings:
        return None
    selected = dict(warnings[0])
    reasons = [decision.get("reason") for decision in warnings if decision.get("reason")]
    selected["reason"] = "; ".join(dict.fromkeys(reasons))
    return selected


def _feedback_gate(event, profile, snapshot):
    """§10-a-C: 미해결 차단성 마커(`!sage-feedback ::`)를 남긴 채 그 파일에 쓰는 것을 막는다.

    규칙은 "마커 있는 파일은 못 고침" 이 아니라 **"고친 뒤에도 마커가 남는가"** 다. 전자로
    만들면 마커를 해소하려는 편집까지 막혀 영원히 못 푸는 자기차단이 된다. 해소하는 방향의
    쓰기(마커를 걷어내는 편집·마커 없는 전체 재작성)는 통과시킨다.

    snapshot["feedback"] 은 어댑터가 주입한다(core 는 순수 — IO 없음). 없으면 skip.
    """
    state = (snapshot or {}).get("feedback")
    if not isinstance(state, dict) or not state.get("enabled"):
        return None
    targets = state.get("targets") or {}
    if not targets:
        return None
    try:
        import feedback_markers
    except Exception:
        return None                     # 모듈 부재 → 판정 불가, 다른 게이트에 맡김

    unresolved = []
    for change in (event.get("changes") or []):
        target = targets.get((change or {}).get("path") or "")
        if not target:
            continue
        if feedback_markers.resolves_blocking(change, target.get("on_disk") or ""):
            continue                    # 해소하는 쓰기 → 통과
        unresolved.append((change.get("path"), target.get("markers") or []))
    if not unresolved:
        return None
    return {"files": [path for path, _ in unresolved],
            "markers": [m for _, markers in unresolved for m in markers]}


_DECLARED_SOURCE = "event"      # cycle_binding 이 env 선언 기원에 붙이는 라벨
_INFERRED_SOURCE = "branch-leaf"   # cycle_binding 이 브랜치 leaf 추론에 붙이는 라벨


def _binding_origin_label(source) -> str:
    """차단 사유가 결속 출처를 갈라 말하게 한다.

    문구가 `브랜치에서 추론한` 으로 고정돼 있던 것은 조건이 추론 출처였을 때만 참이었다. 선언도
    차단 대상이 된 지금 그 단정은 거짓이고, 거짓인 방향이 하필 나쁘다 — 낡은 선언 때문에 막힌
    사용자를 브랜치 쪽으로 보내서 해제 안내를 정면으로 무효화한다.
    """
    if _DECLARED_SOURCE in source:
        return "선언된"
    if _INFERRED_SOURCE in source:
        return "브랜치에서 추론한"
    return "phase 문서에서 결속한"


def _cycle_closed(stem, cfg, snapshot) -> bool:
    """stem 이 완결된 사이클인가 — 그 stem 의 report 문서 존재 **그리고** approve 문서 승인.

    report 문서는 stem 결속을 요구한다(`any_document`) — 저장소의 아무 06 이나 세면 06 이 한 건이라도
    있는 순간 모든 stem 이 완결로 판정돼 대량 과차단이 된다.

    승인까지 함께 요구하는 값은 "작성 중인 06 을 걸러내는 것"이 **아니다**. report 게이트가 05 승인
    없이는 06 을 못 쓰게 하므로 게이트가 켜져 있던 저장소에서는 `06 존재 ⟹ 승인` 이고 두 조건의
    논리곱은 `06 존재` 와 같다. 승인 확인이 실제로 거르는 것은 게이트 설치 전부터 있던 06,
    override 로 만든 06, 사후에 승인을 되돌린 사이클 — 레거시·우회·되돌림 상태다.

    판정 불가(문서 선택 실패·Final Status 오류)는 완결로 보지 않는다 — 여기서 fail-closed 하면
    아직 끝나지 않은 사이클의 소스 편집이 통째로 막힌다.
    """
    report_phase = str(cfg.get("report_phase") or "")
    if not report_phase:
        return False
    # stem 과 approve_phase 는 따로 검사하지 않는다. stem 은 호출부가 binding 오류를 먼저 걸러내므로
    # 항상 값이 있고, approve_phase 미설정은 `_approval_state` 가 빈 문서 목록에서 선택 실패로
    # 걸러낸다. 두 가드는 어떤 입력으로도 죽지 않는 등가 변이였다.
    docs = snapshot.get("phase_docs") or {}
    if not cycle_binding.any_document(docs.get(report_phase) or [], stem):
        return False
    return _approval_state(stem, cfg, snapshot)[0]


def _stamp_cycle_identity(decision: dict, event: dict, profile: dict, snapshot: dict) -> dict:
    """판정에 현재 cycle stem 과 그 출처를 실어 보낸다(판정 자체는 바꾸지 않는다).

    출처를 밖으로 내보내야 하는 이유가 둘이다. 하나는 안내 — stem 을 브랜치 leaf 에서 추론했다면
    올바른 탈출구는 "phase 문서를 작성하라" 가 아니라 "이 사이클이 아니면 선언하라" 다. 다른 하나는
    감사 — env 선언 stem 은 이미 완결된 사이클을 지목해 게이트 전체를 통과시킬 수 있어서, 어댑터가
    그 사실을 기록할 수 있어야 한다. core 는 IO 를 하지 않으므로 여기서는 사실만 싣는다.
    """
    if not isinstance(decision, dict):
        return decision
    cfg = _pdca_cfg(profile)
    if cfg is None:
        # pdca 비활성이면 사이클 개념 자체가 없다 — 노출할 결속이 없으므로 줄도 만들지 않는다.
        return _drop_contentless_binding_line(decision)
    binding = cycle_binding.resolve(event, snapshot, cfg)
    source = binding.get("source") or []
    decision["cycle_stem"] = binding.get("stem") or ""
    decision["cycle_source"] = list(source)
    decision["cycle_stem_declared"] = _DECLARED_SOURCE in source
    # 선언 통로가 둘(env / .sage/cycle.json)이라 출처만으로는 어디서 읽었는지 알 수 없다.
    # 순수 판정 모듈(cycle_binding)은 건드리지 않고 어댑터가 실어 보낸 사실을 여기서 옮긴다.
    decision["cycle_stem_origin"] = event.get("cycle_stem_origin") or ""
    return _drop_contentless_binding_line(decision)


def _drop_contentless_binding_line(decision: dict) -> dict:
    """EH-15/16: 결속할 stem 이 없으면 L1·L0 통과 줄을 만들지 않는다.

    이 줄의 목적은 "이 편집이 어느 사이클에 결속됐나" 하나뿐이다. stem 이 없는데도 내보내면
    정보가 0인 줄이 편집마다 모델 컨텍스트에 쌓인다 — 켠 사람이 얻는 것 없이 비용만 낸다.
    """
    if decision.get("message_key") in ("ok_l1", "ok_l0") and not decision.get("cycle_stem"):
        decision["message_key"] = None
    return decision


def decide(event: dict, profile: dict, snapshot: dict, strategy_result) -> dict:
    """risk-gate 판정. strategy_result: None=미선택 / {found:bool, path?} = 선택된 전략 실행결과."""
    return _stamp_cycle_identity(
        _decide(event, profile, snapshot, strategy_result), event, profile, snapshot)


def _decide(event: dict, profile: dict, snapshot: dict, strategy_result) -> dict:
    c = classify_risk(event, profile)
    risk = c["risk"]

    if risk == "DESKTOP_BLOCK":
        return {"status": "block", "exit_code": 2, "risk": "DESKTOP",
                "message_key": "block_desktop", "reason": c["reason"], "file_short": c["file_short"]}

    # §10-a-C 개발자 피드백 마커: 미해결 차단성 마커를 남긴 채 그 파일에 쓰면 차단.
    # 위험도와 무관하게 적용한다 — "묵은 의문 위에 새 구현을 쌓지 마라" 는 L1 이든 L3 이든 같다.
    fg = _feedback_gate(event, profile, snapshot)
    if fg is not None:
        detail = "; ".join(f"{m['path']}:{m['line']} {m['text']}" for m in fg["markers"][:3])
        return {"status": "block", "exit_code": 2, "risk": "FEEDBACK",
                "message_key": "block_feedback_unresolved",
                "reason": f"미해결 차단성 마커 {len(fg['markers'])}건 — {detail}",
                "file_short": ", ".join(fg["files"][:3])}

    # PDCA cycle identity is a prerequisite for governed source changes and every phase write.
    # Do not infer from branch numbers or recent mtimes: zero/multiple/conflicting candidates block.
    cfg = _pdca_cfg(profile)
    changed_phases = set()
    fast_state = None
    language_warning = None
    if cfg is not None and (_is_phase_write(event, cfg) or risk in ("L1", "L2", "L3")):
        binding = cycle_binding.resolve(event, snapshot, cfg)
        if binding.get("error"):
            return {"status": "block", "exit_code": 2, "risk": "PDCA",
                    "message_key": "block_cycle_binding",
                    "reason": f"cycle binding 실패: {binding['error']}",
                    "file_short": c["file_short"]}
        # 완결 사이클은 00~06 이 다 있어 모든 게이트를 통과한다 — 새 작업이 계획 문서 없이 조용히
        # 진행된다. 예전에는 브랜치 leaf 추론만 막았다. env 선언은 셸과 함께 죽어 무해했기 때문이다.
        # 파일 선언(.sage/cycle.json)은 세션을 넘겨 살아남으므로 3주 전 선언이 이 차단을 통째로
        # 꺼버린다(실측: exit 0, 출력 0바이트). 그래서 결속 출처가 아니라 **무엇을 고치는가**로 가른다.
        # 면제는 "완결 사이클의 문서를 정정하는 편집" 하나뿐이고, 그건 정상 작업이다.
        # 주의: "L1 이상"은 "L0 경로가 아님"과 다르다. 세션 위험도 선언(declared_max)이 L0 경로를
        # 상향시키므로, L3 를 선언한 세션에서는 문서 편집도 이 차단에 걸린다.
        source = binding.get("source") or []
        if (not _phase_only_change(event, cfg)
                and _cycle_closed(binding["stem"], cfg, snapshot)):
            return {"status": "block", "exit_code": 2, "risk": risk,
                    "message_key": "block_cycle_closed",
                    "reason": f"{_binding_origin_label(source)} stem {binding['stem']!r} 은 "
                              f"완결된 사이클",
                    "file_short": c["file_short"]}
        # 문서 언어는 사이클 결속이 성립한 뒤에만 물을 수 있다 — stem 이 없으면 "같은 사이클의
        # 문서" 라는 집합 자체가 정의되지 않는다. 결속 실패는 위에서 이미 차단된다.
        language = _document_language_gate(event, profile, snapshot, binding["stem"])
        if language and language["status"] == "block":
            return {"status": "block", "exit_code": 2, "risk": "PDCA",
                    "message_key": "block_document_language_conflict",
                    "reason": language["reason"], "file_short": c["file_short"]}
        if language:
            language_warning = {"status": "warn", "exit_code": 0, "risk": "PDCA",
                                "message_key": "warn_document_language_missing",
                                "reason": language["reason"], "file_short": c["file_short"]}

        # marker 는 선언 자체(한 줄)만 본다 — 그 아래 본문이 실제로 그 언어인지는 별개 판정이다.
        # 여기서 보는 것은 **이번에 새로 쓰거나 바뀌는 내용**뿐이다(§7.7): 이미 디스크에 있는
        # 다른 phase 문서까지 소급 검사하면 legacy 문서 하나가 이후 모든 편집을 막는다.
        prose = _document_prose_gate(event, snapshot, binding["stem"])
        if prose is not None:
            where = prose["path"] + (f":{prose['line']}" if prose["line"] else "")
            return {"status": "block", "exit_code": 2, "risk": "PDCA",
                    "message_key": prose["key"],
                    # reason 은 두 언어에서 글자까지 같아야 한다 — 위치와 원문 조각뿐이고
                    # 설명 문장은 catalog 가 소유한다.
                    "reason": f"{where} {prose['evidence']!r}".strip() if prose["evidence"]
                              else where,
                    "file_short": prose["path"] or c["file_short"]}

        changed_phases = _changed_phase_ids(event, cfg)
        report_phase = str(cfg.get("report_phase") or "")
        dependency_phases = {str(phase.get("id") or "") for phase in (cfg.get("phases") or [])}
        dependency_phases.discard(report_phase)
        dependency_phases.discard("")
        mixed = sorted(changed_phases & dependency_phases)
        if report_phase and report_phase in changed_phases and mixed:
            return {"status": "block", "exit_code": 2, "risk": "PDCA",
                    "message_key": "block_report_mixed_evidence",
                    "reason": (f"report phase {report_phase}와 dependency phase {mixed}를 같은 변경에서 "
                               "수정하면 pre-write snapshot으로 검증할 수 없음"),
                    "file_short": c["file_short"]}

        governed_progress = bool(changed_phases - {"00"}) or risk in ("L1", "L2", "L3")
        if governed_progress and not _is_phase00_repair_only(event, cfg):
            phase00 = _bound_phase00_risk(event, profile, snapshot)
            if phase00["status"] != "valid":
                return {"status": "block", "exit_code": 2, "risk": "PDCA",
                        "message_key": "block_cycle_risk_declaration",
                        "reason": f"Phase 00 Risk Level 선언 미충족: {phase00['detail']}",
                        "file_short": phase00.get("path") or c["file_short"]}
            if _RANK.get(risk, -1) > _RANK.get(phase00["risk"], -1):
                return {
                    "status": "block",
                    "exit_code": 2,
                    "risk": "PDCA",
                    "message_key": "block_cycle_risk_reconciliation",
                    "reason": (f"계산 위험도 {risk}가 Phase 00 선언 {phase00['risk']}보다 높음; "
                               "Phase 00 Risk Level을 먼저 상향한 뒤 재시도"),
                    "file_short": c["file_short"],
                    "phase00_path": phase00["path"],
                    "phase00_risk": phase00["risk"],
                    "required_risk": risk,
                    # 안내가 갈리는 근거. 세션 선언이 위험도를 올린 경우 00 상향을 먼저 시키면
                    # 실제보다 높은 위험도를 기록하게 된다 — 게이트가 기록 오염을 유도한다.
                    "risk_from_declaration": any(
                        str(s).startswith("declared_") for s in (c.get("trigger_sources") or [])),
                }
            fast_state, fast_error = _fast_cycle_state(event, profile, snapshot, cfg)
            if fast_error:
                return {"status": "block", "exit_code": 2, "risk": "PDCA",
                        "message_key": "block_fast_cycle_audit",
                        "reason": fast_error, "file_short": c["file_short"]}

    # PDCA report←approve 게이트: report phase 문서 작성은 L0(plan_docs)이라 아래 단축 전에 검사.
    # (pdca 비활성이거나 report/approve 미설정 → None → skip, 하위호환)
    rg = _report_gate(event, profile, snapshot)
    if rg is not None and not rg["approved"]:
        return {"status": "block", "exit_code": 2, "risk": "PDCA",
                "message_key": "block_report_without_approval",
                "reason": (f"{rg['report_phase']} 작성 전 {rg['approve_phase']} 승인(APPROVED) 필요: "
                           f"{rg.get('detail') or 'same-cycle document unavailable'}"),
                "file_short": c["file_short"]}

    # Acceptance evidence gate: 04 가 요구사항별 PASS/FAIL/NOT TESTED 를 기록했는지 확인.
    # build/test/lint 통과가 사용자 요구사항 충족을 자동 증명하지 않는 갭을 advisory-first 로 닫는다.
    pending_gate_decisions = []
    if language_warning is not None:
        pending_gate_decisions.append(language_warning)
    dcg = _done_criteria_gate(event, profile, snapshot)
    if dcg is not None:
        pending_gate_decisions.append(dcg)
    acg = _acceptance_gate(event, profile, snapshot)
    if acg is not None and not acg["ok"]:
        if acg.get("waived"):
            pending_gate_decisions.append({"status": "warn", "exit_code": 0, "risk": "PDCA",
                                           "message_key": "warn_report_with_l3_waiver",
                                           "reason": acg["detail"],
                                           "waiver_uses": acg.get("waiver_uses") or [],
                                           "file_short": c["file_short"]})
        elif acg["mode"] == "enforce":
            pending_gate_decisions.append({"status": "block", "exit_code": 2, "risk": "PDCA",
                                           "message_key": "block_report_without_acceptance",
                                           "reason": f"acceptance evidence 미충족(enforce): {acg['detail']}",
                                           "file_short": c["file_short"]})
        else:
            pending_gate_decisions.append({"status": "warn", "exit_code": 0, "risk": "PDCA",
                                           "message_key": "warn_report_without_acceptance",
                                           "reason": f"acceptance evidence 미충족(advisory): {acg['detail']}",
                                           "file_short": c["file_short"]})

    # 9.5 report←approve audit 증거(F-5): 마커는 있으나 cycle 05 가 가리키는 loop run 이 closed+APPROVED 가
    # 아니면 advisory=WARN / enforce=BLOCK. review_loop 비활성·flag off 면 ag=None → skip(하위호환).
    ag = _audit_gate(event, profile, snapshot)
    if ag is not None and not ag["ok"]:
        if ag["mode"] == "enforce":
            pending_gate_decisions.append({"status": "block", "exit_code": 2, "risk": "PDCA",
                                           "message_key": "block_report_without_audit",
                                           "reason": f"리뷰 루프 audit 증거 미충족(enforce): {ag['detail']}",
                                           "file_short": c["file_short"]})
        else:
            pending_gate_decisions.append({"status": "warn", "exit_code": 0, "risk": "PDCA",
                                           "message_key": "warn_report_without_audit",
                                           "reason": f"리뷰 루프 audit 증거 미충족(advisory): {ag['detail']}",
                                           "file_short": c["file_short"]})

    selected = _select_pending_gate_decision(pending_gate_decisions)
    if selected:
        return selected

    if risk in ("none", "L0"):
        if c.get("l0_l3_file"):   # P2-9: L0 문서에 L3 내용 키워드 — 비차단 WARN(exit0, 민감정보 노출 점검)
            return {"status": "warn", "exit_code": 0, "risk": risk,
                    "message_key": "warn_l0_l3_content",
                    "reason": "L0 문서/plan 에 L3 내용 키워드 — 민감정보 노출 여부 점검",
                    "file_short": c["l0_l3_file"]}
        return {"status": "ok", "exit_code": 0, "risk": risk,
                "message_key": "ok_l0" if _discloses_low_risk_binding(profile) else None,
                "reason": c["reason"], "file_short": c["file_short"]}

    # PDCA 의무 phase 강제: 구현 전 필수 phase 결핍 시 L2/L3 BLOCK, L1 WARN.
    # missing=None(pdca 비활성) 또는 [](충족) → falsy → 기존 per-level 로직으로 (하위호환).
    missing = _missing_pre_impl_phases(event, profile, snapshot, risk, fast_state)
    if missing:
        if risk in ("L2", "L3"):
            return {"status": "block", "exit_code": 2, "risk": risk,
                    "message_key": "block_phase_incomplete", "missing_phases": missing,
                    "reason": c["reason"], "file_short": c["file_short"]}
        return {"status": "warn", "exit_code": 0, "risk": "L1",
                "message_key": "warn_phase_incomplete", "missing_phases": missing,
                "reason": c["reason"], "file_short": c["file_short"]}

    plan_exists = _bound_plan_exists(event, profile, snapshot)

    if fast_state is not None and risk in ("L2", "L3"):
        return {"status": "warn", "exit_code": 0, "risk": risk,
                "message_key": "warn_fast_cycle",
                "reason": (f"Fast {fast_state.get('fast_review_level')} · "
                           f"{fast_state.get('minimum_rounds')} round · "
                           f"{len(fast_state.get('lenses') or [])} lenses · "
                           f"{fast_state.get('reason')}"),
                "file_short": c["file_short"]}

    if risk == "L3":
        # 강신호 + plan 없음 → 하드 블록 (공유)
        content_l3_block = ((profile.get("risk") or {}).get("content_l3_enforce", "warn") == "block"
                            and "content_l3" in (c.get("trigger_sources") or []))
        if (c["is_l3_filename"] or c["declared_l3"] or content_l3_block) and not plan_exists:
            return {"status": "block", "exit_code": 2, "risk": "L3",
                    "message_key": "block_l3_no_plan", "reason": c["reason"], "file_short": c["file_short"]}
        # review doc 확인 = 전략. 미선택이면 확인 불가 → 안전 바닥(BLOCK + override)
        if strategy_result is None:
            return {"status": "block", "exit_code": 2, "risk": "L3",
                    "message_key": "block_l3_strategy_unresolved", "safety_degraded": True,
                    "reason": c["reason"], "file_short": c["file_short"]}
        if strategy_result.get("found"):
            return {"status": "ok", "exit_code": 0, "risk": "L3",
                    "message_key": "ok_l3", "reason": c["reason"], "file_short": c["file_short"]}
        if strategy_result.get("enforce"):
            return {"status": "block", "exit_code": 2, "risk": "L3",
                    "message_key": "block_l3_review_evidence",
                    "reason": strategy_result.get("reason") or c["reason"],
                    "file_short": c["file_short"]}
        return {"status": "warn", "exit_code": 0, "risk": "L3",
                "message_key": "warn_l3_no_review", "reason": c["reason"], "file_short": c["file_short"]}

    if risk == "L2":
        if not plan_exists:
            return {"status": "warn", "exit_code": 0, "risk": "L2",
                    "message_key": "warn_l2_no_plan", "reason": c["reason"], "file_short": c["file_short"]}
        return {"status": "ok", "exit_code": 0, "risk": "L2",
                "message_key": "ok_l2", "reason": c["reason"], "file_short": c["file_short"]}

    # L1 통과
    return {"status": "ok", "exit_code": 0, "risk": "L1",
            "message_key": "ok_l1" if _discloses_low_risk_binding(profile) else None,
            "reason": c["reason"], "file_short": c["file_short"]}
