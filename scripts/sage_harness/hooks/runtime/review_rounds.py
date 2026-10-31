"""review_rounds — Phase 05 리뷰 라운드 사이드카와 사이클 이월 장부.

감사(`loop_audit`)는 개수만 남긴다. 무엇을 지적했고 왜 탈락했으며 무엇을 고쳤는지는 남지 않아,
사이클이 끝난 뒤 판단하려면 문서·패킷·세션 기록을 손으로 대조해야 했다. 사이드카가 그 내용을
라운드마다 파일로 남긴다.

사이드카는 로컬 산출물이다(`/.sage/*` 는 git 무시). CI 와 게이트는 감사만 읽으므로 승인 판단에
쓰는 값은 `derive` 가 계산해 감사 라운드 기록에 같이 적는다. 사이드카는 감사가 적은 sha256 과
맞을 때만 읽는다.

이월 장부는 저장하지 않는다. 감사·사이드카·수동 항목에서 매번 만든다 — 저장한 장부는 원본과
어긋날 수 있고, 어긋나면 어느 쪽이 맞는지 말할 근거가 없다.

엔진(`sage` 패키지)을 import 하지 않는다. 설치본 hook 트리에서 단독으로 돈다.
"""
import hashlib
import json
import math
import os
import re
import sys
import tempfile
import time

HOOKS_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if HOOKS_DIR not in sys.path:
    sys.path.insert(0, HOOKS_DIR)
import cycle_binding  # noqa: E402

SCHEMA = "sage.review-round/1"
BASE_REL = os.path.join(".sage", "review-rounds")
SEVERITIES = ("P0", "P1", "P2", "P3")
MAX_SIDECAR_BYTES = 1024 * 1024
MAX_FINDINGS = 500
MAX_CLAIM = 8192
MAX_TEXT = 4096
MAX_NAME = 200

_SOURCE_KINDS = ("lens", "peer")
_VERDICTS = ("refuted", "upheld", "uncertain")
_DROP_REASONS = ("not_a_defect", "out_of_scope_preexisting", "insufficient_evidence")
_STATUSES = ("survived", "refuted")
_TRIAGES = ("local", "architecture_change")
_DISPOSITIONS = ("fix", "residual", "rejected", "pending")
_MEASURED = ("measured", "estimated", "absent")
_UNEXPLORED_REASONS = ("truncated", "timeout", "limit", "other")
# 변경 전 결함에 이번 변경이 준 영향: 그대로·그 줄을 고침·도달 가능성·입력·권한을 바꿈.
_EXPOSURES = ("unchanged", "touched", "reach_changed")
_CATEGORY = re.compile(r"^[a-z][a-z0-9_]{0,63}$")
_PEER_KEYS = ("new_input", "cached_input", "output", "turns", "tool_calls", "wall_s", "cost_usd")
# `sage cross-check` 가 끊긴 실행을 메시지별 usage 로 추정했을 때만 붙는 표기.
_PEER_MEASURED = ("partial",)
_CLOSES = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}:\d{1,6}:[^:\s]{1,64}$")
_RUN_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
_HEX64 = re.compile(r"^[0-9a-f]{64}$")

LEDGER_KINDS = ("out_of_scope", "preexisting", "withdraw")
_LEDGER_REF = re.compile(r"^L-\d{1,6}$")

_TOP_KEYS = {"schema", "run_id", "iteration", "packet", "usage", "unexplored", "findings",
             "closure"}
_FINDING_KEYS = {"id", "source", "file", "line", "severity", "critical", "critical_category",
                 "preexisting", "exposure", "claim", "refute", "refute_pending", "status", "triage",
                 "disposition", "closes", "ledger_ref"}
# 크리티컬급 = P0·P1 이거나 크리티컬 표시(P2)가 있는 지적. 변경 전 결함이라도 노출이 바뀌면 버리지 못한다.
# 수렴에 쓰는 `severity_block` 과 일부러 분리한다 — 그 값을 낮추면 P0 보호가 빠지고, 높이면 P3 까지 막힌다.
_CRITICAL_GRADE = ("P0", "P1")


class SidecarError(ValueError):
    """사이드카가 계약을 지키지 않는다. 메시지는 첫 위반 몇 개를 담는다."""


# ---------------------------------------------------------------- 검사·정규화

def _is_int(value, minimum=0):
    return type(value) is int and value >= minimum


def _text(issues, where, value, limit, required=True):
    if value is None and not required:
        return None
    if not isinstance(value, str) or (required and not value.strip()):
        issues.append(f"{where} must be a non-empty string")
        return None
    if len(value) > limit:
        issues.append(f"{where} exceeds {limit} characters")
        return None
    return value


