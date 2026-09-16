"""hook 의 표시 언어 결정 — 대상 프로젝트 local profile → ko.

CLI 와 달리 hook 에는 `--lang` 이 없다. hook 은 사용자가 부르는 게 아니라 host 가 이벤트로
띄우는 것이라 인자를 실을 자리가 없기 때문이다. 그래서 통로는 대상 프로젝트의 local profile
하나이며, 없으면 한국어다.

판정은 이 값을 쓰지 않는다. 언어가 판정에 닿는 순간 같은 입력이 사람마다 다른 결과를 내고,
그건 거버넌스가 개인 설정에 물린다는 뜻이다.
"""
import os
import re

DEFAULT_LANGUAGE = "ko"
LANGUAGES = ("ko", "en")

_LOCAL_PROFILE = os.path.join("sage", "project-profile.local.yaml")
# pyyaml 이 없는 소비 환경에서도 hook 은 돌아야 한다. 언어 한 줄을 읽자고 의존성을 요구하면
# 그 부재가 게이트 전체를 멈추게 한다 — 값 하나만 정규식으로 집는다.
_INTERFACE = re.compile(r"^interface:\s*$", re.M)
_LANGUAGE = re.compile(r"^[ \t]+language:[ \t]*[\"']?([A-Za-z_-]+)[\"']?[ \t]*$", re.M)


def read_local_language(root):
    """(언어, 손상됨). 읽을 수 없거나 값이 이상하면 (None, True)."""
    path = os.path.join(root or ".", _LOCAL_PROFILE)
    if not os.path.isfile(path):
        return None, False
    try:
        with open(path, encoding="utf-8") as handle:
            text = handle.read()
    except OSError:
        return None, True
    match = _INTERFACE.search(text)
    if not match:
        return None, False
    value = _LANGUAGE.search(text, match.end())
    if not value:
        return None, True
    language = value.group(1)
    if language not in LANGUAGES:
        return None, True
    return language, False


def resolve(root):
    """표시 언어 하나. 손상은 기본값으로 떨어지되 호출자가 표면화할 수 있게 함께 돌려준다."""
    language, damaged = read_local_language(root)
    return (language or DEFAULT_LANGUAGE), damaged
