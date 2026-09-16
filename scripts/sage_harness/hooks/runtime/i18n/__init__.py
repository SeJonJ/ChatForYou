"""설치 hook 의 표시 언어 — 소비 프로젝트에서 단독 실행되는 자체 포함 locale.

이 package 는 main `sage` package 를 import 하지 않는다. hook 은 소비 프로젝트에 설치된
runtime 으로 도는데, 거기에 엔진이 설치돼 있으리라는 보장이 없다. import 하나가 들어오는
순간 hook 은 엔진 없이는 못 도는 물건이 되고, 그건 게이트가 조용히 사라지는 경로다.

CLI 와 key 집합을 공유하지 않는다. `ok_l1` 같은 기존 hook key 는 호환 계약이라 여기에만 있고,
CLI 는 `cli.*` 를 쓴다. 두 도메인이 겹치면 같은 문장의 소유자가 둘이 된다.
"""
from . import context
from . import en as _en
from . import ko as _ko

DEFAULT_LANGUAGE = "ko"
LANGUAGES = ("ko", "en")
CATALOGS = {"ko": _ko.MESSAGES, "en": _en.MESSAGES}
FRAGMENTS = {"ko": _ko.FRAGMENTS, "en": _en.FRAGMENTS}

# 판정 core 가 낼 수 있는 message_key 전부. build-time oracle 이 이 집합과 두 catalog 를
# 대조한다 — 번역 문자열을 뒤지거나 AST 를 추측하면 새 key 하나가 조용히 빠진다.
HOOK_MESSAGE_KEYS = frozenset(_ko.MESSAGES)

__all__ = ["CATALOGS", "DEFAULT_LANGUAGE", "FRAGMENTS", "HOOK_MESSAGE_KEYS",
           "LANGUAGES", "context", "frag", "tr"]


def tr(language, key, **arguments):
    """선택 언어로 렌더. 실패해도 판정과 exit code 는 건드리지 않는다."""
    template = CATALOGS.get(language, {}).get(key)
    if template is None:
        template = CATALOGS[DEFAULT_LANGUAGE].get(key)
    if template is None:
        return f"[SAGE] message_key={key}"
    try:
        return template.format(**arguments)
    except (KeyError, IndexError, ValueError):
        return f"[SAGE] message_key={key}"


def frag(language, key):
    """문장을 감싸는 조각. 없으면 기본 언어, 그것도 없으면 빈 문자열이다.

    조각이 비어도 판정 줄은 서야 하므로 `tr` 처럼 key 를 노출하지 않는다 — 라벨 자리에
    `[SAGE] message_key=...` 가 끼면 줄 전체가 못 읽는 상태가 된다.
    """
    table = FRAGMENTS.get(language) or {}
    return table.get(key) or FRAGMENTS[DEFAULT_LANGUAGE].get(key, "")