def _choice(issues, where, value, allowed, nullable=False):
    if value is None and nullable:
        return None
    if value not in allowed:
        issues.append(f"{where} must be one of {list(allowed)}" + (" or null" if nullable else ""))
        return None
    return value


def _bool(issues, where, value, default, nullable=False):
    if value is None:
        return None if nullable else default
    if not isinstance(value, bool):
        issues.append(f"{where} must be a boolean" + (" or null" if nullable else ""))
        return default
    return value


def _unknown_keys(issues, where, mapping, allowed):
    extra = sorted(set(mapping) - set(allowed), key=str)
    if extra:
        issues.append(f"{where} has unknown keys {extra}")


def _usage(issues, raw):
    if raw is None:
        return {"host": None, "peer": None, "lenses": []}
    if not isinstance(raw, dict):
        issues.append("usage must be an object")
        return {"host": None, "peer": None, "lenses": []}
    _unknown_keys(issues, "usage", raw, {"host", "peer", "lenses"})
    host = raw.get("host")
    if host is not None:
        if not isinstance(host, dict):
            issues.append("usage.host must be an object")
            host = None
        else:
            _unknown_keys(issues, "usage.host", host, {"input", "output", "measured"})
            for key in ("input", "output"):
                if not _is_int(host.get(key)):
                    issues.append(f"usage.host.{key} must be a non-negative integer")
            _choice(issues, "usage.host.measured", host.get("measured"), ("measured", "estimated"))
            host = {"input": host.get("input"), "output": host.get("output"),
                    "measured": host.get("measured")}
    peer = raw.get("peer")
    if peer is not None and peer != "unknown":
        if not isinstance(peer, dict):
            issues.append("usage.peer must be an object, \"unknown\" or null")
            peer = None
        else:
            # `--peer-tokens` 를 함께 주면 그 값과 대조하지만, 생략하면 이 값이 그대로 남는다.
            # 그래서 `parse_peer_tokens` 와 같은 규칙을 여기서도 직접 본다.
            _unknown_keys(issues, "usage.peer", peer, set(_PEER_KEYS) | {"measured"})
            for key in _PEER_KEYS:
                if key not in peer:
                    if key in ("new_input", "output"):
                        issues.append(f"usage.peer.{key} is required")
                    continue
                value = peer[key]
                if key == "cost_usd":
                    if (isinstance(value, bool) or not isinstance(value, (int, float))
                            or not math.isfinite(value) or value < 0):
                        issues.append("usage.peer.cost_usd must be a non-negative finite number")
                elif not _is_int(value):
                    issues.append(f"usage.peer.{key} must be a non-negative integer")
            if "measured" in peer and peer["measured"] not in _PEER_MEASURED:
                issues.append(f"usage.peer.measured must be one of {list(_PEER_MEASURED)}")
            peer = dict(peer)
    lenses = []
    raw_lenses = raw.get("lenses") or []
    if not isinstance(raw_lenses, list):
        issues.append("usage.lenses must be a list")
        raw_lenses = []
    for index, item in enumerate(raw_lenses):
        where = f"usage.lenses[{index}]"
        if not isinstance(item, dict):
            issues.append(f"{where} must be an object")
            continue
        _unknown_keys(issues, where, item, {"lens", "thread_id", "input", "output", "measured"})
        entry = {"lens": _text(issues, f"{where}.lens", item.get("lens"), MAX_NAME),
                 "thread_id": _text(issues, f"{where}.thread_id", item.get("thread_id"),
                                    MAX_NAME, required=False),
                 "input": None, "output": None,
                 "measured": _choice(issues, f"{where}.measured", item.get("measured"), _MEASURED)}
        for key in ("input", "output"):
            value = item.get(key)
            if value is not None and not _is_int(value):
                issues.append(f"{where}.{key} must be a non-negative integer or null")
            else:
                entry[key] = value
        lenses.append(entry)
    return {"host": host, "peer": peer, "lenses": lenses}


