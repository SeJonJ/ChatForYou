"""Deterministic parsing and validation for a composite Fast Plan."""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass

PHASES = ("00", "01", "02", "03", "04")
_HEADING = re.compile(r"(?m)^## Phase (00|01|02|03|04)\b.*$")
_META = re.compile(r"(?m)^([A-Za-z][A-Za-z0-9 -]*):[ \t]*(.*?)\s*$")
_RUN_ID = re.compile(r"^fc-[a-f0-9]{12}$")
_FENCE = re.compile(r"^\s{0,3}(`{3,}|~{3,})")


@dataclass(frozen=True)
class FastPlan:
    metadata: dict[str, str]
    sections: dict[str, str]
    content: str

    @property
    def plan_hash(self) -> str:
        return hashlib.sha256(self.content.encode("utf-8")).hexdigest()


# --- opener 담보 계약 -------------------------------------------------------
#
# 감사 run 하나가 Fast 계약을 실제로 세웠는가. **여기 한 곳**이 정본이다.
#
# 이 계약이 감사 모듈이 아니라 여기 있는 이유는 소비자가 셋이기 때문이다 — 조회(`mode_for_stem`),
# 무결성(`integrity_issues`), 그리고 **게이트**(`_fast_cycle_state`). 앞의 둘을 감사 모듈 안에서
# 통일했을 때 게이트는 여전히 자기 조건문(`clean`/`chain_ok`/`seq_ok`/`terminal`)을 쓰고 있었고,
# `ts` 를 지우고 해시를 다시 계산한 감사가 조회에서는 손상, 게이트에서는 정상 Fast 로 갈렸다.
# 게이트만 느슨한 방향이라 그 상태가 Standard 01·02 면제를 받았다.
#
# 게이트 core 는 파일을 읽지 않으므로 감사 모듈을 import 할 수 없다. 그래서 순수 계약을 양쪽이
# 함께 닿는 이 모듈로 올린다.

OPENER_REQUIRED = ("cycle_stem", "actual_risk", "fast_review_level", "reason",
                   "minimum_rounds", "entry_mode", "lenses", "ts", "epoch", "actor")
OPENER_REQUIRED_BY_MODE = {
    "FAST": ("profile_hash", "plan_hash_open"),
    # `current_phase` 없이는 provenance 가 무엇을 덮어야 하는지 판정할 수 없다.
    "FAST-CONVERTED": ("source_phases_open", "confirmed_by", "current_phase"),
}


# --- 전환 provenance 계약 ---------------------------------------------------
#
# `source_phases_open` 은 Standard→Fast 전환 시점에 **실재하던** phase 문서들의 저장소 상대
# path · raw-byte SHA-256 · byte size 다. 이것이 담보인 이유는 전환 run 이 composite Fast Plan
# 문서를 갖지 않기 때문이다 — 그 run 이 Standard 01·02 요구를 면제받는 근거는 오직 "그때 그
# 문서들이 있었다" 는 기록뿐이다.
#
# 이전에는 그 기록이 **빈 dict 가 아닌지**만 확인됐다. `{"00": {}, "01": {}}` 처럼 구조가 없는
# 값이 writer·감사·게이트 셋을 모두 통과했고, 실제 문서의 근거 없이 Standard 01·02 가
# 면제됐다. 담보의 형태를 확인하지 않으면 담보를 요구한 것이 아니다.

SOURCE_PHASE_ENTRY_KEYS = ("path", "sha256", "size")
_CANONICAL_DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")
_WINDOWS_DRIVE = re.compile(r"^[A-Za-z]:")


def expected_source_phases(current_phase) -> tuple:
    """(반드시 있어야 하는 phase 들, issue). 전환 시점까지의 **연속 접두**다.

    writer(`sage.commands.fast_cycle._source_phase_snapshot`)가 만드는 것이 정확히 이것이다 —
    `PHASES[:index(current_phase)+1]`. 더 적으면 진행하지 않은 phase 를 담보로 셀 수 없고,
    더 많으면 전환 시점에 없던 문서를 담보로 싣는 것이다. 그래서 부분집합이 아니라 **일치**를
    요구한다.
    """
    if current_phase not in PHASES:
        return (), (f"current_phase must be one of {list(PHASES)}, "
                    f"found {current_phase!r}")
    return PHASES[:PHASES.index(current_phase) + 1], None


