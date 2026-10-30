"""capture-declared-risk — canonical core (pure policy, IO 없음).

SAGE hook 단일소스의 알고리즘 계층. 런타임 입력 추출/출력 렌더/파일IO는 adapter 책임.
계약: decide(event: dict) -> decision: dict  (런타임 중립, now_utc 주어지면 결정론적).

양 런타임(claude/codex) hook 공유 알고리즘
(위험레벨 정규식 2패턴, 세션 sanitize, 2일 cleanup 선언, state 구조).
런타임 차이(env명/로그경로/출력 프로토콜/메시지 텍스트)는 adapter 로 분리됨.
"""

import re

# adapter 계약 버전 — manifest.adapter_contract_version 과 일치해야 함
CONTRACT_VERSION = "1"

# 위험레벨 선언 탐지 — 개발 의도 맥락 동반 시에만 (메타 대화 오탐 회피). 정규식은 정책이라 정규화 금지.
_LEVEL_PATTERN_1 = re.compile(
    r"(?i)\bL([0-3])\s*(으?로|로|레벨|수준|작업|개발|진행|이야|야|입니다|처리|로\s*개발|로\s*진행)"
)
_LEVEL_PATTERN_2 = re.compile(
    r"(?i)(?:risk\s*level|리스크\s*(?:레벨)?|레벨)\s*([0-3])"
)

# 선언 해제 — 레벨을 포함하지 않는다(포착 패턴과 경합 금지). capture 보다 먼저 판정한다.
_CLEAR_PATTERN = re.compile(
    r"(?i)(?:위험도|리스크|risk)\s*선언\s*(?:해제|취소|초기화)|clear\s+risk\s+declaration"
)

# 해제 부정. "해제하지 않을 거예요" 로 안전장치가 꺼지면 오탐 포착보다 나쁘다 —
# 사용자가 명시적으로 반대한 방향으로 게이트를 약화시키는 것이다.
# 해제 구절 **직후**만 본다. 문장 전체를 보면 "L3 아니야 위험도 선언 해제" 처럼 부정어가
# 레벨을 향한 경우까지 해제 거부로 오인해 탈출구를 막는다.
_NEGATION_WINDOW = 12
_NEGATED_AFTER = re.compile(
    r"(?i)^\s*(?:하지\s*마|하지\s*않|하지\s*말|안\s*하|안\s*할|말고|말아|말아라|"
    r"필요\s*없|하면\s*안|은\s*안|는\s*안)"
)

# 문장 분리 — 종결부호를 남긴다(의문부호가 판정 재료다). 소수점·버전 표기(3.5, L3.java)에서
# 쪼개지지 않도록 숫자·영문 사이의 점은 경계로 보지 않는다.
_SENTENCE = re.compile(r"(?:[^.!?…\n]|\.(?=[0-9A-Za-z]))+[.!?…]*")

# 의문·가정 표지 — 문장 어디에 있어도 질문·가정으로 보는 강한 표지.
_HYPOTHETICAL = re.compile(
    r"(?i)\?|만약|가정|친다면|친다고|라고\s*치|예를\s*들|한다면|어떻게\s*되|어떡|"
    r"what\s+if|suppose|hypothetic"
)

# 종결형 의문 어미 — 문장 **끝**에서만 본다. 무경계로 잡으면 "지나가요", "만나요" 같은
# 평서형 동사에 걸려 같은 문장의 정당한 선언까지 버린다(실측).
_QUESTION_ENDING = re.compile(
    r"(?i)(?:나요|가요|습니까|입니까|은가|을까|일까|할까|될까|되나|어때|어떤가)"
    r"\s*[.!?…]*\s*$"
)


def _is_hypothetical(sentence: str) -> bool:
    return bool(_HYPOTHETICAL.search(sentence) or _QUESTION_ENDING.search(sentence))

# 호스트가 프롬프트 맨 앞에 붙여 넣는 봉투. claude 는 서브에이전트 결과를 `<agent-message …>` 로 감싸
# UserPromptSubmit 프롬프트로 넘긴다(2026-10 실측). 그 안의 「L3로 진행했습니다」·「위험도 선언 해제」는
# 사람이 한 말이 아니므로 선언 판정에서 뺀다.
_HOST_ENVELOPES = ("agent-message", "task-notification")

