"""Strict audit trail for the SAGE Fast Cycle protocol."""

import os
import re
import time
import uuid

import loop_audit as _chain
import fast_cycle_contract as _contract
from fast_cycle_contract import PHASES as SOURCE_PHASES

AUDIT_REL = os.path.join(".sage", "fast_cycle.jsonl")
EVENTS = ("fast_open", "fast_convert", "fast_review", "fast_close", "fast_abort")
# run 을 여는 이벤트는 둘이다 — 새 Fast Plan(`fast_open`)과 Standard 에서 전환한 run(`fast_convert`).
# 이후 review/close/abort 는 두 경로가 공유하므로 "opener 정확히 1개" 라는 공통 상태로 정규화한다.
OPENER_EVENTS = ("fast_open", "fast_convert")
ENTRY_MODES = {"fast_open": "FAST", "fast_convert": "FAST-CONVERTED"}
TERMINAL_EVENTS = ("fast_close", "fast_abort")
AuditWriteError = _chain.AuditWriteError


def audit_path(root):
    return os.path.join(root, AUDIT_REL)


def open_operation_lock(root):
    """Serialize the cross-file plan binding and fast_open transition."""
    return _chain._audit_lock(audit_path(root) + ".open")


def new_run_id():
    return "fc-" + uuid.uuid4().hex[:12]


def _records(root):
    return _chain._read_status(audit_path(root))


def read_records(root):
    return _records(root)[0]


def _for_run(root, run_id):
    return [record for record in read_records(root) if record.get("run_id") == run_id]


def _state_from_records(all_records, run_id):
    records = [record for record in all_records if record.get("run_id") == run_id]
    opens = [record for record in records if record.get("event") in OPENER_EVENTS]
    reviews = [record for record in records if record.get("event") == "fast_review"]
    terminals = [record for record in records if record.get("event") in TERMINAL_EVENTS]
    return records, opens, reviews, terminals


def _state(root, run_id):
    return _state_from_records(read_records(root), run_id)


def _append(root, record, validator=None):
    try:
        return _chain._append(audit_path(root), record, validator=validator)
    except _chain.AuditWriteError as exc:
        raise AuditWriteError(str(exc).replace("loop audit", "fast cycle audit")) from exc


def _base(event, run_id, cycle_stem, now=None):
    epoch = time.time() if now is None else now
    return {
        "event": event,
        "run_id": run_id,
        "cycle_stem": cycle_stem,
        "ts": _chain._iso(epoch),
        "epoch": int(epoch),
        "actor": os.environ.get("SAGE_ACTOR") or os.environ.get("USER") or "unknown",
    }


def open_fast(root, *, cycle_stem, actual_risk, fast_review_level, reason,
              minimum_rounds, lenses, profile_hash, plan_hash_open,
              run_id=None, now=None):
    rid = run_id or new_run_id()
    records, opens, _reviews, terminals = _state(root, rid)
    if records or opens or terminals:
        raise AuditWriteError(f"run {rid!r} already exists")
    for other_id, state in audit_summary(root)["runs"].items():
        if state.get("cycle_stem") == cycle_stem and not state.get("terminal"):
            raise AuditWriteError(f"cycle stem {cycle_stem!r} already has active run {other_id}")
    record = _base("fast_open", rid, cycle_stem, now)
    record.update({
        "entry_mode": ENTRY_MODES["fast_open"],
        "actual_risk_open": actual_risk,
        "fast_review_level": fast_review_level,
        "reason": reason,
        "minimum_rounds": minimum_rounds,
        "lens_count": len(lenses),
        "lenses": list(lenses),
        "profile_hash": profile_hash,
        "plan_hash_open": plan_hash_open,
    })

    def validate(prior, _record):
        records, opens, _reviews, terminals = _state_from_records(prior, rid)
        if records or opens or terminals:
            raise AuditWriteError(f"run {rid!r} already exists")
        run_ids = {item.get("run_id") for item in prior
                   if isinstance(item.get("run_id"), str) and item.get("run_id")}
        for other_id in run_ids:
            _items, other_opens, _other_reviews, other_terminals = _state_from_records(prior, other_id)
            if (len(other_opens) == 1 and not other_terminals
                    and other_opens[0].get("cycle_stem") == cycle_stem):
                raise AuditWriteError(f"cycle stem {cycle_stem!r} already has active run {other_id}")

    _append(root, record, validator=validate)
    return rid