def _source_phase_path_issue(phase, path) -> str | None:
    """저장소 상대 **파일** 경로의 문법. 절대 경로·상위 이탈·디렉터리 표기를 거부한다.

    문법만 본다 — 확장자는 요구하지 않는다. phase glob 은 profile 이 정하므로 `.md` 를 이
    계층에 박으면 프로필이 소유한 결정을 계약이 가로챈다. "실제로 그 phase 문서인가" 는
    `sage.fast_cycle_sources` 의 정확 대조가 답한다.

    `.` 이 여기서 걸리는 이유가 이 함수의 존재 이유다. `.` 은 저장소 상대 문자열이고 정규
    digest 와 정수 size 를 곁들이면 구조 계약을 통과했지만, 파일이 아니다 — 구조만 보는 검사는
    "저장소 안의 그 파일" 을 결코 보증할 수 없다.
    """
    if not isinstance(path, str) or not path.strip():
        return f"phase {phase} path must be a non-empty string"
    if path != path.strip():
        return f"phase {phase} path must not be padded with whitespace, found {path!r}"
    normalized = path.replace("\\", "/")
    if normalized.startswith("/") or _WINDOWS_DRIVE.match(normalized):
        return f"phase {phase} path must be repository-relative, found {path!r}"
    if normalized.endswith("/"):
        return f"phase {phase} path must name a file, not a directory, found {path!r}"
    parts = normalized.split("/")
    if any(part in ("", ".", "..") for part in parts):
        # 빈 성분(`a//b`)·`.`·`..` 는 파일 이름이 아니다. `.` 하나로 된 경로도 여기서 걸린다.
        return (f"phase {phase} path must be a normalized repository-relative file path, "
                f"found {path!r}")
    return None


def source_phase_snapshot_issue(snapshot, expected) -> str | None:
    """provenance 스냅샷의 구조 계약. `None` 이 통과다.

    writer·감사·게이트 **셋이 이 함수 하나**를 쓴다. writer 만 검증하면 이미 기록된 값이
    영원히 통과하고, 감사만 검증하면 게이트가 자기 판단으로 면제를 준다.
    """
    if not isinstance(snapshot, dict):
        found = type(snapshot).__name__
        return f"source phase snapshot must be an object, found {found}"
    expected = tuple(expected)
    if set(snapshot) != set(expected):
        return (f"source phase snapshot must cover exactly {list(expected)}, "
                f"found {sorted(snapshot)}")
    for phase in expected:
        entry = snapshot.get(phase)
        if not isinstance(entry, dict):
            return f"phase {phase} entry must be an object"
        unknown = sorted(set(entry) - set(SOURCE_PHASE_ENTRY_KEYS))
        if unknown:
            return f"phase {phase} entry has unknown keys: {unknown}"
        missing = [key for key in SOURCE_PHASE_ENTRY_KEYS if key not in entry]
        if missing:
            return f"phase {phase} entry is missing {missing}"
        issue = _source_phase_path_issue(phase, entry["path"])
        if issue:
            return issue
        digest = entry["sha256"]
        if not isinstance(digest, str) or not _CANONICAL_DIGEST.match(digest):
            return f"phase {phase} sha256 must be a canonical digest, found {digest!r}"
        size = entry["size"]
        # `type(...) is not int` 로 본다 — `bool` 은 `int` 의 하위형이라 `isinstance` 로는
        # `True` 가 크기 1 로 통과한다.
        if type(size) is not int or size < 0:
            return f"phase {phase} size must be a non-negative integer, found {size!r}"
    return None


def converted_provenance_issue(current_phase, snapshot) -> str | None:
    """전환 run 한 건의 provenance 계약 전체. phase 집합 결정 + 구조 검증."""
    expected, issue = expected_source_phases(current_phase)
    if issue:
        return issue
    return source_phase_snapshot_issue(snapshot, expected)


def absent(value) -> bool:
    """비어 있는 것은 없는 것이다.

    이전에는 `value in (None, "")` 로 봤다. 그 술어는 필드의 **존재**만 본다 — `lenses=[]` 는
    렌즈를 하나도 고르지 않은 상태이고 `source_phases_open={}` 은 전환 시점 문서를 하나도
    기록하지 않은 상태인데, 둘 다 담보로 인정됐다. 실제 CLI 가 만들 수 없는 값이다.

    `0` 과 `False` 는 값이므로 제외한다. 정책이 고른 숫자를 없는 것으로 읽으면 반대 방향의
    거짓말이 된다.
    """
    if value is None:
        return True
    if isinstance(value, (str, bytes, list, tuple, dict, set, frozenset)):
        return len(value) == 0
    return False


