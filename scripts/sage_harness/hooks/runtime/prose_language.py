"""Phase 문서 본문(prose)의 선언 언어 위반 — 구조적 smoke, 자연어 품질 검토가 아니다.

`document_language.py` 가 `Document-Language:` 한 줄의 marker 일치를 본다면, 여기는 그 아래
본문이 실제로 그 언어인지를 본다. 완벽한 판정은 하지 않는다 — fenced code·inline code·Markdown
링크 목적지·인용된 외부 evidence(blockquote)를 뺀 나머지에서 명백한 구조 이상만 잡는다. 전체
자연어 품질과 주 언어 준수는 design 문서 §7.8 이 명시한 대로 사람이 검토한다.

이 모듈은 사람이 읽는 문장을 만들지 않는다 — 위치와 원문 조각만 돌려주고 설명은 표시 계층
catalog 가 소유한다. 여기서 한국어 설명을 만들면 표시 언어가 en 인 화면에 그 문장이 그대로
실려 나간다(catalog 를 import 하지도 않는다 — 표시 언어와 문서 언어는 수명이 다르다).

Markdown 처리는 CommonMark 전체 parser 가 아니라 고정된 규칙 몇 개다: 같은 길이의 backtick
run 끼리만 inline code 로 짝짓고, fence 는 opener 의 문자·길이를 기억해 그와 같은 closer 만
인정하며, 닫히지 않은 fence 는 남은 본문을 조용히 code 로 삼키지 않고 결함으로 보고한다.
"""
import collections
import re
import unicodedata

LANGUAGES = ("ko", "en")

# 지원하는 Hangul 범위를 한 곳에 모아 감사 가능하게 둔다. 범위를 코드 여기저기에 흩어놓으면
# "어디까지 한글로 보는가" 가 문서화되지 않은 채 갈린다. NFD 로 분해된 글자는 검사 전에 NFC 로
# 모으고, 그래도 남는 단독 자모와 반각·괄호/원문자 형태까지 여기에 포함한다.
# 블록 경계가 아니라 **실제 할당 구간**으로 적는다. 블록째로 잡으면 미할당 코드 포인트(U+3130
# 등)까지 한국어로 판정해, 쓰지도 않은 글자 하나로 문서가 막힌다. 구간이 쪼개져 보이는 자리가
# 곧 그 빈 구간이다. 모든 경계가 실제로 할당된 한글인지는 테스트가 unicodedata 로 감사한다.
HANGUL_RANGES = (
    ("가", "힣"),   # Hangul Syllables (완성형)
    ("ᄀ", "ᇿ"),   # Hangul Jamo — 블록 전체가 할당돼 있다
    ("ㄱ", "ㆎ"),   # Compatibility Jamo (U+3130 · U+318F 미할당)
    ("ꥠ", "ꥼ"),   # Jamo Extended-A (U+A97D–A97F 미할당)
    ("ힰ", "ퟆ"),   # Jamo Extended-B 앞 구간 (U+D7C7–D7CA 미할당)
    ("ퟋ", "ퟻ"),   # Jamo Extended-B 뒤 구간 (U+D7FC–D7FF 미할당)
    ("㈀", "㈞"),   # 괄호 한글 (U+321F 미할당)
    ("㉠", "㉾"),   # 원문자 한글 (U+327F 는 원화 기호라 제외)
    ("ﾠ", "ﾾ"),   # Halfwidth 한글 filler + 자음
    ("ￂ", "ￇ"),   # Halfwidth 한글 모음 — 아래 세 구간과 함께 빈 칸을 건너뛴다
    ("ￊ", "ￏ"),
    ("ￒ", "ￗ"),
    ("ￚ", "ￜ"),
)
_HANGUL = re.compile("[" + "".join(f"{lo}-{hi}" for lo, hi in HANGUL_RANGES) + "]")
_FENCE = re.compile(r"^\s{0,3}(`{3,}|~{3,})(.*)$")
_BLOCKQUOTE = re.compile(r"^\s*>")
# 같은 길이의 backtick run 끼리만 짝짓는다(CommonMark code span). 앞뒤 lookaround 가 run 경계를
# 강제한다 — 이게 없으면 `한글 `` 처럼 길이가 다른 run 을 code span 으로 오인해 안쪽 원문을
# 검사에서 지운다. 짝이 맞지 않는 backtick 은 CommonMark 대로 그냥 글자로 남는다.
_INLINE_CODE = re.compile(r"(?<!`)(`+)(?!`)(?:(?!\1)[^\n])*?\1(?!`)")
_MD_LINK_DEST = re.compile(r"\]\([^)]*\)")
# marker 줄은 **표본에서만** 뺀다. `Status: NOT READY — 아직 구현되지 않음` 처럼 고정 enum
# 뒤에 붙는 설명은 선택 언어로 쓰는 사람 글이라 한글 탐지 대상으로는 그대로 남는다.
_MARKER_LINE = re.compile(
    r"^\s*(Cycle-Stem|Document-Language|Risk\s+Level|Done-Criteria-Revision|Final\s+Status|"
    r"Loop-Run|Phase00-Hash|Phase|Status)\s*:", re.IGNORECASE)
