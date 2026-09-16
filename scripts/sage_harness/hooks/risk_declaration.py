#!/usr/bin/env python3
"""Phase 00 Risk Level 선언의 단일 해석 정본.

이 모듈이 생기기 전에는 선언을 읽는 구현이 둘이었고 규칙이 서로 달랐다. 게이트 쪽은 **문서 전체**를
훑어 본문 산문의 `Risk Level` 언급까지 선언 후보로 잡았고(이 저장소 Phase 00 38개 중 6개가 실제로
오판됐다), hook 런타임 쪽은 헤더만 보는 대신 label 강조·오류 종류 구분·near-miss 봉쇄가 없었다.
한쪽 정규식만 줄이면 다른 소비자와 다시 어긋나므로 두 규칙의 옳은 쪽만 여기 모은다.

**고친 것은 영역이지 문법이 아니다.** 실제 오탐은 전부 본문·인용문·코드블록에서 나왔다. 반면
bullet 접두(`- Risk Level: L2`), 뒤쪽 사유(`Risk Level: L2 — 설명`), 한국어 label(`위험도: L2`)은
설치된 소비 프로젝트의 기존 Phase 00 이 실제로 쓰는 형태다. 문법을 좁히면 오탐은 그대로 두고
멀쩡한 문서만 깨진다. 그래서 읽기 문법은 기존 그대로 두고 헤더 영역으로만 좁힌다.

**near-miss 는 조용히 넘기지 않는다.** `Risk Level [custom]: L3` 처럼 선언을 의도한 게 분명하지만
문법을 벗어난 줄은 `malformed` 로 막는다. 후보에서 빼버리면 "선언이 하나도 없다"가 아니라 "다른
정상 선언이 채택됨"이 되어, 잘못 쓴 tier 가 조용히 통과한다.

**문법과 tier 정책은 분리한다.** 여기서는 `L0`~`L3` 를 모두 인식해 돌려주고, 무엇이 유효한
선언인지는 소비자가 정한다 — 사이클 결속은 `L1`~`L3` 만 받지만 write-back tier 판정은 `L0` 도
읽어야 하기 때문이다.

표준 라이브러리만 쓰고 다른 모듈도 import 하지 않는다 — 설치 hook 런타임과 게이트 양쪽이 같은
판정을 쓰려면 의존이 없어야 한다.
"""

import re

TIERS = ("L0", "L1", "L2", "L3")

# 제로폭/BOM 포맷 문자. `\s` 는 이들을 매치하지 못해 줄 앞에 끼면 선언을 통째로 놓친다.
_ZERO_WIDTH = {c: None for c in (0xFEFF, 0x200B, 0x200C, 0x200D, 0x2060)}

# 리스트 항목. 느슨한 리스트(항목 사이 빈 줄)의 들여쓴 하위 항목을 코드블록으로 오인하지 않는다.
_LIST_ITEM_RE = re.compile(r"(?:[-*+]|\d+[.)])\s")


def _indent_width(raw):
    """markdown 기준 들여쓰기 폭. tab 은 4칸으로 센다."""
    width = 0
    for char in raw:
        if char == " ":
            width += 1
        elif char == "\t":
            width += 4
        else:
            break
    return width


# H1 제목은 헤더 영역의 일부다 — 종료는 H2 이상에서만.
_H2_PLUS_RE = re.compile(r"#{2,}(?:\s|$)")

# `**Risk Level:**` 처럼 콜론까지 감싼 강조를 label 과 콜론만 남기고 벗긴다.
_LABEL_EMPHASIS_RE = re.compile(
    r"(?P<mark>\*{1,3}|_{1,3})(?P<label>Risk\s+Level|Risk|위험도)(?P<colon>\s*[:：]?)(?P=mark)",
    re.IGNORECASE)

_LABEL_RE = re.compile(r"(?i)(risk\s*level|risk|위험도)\s*[:：]")
_VALUE_RE = re.compile(r"(?i)(risk\s*level|risk|위험도)\s*[:：]\s*(L[0-3])\b")
_ALTERNATIVES_RE = re.compile(r"\s*[|/]\s*L[0-3]\b", re.IGNORECASE)
_TIER_TOKEN_RE = re.compile(r"(?i)\bL[0-9]+\b")
# 선언 label 바로 뒤의 구조 표시는 값 철자가 깨져도 선언 의도가 명확하다. `guidance` 같은
# 산문 접미사까지 선언으로 만들지 않도록 구조 문자만 좁게 받는다.
_STRUCTURAL_NEAR_MISS_RE = re.compile(
    r"(?i)^(?:risk\s*level|risk|위험도)\s*(?:[\[({<]|=)")