def opener_run_issues(state) -> list[tuple[str, dict]]:
    """이 run 의 결함 목록 — `(code, arguments)`. 빈 리스트가 통과다.

    `chain_ok` 는 `is False` 가 아니라 `is not True` 로 본다 — `None` 은 chain 필드가 아예
    없다는 뜻이고, 그건 "검증했는데 괜찮다" 가 아니라 "검증할 것이 없었다" 다.
    """
    if not isinstance(state, dict):
        return [("fast_cycle_audit.opener_incomplete", {"fields": "<run state unavailable>"})]
    issues = []
    if not state.get("clean", False):
        issues.append(("fast_cycle_audit.duplicate_or_orphan", {}))
    if state.get("seq_ok") is False:
        issues.append(("fast_cycle_audit.seq_broken", {}))
    if state.get("chain_ok") is False:
        issues.append(("fast_cycle_audit.chain_invalid", {}))
    elif state.get("chain_ok") is not True:
        issues.append(("fast_cycle_audit.chain_unverified", {}))
    # `.get(mode, ())` 는 **모르는 mode 를 요구 없음으로** 읽는다. 그래서 `entry_mode` 를
    # 아무 문자열로 바꾸면 fresh 전용 담보(`profile_hash`·`plan_hash_open`)도, 전환 전용
    # 담보(`source_phases_open`·`confirmed_by`)도 요구되지 않았다. 부재를 안전 방향으로 읽는
    # 것과 같은 모양이 dict 조회에서 난 것이다 — mode 를 모르면 무엇을 실어야 하는지도 모른다.
    entry_mode = state.get("entry_mode")
    if entry_mode is not None and entry_mode not in OPENER_REQUIRED_BY_MODE:
        issues.append(("fast_cycle_audit.opener_mode_unknown", {"mode": repr(entry_mode)}))
    if entry_mode == "FAST-CONVERTED":
        # 담보의 **형태**를 본다. 비어 있지 않다는 것은 담보가 있다는 뜻이 아니다 —
        # `{"00": {}}` 는 문서가 있었다는 어떤 근거도 싣지 않는다.
        provenance = converted_provenance_issue(state.get("current_phase"),
                                                state.get("source_phases_open"))
        if provenance:
            issues.append(("fast_cycle_audit.provenance_invalid", {"detail": provenance}))
    required = OPENER_REQUIRED + OPENER_REQUIRED_BY_MODE.get(entry_mode, ())
    missing = [field for field in required if absent(state.get(field))]
    if missing:
        issues.append(("fast_cycle_audit.opener_incomplete", {"fields": ", ".join(missing)}))
    # 있는 것과 계약을 만족하는 것은 또 다르다. `minimum_rounds` 는 리뷰를 몇 번 돌려야 Fast 를
    # 닫을 수 있는가이고, 0 이면 리뷰 없는 Fast 계약이 된다. profile schema 는 L2·L3 모두
    # `minimum: 1` 이므로 정책이 만들 수 있는 값이 아니다.
    rounds = state.get("minimum_rounds")
    if rounds is not None and (type(rounds) is not int or rounds < 1):
        issues.append(("fast_cycle_audit.opener_field_invalid",
                       {"field": "minimum_rounds", "value": repr(rounds)}))
    return issues


def parse_fast_plan(content: str) -> tuple[FastPlan | None, list[str]]:
    if not isinstance(content, str):
        return None, ["Fast Plan content must be text"]
    matches = list(_HEADING.finditer(content))
    found = [match.group(1) for match in matches]
    issues = []
    if found != list(PHASES):
        issues.append(f"Fast Plan phase headings must appear exactly once in order 00..04; found={found}")
    metadata = {}
    for key, value in _META.findall(content[:matches[0].start()] if matches else content):
        if key in metadata:
            issues.append(f"duplicate Fast Plan metadata: {key}")
        metadata[key] = value.strip().strip("`")
    sections = {}
    if found == list(PHASES):
        for index, match in enumerate(matches):
            end = matches[index + 1].start() if index + 1 < len(matches) else len(content)
            sections[match.group(1)] = content[match.end():end]
    return FastPlan(metadata, sections, content), issues


