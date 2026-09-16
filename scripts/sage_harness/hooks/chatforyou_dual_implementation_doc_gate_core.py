"""ChatForYou component implementation-doc gate — pure decision core.

Root 03 문서 검사는 CORE pre-phase4-checklist-gate 가 계속 소유한다. 이 core 는 ChatForYou
고유 규칙만 본다 — Phase 04 진입 전에 backend/frontend 각각이 영향을 받는지 00 base-plan 이
선언했는지, 영향받는 컴포넌트의 구현 문서가 존재하고 완료됐는지.

filesystem/clock/environment 에 직접 접근하지 않는다. 읽을 경로는 plan_reads 가 지목하고
런타임이 읽어 snapshot['files'] 로 넘긴다.
"""

import hashlib
import posixpath
import re
import unicodedata

CONTRACT_VERSION = "1"

GATE_MARKER = "ChatForYou-Component-Doc-Gate"
GATE_MARKER_VERSION = "v1"

BASE_PLAN_DIR = "plan_docs/00-base_plan"
PHASE4_DIR = "plan_docs/04-analyze"

# rollout 스냅샷은 로직이 아니라 데이터다. 소스에 두지 않는 실무적 이유가 하나 더 있다 —
# 사이클 stem 은 프로젝트 도메인 어휘를 그대로 쓰므로 게이트 소스에 나열하면 risk 분류의
# content keyword 와 매칭돼 이 파일 자신의 위험도가 올라간다.
LEGACY_CYCLES_FILE = "sage/chatforyou-legacy-cycles.txt"

# rollout 스냅샷은 얼어 있어야 한다. 고정하지 않으면 면제 목록에 stem 을 먼저 한 번 추가하고
# (그 변경 자체는 Phase 04 가 아니라 이 게이트를 지나간다) 다음 변경에서 그 Phase 04 를 쓰는
# 두-단계 우회가 성립한다. 파싱된 stem 집합의 digest 라 주석·순서·공백 정리는 깨지 않고
# 집합이 바뀔 때만 어긋난다.
LEGACY_CYCLES_DIGEST = "2eb0a760c28a71aaa59eddfa53f43d3624c886e813ba96018c927802c0599b1d"

# 선언 이름 → 그 컴포넌트가 REQUIRED 일 때 있어야 하는 구현 문서 디렉터리.
COMPONENTS = (
    ("Backend", "springboot-backend/plan_docs"),
    ("Frontend", "nodejs-frontend/plan_docs"),
)

# stem 은 경로 basename 이자 다시 경로로 조립되는 값이라 구분자·상대 참조가 섞이면 안 된다.
_SAFE_STEM = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,159}\Z")
_FENCE = re.compile(r"^\s{0,3}(`{3,}|~{3,})")
_UNCHECKED_BOX = re.compile(r"^\s*[-*+]\s+\[ \]\s*(.*)$")
_ANY_COMPONENT = re.compile(r"^Component-([^:]*):")

_KNOWN_LABELS = frozenset(label for label, _ in COMPONENTS)

# 유니코드 카테고리로는 문자(Lo)·기호(So)인데 화면에는 공백으로 그려지는 것들.
# 카테고리만 보면 "가시 문자 1개 있음" 으로 세어져 사유 검사를 그대로 통과한다.
_BLANK_GLYPHS = frozenset("\u2800\u115f\u1160\u3164\uffa0")

_MAX_EVIDENCE_LINES = 10