# 정확한 한 줄 표식. 자연어 규칙과 별개의 추가 통로이고, 표식이 있으면 자연어는 보지 않는다.
_EXPLICIT = re.compile(r"^ {0,3}SAGE-RISK:[ \t]*(L[0-3]|clear)[ \t]*$", re.IGNORECASE)
_FENCE_ANYWHERE = re.compile(r"`{3,}|~{3,}")

_CLEANUP_PATTERN = "declared-risk-*.json"
_CLEANUP_OLDER_THAN_SECONDS = 2 * 86400  # 2일


def _strip_host_envelopes(prompt: str):
    """프롬프트 맨 앞의 호스트 봉투를 걷어 낸 나머지. 맨 앞 봉투가 닫히지 않으면 None.

    맨 앞만 본다 — 호스트 봉투는 프롬프트 시작에 붙고, 중간에 나온 태그(사람이 코드 예시로 적은
    것)를 봉투로 보면 그 뒤의 사람 글까지 지운다. 닫는 태그는 0열 줄만 인정한다. claude 는 보고 본문을
    모두 들여 쓰므로 0열 줄은 호스트만 만들 수 있고, 보고가 닫는 태그를 흉내 내 구간을 일찍 끝낼 수
    없다. 닫히지 않은 봉투는 프롬프트 전체를 호스트 글로 본다 — 상태를 바꾸지 않는 방향이다.
    """
    lines = (prompt or "").splitlines()
    index = 0
    while True:
        while index < len(lines) and not lines[index].strip():
            index += 1
        if index >= len(lines):
            return ""
        head = lines[index]
        tag = next((name for name in _HOST_ENVELOPES
                    if head.startswith(f"<{name}") and head[len(name) + 1:len(name) + 2] in (">", " ")),
                   None)
        if tag is None:
            return "\n".join(lines[index:])
        closing = f"</{tag}>"
        end = next((j for j in range(index + 1, len(lines)) if lines[j].rstrip() == closing), None)
        if end is None:
            return None
        index = end + 1


def _lines_before_code(text: str):
    """첫 코드 펜스보다 앞의 줄. 붙여 넣은 문서·로그의 예시 줄이 선언·해제가 되지 않게 한다.

    펜스 안팎을 끝까지 추적하지 않는다. 닫는 펜스 판정(같은 문자·길이·뒤 글자, 목록·인용 문맥,
    상대 들여쓰기)은 리뷰마다 새 반례가 나온 표면이었고, 하나라도 틀리면 코드 예시가 선언·해제로
    실행된다. 표식은 보통 프롬프트 맨 위에 쓰므로 「어디든 펜스 모양(``` · ~~~)이 처음 나오기 전까지만
    본다」로 닫는다. 그 뒤의 표식은 받지 않을 뿐이고, 자연어 규칙은 지금처럼 전체를 본다.
    들여쓰기 코드 줄(탭·4칸)도 받지 않는다.
    """
    for raw in (text or "").splitlines():
        if _FENCE_ANYWHERE.search(raw):
            return
        if raw.startswith("\t") or raw.startswith("    "):
            continue
        yield raw


def _explicit_values(text: str) -> set:
    values = set()
    for line in _lines_before_code(text):
        match = _EXPLICIT.match(line)
        if match:
            value = match.group(1)
            values.add("clear" if value.lower() == "clear" else value.upper())
    return values


def _sentences(text: str) -> list:
    return [m.group(0) for m in _SENTENCE.finditer(text) if m.group(0).strip()]


def _declared_levels(prompt: str) -> list:
    """선언으로 볼 수 있는 레벨들. 의문·가정 문장의 매치는 버린다.

    모호함은 **선언 시도**의 수로 판정한다. 접미사 없는 단순 언급(`캐시는 L2 유지`,
    `L3.java`, 코드블록 주석)은 선언 시도가 아니므로 세지 않는다 — 세면 개발자 프롬프트에서
    흔한 레벨 언급 하나가 정당한 선언을 통째로 폐기한다(실측).
    """
    levels = []
    for sentence in _sentences(prompt):
        if _is_hypothetical(sentence):
            continue
        levels += [int(m.group(1)) for m in _LEVEL_PATTERN_1.finditer(sentence)]
        levels += [int(m.group(1)) for m in _LEVEL_PATTERN_2.finditer(sentence)]
    return levels