def open_issues(plan: FastPlan, *, stem: str, level: str, lens_count: int,
                reason: str, minimum_rounds: int, lenses: list[str],
                require_pending_phase4: bool = True) -> list[str]:
    meta = plan.metadata
    expected = {
        "Cycle-Stem": stem,
        "Cycle-Mode": "FAST",
        "Fast-Review-Level": level,
        "Fast-Minimum-Rounds": str(minimum_rounds),
        "Fast-Lens-Count": str(lens_count),
        "Fast-Lenses": ", ".join(lenses),
        "Fast-Reason": reason,
    }
    issues = [f"{key} must be {value!r}, got {meta.get(key)!r}"
              for key, value in expected.items() if meta.get(key) != value]
    if meta.get("Risk Level") not in ("L2", "L3"):
        issues.append("Risk Level must be L2 or L3 for Fast Cycle")
    run_id = meta.get("Fast-Audit-Run")
    if run_id != "pending" and not (isinstance(run_id, str) and _RUN_ID.fullmatch(run_id)):
        issues.append("Fast-Audit-Run must be pending or a valid fc-* run id")
    phase0 = plan.sections.get("00", "")
    required_mapping = (
        "- [x] Phase 00 context complete",
        "- [x] Phase 01 requirements and acceptance matrix embedded",
        "- [x] Phase 02 design and failure handling embedded",
        "- [x] Phase 03 ownership, implementation checklist, and verification plan ready",
    )
    issues.extend(f"missing checked mapping item: {item}" for item in required_mapping if item not in phase0)
    phase3 = plan.sections.get("03", "")
    required_pre = (
        "- [x] File ownership assigned before source edits",
        "- [x] Acceptance IDs mapped to implementation tasks",
        "- [x] Verification command plan recorded",
    )
    issues.extend(f"missing checked pre-implementation item: {item}" for item in required_pre if item not in phase3)
    phase1 = plan.sections.get("01", "")
    ids = re.findall(r"(?m)^\|\s*([A-Za-z][A-Za-z0-9_-]*)\s*\|", phase1)
    ids = [item for item in ids if item != "ID"]
    if not ids or len(ids) != len(set(ids)):
        issues.append("Phase 01 acceptance matrix needs at least one unique acceptance ID")
    for acceptance_id in ids:
        if not re.search(rf"\b{re.escape(acceptance_id)}\b", phase3):
            issues.append(f"Phase 03 acceptance trace missing {acceptance_id}")
    if require_pending_phase4 and "Status: PENDING — implementation not started" not in plan.sections.get("04", ""):
        issues.append("Phase 04 must retain the exact pre-implementation PENDING marker at open")
    return issues


def done_criteria_issues(plan: FastPlan, *, include_unresolved: bool) -> list[str]:
    """Return Fast Done Criteria diagnostics without choosing policy severity."""
    from done_criteria_contract import parse_done_criteria
    result = parse_done_criteria(plan.content, mode="fast")
    issues = [f"Done Criteria: {issue}" for issue in result.issues]
    if include_unresolved:
        issues.extend(
            f"Done Criteria unresolved at line {item.line}: {item.text}"
            for item in result.unresolved
        )
    return issues
def bind_run_id(content: str, run_id: str) -> str:
    matches = list(re.finditer(r"(?m)^Fast-Audit-Run:[ \t]*(pending|fc-[a-f0-9]{12})[ \t]*$", content))
    if len(matches) != 1:
        raise ValueError("Fast-Audit-Run line must occur exactly once")
    current = matches[0].group(1)
    if current != "pending" and current != run_id:
        raise ValueError(f"Fast-Audit-Run already bound to {current}")
    return content[:matches[0].start()] + f"Fast-Audit-Run: {run_id}" + content[matches[0].end():]


def reason_issue(reason: str) -> str | None:
    if not isinstance(reason, str) or not reason.strip():
        return "reason must be a non-empty line"
    if len(reason) > 1000 or any(ord(char) < 32 or char in "\u0085\u2028\u2029" for char in reason):
        return "reason must be one line without control characters and at most 1000 characters"
    return None


def evidence_marker_issues(content: str, *, fast_run_id: str, loop_run_id: str) -> list[str]:
    """Require one exact Fast evidence declaration outside Markdown code blocks."""
    expected = (
        f"Fast-Run: {fast_run_id}",
        f"Loop-Run: {loop_run_id}",
        "Final Status: APPROVED",
    )
    counts = {marker: 0 for marker in expected}
    in_fence = False
    fence_char = ""
    fence_len = 0
    for raw in (content or "").splitlines():
        match = _FENCE.match(raw)
        if match:
            token = match.group(1)
            if not in_fence:
                in_fence, fence_char, fence_len = True, token[0], len(token)
            elif token[0] == fence_char and len(token) >= fence_len:
                in_fence = False
            continue
        if in_fence or raw.startswith("\t") or re.match(r"^ {4,}", raw):
            continue
        if raw in counts:
            counts[raw] += 1
    return [f"{marker!r} must appear exactly once outside Markdown fences (found {count})"
            for marker, count in counts.items() if count != 1]
