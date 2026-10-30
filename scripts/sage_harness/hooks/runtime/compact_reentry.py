"""compact_reentry — 컨텍스트 압축 직후 모델에 넣을 재진입 문맥.

에이전트는 압축을 직접 못 한다(Claude Code·codex 모두). 압축은 사용자 `/compact` 나 자동 압축이고,
그 직후 `SessionStart`(source `compact`)가 발화한다. 이 모듈은 그때 넣을 짧은 글을 만든다.

원칙은 하나다. **압축 요약을 믿지 않고 문서에서 다시 읽은 것이 상태를 정한다.** 그래서 이 글은
상태 자체가 아니라 상태가 있는 곳(사이클·snapshot·briefing 을 만드는 명령·루프 run)을 가리킨다.

- 엔진(`sage` 패키지)을 import 하지 않는다. 설치본 hook 트리에서 단독으로 돈다. 그래서 briefing 을
  만드는 `sage context restore` 를 여기서 실행하지 않고 명령을 알려 준다.
- 사이클이 모호하면 고르지 않는다. 엉뚱한 사이클의 briefing 을 넣는 것은 아무것도 안 넣는 것보다
  나쁘다.
- 실패를 삼키지 않는다. 각 항목이 실패하면 그 사유를 그 자리에 적는다.
- 크기는 UTF-8 2048 바이트 이하다(codex 의 문맥 주입 한도 아래).
"""
import glob
import os
import re

import cycle_state
import loop_audit

try:  # 설치본은 flat import, 엔진 체크아웃 테스트는 패키지 import 로 올 수 있다
    from . import i18n as _i18n
except ImportError:
    import i18n as _i18n

MAX_BYTES = 2048
_SNAPSHOT_NAME = re.compile(r"^(?P<phase>[0-9A-Za-z_-]+?)-ctx-[0-9a-f]+\.json$")


def _phase_order(profile):
    pdca = (profile or {}).get("pdca") if isinstance(profile, dict) else None
    phases = (pdca or {}).get("phases") if isinstance(pdca, dict) else None
    order = [str(item.get("id")) for item in phases or []
             if isinstance(item, dict) and item.get("id") is not None]
    return order or ["00", "01", "02", "03", "04", "05", "06"]


def _latest_snapshot(root, stem, order):
    """(상대 경로, 완료 phase, 다음 phase) 또는 None. 내용은 읽지 않는다 — 검증은 restore 가 한다."""
    root_real = os.path.realpath(root)
    directory = os.path.join(root_real, ".sage", "context", "snapshots", stem)
    if os.path.islink(directory) or not os.path.isdir(directory):
        return None
    best = None
    for path in glob.glob(os.path.join(glob.escape(directory), "*.json")):
        if os.path.islink(path) or not os.path.isfile(path):
            continue
        match = _SNAPSHOT_NAME.match(os.path.basename(path))
        if not match:
            continue
        phase = match.group("phase")
        rank = order.index(phase) if phase in order else -1
        key = (rank, os.path.getmtime(path))
        if best is None or key > best[0]:
            best = (key, path, phase)
    if best is None:
        return None
    _key, path, phase = best
    following = None
    if phase in order and order.index(phase) + 1 < len(order):
        following = order[order.index(phase) + 1]
    return os.path.relpath(path, root_real).replace(os.sep, "/"), phase, following


def _open_runs(records):
    closed = {r.get("run_id") for r in records if r.get("event") == "loop_close"}
    return [r for r in records
            if r.get("event") == "loop_open" and r.get("run_id") not in closed]


def _declaration_problem(error):
    """`read_declaration` 진단(언어 중립 code+arguments) → 한 줄. 부재와 손상을 섞지 않는다."""
    if isinstance(error, dict):
        code = error.get("code") or "cycle_state.unknown"
        arguments = error.get("arguments") or {}
        detail = arguments.get("error_type") if isinstance(arguments, dict) else None
        return f"{code}" + (f" ({detail})" if detail else "")
    return str(error)[:200]


def _cycle_choice(root, environ, records, problems):
    """(stem 또는 None, 모호할 때의 값 목록). 게이트와 같은 env > 파일 해석에 열린 run 을 대조한다.

    선언 파일이 깨졌으면 `problems` 에 적는다 — 「선언 없음」으로 보이면 손상이 묻힌다.
    """
    env_stem = (environ.get("SAGE_CYCLE_STEM") or "").strip()
    file_stem, error = cycle_state.read_declaration(root)
    if error:
        problems.append(_declaration_problem(error))
    run_stems = sorted({r.get("cycle_stem") for r in _open_runs(records)
                        if isinstance(r.get("cycle_stem"), str) and r.get("cycle_stem")})
    values = []
    if env_stem:
        values.append(("env", env_stem))
    if file_stem:
        values.append(("file", file_stem))
    values += [("run", stem) for stem in run_stems]
    distinct = {value for _origin, value in values}
    if len(distinct) > 1:
        return None, values
    if not distinct:
        return None, []
    stem = distinct.pop()
    origin = "env" if env_stem else ("file" if file_stem else "run")
    return (stem, origin), []


def _review_cfg(profile):
    pdca = profile.get("pdca") if isinstance(profile, dict) else None
    cfg = pdca.get("review_loop") if isinstance(pdca, dict) else None
    return cfg if isinstance(cfg, dict) else {}