def _finding(issues, index, raw, seen_ids):
    where = f"findings[{index}]"
    if not isinstance(raw, dict):
        issues.append(f"{where} must be an object")
        return None
    _unknown_keys(issues, where, raw, _FINDING_KEYS)
    fid = _text(issues, f"{where}.id", raw.get("id"), 64)
    if fid is not None:
        if any(ch.isspace() or ch == ":" for ch in fid):
            issues.append(f"{where}.id must not contain whitespace or ':'")
        elif fid in seen_ids:
            issues.append(f"{where}.id {fid!r} is duplicated")
        seen_ids.add(fid)
    source = raw.get("source")
    if not isinstance(source, dict):
        issues.append(f"{where}.source must be an object")
        source = {}
    else:
        _unknown_keys(issues, f"{where}.source", source, {"kind", "name"})
    source = {"kind": _choice(issues, f"{where}.source.kind", source.get("kind"), _SOURCE_KINDS),
              "name": _text(issues, f"{where}.source.name", source.get("name"), MAX_NAME)}
    line = raw.get("line")
    if line is not None and not _is_int(line, 1):
        issues.append(f"{where}.line must be a positive integer or null")
        line = None
    refute = []
    raw_refute = raw.get("refute") or []
    if not isinstance(raw_refute, list):
        issues.append(f"{where}.refute must be a list")
        raw_refute = []
    for vote_index, vote in enumerate(raw_refute):
        vwhere = f"{where}.refute[{vote_index}]"
        if not isinstance(vote, dict):
            issues.append(f"{vwhere} must be an object")
            continue
        _unknown_keys(issues, vwhere, vote, {"refuter", "verdict", "reason", "drop_reason"})
        refute.append({
            "refuter": _text(issues, f"{vwhere}.refuter", vote.get("refuter"), MAX_NAME),
            "verdict": _choice(issues, f"{vwhere}.verdict", vote.get("verdict"), _VERDICTS),
            "reason": _text(issues, f"{vwhere}.reason", vote.get("reason"), MAX_TEXT,
                            required=False),
            "drop_reason": _choice(issues, f"{vwhere}.drop_reason", vote.get("drop_reason"),
                                   _DROP_REASONS, nullable=True),
        })
    closes = raw.get("closes") or []
    if not isinstance(closes, list):
        issues.append(f"{where}.closes must be a list")
        closes = []
    for item in closes:
        if not isinstance(item, str) or not _CLOSES.match(item):
            issues.append(f"{where}.closes entries must look like <run_id>:<iteration>:<id>")
    ledger_ref = raw.get("ledger_ref")
    if ledger_ref is not None and (not isinstance(ledger_ref, str)
                                   or not _LEDGER_REF.match(ledger_ref)):
        issues.append(f"{where}.ledger_ref must look like L-<n> or be null")
    category = raw.get("critical_category")
    if category is not None and (not isinstance(category, str) or not _CATEGORY.match(category)):
        issues.append(f"{where}.critical_category must look like a lower_snake_case id or be null")
        category = None
    return {
        "id": fid,
        "source": source,
        "file": _text(issues, f"{where}.file", raw.get("file"), MAX_TEXT, required=False),
        "line": line,
        "severity": _choice(issues, f"{where}.severity", raw.get("severity"), SEVERITIES),
        "critical": _bool(issues, f"{where}.critical", raw.get("critical"), False),
        "critical_category": category,
        "preexisting": _bool(issues, f"{where}.preexisting", raw.get("preexisting"), None,
                             nullable=True),
        "exposure": _choice(issues, f"{where}.exposure", raw.get("exposure"), _EXPOSURES,
                            nullable=True),
        "claim": _text(issues, f"{where}.claim", raw.get("claim"), MAX_CLAIM),
        "refute": refute,
        "refute_pending": _bool(issues, f"{where}.refute_pending", raw.get("refute_pending"),
                                False),
        "status": _choice(issues, f"{where}.status", raw.get("status"), _STATUSES),
        "triage": _choice(issues, f"{where}.triage", raw.get("triage"), _TRIAGES, nullable=True),
        "disposition": _choice(issues, f"{where}.disposition", raw.get("disposition"),
                               _DISPOSITIONS, nullable=True),
        "closes": [item for item in closes if isinstance(item, str)],
        "ledger_ref": ledger_ref,
    }