_NON_SPACE = re.compile(r"\s")

# ko 문서의 "번역 대상 prose 가 사실상 전부 영어" smoke 판정 최소 표본(공백 제외 문자 수).
# 표본이 이보다 작으면 짧은 skeleton/제목뿐인 문서를 오탐으로 막을 위험이 더 크다.
MIN_PROSE_SAMPLE = 40


def _clean(line):
    return _MD_LINK_DEST.sub(" ", _INLINE_CODE.sub(" ", line))


def scan(text):
    """(prose, unclosed_fence_line) — prose 는 [(line_no, cleaned, is_marker)].

    fence 안, blockquote(인용된 외부 원문), inline code, 링크 목적지는 검사 대상이 아니다.
    inline code 와 링크 목적지는 그 줄 안에서만 지운다 — 명령어 하나만 backtick 에 감싼
    문장의 나머지 서술까지 함께 지우면 안 되기 때문이다.
    """
    out = []
    opener = None            # (문자, 길이, 줄번호)
    for line_no, line in enumerate(unicodedata.normalize("NFC", text or "").splitlines(), 1):
        fence = _FENCE.match(line)
        # backtick fence 의 info string 에는 backtick 이 올 수 없다(CommonMark). 이걸 열림으로
        # 인정하면 ```lang` 한 줄이 뒤 본문 전체를 code 로 삼켜 검사에서 지운다.
        if fence and fence.group(1)[0] == "`" and "`" in fence.group(2):
            fence = None
        if fence:
            mark = fence.group(1)
            if opener is None:
                opener = (mark[0], len(mark), line_no)
            elif (mark[0] == opener[0] and len(mark) >= opener[1]
                    and not fence.group(2).strip()):
                # closer 는 같은 문자로 같거나 더 길어야 하고 info string 이 없어야 한다.
                # 그래서 ```` fence 안의 ``` 은 닫지 않고 내용으로 남는다.
                opener = None
            continue
        if opener is not None or _BLOCKQUOTE.match(line):
            continue
        out.append((line_no, _clean(line), bool(_MARKER_LINE.match(line))))
    return out, (opener[2] if opener else None)


def foreign_prose(text, language):
    """en 선언 문서의 prose 에 남은 한국어. [(line_no, snippet)] — 빈 리스트면 통과.

    `text` 는 **전체 문서**여야 한다. 조각만 넘기면 그 줄이 기존 fence 안에 들어가는 줄이었는지
    알 수 없어, 코드 예시를 한 줄 넣는 정상 편집이 차단된다.

    ko 쪽 대칭(한국어 문서 안의 영어 문장)은 줄 단위로 판별할 수 없다 — 영어 고유명사와
    기계 토큰은 한국어 문서의 정상 구성요소다. ko 는 `lacks_native_prose` 의 전체 표본으로만 본다.
    """
    if language != "en":
        return []
    prose, _unclosed = scan(text)
    return [(line_no, cleaned.strip()) for line_no, cleaned, _marker in prose
            if _HANGUL.search(cleaned)]


