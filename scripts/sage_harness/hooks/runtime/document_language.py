"""Phase 문서의 `Document-Language` 마커 — 언어 중립 기계 계약.

이 모듈은 catalog 를 import 하지 않는다. 하는 일이 마커 값의 동일성 비교뿐이고, locale 을
끌어들이면 "어느 언어로 보여줄까"와 "어느 언어로 쓰기로 선언했나"가 한 곳에서 섞인다. 둘은
수명이 다르다 — 표시 언어는 실행 하나의 성질이고 문서 언어는 사이클 전체의 성질이다.

Phase 00 의 마커가 정본이다. `.sage/cycle.json` 은 재개 편의를 위한 미러이므로 둘이 다르면
파일이 이기는 게 아니라 hard conflict 이고, 자동으로 어느 쪽도 고치지 않는다.
"""
import re

LANGUAGES = ("ko", "en")
LEGACY_LANGUAGE = "ko"
MARKER = "Document-Language"

# fence 밖에서만 본다. 예시 블록 안의 마커까지 세면 문서에 사용법을 적는 순간 중복이 된다.
_FENCE = re.compile(r"^\s*(```|~~~)")
_MARKER = re.compile(rf"^{MARKER}:\s*(\S+)\s*$")

MISSING = "missing"
DUPLICATE = "duplicate"
INVALID = "invalid"


def scan(text):
    """(언어, 문제). 정상이면 (값, ""), 아니면 (None, MISSING|DUPLICATE|INVALID)."""
    found = []
    in_fence = False
    for line in (text or "").splitlines():
        if _FENCE.match(line):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        match = _MARKER.match(line)
        if match:
            found.append(match.group(1))
    if not found:
        return None, MISSING
    if len(found) > 1:
        return None, DUPLICATE
    value = found[0]
    if value not in LANGUAGES:
        return None, INVALID
    return value, ""


def consistency_issues(documents, declared=None):
    """같은 stem 문서들의 마커 일치 검사. [(path, 사유)] 를 돌려준다.

    `declared` 는 cycle state 의 미러 값이다. 넘기면 Phase 00 정본과의 불일치도 잡는다.
    빈 목록이 통과이며, 여기서 fail-closed 하는 것이 쓰기 전에 막는 유일한 지점이다.

    reason 문자열은 소비자가 감싸는 문장 안에 그대로 실린다. gate core 는 고정 한국어 문장
    (`"문서 언어 선언 충돌 …건 — {detail}"`)으로 감싸고 — 그 gate core 전체가 reason 을 항상
    한국어로 고정하는 것은 이 모듈 범위 밖의 더 큰 기존 한계다 — `sage retro` 는 표시 언어로
    렌더한 문장 안에 끼운다. 어느 쪽이든 여기 값은 catalog 로 이관하지 않고 영어로 직접
    고정한다: 언어별로 갈리지 않는 기계 사유값이고, 영어 fragment 가 한국어 문장에 섞이는
    것은 이미 흔한 패턴이다.
    """
    issues = []
    languages = {}
    for path, text in sorted(documents.items()):
        language, problem = scan(text)
        if problem:
            issues.append((path, problem))
            continue
        languages[path] = language

    distinct = set(languages.values())
    if len(distinct) > 1:
        for path, language in sorted(languages.items()):
            issues.append((path, f"mismatch: {language} (mixed {sorted(distinct)} within the same stem)"))
        return issues

    if declared is not None and distinct and declared not in distinct:
        for path, language in sorted(languages.items()):
            issues.append((path, f"state-mismatch: document={language} vs declared={declared}"))
    return issues