def _clear_requested(prompt: str) -> bool:
    """해제는 포착과 같은 문장 필터를 거친다 — 질문·부정에서 안전장치를 끄지 않는다."""
    for sentence in _sentences(prompt):
        if _is_hypothetical(sentence):
            continue
        for match in _CLEAR_PATTERN.finditer(sentence):
            tail = sentence[match.end():match.end() + _NEGATION_WINDOW]
            if not _NEGATED_AFTER.match(tail):
                return True
    return False


def _sanitize_session(session_id: str) -> str:
    return re.sub(r"[^A-Za-z0-9_-]", "_", session_id or "nosession")[:64]


def decide(event: dict) -> dict:
    """유저 프롬프트에서 명시적 risk level 선언을 포착할지 결정한다.

    event: { prompt, session_id, now_utc, ... }
    decision: { action: capture|noop, level, session_key, state_file, state, cleanup, exit_code, message_key }
    """
    raw_prompt = event.get("prompt") or ""
    session_key = _sanitize_session(event.get("session_id") or "nosession")
    now_utc = event.get("now_utc")  # adapter 가 실행시각/고정값을 주입 (core는 시간 호출 안 함)

    cleanup = {"pattern": _CLEANUP_PATTERN, "older_than_seconds": _CLEANUP_OLDER_THAN_SECONDS}
    state_file = f"declared-risk-{session_key}.json"

    def _captured(level, source):
        # `source` 는 기록용이다. 읽는 쪽(게이트)은 `level` 만 본다 — 이 필드가 없는 기존 상태
        # 파일도 그대로 유효하다.
        return {
            "kind": "capture_declared_risk",
            "action": "capture",
            "level": level,
            "session_key": session_key,
            "state_file": state_file,
            "state": {
                "level": level,
                "ts": now_utc,
                "source": source,
                "excerpt": prompt[:120].replace("\n", " "),
            },
            "cleanup": cleanup,
            "exit_code": 0,
            "message_key": "risk_declared",
        }

    def _plain(action, level=None, file=None, message_key=None):
        return {
            "kind": "capture_declared_risk",
            "action": action,
            "level": level,
            "session_key": session_key,
            "state_file": file,
            "state": None,
            "cleanup": cleanup,
            "exit_code": 0,
            "message_key": ("risk_declaration_cleared" if action == "clear" else message_key),
        }

    prompt = _strip_host_envelopes(raw_prompt)
    if not prompt:
        return _plain("noop")

    explicit = _explicit_values(prompt)
    if not explicit and any(_EXPLICIT.match(line) for line in prompt.splitlines()):
        # 표식을 썼지만 인정 위치(첫 코드 펜스 앞)에 없다. 표식을 쓴 사람은 표식 통로로 정하려 한
        # 것이므로 자연어 규칙으로 넘어가지 않는다 — 넘어가면 코드 블록 안 예시 문장이 선언을
        # 해제하거나 낮춘다. 상태를 바꾸지 않고 위치만 알린다.
        return _plain("noop", message_key="risk_declaration_marker_position")
    if len(explicit) > 1:
        return _plain("noop", message_key="risk_declaration_ambiguous")
    if explicit == {"clear"}:
        return _plain("clear", file=state_file)
    if explicit:
        return _captured(explicit.pop(), "explicit")

    # 해제를 포착보다 먼저 본다. 잘못 잡힌 선언을 지우려는 프롬프트가 다시 선언으로 잡히면
    # 사용자에게 남는 탈출구가 없다.
    if _clear_requested(prompt):
        return _plain("clear", file=state_file)

    levels = _declared_levels(prompt)
    if not levels:
        return _plain("noop")

    # 서로 다른 레벨을 함께 선언하면 선언이 아니라 비교·설명이다. 실측: "L3 개발을 1차로 한후
    # … 다시 L2 로 개발을" 이 max() 때문에 L3 선언으로 잡혀 세션 전체가 묶였다.
    #
    # 기각을 사용자에게 알린다. 조용히 넘기면 선언했다고 믿은 채 진행하게 되고, 포착 확인
    # 메시지의 *부재* 가 유일한 신호가 된다 — 부재는 눈치채기 어렵다. 포착을 넓히는 것이 아니라
    # 기각 사실만 알리므로 오탐 위험은 없다.
    if len(set(levels)) != 1:
        return _plain("noop", message_key="risk_declaration_ambiguous")

    return _captured(f"L{levels[0]}", "natural")