def convert_fast(root, *, profile, cycle_stem, current_phase, actual_risk, fast_review_level,
                 reason, confirmed_by, minimum_rounds, lenses, source_phases,
                 run_id=None, now=None):
    """진행 중 Standard Cycle 을 Fast 계약으로 바꾸는 opener.

    문서를 하나도 쓰지 않는다 — 그래서 prepare/commit 중간 상태도, 교차 파일 롤백도 없다. 이 append
    가 실패하면 전환 전 상태가 그대로 남는다. `source_phases` 는 전환 시점에 존재하던 00~04 각각의
    저장소 상대 경로·raw-byte SHA-256·크기이고, 동결 기준이 아니라 "어디까지 어떤 문서로 Standard 를
    진행했는가" 를 남기는 provenance 다.

    `actual_risk` 와 `fast_review_level` 은 다른 값이다. 전자는 실제 위험도라 감사에 고정되고,
    후자는 Fast 리뷰 정책 선택일 뿐 실제 risk 를 바꾸지 않는다.
    """
    # 담보를 append **전에** 검증한다. 기록된 뒤에 잡으면 그 줄은 append-only 라 지울 수 없고,
    # 감사는 영구히 붉어진다. 그리고 무엇보다, 쓰기가 막지 않으면 읽기가 유일한 방어선이 된다.
    #
    # 형식만 보면 `{"path": ".", "sha256": "sha256:<64 hex>", "size": 0}` 이 통과한다. `.` 은
    # 저장소 상대 문자열이지만 phase 문서가 아니다 — 구조는 "저장소 안의 그 파일" 을 보증하지
    # 못한다. 그래서 writer 는 **지금 실재하는 문서에서 만든 snapshot 과 대조**한다. 이 검증이
    # `profile` 을 요구하는 이유이고, CLI 밖의 직접 호출도 같은 문을 지나게 하는 이유다.
    #
    # 이 확인은 writer 시점 한 번이다. 감사와 게이트가 나중에 디스크를 재검증하면 전환 뒤의
    # 정상 개발이 손상으로 오판된다.
    try:
        from fast_cycle_sources import SourceProvenanceError, verify_source_phases
    except Exception as exc:                                    # pragma: no cover
        raise AuditWriteError(
            f"source provenance verifier unavailable: {type(exc).__name__}: {exc}") from exc
    try:
        verify_source_phases(root, profile, cycle_stem, current_phase, source_phases)
    except SourceProvenanceError as exc:
        raise AuditWriteError("converted Fast provenance is invalid: " + str(exc)) from exc
    rid = run_id or new_run_id()
    records, opens, _reviews, terminals = _state(root, rid)
    if records or opens or terminals:
        raise AuditWriteError(f"run {rid!r} already exists")
    for other_id, state in audit_summary(root)["runs"].items():
        if state.get("cycle_stem") == cycle_stem and not state.get("terminal"):
            raise AuditWriteError(f"cycle stem {cycle_stem!r} already has active run {other_id}")
    record = _base("fast_convert", rid, cycle_stem, now)
    record.update({
        "entry_mode": ENTRY_MODES["fast_convert"],
        "current_phase": current_phase,
        "actual_risk_open": actual_risk,
        "fast_review_level": fast_review_level,
        "reason": reason,
        "confirmed_by": confirmed_by,
        "attestation": "self_asserted_local",
        "minimum_rounds": minimum_rounds,
        "lens_count": len(lenses),
        "lenses": list(lenses),
        "source_phases_open": source_phases,
    })

    def validate(prior, _record):
        records, opens, _reviews, terminals = _state_from_records(prior, rid)
        if records or opens or terminals:
            raise AuditWriteError(f"run {rid!r} already exists")
        run_ids = {item.get("run_id") for item in prior
                   if isinstance(item.get("run_id"), str) and item.get("run_id")}
        for other_id in run_ids:
            _items, other_opens, _other_reviews, other_terminals = _state_from_records(prior, other_id)
            if (len(other_opens) == 1 and not other_terminals
                    and other_opens[0].get("cycle_stem") == cycle_stem):
                raise AuditWriteError(f"cycle stem {cycle_stem!r} already has active run {other_id}")

    _append(root, record, validator=validate)
    return rid