# 문법을 벗어났지만 선언을 의도한 게 분명한 줄 — 조용히 무시하지 않고 fail-closed 한다.
_NEAR_MISS_RE = re.compile(r"(?i)(risk\s*level|\brisk\b|위험도).*[：:]")
_INLINE_CODE_RE = re.compile(r"(`+).*?\1")
# 선언은 줄을 **시작**해야 한다. 영역을 헤더로 좁힌 뒤에도 문장 중간의 언급은 그대로 통과했다 —
# `Example: `Risk Level: L1`` 과 `Do not use Risk Level: L1 here.` 가 둘 다 유효한 L1 선언이었고,
# 헤더에 그런 줄만 있으면 산문이 곧 tier 가 됐다. 여기서 막는 것도 문법이 아니라 **자리**다:
# bullet 접두·강조·인라인코드 래핑·뒤쪽 사유는 실제 문서가 쓰는 형태라 전부 그대로 허용한다.
_DECLARATION_START_RE = re.compile(r"(?i)^(?:[-*+]|\d+[.)])?\s*(risk\s*level|risk|위험도)\b")

_EXCERPT_MAX = 80


class Declaration:
    """선언 해석 결과. 소비자는 이 값만 쓰고 원문을 다시 스캔하지 않는다.

    status  valid | missing | duplicate | malformed | placeholder
    tier    valid 일 때만 `L0`~`L3`, 그 외 None
    line    문제 지점의 1-based 원본 줄 번호. missing 은 None
    excerpt 문제 줄의 짧은 원문(공백 정규화, 최대 80자)
    """

    __slots__ = ("status", "tier", "line", "excerpt")

    def __init__(self, status, tier=None, line=None, excerpt=""):
        self.status = status
        self.tier = tier
        self.line = line
        self.excerpt = excerpt

    def __repr__(self):
        return f"Declaration(status={self.status!r}, tier={self.tier!r}, line={self.line!r})"

    def __eq__(self, other):
        if not isinstance(other, Declaration):
            return NotImplemented
        return (self.status, self.tier, self.line) == (other.status, other.tier, other.line)


def _excerpt(raw):
    return " ".join((raw or "").split())[:_EXCERPT_MAX]


def _normalize(raw):
    """label 강조와 줄 전체를 감싼 강조·인라인코드 하나를 벗긴다.

    백틱을 함께 벗기는 이유는 hint 문구가 `Risk Level: L1` 을 백틱째 보여주기 때문이다 — 그대로
    복사해 적은 줄이 선언으로 읽히지 않으면 안내가 스스로를 배신한다. 줄 전체를 감싼 경우만
    벗기므로 문장 안의 인라인 예시(Example: 뒤에 백틱으로 감싼 형태)는 대상이 아니다.
    """
    line = _LABEL_EMPHASIS_RE.sub(
        lambda match: f"{match.group('label')}{match.group('colon')}", raw or "").strip()
    for marker in ("***", "___", "**", "__", "*", "_", "``", "`"):
        if len(line) > len(marker) * 2 and line.startswith(marker) and line.endswith(marker):
            return line[len(marker):-len(marker)].strip()
    return line


def header_lines(content):
    """헤더 metadata 영역의 (1-based 원본 줄 번호, 정규화 줄)만 흘린다.

    제외 대상: 첫 H2 이상 헤딩부터의 본문 전체, fence 코드블록(``` / ~~~ 를 종류별로 열고 닫는다 —
    혼합 fence 로는 닫지 못한다), 인용문. 줄 번호는 정규화와 무관하게 원본 기준을 유지한다.

    **들여쓰기 자체는 제외 사유가 아니다.** 4칸 들여쓴 줄은 리스트 하위 항목인 경우가 많고
    (`- detail` 아래의 `    - Risk Level: L3`), 그걸 버리면 사람이 L3 로 읽는 문서를 게이트가
    옆의 낮은 선언으로 확정한다. 제외하는 것은 markdown 이 코드블록으로 읽는 형태 하나다 —
    **빈 줄 뒤에 시작하는 4칸 들여쓰기이면서 리스트 안이 아닌 것**. fence 없는 예시가 그렇게
    적히며, 그걸 읽으면 문서가 설명하려던 값이 그 사이클의 위험도로 확정된다.
    """
    fence = None
    blank_before = True     # 문서의 첫 줄은 빈 줄 뒤와 같은 자리다
    list_context = False
    code_block = False
    for number, raw in enumerate((content or "").translate(_ZERO_WIDTH).splitlines(), 1):
        stripped = raw.lstrip()
        opener = "```" if stripped.startswith("```") else ("~~~" if stripped.startswith("~~~") else None)
        if fence is not None:
            if opener == fence:
                fence = None
            continue
        if opener is not None:
            fence = opener
            continue
        if _H2_PLUS_RE.match(stripped):
            return
        if code_block:
            # 빈 줄은 코드블록을 끝내지 않는다. 들여쓰기가 풀린 줄에서만 끝난다.
            if not stripped or _indent_width(raw) >= 4:
                blank_before = not stripped
                continue
            code_block = False
        if stripped and blank_before and not list_context and _indent_width(raw) >= 4:
            code_block = True
            blank_before = False
            continue
        blank_before = not stripped
        if stripped:
            list_context = bool(_LIST_ITEM_RE.match(stripped))
        if stripped.startswith(">"):
            continue
        yield number, _normalize(raw)