def _plain_lines(text):
    """fenced code block 밖의 (줄번호, 원문) 만 흘린다.

    코드 예시 안의 선언·체크박스가 판정에 쓰이면 문서가 자기 문법 설명 때문에 차단된다.
    """
    in_fence = False
    fence_char = ""
    fence_len = 0
    for number, raw in enumerate((text or "").splitlines(), start=1):
        marker = _FENCE.match(raw)
        if marker:
            token = marker.group(1)
            if not in_fence:
                # CommonMark: backtick fence 의 info string 은 backtick 을 가질 수 없다.
                # 이걸 안 보면 ```bad` 같은 평범한 줄이 fence 를 열어 그 뒤 본문 선언 전체가
                # 코드로 취급되고, 멀쩡한 문서가 marker 누락으로 차단된다.
                if token[0] == "`" and "`" in raw[marker.end():]:
                    yield number, raw
                    continue
                in_fence, fence_char, fence_len = True, token[0], len(token)
                continue
            # CommonMark: closing fence 는 info string 을 가질 수 없다. 이걸 안 보면
            # ```text 안의 ```python 이 fence 를 닫아버려 그 뒤 코드 예시가 본문 선언으로 읽힌다.
            if (token[0] == fence_char and len(token) >= fence_len
                    and not raw[marker.end():].strip()):
                in_fence = False
            continue
        if not in_fence:
            yield number, raw


def _canon_path(path):
    """관할 판정용 경로 키.

    호스트 파일시스템의 case 동작에 판정이 좌우되면 안 된다. macOS 처럼 case 를 구분하지 않는
    볼륨에서는 `PLAN_DOCS/04-ANALYZE/x.md` 가 관할 디렉터리와 같은 실제 경로인데, 문자열
    그대로 비교하면 관할 밖으로 읽혀 그대로 통과한다. case 를 구분하는 볼륨에서는 반대로 별개
    경로를 관할로 끌어들이지만 그쪽은 차단 방향이라 안전하다.

    `.`·`..`·중복 구분자 정리는 런타임의 root-상대 변환이 이미 끝내고 넘긴다.
    """
    return (path or "").replace("\\", "/").casefold()


def _has_visible_text(value):
    """공백을 걷어낸 뒤 눈에 보이는 문자가 하나라도 남는가.

    `strip()` 만으로 검사하면 U+200B 같은 zero-width 나 방향 제어 문자만으로 이루어진 값이
    "사유를 적었다" 로 통과한다. 사람이 읽을 근거를 요구하는 자리라 가시성이 곧 조건이다.
    """
    for ch in unicodedata.normalize("NFKC", value or ""):
        # C* = 제어·format·미할당, Z* = 각종 구분자(일반 공백 포함).
        if unicodedata.category(ch)[0] not in ("C", "Z") and ch not in _BLANK_GLYPHS:
            return True
    return False


def _readable(value):
    """메시지에 끼워 넣기 전 제어 문자를 걷어낸다.

    사유·경로·체크박스 본문은 전부 문서에서 온 텍스트다. 판정 결과를 사람이 읽는 채널이라
    터미널 제어열이 섞여 나가면 차단 메시지를 지우고 통과처럼 보이게 덮어쓸 수 있다.
    """
    return "".join(ch for ch in (value or "") if unicodedata.category(ch)[0] != "C")


def _legacy_stems(snapshot):
    """allowlist 파일 → (stem 집합, 집합 digest).

    파일이 없으면 빈 집합(=아무것도 면제하지 않음). 전면 skip 이 아니라 전면 검사인 이유는
    파일 삭제가 곧 게이트 해제가 되면 안 되기 때문이다.
    """
    text = ((snapshot or {}).get("files") or {}).get(LEGACY_CYCLES_FILE)
    stems = set()
    for raw in (text or "").splitlines():
        line = raw.strip()
        if line and not line.startswith("#"):
            stems.add(line)
    digest = hashlib.sha256("\n".join(sorted(stems)).encode("utf-8")).hexdigest()
    return stems, digest


def _stem_of(path):
    name = posixpath.basename((path or "").replace("\\", "/"))
    if not name.lower().endswith(".md"):
        return None
    name = name[:-3]
    return name if _SAFE_STEM.match(name) else None