def _snapshot_delta(before, after):
    """전환 시점 스냅샷 대비 리뷰 시점의 추가·변경·삭제. 판정이 아니라 구조화된 기록이다.

    변경 판단은 phase 별 `sha256` 과 `path` 둘 다로 한다 — 내용이 같고 파일만 옮긴 경우도 리뷰가
    본 것과 다른 문서다. `added`/`removed` 는 리뷰 스냅샷 범위가 전환 시점과 같아 보통 비어 있지만,
    범위가 달라지는 호출자를 위해 계산은 남긴다.
    """
    before = before if isinstance(before, dict) else {}
    after = after if isinstance(after, dict) else {}
    changed = [phase for phase in sorted(set(before) & set(after))
               if ((before[phase] or {}).get("sha256") != (after[phase] or {}).get("sha256")
                   or (before[phase] or {}).get("path") != (after[phase] or {}).get("path"))]
    return {"added": sorted(set(after) - set(before)),
            "removed": sorted(set(before) - set(after)),
            "changed": changed}


def _review_snapshot_issue(snapshot):
    """전환 review 가 증언하는 최종 00~04 스냅샷. open 쪽과 **같은** 구조 계약을 쓴다.

    이전에는 이 함수만 구조를 봤고 open 쪽은 "빈 dict 가 아닌가" 만 봤다. 같은 모양의 데이터에
    검사가 둘이면 느슨한 쪽으로 들어온다.
    """
    return _contract.source_phase_snapshot_issue(snapshot, SOURCE_PHASES)


def record_review(root, run_id, *, loop_run_id, actual_risk, rounds,
                  lens_receipts_hash, plan_hash_before_review, result, now=None,
                  source_phases_review=None):
    _records_for_run, opens, reviews, terminals = _state(root, run_id)
    if len(opens) != 1 or terminals:
        raise AuditWriteError(f"run {run_id!r} cannot accept fast_review")
    converted = opens[0].get("entry_mode") == ENTRY_MODES["fast_convert"]
    snapshot_issue = _review_snapshot_issue(source_phases_review) if converted else None
    if snapshot_issue:
        raise AuditWriteError(f"converted fast_review source phase snapshot is invalid: {snapshot_issue}")
    record = _base("fast_review", run_id, opens[0].get("cycle_stem"), now)
    record.update({
        "loop_run_id": loop_run_id,
        "actual_risk_review": actual_risk,
        "rounds": rounds,
        "lens_receipts_hash": lens_receipts_hash,
        "plan_hash_before_review": plan_hash_before_review,
        "result": result,
    })
    # 전환 run 은 composite 문서가 없어 plan hash 로 "리뷰가 무엇을 봤는가" 를 고정할 수 없다.
    # 그 자리를 스냅샷이 대신한다. fresh run 에는 plan hash 가 정본이므로 지어내지 않는다.
    if source_phases_review is not None:
        record["source_phases_review"] = source_phases_review
        record["source_phases_delta"] = _snapshot_delta(
            opens[0].get("source_phases_open"), source_phases_review)

    def validate(prior, _record):
        _records_for_run, current_opens, _current_reviews, current_terminals = _state_from_records(prior, run_id)
        if len(current_opens) != 1 or current_terminals:
            raise AuditWriteError(f"run {run_id!r} cannot accept fast_review")
        if current_opens[0].get("entry_mode") == ENTRY_MODES["fast_convert"]:
            issue = _review_snapshot_issue(source_phases_review)
            if issue:
                raise AuditWriteError(
                    f"converted fast_review source phase snapshot is invalid: {issue}")

    return _append(root, record, validator=validate)