def _classify(line):
    """한 줄을 (status, tier) 로 — status None 은 선언과 무관한 줄이다."""
    labels = list(_LABEL_RE.finditer(line))
    # 인라인 예시는 선언 후보가 아니다. 그 밖의 평문에 선언과 같은 label/colon이 남아 있으면
    # 조용히 무시하지 않는다. `Final Risk Level: L3` 같은 prefixed 선언을 버리면 옆의 L1이
    # authoritative tier로 채택되기 때문이다. blockquote와 fenced/indented code는 header_lines가
    # 이 분기 전에 제외한다.
    if not _DECLARATION_START_RE.match(line):
        prose = _INLINE_CODE_RE.sub("", line)
        return (("malformed", None)
                if _NEAR_MISS_RE.search(prose) else (None, None))
    if not labels:
        return ("malformed", None) if _NEAR_MISS_RE.search(line) else (None, None)
    start = _DECLARATION_START_RE.match(line)
    if labels[0].start() != start.start(1):
        # 줄이 label 로 시작해도 첫 label 뒤 문법이 깨졌다면, 뒤쪽 인라인 예시의 정확한 label을
        # 그 선언으로 훔쳐 읽으면 안 된다. 첫 구간에 tier가 있으면 명백한 near-miss이므로 막고,
        # `Risk Level guidance: use `Risk Level: L1`` 같은 산문은 선언으로 만들지 않는다.
        prefix = line[start.start(1):labels[0].start()]
        return (("malformed", None)
                if (_TIER_TOKEN_RE.search(prefix) or _STRUCTURAL_NEAR_MISS_RE.search(prefix))
                else (None, None))
    if len(labels) != 1:
        # 한 줄에 선언 둘을 숨기는 형태.
        return "malformed", None
    match = _VALUE_RE.match(line, labels[0].start())
    if not match:
        return "malformed", None
    if _ALTERNATIVES_RE.match(line[match.end():]):
        return "placeholder", None
    return "valid", match.group(2).upper()


def scan(content):
    """헤더 영역을 한 번 훑어 `(정확한 선언 목록, 첫 오류 또는 None)` 을 돌려준다.

    두 정보를 함께 주는 이유는 소비자마다 필요한 조합이 다르기 때문이다. 사이클 결속은 "정확히
    1개"를 요구하고, 06 depth 판정은 최대 tier 를 취하며, acceptance risk 추정은 "선언 목록 + 하나라도
    깨졌으면 unknown" 이 필요하다. 셋 다 fail-closed 지만 방향이 달라 파서가 하나로 정해줄 수 없다.

    오류는 문법을 벗어났지만 선언을 의도한 게 분명한 줄(`Risk Level [custom]: L3`)과 선택지
    나열(placeholder)이다. 중복은 오류로 보지 않고 목록 길이로 드러낸다 — 최대 tier 를 취하는
    소비자에게 중복은 정상 입력이다.

    **오류를 만나도 스캔을 끝까지 한다.** 첫 오류에서 멈추면 그 뒤의 정상 선언이 목록에서 빠지고,
    최대 tier 를 취하는 소비자가 남은 낮은 선언을 채택한다 — 오류 한 줄이 tier 를 낮추는 통로가
    된다. 오류는 첫 건만 보고하되 목록은 온전해야 한다.
    """
    found = []
    error = None
    for number, line in header_lines(content):
        status, tier = _classify(line)
        if status is None:
            continue
        if status == "valid":
            found.append((number, tier))
            continue
        if error is None:
            error = Declaration(status, None, number, _excerpt(line))
    return found, error


def declarations(content):
    """헤더 영역의 정확한 선언 전부를 [(1-based 줄 번호, tier)] 로. 문법을 벗어난 줄은 제외한다.

    **여러 선언 중 무엇을 고를지는 소비자가 정한다.** 사이클 결속은 "정확히 1개"를 요구해 중복을
    fail-closed 하지만, 06 depth 판정은 최대 tier 를 취하는 것이 fail-closed 다 — 거기서 선언 하나를
    놓치면 tier 가 낮게 읽혀 검증이 얕아지기 때문이다. 두 요구는 반대 방향이라 파서가 한쪽으로
    정해줄 수 없다.
    """
    return scan(content)[0]


def parse(content):
    """헤더 영역에 정확한 선언이 하나 있는가 — 사이클 결속용 "정확히 1개" 계약.

    오류 우선순위는 fail-closed 다. 헤더에 malformed/placeholder 줄이 하나라도 있으면 정확한 선언이
    따로 있어도 그 오류를 먼저 보고한다 — 잘못 쓴 줄을 무시하고 다른 줄을 채택하면 작성자가 의도한
    tier 와 게이트가 읽은 tier 가 갈린다.
    """
    found, error = scan(content)
    if error is not None:
        return error
    if len(found) > 1:
        number, _tier = found[1]
        return Declaration("duplicate", None, number, "")
    if len(found) == 1:
        number, tier = found[0]
        return Declaration("valid", tier, number, "")
    return Declaration("missing", None, None, "")