def _phase4_targets(event):
    """이 이벤트의 Phase 04 대상 -> (stem 집합, 유효하지 않은 경로 목록).

    "Phase 04 를 안 건드림" 과 "Phase 04 인데 stem 을 읽을 수 없음" 은 다르다. 후자를 묶어서
    skip 하면 `_new.md` 같은 이름만으로 게이트 전체를 지나간다.
    """
    stems, invalid = set(), []
    for change in (event or {}).get("changes") or []:
        path = ((change or {}).get("path") or "").replace("\\", "/")
        if _canon_path(posixpath.dirname(path)) != _canon_path(PHASE4_DIR):
            continue
        stem = _stem_of(path)
        if stem:
            stems.add(stem)
        elif path.lower().endswith(".md"):
            invalid.append(path)
    return stems, invalid


def _base_plan_path(stem):
    return BASE_PLAN_DIR + "/" + stem + ".md"


def _base_plan_glob(stem):
    """00 base-plan 을 `BASE_PLAN_DIR` 아래 임의 깊이에서 찾는 glob.

    프로젝트가 Phase 00 문서를 `YYYY/MM/` 월 폴더로 정리하므로, flat 경로 하나만 지목하면
    월 폴더에 있는 00 문서를 못 찾아 신규 사이클처럼 오차단한다.
    """
    return BASE_PLAN_DIR + "/**/" + stem + ".md"


def _is_base_plan_path(path, stem):
    """`path` 가 이 stem 의 00 base-plan 인지 — 깊이 무관, `_canon_path` 로 case/구분자 정규화."""
    canon = _canon_path(path)
    return (canon.startswith(_canon_path(BASE_PLAN_DIR + "/"))
            and canon.endswith(_canon_path("/" + stem + ".md")))


def _find_base_plan(files, stem):
    """snapshot 에 실제로 읽힌 파일들 중 이 stem 의 00 base-plan 경로 전부(정렬)."""
    return sorted(path for path in files if _is_base_plan_path(path, stem))


def _component_doc_path(directory, stem):
    return directory + "/" + stem + ".md"


def plan_reads(event, profile=None):
    """판정에 필요한 정확 경로만 지목한다.

    legacy 여부는 snapshot 을 받아야 알 수 있으므로 allowlist 파일을 항상 포함한다.
    리터럴 경로는 자기 자신만 매칭하는 glob 이라 prefix 유사 파일이나 최근 파일 fallback 이
    끼어들 여지가 없다. 경로 안전(절대·상대탈출·symlink·root 이탈)은 런타임이 검사한다.
    """
    stems, _ = _phase4_targets(event)
    if len(stems) != 1:
        return {"globs": []}
    stem = next(iter(stems))
    globs = [LEGACY_CYCLES_FILE, _base_plan_glob(stem)]
    globs.extend(_component_doc_path(directory, stem) for _, directory in COMPONENTS)
    return {"globs": globs}


def _marker_error(lines):
    prefix = GATE_MARKER + ":"
    found = [line.strip() for _, line in lines if line.strip().startswith(prefix)]
    if not found:
        return "missing_gate_marker"
    if len(found) > 1:
        return "duplicate_gate_marker"
    if found[0][len(prefix):].strip() != GATE_MARKER_VERSION:
        return "invalid_component_status"
    return None


def _declaration(lines, label):
    """Component-<label> 선언 1건 → (status, reason, error_key)."""
    prefix = "Component-" + label + ":"
    found = [line.strip() for _, line in lines if line.strip().startswith(prefix)]
    if not found:
        return None, None, "missing_component_declaration"
    if len(found) > 1:
        return None, None, "duplicate_component_declaration"
    body = found[0][len(prefix):].strip()
    if body == "REQUIRED":
        return "REQUIRED", None, None
    if body == "N/A":
        return None, None, "empty_na_reason"
    if body.startswith("N/A:"):
        reason = body[len("N/A:"):].strip()
        if not _has_visible_text(reason):
            return None, None, "empty_na_reason"
        return "N/A", reason, None
    return None, None, "invalid_component_status"