def close_fast(root, run_id, *, loop_run_id, actual_risk, plan_hash_final,
               report_path, now=None):
    _records_for_run, opens, reviews, terminals = _state(root, run_id)
    if len(opens) != 1 or not reviews or terminals:
        raise AuditWriteError(f"run {run_id!r} cannot accept fast_close")
    if reviews[-1].get("result") != "APPROVED" or reviews[-1].get("loop_run_id") != loop_run_id:
        raise AuditWriteError(f"run {run_id!r} has no matching APPROVED review")
    record = _base("fast_close", run_id, opens[0].get("cycle_stem"), now)
    record.update({
        "loop_run_id": loop_run_id,
        "actual_risk_final": actual_risk,
        "result": "APPROVED",
        "plan_hash_final": plan_hash_final,
        "report_path": report_path,
    })

    def validate(prior, _record):
        _records_for_run, current_opens, current_reviews, current_terminals = _state_from_records(prior, run_id)
        if len(current_opens) != 1 or not current_reviews or current_terminals:
            raise AuditWriteError(f"run {run_id!r} cannot accept fast_close")
        if (current_reviews[-1].get("result") != "APPROVED"
                or current_reviews[-1].get("loop_run_id") != loop_run_id):
            raise AuditWriteError(f"run {run_id!r} has no matching APPROVED review")

    return _append(root, record, validator=validate)


def abort_fast(root, run_id, *, reason, stage, actual_risk, now=None):
    _records_for_run, opens, _reviews, terminals = _state(root, run_id)
    if len(opens) != 1 or terminals:
        raise AuditWriteError(f"run {run_id!r} cannot accept fast_abort")
    record = _base("fast_abort", run_id, opens[0].get("cycle_stem"), now)
    record.update({"reason": reason, "stage": stage, "actual_risk_at_abort": actual_risk})

    def validate(prior, _record):
        _records_for_run, current_opens, _current_reviews, current_terminals = _state_from_records(prior, run_id)
        if len(current_opens) != 1 or current_terminals:
            raise AuditWriteError(f"run {run_id!r} cannot accept fast_abort")

    return _append(root, record, validator=validate)


def summarize_records(records, file_issues):
    """레코드 목록 하나를 상태로 접는다. 파일도 락도 모르는 순수 함수.

    락을 잡는 `audit_summary` 와 잡지 않는 `snapshot` 이 **같은 이 함수**를 쓴다.
    접기 로직을 복사하면 감사 형식의 해석기가 둘이 되고, 갈렸을 때 어느 쪽이 옳은지
    판정할 근거가 없어진다 — 읽기 경로를 새로 낸 이유가 그것을 피하려던 것이다.
    """
    chain_states = _chain._chain_states(records)
    runs = {}
    seqs = {}
    for record in records:
        rid = record.get("run_id")
        if not isinstance(rid, str) or not rid:
            continue
        state = runs.setdefault(rid, {
            "cycle_stem": None, "opens": 0, "reviews": 0, "terminals": 0,
            "terminal": False, "result": None, "loop_run_id": None, "entry_mode": None,
        })
        seqs.setdefault(rid, []).append(record.get("seq"))
        event = record.get("event")
        if event in OPENER_EVENTS:
            state["opens"] += 1
            state["entry_mode"] = ENTRY_MODES[event]
            # `_base` 가 모든 레코드에 싣는 공통 필드다. 상태로 올리지 않으면 opener 완전성
            # 검사가 볼 수 없고, 이 셋이 없는 기록은 감사가 아니라 손으로 쓴 한 줄이다.
            state["ts"] = record.get("ts")
            state["epoch"] = record.get("epoch")
            state["actor"] = record.get("actor")
            state["current_phase"] = record.get("current_phase")
            state["confirmed_by"] = record.get("confirmed_by")
            state["source_phases_open"] = record.get("source_phases_open")
            state["cycle_stem"] = record.get("cycle_stem")
            state["actual_risk"] = record.get("actual_risk_open")
            state["fast_review_level"] = record.get("fast_review_level")
            state["minimum_rounds"] = record.get("minimum_rounds")
            state["lenses"] = record.get("lenses")
            state["reason"] = record.get("reason")
            state["profile_hash"] = record.get("profile_hash")
            state["plan_hash_open"] = record.get("plan_hash_open")
        elif event == "fast_review":
            state["reviews"] += 1
            state["loop_run_id"] = record.get("loop_run_id")
            state["review_result"] = record.get("result")
            state["plan_hash_before_review"] = record.get("plan_hash_before_review")
            # 전환 run 의 "리뷰가 무엇을 봤는가" 는 plan hash 가 아니라 이 스냅샷이다. 상태로
            # 올리지 않으면 close 가 대조할 기준을 갖지 못한다.
            state["source_phases_review"] = record.get("source_phases_review")
        elif event in TERMINAL_EVENTS:
            state["terminals"] += 1
            state["terminal"] = True
            state["result"] = record.get("result") or "ABORTED"
    for rid, state in runs.items():
        state["clean"] = state["opens"] == 1 and state["terminals"] <= 1
        state["seq_ok"] = _chain._seq_ok(seqs.get(rid, []))
        state["chain_ok"] = chain_states.get(rid)
        del state["opens"]
        del state["reviews"]
        del state["terminals"]
    active = sorted(rid for rid, state in runs.items() if not state["terminal"])
    return {
        "runs": runs,
        "active": active,
        "has_any_records": bool(records),
        "file_ok": not file_issues,
        "file_issues": file_issues,
    }