def validate(doc, run_id=None, iteration=None):
    """사이드카를 검사하고 정규화한 사본을 돌려준다. 위반이 있으면 SidecarError.

    정규화는 선택 필드를 명시값(null·빈 목록)으로 채우는 것뿐이다. 값의 뜻을 바꾸지 않는다.
    """
    issues = []
    if not isinstance(doc, dict):
        raise SidecarError("sidecar must be a JSON object")
    _unknown_keys(issues, "sidecar", doc, _TOP_KEYS)
    if doc.get("schema") != SCHEMA:
        issues.append(f"schema must be {SCHEMA!r}")
    if not isinstance(doc.get("run_id"), str) or not _RUN_ID.match(doc.get("run_id") or ""):
        issues.append("run_id must be a run id string")
    elif run_id is not None and doc["run_id"] != run_id:
        issues.append(f"run_id {doc['run_id']!r} does not match --run-id {run_id!r}")
    if not _is_int(doc.get("iteration")):
        issues.append("iteration must be a non-negative integer")
    elif iteration is not None and doc["iteration"] != iteration:
        issues.append(f"iteration {doc['iteration']} does not match --iteration {iteration}")
    packet = doc.get("packet")
    if packet is not None:
        if not isinstance(packet, dict):
            issues.append("packet must be an object or null")
            packet = None
        else:
            _unknown_keys(issues, "packet", packet, {"path", "sha256"})
            packet = {"path": _text(issues, "packet.path", packet.get("path"), MAX_TEXT),
                      "sha256": packet.get("sha256")}
            if not isinstance(packet["sha256"], str) or not _HEX64.match(packet["sha256"]):
                issues.append("packet.sha256 must be 64 lowercase hex characters")
    usage = _usage(issues, doc.get("usage"))
    unexplored = []
    raw_unexplored = doc.get("unexplored") or []
    if not isinstance(raw_unexplored, list):
        issues.append("unexplored must be a list")
        raw_unexplored = []
    for index, item in enumerate(raw_unexplored):
        where = f"unexplored[{index}]"
        if not isinstance(item, dict):
            issues.append(f"{where} must be an object")
            continue
        _unknown_keys(issues, where, item, {"lens", "reason", "detail"})
        unexplored.append({
            "lens": _text(issues, f"{where}.lens", item.get("lens"), MAX_NAME),
            "reason": _choice(issues, f"{where}.reason", item.get("reason"), _UNEXPLORED_REASONS),
            "detail": _text(issues, f"{where}.detail", item.get("detail"), MAX_TEXT,
                            required=False),
        })
    closure = []
    raw_closure = doc.get("closure") or []
    if not isinstance(raw_closure, list):
        issues.append("closure must be a list")
        raw_closure = []
    for index, item in enumerate(raw_closure):
        where = f"closure[{index}]"
        if not isinstance(item, dict):
            issues.append(f"{where} must be an object")
            continue
        _unknown_keys(issues, where, item, {"ref", "closed", "note"})
        ref = item.get("ref")
        if not isinstance(ref, str) or not _CLOSES.match(ref):
            issues.append(f"{where}.ref must look like <run_id>:<iteration>:<id>")
            ref = None
        if not isinstance(item.get("closed"), bool):
            issues.append(f"{where}.closed must be a boolean")
        closure.append({"ref": ref, "closed": item.get("closed") is True,
                        "note": _text(issues, f"{where}.note", item.get("note"), MAX_TEXT,
                                      required=False)})
    raw_findings = doc.get("findings")
    findings = []
    if not isinstance(raw_findings, list):
        issues.append("findings must be a list")
    elif len(raw_findings) > MAX_FINDINGS:
        issues.append(f"findings exceeds {MAX_FINDINGS} entries")
    else:
        seen_ids = set()
        for index, raw in enumerate(raw_findings):
            item = _finding(issues, index, raw, seen_ids)
            if item is not None:
                findings.append(item)
    if issues:
        more = "" if len(issues) <= 5 else f"; ... and {len(issues) - 5} more"
        raise SidecarError("; ".join(issues[:5]) + more)
    return {"schema": SCHEMA, "run_id": doc["run_id"], "iteration": doc["iteration"],
            "packet": packet, "usage": usage, "unexplored": unexplored, "findings": findings,
            "closure": closure}


def parse(data, run_id=None, iteration=None):
    """원문 바이트 → 정규화 사이드카. 크기·인코딩·JSON 위반은 SidecarError."""
    if len(data) > MAX_SIDECAR_BYTES:
        raise SidecarError(f"sidecar exceeds {MAX_SIDECAR_BYTES} bytes")
    try:
        doc = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, ValueError) as exc:
        raise SidecarError(f"sidecar is not valid UTF-8 JSON: {exc}") from None
    normalized = validate(doc, run_id=run_id, iteration=iteration)
    # 저장본은 정규화(들여쓰기·키 정렬)한 바이트라 입력보다 커질 수 있다. `load` 는 상한까지만
    # 읽으므로, 저장본이 상한을 넘으면 기록은 되고 다시는 읽히지 않는다. 쓰기 전에 거부한다.
    if len(canonical_bytes(normalized)) > MAX_SIDECAR_BYTES:
        raise SidecarError(f"normalized sidecar exceeds {MAX_SIDECAR_BYTES} bytes")
    return normalized