def new_foreign_prose(before_text, after_text, language):
    """이번 변경으로 **새로 생기거나 드러난** 한국어. [(line_no, snippet)].

    "새로 추가된 줄" 이 아니라 **부채의 증가분**을 본다. 줄 추가만 보면 Markdown 구문을 지워
    기존 한국어가 prose 로 노출되는 경우를 통째로 놓친다 — fence 한 줄을 지우면 새 줄은 없는데
    안에 있던 한국어가 본문이 된다(blockquote `>`·inline backtick·링크 문법 제거도 같은 구조).

    반대로 이미 있던 위반은 그대로 통과시킨다. 그러지 않으면 legacy 문서 하나가 이후 모든
    편집을 막는 과차단이 된다(§7.7). 같은 원문이 늘어난 만큼만 새 부채로 센다.
    """
    baseline = collections.Counter(snippet for _line, snippet in foreign_prose(before_text, language))
    fresh = []
    for line_no, snippet in foreign_prose(after_text, language):
        if baseline[snippet] > 0:
            baseline[snippet] -= 1
            continue
        fresh.append((line_no, snippet))
    return fresh


def new_unclosed_fence(before_text, after_text):
    """이번 변경으로 새로 생긴 닫히지 않은 fence 의 줄번호(없으면 None).

    이미 닫히지 않은 채였다면 그건 이 변경이 만든 결함이 아니다 — 물려받은 결함으로 편집을
    막으면 고칠 방법이 없어진다.
    """
    after = unclosed_fence(after_text)
    if after is None or unclosed_fence(before_text) is not None:
        return None
    return after


def newly_lacks_native_prose(before_text, after_text, language):
    """이번 변경으로 한국어가 **사라졌는가**(있다가 없어졌거나, 새 문서가 처음부터 없거나).

    이미 한국어가 없던 문서(legacy·마커 오선언)를 편집할 때마다 막으면 고칠 방법이 없다.
    새 문서는 before 가 빈 문자열이라 표본이 0이고, 따라서 처음 쓰는 순간이 곧 증가분이 된다.
    """
    return (lacks_native_prose(after_text, language)
            and not lacks_native_prose(before_text, language))


def unclosed_fence(text):
    """닫히지 않은 code fence 의 시작 줄번호(없으면 None).

    CommonMark 는 닫히지 않은 fence 를 문서 끝까지로 본다. 그대로 따르면 fence 하나를 안 닫는
    것이 이 검사를 끄는 스위치가 되므로, 삼키는 대신 문서 결함으로 보고해 사람이 닫게 한다.
    **변경 후 전체 본문에만 물을 수 있다** — 부분 diff 는 애초에 fence 가 짝이 맞지 않는다.
    """
    _prose, opener = scan(text)
    return opener


def lacks_native_prose(text, language):
    """ko 선언 문서인데 번역 대상 prose 에 한국어가 하나도 없는가(구조 이상).

    **변경 후 전체 본문**에 대해서만 물을 수 있다. 부분 diff 에 적용하면 정상 문서에 영어
    조각 하나를 더하는 편집이 곧바로 차단된다 — 이 판정의 전제는 "이 글 전체" 다.

    marker 줄은 표본에서도 근거에서도 빠진다. `Status: NOT READY — 한국어 설명` 한 줄을 한국어
    본문의 존재 증거로 세면, 실제 본문이 전부 영어여도 marker suffix 하나로 통과한다.
    (en 의 외국어 누출 검사에는 그대로 포함된다 — 거기서는 "어디에 있든 남아 있는가" 가 질문이다.)
    """
    if language != "ko":
        return False
    prose, _unclosed = scan(text)
    if any(_HANGUL.search(cleaned) for _line_no, cleaned, marker in prose if not marker):
        return False
    sample = sum(len(_NON_SPACE.sub("", cleaned))
                 for _line_no, cleaned, marker in prose if not marker)
    return sample >= MIN_PROSE_SAMPLE