def audit_summary(root):
    return summarize_records(*_records(root))


def snapshot(root):
    """락도 쓰기도 없는 조회용 상태. `audit_summary` 와 같은 접기 함수를 쓴다.

    `status` 는 "지금 이 설치를 쓸 수 있는가" 에 1~2초 안에 답해야 한다. 그 질문에
    답하려고 감사 락을 잡으면 진행 중인 Fast 전이를 기다리게 되고, 락 파일 자체가
    읽기 전용 계약을 깬다.

    `status` 키는 absent/valid/damaged 셋이다. damaged 를 valid 로 접지 않는 것이
    이 API 의 요점이다 — 락이 없으니 append 중간을 볼 수 있고, 그 절반짜리 파일을
    "기록이 없다" 로 읽으면 진행 중인 run 이 사라진다.
    """
    state, records, issues = _chain._read_status_unlocked(audit_path(root))
    summary = summarize_records(records, issues)
    summary["status"] = state
    return summary


# opener 가 실제로 담고 있어야 하는 것. 하나라도 없으면 그 run 은 Fast 계약을 세우지 못한
# 상태다 — 손으로 만든 한 줄이거나 중간에 잘린 기록이다.
#
# 두 opener 는 담보가 다르다. 새 Fast run 은 composite 계획의 `profile_hash` 가, 전환 run 은
# 전환 시점에 실재하던 문서 목록(`source_phases_open`)과 확인자가 담보다. 한 집합으로 묶으면
# 정상 전환 run 을 손상으로 몰거나, 반대로 빠진 담보를 못 본다.
# opener 담보 계약의 정본은 `sage.fast_cycle_contract` 에 있다. 이 모듈과 게이트가 **같은**
# 판정을 써야 하는데, 게이트 core 는 파일을 읽지 않아 이 모듈을 import 할 수 없기 때문이다.
# 여기 다시 정의하면 해석기가 둘이 되고, 실제로 그렇게 갈렸을 때 같은 감사가 조회에서는 손상,
# 게이트에서는 정상으로 보였다.
OPENER_REQUIRED = _contract.OPENER_REQUIRED
OPENER_REQUIRED_BY_MODE = _contract.OPENER_REQUIRED_BY_MODE
_absent = _contract.absent
run_issues = _contract.opener_run_issues