def derive(doc):
    """감사 라운드 기록에 쓸 값. 손으로 쓰던 개수와 영수증을 여기서 산출한다."""
    findings = doc["findings"]
    survived = [item for item in findings if item["status"] == "survived"]
    refuted = [item for item in findings if item["status"] == "refuted"]
    by_severity = {key: 0 for key in SEVERITIES}
    for item in survived:
        by_severity[item["severity"]] += 1
    return {
        "found": len(findings),
        "survived": len(survived),
        "accepted": sum(1 for item in survived if item["disposition"] == "fix"),
        "arch": sum(1 for item in survived if item["triage"] == "architecture_change"),
        "survived_by_severity": by_severity,
        "receipt": {
            "critical": sum(1 for item in survived if item["critical"]),
            "refute_pending": sum(1 for item in survived if item["refute_pending"]),
            "unexplored": len(doc["unexplored"]),
            "residual": sum(1 for item in survived if item["disposition"] == "residual"),
            "refuted_out_of_scope": sum(
                1 for item in refuted
                if any(vote["drop_reason"] == "out_of_scope_preexisting"
                       for vote in item["refute"])),
            # 크리티컬 결정은 이 목록(id·주장 해시)에 묶인다. 감사에 있으므로 CI·게이트도 같은
            # 대조를 사이드카 없이 한다.
            "critical_findings": [
                {"id": item["id"], "claim_sha256": claim_sha256(item["claim"]),
                 "category": item["critical_category"]}
                for item in survived if item["critical"]],
            "unclosed": sum(1 for item in doc.get("closure") or [] if not item["closed"]),
        },
    }


def claim_sha256(claim):
    return hashlib.sha256((claim or "").encode("utf-8")).hexdigest()


def policy_issues(doc, policy):
    """`converge_on: blocking` run 의 사이드카 규칙. 형식 검사(`validate`)와 따로 둔다.

    `all` run 에는 부르지 않는다 — 기본 모드의 기록 동작을 바꾸지 않는다. 판정은 리뷰어가 하고,
    엔진은 그 판정이 서로 모순되지 않는지와 크리티컬급 결함을 「변경 전」을 이유로 버릴 때의 조건만
    본다.
    """
    issues = []
    allowed = set(policy.get("critical_p2") or [])
    for item in doc["findings"]:
        where = f"finding {item['id']}"
        if item["critical"] and item["severity"] != "P2":
            issues.append(f"{where}: critical marks a P2 only (severity {item['severity']})")
        if item["critical"] and item["critical_category"] not in allowed:
            issues.append(f"{where}: critical_category {item['critical_category']!r} is not one "
                          f"of {sorted(allowed)}")
        if item["critical_category"] is not None and not item["critical"]:
            issues.append(f"{where}: critical_category is set but critical is false")
        if item["exposure"] is not None and item["preexisting"] is not True:
            issues.append(f"{where}: exposure is only for preexisting findings")
        out_of_scope = any(vote["drop_reason"] == "out_of_scope_preexisting"
                           for vote in item["refute"])
        if out_of_scope and item["preexisting"] is not True:
            issues.append(f"{where}: out_of_scope_preexisting needs preexisting: true")
        grade = item["critical"] or item["severity"] in _CRITICAL_GRADE
        if (out_of_scope and grade and item["status"] == "refuted"
                and item["exposure"] != "unchanged"):
            issues.append(f"{where}: a {item['severity']} critical-grade finding can be dropped as "
                          "out_of_scope_preexisting only when exposure is 'unchanged' (this change "
                          "touched it or changed its reach)")
    return issues