def _unknown_component_labels(lines):
    """본문의 Component-* 선언 중 이 프로젝트가 모르는 이름들.

    Backend/Frontend 만 조회하면 Component-Desktop 같은 선언이 조용히 무시돼,
    작성자는 선언했다고 믿는데 게이트는 아무것도 검사하지 않는 상태가 된다.
    """
    seen = []
    for _, line in lines:
        match = _ANY_COMPONENT.match(line.strip())
        if match and match.group(1).strip() not in _KNOWN_LABELS:
            seen.append(match.group(1).strip() or "(빈 이름)")
    return seen


def _conflicting_paths(event, stem):
    """같은 이벤트가 Phase 04 와 함께 건드리는 00/컴포넌트 문서 경로.

    snapshot 은 쓰기 **전** 디스크 상태라, 한 번의 patch 로 00 을 무효화하면서 04 를 쓰면
    아직 유효한 00 으로 판정돼 게이트가 통과한다. 그 조합은 판정할 수 없으므로 거부한다.
    """
    governed_exact = {_canon_path(LEGACY_CYCLES_FILE)}
    governed_exact.update(_canon_path(_component_doc_path(directory, stem))
                          for _, directory in COMPONENTS)
    hit = []
    for change in (event or {}).get("changes") or []:
        path = ((change or {}).get("path") or "").replace("\\", "/")
        # 비교는 정규화 키로, 안내는 작성자가 실제로 쓴 경로로. 00 문서는 월 폴더 배치까지
        # 잡아야 하므로 깊이-무관 매칭을 따로 쓴다.
        if _canon_path(path) in governed_exact or _is_base_plan_path(path, stem):
            hit.append(path)
    return sorted(set(hit))


def _unchecked(text):
    result = []
    for number, line in _plain_lines(text):
        match = _UNCHECKED_BOX.match(line)
        if match:
            result.append((number, match.group(1).strip()))
    return result


def _block(key, stem, detail):
    # 정제는 조립 지점 한 곳에서만 한다 — 호출자마다 기억해야 하면 언젠가 빠진다.
    body = "\n".join("  " + _readable(line) for line in detail)
    header = "[chatforyou-dual-implementation-doc-gate/" + key + "] cycle=" + _readable(stem)
    return {"status": "block", "exit_code": 2, "message": header + "\n" + body}