def _loop_lines(language, records, stem, profile):
    lines = []
    cfg = _review_cfg(profile)
    for opened in _open_runs(records):
        if opened.get("cycle_stem") != stem:
            continue
        rid = opened.get("run_id")
        rounds = [r for r in records if r.get("event") == "round" and r.get("run_id") == rid]
        state = loop_audit.run_cycle_state(records, rid)
        # `next` 와 같은 판정이다. 상한 도달만 보면 예산·아키텍처 STOP 을 ASK 로 보여 준다.
        verdict = loop_audit.loop_verdict(records, rid, cfg, opened.get("risk"))
        if verdict["action"] == "ASK" and verdict["reason"] == loop_audit.CRITICAL_P2_REASON:
            pending = (verdict.get("critical") or {}).get("pending") or []
            waiting = _i18n.tr(language, "hook.compact.ask_critical_p2",
                               findings=", ".join(pending) or "-")
        elif verdict["action"] == "ASK":
            waiting = _i18n.tr(language, "hook.compact.ask_cycle_cap")
        elif verdict["action"] == "STOP":
            waiting = _i18n.tr(language, "hook.compact.wait_stop", reason=verdict["reason"])
        else:
            waiting = _i18n.tr(language, "hook.compact.ask_none")
        lines.append(_i18n.tr(language, "hook.compact.loop", run_id=rid, rounds=len(rounds),
                         cycle_rounds=(state or {}).get("rounds", "-"),
                         cap=(state or {}).get("cap", "-"), waiting=waiting))
    return lines or [_i18n.tr(language, "hook.compact.loop_none")]


def _guarded(language, label, build):
    try:
        return build()
    except Exception as exc:  # noqa: BLE001 - 한 항목의 실패가 문맥 전체를 지우면 안 된다
        return [_i18n.tr(language, "hook.compact.item_failed", item=label,
                         error=f"{type(exc).__name__}: {str(exc)[:160]}")]


def _origin_label(language, origin):
    # key 를 조립하지 않는다 — catalog 사용 검사가 리터럴 key 만 센다.
    if origin == "env":
        return _i18n.tr(language, "hook.compact.origin_env")
    if origin == "file":
        return _i18n.tr(language, "hook.compact.origin_file")
    return _i18n.tr(language, "hook.compact.origin_run")


def build(root, profile=None, environ=None, language=None):
    """재진입 문맥 문자열. 예외를 밖으로 내지 않는다."""
    environ = os.environ if environ is None else environ
    if language is None:
        try:
            language = _i18n.context.resolve(root)[0]
        except Exception:  # noqa: BLE001
            language = _i18n.DEFAULT_LANGUAGE
    head = [_i18n.tr(language, "hook.compact.head")]
    tail = [_i18n.tr(language, "hook.compact.decisions_warning"),
            _i18n.tr(language, "hook.compact.next")]
    body = []
    try:
        _state, records, issues = loop_audit._read_status_unlocked(loop_audit.audit_path(root))
        if issues:
            body.append(_i18n.tr(language, "hook.compact.item_failed",
                            item=_i18n.tr(language, "hook.compact.label_loop"),
                            error="; ".join(str(i) for i in issues[:2])[:200]))
    except Exception as exc:  # noqa: BLE001
        records = []
        body.append(_i18n.tr(language, "hook.compact.item_failed",
                        item=_i18n.tr(language, "hook.compact.label_loop"),
                        error=f"{type(exc).__name__}: {exc}"[:200]))
    declaration_problems = []
    try:
        choice, ambiguous = _cycle_choice(root, environ, records, declaration_problems)
    except Exception as exc:  # noqa: BLE001
        choice, ambiguous = None, []
        declaration_problems.append(f"{type(exc).__name__}: {exc}"[:200])
    for problem in declaration_problems:
        body.append(_i18n.tr(language, "hook.compact.item_failed",
                        item=_i18n.tr(language, "hook.compact.label_cycle"), error=problem))
    if ambiguous:
        listed = ", ".join(f"{origin}={value}" for origin, value in ambiguous)
        body.append(_i18n.tr(language, "hook.compact.cycle_ambiguous", values=listed))
    elif choice is None:
        body.append(_i18n.tr(language, "hook.compact.cycle_none"))
    else:
        stem, origin = choice
        body.append(_i18n.tr(language, "hook.compact.cycle", stem=stem,
                        origin=_origin_label(language, origin)))
        order = _phase_order(profile)

        def _snapshot_lines():
            found = _latest_snapshot(root, stem, order)
            if found is None:
                return [_i18n.tr(language, "hook.compact.snapshot_none")]
            path, phase, following = found
            return [_i18n.tr(language, "hook.compact.snapshot", path=path, phase=phase,
                        next=following or "-"),
                    _i18n.tr(language, "hook.compact.restore", path=path)]
        body += _guarded(language, _i18n.tr(language, "hook.compact.label_snapshot"),
                         _snapshot_lines)
        body += _guarded(language, _i18n.tr(language, "hook.compact.label_loop"),
                         lambda: _loop_lines(language, records, stem, profile))
        body.append(_i18n.tr(language, "hook.compact.ledger", stem=stem))
    return _fit(head, body, tail)


def _fit(head, body, tail):
    """2048 바이트 안으로. 경로·명령이 든 본문을 지키고, 넘치면 꼬리 경고를 먼저 줄인다."""
    def _size(lines):
        return len("\n".join(lines).encode("utf-8"))
    lines = head + body + tail
    if _size(lines) <= MAX_BYTES:
        return "\n".join(lines)
    lines = head + body + tail[-1:]
    if _size(lines) <= MAX_BYTES:
        return "\n".join(lines)
    text = "\n".join(lines).encode("utf-8")[:MAX_BYTES - 16].decode("utf-8", "ignore")
    return text + "\n…(truncated)"