def canonical_bytes(doc):
    return (json.dumps(doc, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")


def sha256_hex(data):
    return hashlib.sha256(data).hexdigest()


# ---------------------------------------------------------------- 경로·저장

def _safe_dir(root, *parts):
    """root 아래 디렉터리를 만들고 실제 경로를 돌려준다. symlink 를 따라가지 않는다.

    `.sage/review-rounds/runs/<run_id>` 처럼 사용자 입력이 경로에 들어간다. 각 단계에서 symlink 를
    거부하고, 마지막에 실제 경로가 root 안인지 다시 본다.
    """
    root_real = os.path.realpath(root)
    current = root_real
    for part in parts:
        if not part or part in (".", "..") or "/" in part or "\\" in part or "\0" in part:
            raise SidecarError(f"unsafe path component {part!r}")
        current = os.path.join(current, part)
        if os.path.islink(current):
            raise SidecarError(f"refusing symlinked directory {current}")
        if not os.path.isdir(current):
            if os.path.lexists(current):
                raise SidecarError(f"not a directory: {current}")
            os.mkdir(current)
    real = os.path.realpath(current)
    if os.path.commonpath((root_real, real)) != root_real:
        raise SidecarError(f"directory escapes project root: {real}")
    return real


def _atomic_write(directory, name, data):
    target = os.path.join(directory, name)
    fd, temp = tempfile.mkstemp(prefix=".sage-round-", dir=directory)
    try:
        with os.fdopen(fd, "wb") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temp, target)
    except BaseException:
        try:
            os.unlink(temp)
        except OSError:
            pass
        raise
    return target


def _base_parts():
    return BASE_REL.split(os.sep)


def run_dir(root, run_id):
    if not isinstance(run_id, str) or not _RUN_ID.match(run_id):
        raise SidecarError(f"unsafe run id {run_id!r}")
    return _safe_dir(root, *_base_parts(), "runs", run_id)


def cycle_dir(root, stem):
    normalized = cycle_binding.normalize_stem(stem)
    if normalized is None:
        raise SidecarError(f"invalid cycle stem {stem!r}")
    return _safe_dir(root, *_base_parts(), "cycles", normalized)


def _relpath(root, path):
    return os.path.relpath(path, os.path.realpath(root)).replace(os.sep, "/")


def store(root, doc):
    """정규화 사이드카를 저장하고 감사에 적을 참조를 돌려준다.

    파일명에 해시를 붙인다. 같은 iteration 이 두 번 기록될 수 있어서다(`record_round` 는
    iteration 단조성을 강제하지 않는다). 어느 파일이 그 라운드의 것인지는 감사의 경로·해시가 정한다.
    """
    data = canonical_bytes(doc)
    digest = sha256_hex(data)
    directory = run_dir(root, doc["run_id"])
    name = f"{doc['iteration']}-{digest[:12]}.json"
    path = _atomic_write(directory, name, data)
    return {"path": _relpath(root, path), "sha256": digest, "schema": SCHEMA}


def store_patch(root, run_id, iteration, data):
    """직전 라운드 대비 delta. 내용 주소 이름이라 같은 내용은 같은 파일이다."""
    directory = run_dir(root, run_id)
    name = f"{iteration}-{sha256_hex(data)[:12]}.patch"
    return _relpath(root, _atomic_write(directory, name, data))


def load(root, ref):
    """감사의 사이드카 참조 → (정규화 사이드카, 문제). 해시가 다르면 읽지 않는다."""
    if not isinstance(ref, dict):
        return None, "absent"
    rel = ref.get("path")
    expected = ref.get("sha256")
    if not isinstance(rel, str) or not isinstance(expected, str):
        return None, "malformed reference"
    root_real = os.path.realpath(root)
    path = os.path.realpath(os.path.join(root_real, rel))
    if os.path.commonpath((root_real, path)) != root_real:
        return None, "reference escapes project root"
    if os.path.islink(os.path.join(root_real, rel)):
        return None, "reference is a symlink"
    try:
        with open(path, "rb") as handle:
            data = handle.read(MAX_SIDECAR_BYTES + 1)
    except OSError as exc:
        return None, f"unreadable: {type(exc).__name__}"
    if sha256_hex(data) != expected:
        return None, "sha256 mismatch"
    try:
        return parse(data), None
    except SidecarError as exc:
        return None, f"invalid: {exc}"


# ---------------------------------------------------------------- 이월 장부

def _entries_path(root, stem):
    return os.path.join(cycle_dir(root, stem), "entries.jsonl")


def _existing_path(root, *parts):
    """root 아래 이미 있는 경로를 만들지 않고 찾는다 → (경로 또는 None, 문제 또는 None).

    읽기도 쓰기(`_safe_dir`)와 같이 각 단계의 symlink 를 거부한다. 마지막 파일만 보면 상위
    디렉터리 하나를 바깥으로 돌려 프로젝트 밖 파일을 장부로 읽힐 수 있다.
    """
    root_real = os.path.realpath(root)
    current = root_real
    for part in parts:
        current = os.path.join(current, part)
        if os.path.islink(current):
            return None, f"{_relpath(root, current)} is a symlink"
        if not os.path.lexists(current):
            return None, None
    real = os.path.realpath(current)
    if os.path.commonpath((root_real, real)) != root_real:
        return None, "ledger path escapes project root"
    return current, None


def _entry_issues(number, item, known):
    """수동 항목 한 줄의 구조. `add_entry` 가 쓰는 모양과 번호 규칙을 그대로 본다."""
    where = f"entries.jsonl line {number}"
    issues = []
    entry_id = item.get("id")
    kind = item.get("kind")
    if not isinstance(entry_id, str) or not _LEDGER_REF.match(entry_id):
        issues.append(f"{where}: id must look like L-<n>")
    elif entry_id in known:
        issues.append(f"{where}: id {entry_id} is duplicated")
    if kind not in LEDGER_KINDS:
        issues.append(f"{where}: kind must be one of {list(LEDGER_KINDS)}")
    if not isinstance(item.get("reason"), str) or not item.get("reason").strip():
        issues.append(f"{where}: reason is missing")
    if kind == "withdraw":
        ref = item.get("ref")
        if not isinstance(ref, str) or ref not in known or known.get(ref) == "withdraw":
            issues.append(f"{where}: withdraw refers to unknown entry {ref!r}")
    elif kind in LEDGER_KINDS:
        if not isinstance(item.get("source"), str) or not item.get("source").strip():
            issues.append(f"{where}: source is missing")
        if item.get("severity") is not None and item.get("severity") not in SEVERITIES:
            issues.append(f"{where}: severity must be one of {list(SEVERITIES)} or null")
    return issues


def read_entries(root, stem):
    """수동 장부 항목(append 순). 깨진 줄·구조 위반은 건너뛰지 않고 문제로 돌려준다.

    수동 항목은 감사 hash chain 밖에 있다. 손상을 여기서 잡지 않으면 잡을 곳이 없다.
    """
    normalized = cycle_binding.normalize_stem(stem)
    if normalized is None:
        return [], [f"invalid cycle stem {stem!r}"]
    path, problem = _existing_path(root, *_base_parts(), "cycles", normalized, "entries.jsonl")
    if problem:
        return [], [problem]
    if path is None:
        return [], []
    entries, issues = [], []
    known = {}
    try:
        with open(path, encoding="utf-8") as handle:
            for number, line in enumerate(handle, 1):
                if not line.strip():
                    continue
                try:
                    item = json.loads(line)
                except ValueError:
                    issues.append(f"entries.jsonl line {number}: malformed JSON")
                    continue
                if not isinstance(item, dict):
                    issues.append(f"entries.jsonl line {number}: not an object")
                    continue
                problems = _entry_issues(number, item, known)
                if problems:
                    issues.extend(problems)
                    continue
                known[item["id"]] = item["kind"]
                entries.append(item)
    except (OSError, UnicodeDecodeError) as exc:
        issues.append(f"entries.jsonl unreadable: {type(exc).__name__}: {exc}")
    return entries, issues


def add_entry(root, stem, kind, source=None, reason=None, severity=None, location=None,
              ref=None, now=None, lock=None):
    """수동 장부 항목 1건. 지우지 않는다 — 철회도 `withdraw` 항목으로 남긴다.

    `lock` 은 경로를 받아 컨텍스트 매니저를 돌려주는 함수다(`loop_audit._audit_lock`). 번호를
    매기는 읽기와 append 를 한 잠금 안에서 해야 두 세션이 같은 번호를 쓰지 않는다.
    """
    if kind not in LEDGER_KINDS:
        raise SidecarError(f"kind must be one of {list(LEDGER_KINDS)}")
    issues = []
    reason = _text(issues, "reason", reason, MAX_TEXT)
    if kind == "withdraw":
        if not isinstance(ref, str) or not _LEDGER_REF.match(ref):
            issues.append("withdraw needs --ref L-<n>")
    else:
        source = _text(issues, "source", source, MAX_TEXT)
        if severity is not None:
            _choice(issues, "severity", severity, SEVERITIES)
        location = _text(issues, "location", location, MAX_TEXT, required=False)
    if issues:
        raise SidecarError("; ".join(issues))
    path = _entries_path(root, stem)

    def _write():
        entries, problems = read_entries(root, stem)
        if problems:
            raise SidecarError("ledger entries are damaged: " + "; ".join(problems[:3]))
        known = {item.get("id"): item.get("kind") for item in entries}
        if kind == "withdraw" and (ref not in known or known[ref] == "withdraw"):
            raise SidecarError(f"unknown ledger entry {ref}")
        t = time.time() if now is None else now
        entry = {"id": f"L-{len(entries) + 1}", "kind": kind,
                 "ts": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(t)), "reason": reason}
        if kind == "withdraw":
            entry["ref"] = ref
        else:
            entry.update({"source": source, "severity": severity, "location": location})
        flags = os.O_WRONLY | os.O_CREAT | os.O_APPEND | getattr(os, "O_NOFOLLOW", 0)
        fd = os.open(path, flags, 0o600)
        try:
            os.write(fd, (json.dumps(entry, ensure_ascii=False) + "\n").encode("utf-8"))
            os.fsync(fd)
        finally:
            os.close(fd)
        return entry

    if lock is None:
        return _write()
    with lock(path):
        return _write()