def decide(event, profile, snapshot):
    stems, invalid = _phase4_targets(event)
    if invalid:
        return _block("invalid_phase4_stem", "(미상)", [
            "Phase 04 문서 이름에서 사이클을 읽을 수 없습니다: " + ", ".join(sorted(invalid)),
            "파일명은 영숫자로 시작하고 [A-Za-z0-9._-] 만 쓸 수 있습니다.",
        ])

    stem = stems.pop() if len(stems) == 1 else None
    if stem is None:
        return {"status": "skip", "exit_code": 0, "message": ""}

    # legacy 면제보다 충돌 검사가 먼저다 — allowlist 를 같은 patch 로 고치면서 04 를 쓰면
    # 아직 그 stem 이 남아 있는 옛 snapshot 으로 면제돼 영구 통과한다.
    conflicting = _conflicting_paths(event, stem)
    if conflicting:
        return _block("mixed_change_scope", stem, [
            "Phase 04 와 게이트 판정 대상 문서를 한 번에 바꾸면 판정할 수 없습니다.",
            "함께 변경된 문서: " + ", ".join(conflicting),
            "문서를 먼저 확정한 뒤 Phase 04 를 별도 변경으로 작성하세요.",
        ])

    # 면제를 실제로 쓰는 순간에만 목록의 무결성을 따진다. 목록이 없거나 이 stem 과 무관하면
    # 어차피 전면 검사로 가므로, 파일 부재가 전면 차단으로 뒤집히지 않는다.
    legacy, legacy_digest = _legacy_stems(snapshot)
    if stem in legacy:
        if legacy_digest != LEGACY_CYCLES_DIGEST:
            return _block("legacy_allowlist_changed", stem, [
                LEGACY_CYCLES_FILE + " 의 면제 목록이 고정된 rollout 스냅샷과 다릅니다.",
                "기대 digest: " + LEGACY_CYCLES_DIGEST,
                "현재 digest: " + legacy_digest,
                "이 사이클의 면제를 신뢰할 수 없습니다. 목록 변경이 정당하다면 게이트 소스의"
                " LEGACY_CYCLES_DIGEST 를 같은 변경에서 함께 갱신하세요.",
            ])
        return {"status": "skip", "exit_code": 0, "message": ""}

    files = (snapshot or {}).get("files") or {}
    base_matches = _find_base_plan(files, stem)
    if len(base_matches) == 0:
        return _block("missing_base_plan", stem, [
            "Phase 04 를 쓰기 전에 00 base-plan 이 있어야 합니다: " + _base_plan_path(stem),
            "신규 사이클은 00 에서 컴포넌트 영향을 선언해야 합니다.",
        ])
    if len(base_matches) > 1:
        return _block("ambiguous_base_plan", stem, [
            "동일 사이클의 00 base-plan 문서가 여러 곳에 있어 판정할 수 없습니다: "
            + ", ".join(base_matches),
            "하나만 남기고 나머지를 정리하세요.",
        ])
    base_path = base_matches[0]
    base_text = files[base_path]

    lines = list(_plain_lines(base_text))
    marker_error = _marker_error(lines)
    if marker_error:
        return _block(marker_error, stem, [
            base_path + " 에 " + GATE_MARKER + ": " + GATE_MARKER_VERSION
            + " 선언이 정확히 한 번 필요합니다.",
        ])

    unknown = _unknown_component_labels(lines)
    if unknown:
        return _block("unknown_component_declaration", stem, [
            base_path + " 에 이 프로젝트가 모르는 컴포넌트 선언이 있습니다: "
            + ", ".join("Component-" + name for name in unknown),
            "허용 컴포넌트: " + ", ".join(sorted(_KNOWN_LABELS)),
        ])

    required, waived = [], []
    for label, directory in COMPONENTS:
        status, reason, error_key = _declaration(lines, label)
        if error_key:
            return _block(error_key, stem, [
                base_path + " 의 Component-" + label + " 선언을 확인하세요.",
                "허용 형식: Component-" + label + ": REQUIRED"
                + " 또는 Component-" + label + ": N/A: <사유>",
            ])
        if status == "REQUIRED":
            required.append((label, _component_doc_path(directory, stem)))
        else:
            waived.append(label + "=N/A(" + reason + ")")

    for label, doc_path in required:
        text = files.get(doc_path)
        if text is None:
            return _block("missing_component_doc", stem, [
                label + " 이 REQUIRED 인데 구현 문서가 없습니다: " + doc_path,
                "영향이 없다면 " + base_path + " 에서 Component-" + label
                + ": N/A: <사유> 로 바꾸세요.",
            ])
        unchecked = _unchecked(text)
        if unchecked:
            detail = [label + " 구현 문서에 미완료 항목 " + str(len(unchecked))
                      + "건: " + doc_path]
            detail.extend("  L" + str(number) + ": " + body
                          for number, body in unchecked[:_MAX_EVIDENCE_LINES])
            if len(unchecked) > _MAX_EVIDENCE_LINES:
                detail.append("... 외 " + str(len(unchecked) - _MAX_EVIDENCE_LINES) + "건")
            return _block("unchecked_component_doc", stem, detail)

    summary = ", ".join([label + "=REQUIRED" for label, _ in required] + waived)
    return {"status": "ok", "exit_code": 0,
            "message": "[chatforyou-dual-implementation-doc-gate/gate_ok] cycle="
                       + _readable(stem) + " | " + _readable(summary)}