# 사람이 읽는 evidence 문자열. 판정은 하지 않고 code 를 화면용 한 줄로 바꾸기만 한다.
_ISSUE_TEXT = {
    "fast_cycle_audit.duplicate_or_orphan": "duplicate or orphan run",
    "fast_cycle_audit.seq_broken": "sequence broken",
    "fast_cycle_audit.chain_invalid": "hash chain invalid",
    "fast_cycle_audit.chain_unverified": "hash chain unverified",
    "fast_cycle_audit.opener_incomplete": "opener fields missing: {fields}",
    "fast_cycle_audit.opener_field_invalid": "opener field out of contract: {field}={value}",
    "fast_cycle_audit.opener_mode_unknown": "unknown opener entry mode: {mode}",
    "fast_cycle_audit.provenance_invalid": "converted provenance is invalid: {detail}",
}


def opener_issues(state):
    """`run_issues` 를 사람이 읽는 한 줄 목록으로. 판정은 하지 않는다."""
    return [_ISSUE_TEXT[code].format(**arguments) for code, arguments in run_issues(state)]


def mode_for_stem(root, cycle_stem):
    """(mode, evidence). mode 는 FAST | STANDARD | UNKNOWN.

    STANDARD 는 **정상적으로 읽은 감사에 이 stem 의 열린 run 이 없다** 는 적극적 사실일
    때만 낸다. 못 읽었거나 깨졌으면 UNKNOWN 이다 — 부재를 STANDARD 로 접으면 손상된
    감사가 조용히 "일반 사이클" 로 보이고, 그건 부재를 안전 방향으로 읽는 것이다.
    """
    summary = snapshot(root)
    evidence = {"audit": summary["status"]}
    if summary["status"] == "absent":
        return "STANDARD", evidence
    if summary["status"] != "valid":
        evidence["issues"] = tuple(summary["file_issues"][:3])
        return "UNKNOWN", evidence

    # 파일이 잘 읽혔다는 것과 감사가 말이 된다는 것은 다르다. 줄이 전부 유효한 JSON 이어도
    # 같은 stem 에 열린 run 이 둘이면 그건 Fast CLI 가 애초에 거부하는 상태다 — 그 상태에서
    # 첫 run 을 골라 FAST 라고 답하면, 답을 만들 수 없는 자리에서 답을 지어내는 것이다.
    damaged = sorted(run_id for run_id, state in summary["runs"].items()
                     if opener_issues(state))
    if damaged:
        evidence["damaged_runs"] = tuple(damaged)
        evidence["issues"] = tuple(opener_issues(summary["runs"][damaged[0]])[:3])
        return "UNKNOWN", evidence

    mine = [run_id for run_id in summary["active"]
            if cycle_stem is None or summary["runs"].get(run_id, {}).get("cycle_stem") == cycle_stem]
    if len(mine) > 1:
        evidence["active_runs"] = tuple(mine)
        return "UNKNOWN", evidence
    if not mine:
        return "STANDARD", evidence

    run_id = mine[0]
    evidence["run_id"] = run_id
    # 전환 run 의 entry_mode 는 FAST-CONVERTED 다. 표시 mode 는 FAST 로 정규화하되
    # 어떻게 들어왔는지는 지운다 — 그 구분은 evidence 가 진다.
    evidence["entry_mode"] = summary["runs"][run_id].get("entry_mode")
    return "FAST", evidence


def _diagnostic(code, **arguments):
    """언어 중립 진단(code+arguments). 이 모듈은 sage.diagnostics 를 import 할 수 없어
    (엔진 없이 소비 프로젝트에서 단독 실행되어야 하므로) 같은 모양의 plain dict 로 올린다 —
    CLI 호출부(cycle.py/fast_cycle.py)가 각자 필요한 언어로 렌더한다."""
    return {"code": code, "arguments": arguments, "evidence": ""}


def integrity_issues(root):
    """감사의 결함 진단 목록. 판정은 `run_issues` 가 하고 여기는 진단으로 옮기기만 한다."""
    summary = audit_summary(root)
    issues = [_diagnostic("fast_cycle_audit.damaged", detail=item) for item in summary["file_issues"]]
    for rid in sorted(summary["runs"]):
        for code, arguments in run_issues(summary["runs"][rid]):
            issues.append(_diagnostic(code, run_id=rid, **arguments))
    return issues