def build_ledger(root, records, stem):
    """사이클의 이월 장부. 감사 레코드·해시가 맞는 사이드카·수동 항목에서 만든다."""
    opens = [r for r in records if r.get("event") == "loop_open" and r.get("cycle_stem") == stem]
    run_ids = [r.get("run_id") for r in opens]
    members = set(run_ids)
    residual, refuted, conflicts, sidecar_issues = [], [], [], []
    decisions, closes, preexisting, unclosed = [], [], [], []
    last_round = {}
    for record in records:
        rid = record.get("run_id")
        if rid not in members:
            continue
        event = record.get("event")
        if event == "round":
            last_round[rid] = record
            ref = record.get("sidecar")
            if not isinstance(ref, dict):
                continue
            doc, problem = load(root, ref)
            if problem:
                sidecar_issues.append({"run_id": rid, "iteration": record.get("iteration"),
                                       "problem": problem})
                continue
            for item in doc["findings"]:
                where = {"run_id": rid, "iteration": doc["iteration"], "id": item["id"],
                         "severity": item["severity"], "file": item["file"],
                         "line": item["line"], "claim": item["claim"]}
                if item["status"] == "refuted":
                    reasons = [vote for vote in item["refute"] if vote["verdict"] == "refuted"]
                    refuted.append({**where, "reasons": [
                        {"reason": vote["reason"], "drop_reason": vote["drop_reason"]}
                        for vote in reasons]})
                    # 변경 전 결함을 범위 밖으로 버린 것은 사라지는 것이 아니라 별도 이슈 후보다.
                    if any(vote["drop_reason"] == "out_of_scope_preexisting"
                           for vote in item["refute"]):
                        preexisting.append({**where, "exposure": item.get("exposure")})
                if item["ledger_ref"]:
                    conflicts.append({**where, "ledger_ref": item["ledger_ref"]})
            for check in doc.get("closure") or []:
                if not check["closed"]:
                    unclosed.append({"run_id": rid, "iteration": doc["iteration"],
                                     "ref": check["ref"], "note": check["note"]})
        elif event == "decision":
            decisions.append({key: record.get(key) for key in (
                "run_id", "kind", "choice", "extend", "cycle_rounds", "cap_before", "cap_after",
                "iteration", "finding_id", "category", "reason", "decided_by", "ts")})
        elif event == "loop_close":
            closes.append({key: record.get(key) for key in (
                "run_id", "result", "reason", "iterations", "authorization_reason",
                "confirmed_by", "survived_by_severity", "stopped_at", "residual",
                "review_assurance", "ts")})
    # 잔여는 각 run 의 마지막 라운드에서만 읽는다. 앞 라운드의 잔여는 다음 라운드가 다시 판정했다.
    for rid in run_ids:
        record = last_round.get(rid)
        if record is None or not isinstance(record.get("sidecar"), dict):
            continue
        doc, problem = load(root, record["sidecar"])
        if problem:
            continue
        for item in doc["findings"]:
            if item["status"] == "survived" and item["disposition"] in ("residual", "pending", None):
                residual.append({"run_id": rid, "iteration": doc["iteration"], "id": item["id"],
                                 "severity": item["severity"], "file": item["file"],
                                 "line": item["line"], "claim": item["claim"],
                                 "disposition": item["disposition"]})
    entries, entry_issues = read_entries(root, stem)
    withdrawn = {item.get("ref") for item in entries if item.get("kind") == "withdraw"}
    manual = [item for item in entries
              if item.get("kind") in ("out_of_scope", "preexisting")
              and item.get("id") not in withdrawn]
    accepted_critical = [item for item in decisions
                         if item.get("kind") == "critical_p2" and item.get("choice") == "accept"]
    return {"stem": stem, "runs": run_ids, "residual": residual, "refuted": refuted,
            "decisions": decisions, "closes": closes, "manual": manual,
            "accepted_critical": accepted_critical, "preexisting": preexisting,
            "unclosed": unclosed,
            "conflicts": conflicts, "sidecar_issues": sidecar_issues,
            "entry_issues": entry_issues}
