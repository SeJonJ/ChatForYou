"""override_audit — 게이트 BLOCK 의 합법적 우회 + 감사 추적.

게이트(pre-implementation-gate · pre-phase4-checklist-gate)가 BLOCK 을 걸면, 운영자가 사유·기한과
함께 명시적으로 우회한다. 권한과 감사를 두 저장소로 분리한다:

- 감사 로그 `.sage/override.jsonl` — grant·bypass 전 이력(append-only). 저장소 안에 두고 커밋해
  "누가 언제 왜 무엇을 우회했는지"를 동료·CI·리뷰어가 clone 후에도 추적할 수 있다.
- 권한 캐시 — 이 머신에서 활성인 grant. **저장소 트리 밖**(상태 디렉터리)에 두어 git 이 실어나를 수
  없게 한다. 예전에는 `.sage/tmp/grants.jsonl` 이었고 `.gitignore` 로 막는다고 적었지만, 설치
  프로젝트에는 그 규칙을 넣는 코드가 없어서 기본값이 '추적'이었다. 실제로 커밋되면 다른 clone 에서
  남이 발급한 우회가 활성화됐다(0.9.73 재현). 무시 규칙은 사용자가 지울 수 있는 파일이므로 보안
  속성의 근거로 쓰지 않고, 전파 경로 자체를 없앤다.

활성 여부는 권한 캐시만으로 판정한다. TTL 만료 시 자동 회수되어 상시 우회를 막는다(wall-clock 기준,
세션 교차에도 일관). gate 스코프는 특정 게이트 id 또는 "all" — 우회는 grant.gate ∈ {요청 gate, "all"}.

엔진 모듈(도메인값 0): 게이트 id 는 호출자가 주입하고, 경로/시간만 여기서 결정한다.
"""
import hashlib
import json
import os
import time
import uuid

AUDIT_REL = os.path.join(".sage", "override.jsonl")        # 커밋되는 감사 이력

# 구 권한 캐시 경로. 읽지 않는다 — MAX_TTL_SECONDS(24h) 가 grant() 안에서 강제되므로 이전 grant 는
# 하루 안에 전부 만료된다. 따라서 마이그레이션이 필요 없고, 남은 파일은 무해한 잔존물이다.
LEGACY_GRANTS_REL = os.path.join(".sage", "tmp", "grants.jsonl")

STATE_HOME_ENV = "SAGE_STATE_HOME"   # 명시 지정(테스트·운영). XDG_STATE_HOME 보다 우선.
_UNITS = {"s": 1, "m": 60, "h": 3600, "d": 86400}

# TTL 상한. "시한부 우회"가 임의로 길어지면 사실상 상시 우회가 되므로 24h 로 캡한다. 초과 grant 는
# 거부하고, 더 길게 필요하면 만료 후 재발급하게 강제한다.
MAX_TTL_SECONDS = 24 * 3600


def audit_path(root):
    return os.path.join(root, AUDIT_REL)


def _candidate_state_home(env):
    explicit = (env.get(STATE_HOME_ENV) or "").strip()
    if explicit:
        return explicit
    xdg = (env.get("XDG_STATE_HOME") or "").strip()
    if xdg:
        return os.path.join(xdg, "sage")
    # Windows 는 XDG 관례가 없다. %LOCALAPPDATA% 가 같은 역할(머신 로컬·비로밍)이다.
    if os.name == "nt":
        local = (env.get("LOCALAPPDATA") or "").strip()
        if local:
            return os.path.join(local, "sage", "state")
    return os.path.join(os.path.expanduser("~"), ".local", "state", "sage")


def _diagnostic(code, **arguments):
    """언어 중립 진단(code+arguments). 이 모듈은 sage.diagnostics 를 import 할 수 없어
    (엔진 없이 소비 프로젝트에서 단독 실행되어야 하므로) 같은 모양의 plain dict 로 올린다 —
    hook_runtime.py 는 자체 _i18n(hook_runtime._overlay_say)으로, CLI(sage/commands/override.py)는
    sage.i18n 으로 각자 렌더한다."""
    return {"code": code, "arguments": arguments, "evidence": ""}


class StateHomeError(RuntimeError):
    """권한 캐시 위치를 안전하게 정할 수 없음. 우회 판정 불가 → 호출자는 BLOCK 을 유지해야 한다.

    `diagnostic` 은 code+arguments dict(위 `_diagnostic()`)다 — CLI 는 sage.diagnostics.render 로,
    hook 은 hook_runtime._overlay_say 로 각자 렌더한다. `str(exc)` 는 code 만 낸다(완성 문장은
    아니지만, 진단을 렌더하지 않고 그대로 찍는 경로에서도 원인이 사라지지 않는다)."""

    def __init__(self, diagnostic):
        self.diagnostic = diagnostic
        super().__init__(diagnostic.get("code", str(diagnostic)))


def state_home(environ=None):
    """권한 캐시가 사는 머신 로컬 상태 디렉터리. **반드시 절대경로**여야 한다.

    해석 불가면 예외로 fail-closed 한다. 폴백을 두지 않는 이유: 예측 가능한 공용 위치(temp 등)로
    물러서면 그 경로에 유효한 grant JSONL 을 **미리 심어두는 것만으로 우회 권한이 생긴다**
    (공용 temp 는 보통 0755). 우회는 권한이므로, 위치를 확신할 수 없으면 권한을 만들지 않는 쪽이 맞다.
    운영자는 SAGE_STATE_HOME 으로 명시할 수 있다.
    """
    env = os.environ if environ is None else environ
    candidate = _candidate_state_home(env)
    if not os.path.isabs(candidate):
        raise StateHomeError(_diagnostic("override_audit.state_home_not_absolute",
                                         candidate=repr(candidate), env_var=STATE_HOME_ENV))
    return candidate


def _is_within(child, parent):
    child = os.path.realpath(child)
    parent = os.path.realpath(parent)
    return child == parent or child.startswith(parent + os.sep)


_GIT_ABSENT = "absent"      # `.git` 항목 자체가 없다 → 상위로 계속 탐색
_GIT_OK = "ok"              # 해석 성공
_GIT_BROKEN = "broken"      # `.git` 은 있는데 해석 실패 → 판단 불가


def _probe_gitdir(path):
    """`path` 자신의 `.git` 을 3-상태로 판정 → (상태, gitdir 또는 사유).

    "없음"과 "있는데 못 읽음"을 구분하는 것이 핵심이다. 후자를 "git 아님"으로 뭉개면 마커가
    `.sage/` 로 떨어지는데, 그 위치는 부모 저장소 안일 수 있어 커밋·clone 으로 전파된다.
    `.git` 이 존재한다는 것은 이미 "여기가 저장소 경계"라는 신호이므로, 해석 실패는 모른다는 뜻이지
    아니라는 뜻이 아니다(codex 3R)."""
    git = os.path.join(path, ".git")
    if not os.path.lexists(git):
        return _GIT_ABSENT, None
    if os.path.isdir(git):
        return _GIT_OK, git
    if not os.path.isfile(git):
        return _GIT_BROKEN, _diagnostic("override_audit.git_neither_file_nor_dir", git=git)
    try:                                     # worktree/submodule: gitdir 를 가리키는 포인터 파일
        with open(git, encoding="utf-8") as f:
            line = f.read().strip()
    except OSError as exc:
        return _GIT_BROKEN, _diagnostic("override_audit.git_pointer_unreadable", git=git,
                                        error_type=type(exc).__name__, exc=exc)
    if not line.startswith("gitdir:"):
        return _GIT_BROKEN, _diagnostic("override_audit.git_pointer_bad_format", git=git)
    target = line.split(":", 1)[1].strip()
    if target and not os.path.isabs(target):
        target = os.path.join(path, target)
    if not target or not os.path.isdir(target):
        return _GIT_BROKEN, _diagnostic("override_audit.git_pointer_target_missing", target=repr(target))
    return _GIT_OK, target


def _gitdir(root):
    """root 를 **포함하는** 저장소의 gitdir. 상위로 올라가며 찾는다. 어디에도 없으면 None.

    root 자신만 보면, 모노레포에서 하위 디렉터리를 root 로 잡은 구성(`CLAUDE_PROJECT_DIR=<repo>/apps/web`)
    이 "git 아님"으로 판정돼 마커가 `<repo>/apps/web/.sage/` 로 떨어진다. 그 위치는 부모 저장소 안이라
    커밋·clone 으로 전파될 수 있어, git 이 아니어서 전파 위험이 없다는 전제가 깨진다(codex 2R 후속).

    손상된 `.git` 을 만나면 **거기서 멈추고 fail-closed** 한다. 더 바깥 저장소로 탐색을 이어가면
    엉뚱한 저장소의 정체성을 이 워킹카피 것으로 쓰게 된다(codex 3R)."""
    current = os.path.realpath(root)
    while True:
        state, value = _probe_gitdir(current)
        if state == _GIT_OK:
            return value
        if state == _GIT_BROKEN:
            raise StateHomeError(_diagnostic("override_audit.repo_boundary_unresolved", reason=value))
        parent = os.path.dirname(current)
        if parent == current:
            return None
        current = parent


def _identity_marker(root):
    """정체성 마커 경로. 저장소 트리 안이어야 교체 시 함께 사라진다.

    git 저장소(상위 포함) 안이면 그 `.git/sage/` 에 둔다 — clone·커밋으로 절대 전파되지 않는다.
    도구 전용 하위 폴더를 쓰는 것은 생태계 관례다(`.git/lfs/`·`.git/annex/`·`.git/git-crypt/`,
    GUI 클라이언트의 `.git/gk/`·`.git/cursor/`). 최상위에 낱개 파일을 두면 이름이 충돌하기 쉽다.
    git 자신도 워킹카피 전용 로컬 상태를 `.git/info/exclude`·`.git/worktrees/` 로 같은 자리에 둔다.

    어떤 저장소에도 속하지 않을 때만 `.sage/` 에 두며, 이 경우 clone/commit 경로 자체가 없다.

    같은 저장소의 서로 다른 하위 root 는 마커를 공유하지만 키는 realpath 를 함께 해싱하므로 갈린다.
    저장소가 교체되면 `.git` 이 함께 바뀌어 하위 root 들도 일제히 새 정체성을 얻는다."""
    gitdir = _gitdir(root)
    if gitdir:
        return os.path.join(gitdir, "sage", "state-id")
    return os.path.join(root, ".sage", "instance-id")


def _repo_id(root, create=False):
    """이 워킹카피의 정체성. 저장소를 지우고 같은 경로에 다른 저장소를 만들면 새 값이 된다 —
    경로만으로 식별하면 교체된 저장소가 이전 grant 를 물려받는다(CI 워크스페이스 경로 재사용).

    읽기(create=False)에서는 만들지 않는다. 부작용이기도 하고, 마커가 없으면 키가 달라져 grant 를
    못 찾는 안전한 방향으로 떨어지기 때문이다.

    발급(create=True)에서 영속에 실패하면 **fail-closed**(StateHomeError). 읽기 전용 `.git`·권한
    문제·I/O 오류일 때 조용히 경로 전용 키로 물러서면, 교체된 저장소가 이전 grant 를 그대로
    상속한다(codex 2R 재현). 정체성을 보장할 수 없으면 권한을 만들지 않는다."""
    marker = _identity_marker(root)
    value = _read_identity(marker)
    if value:
        return value
    if not create:
        return None
    try:
        os.makedirs(os.path.dirname(marker), exist_ok=True)
        # O_EXCL 로 원자적 생성 — 최초 동시 발급에서 두 프로세스가 서로 다른 id 를 쓰면
        # 한쪽 grant 가 즉시 미아가 된다. 경쟁에서 지면 승자의 값을 읽어 쓴다.
        fd = os.open(marker, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        try:
            os.write(fd, (uuid.uuid4().hex + "\n").encode("utf-8"))
        finally:
            os.close(fd)
    except FileExistsError:
        pass
    except OSError as exc:
        raise StateHomeError(_diagnostic("override_audit.identity_marker_create_failed",
                                         marker=repr(marker), error_type=type(exc).__name__,
                                         exc=exc)) from exc
    value = _await_identity(marker)
    if not value:
        raise StateHomeError(_diagnostic("override_audit.identity_marker_unreadable", marker=repr(marker)))
    return value


# 승자가 O_EXCL 로 파일을 만든 시점과 33 bytes 를 쓰는 시점 사이에 아주 짧은 창이 있다. 그 사이에
# 패자가 읽으면 빈 문자열을 보고 실패한다 — "경쟁에서 지면 승자 값을 읽는다"는 계약이 깨진다.
# 창이 write() 한 번이라 짧은 재시도로 충분하다. rename/link 로 없앨 수도 있지만 Windows 파일시스템
# 편차를 새로 떠안게 되므로(10-d 직후) 이식성이 확실한 재시도를 택한다.
_IDENTITY_READ_ATTEMPTS = 20
_IDENTITY_READ_DELAY = 0.01


def _await_identity(marker):
    for attempt in range(_IDENTITY_READ_ATTEMPTS):
        value = _read_identity(marker)
        if value:
            return value
        if attempt + 1 < _IDENTITY_READ_ATTEMPTS:
            time.sleep(_IDENTITY_READ_DELAY)
    return None


def _read_identity(marker):
    try:
        with open(marker, encoding="utf-8") as f:
            return f.read().strip()
    except OSError:
        return None


def _root_key(root, create=False):
    """저장소를 식별하는 안정 키 = realpath + 워킹카피 정체성.

    realpath 정규화가 없으면 symlink 경유 접근이 같은 저장소를 두 키로 갈라 발급한 grant 가 안 보인다.
    경로만 쓰면 반대로 서로 다른 저장소가 같은 키를 공유한다(CI 워크스페이스처럼 경로를 재사용하는
    환경에서 실제로 발생한다). 해시는 절단하지 않는다 — 절단으로 얻는 이점이 없다."""
    ident = os.path.realpath(root) + "\0" + (_repo_id(root, create=create) or "")
    return hashlib.sha256(ident.encode("utf-8")).hexdigest()


def grants_path(root, environ=None, create=False):
    """권한 캐시 경로. 저장소 트리 안이면 fail-closed — 트리 밖이라는 것이 이 설계의 불변식이고,
    안이면 커밋돼서 다른 clone 으로 우회가 전파된다(이 사이클이 막으려던 바로 그 상태)."""
    home = state_home(environ)
    if _is_within(home, root):
        raise StateHomeError(_diagnostic("override_audit.state_home_inside_repo",
                                         home=repr(home), root=repr(os.path.realpath(root)),
                                         env_var=STATE_HOME_ENV))
    return os.path.join(home, "grants", _root_key(root, create=create) + ".jsonl")


def legacy_grants_path(root):
    """구 저장소 내 권한 캐시 경로(진단·안내용). 판정에는 쓰지 않는다."""
    return os.path.join(root, LEGACY_GRANTS_REL)


def parse_ttl(s):
    """'30m' | '2h' | '1d' | '90s' | '1800'(초) → seconds(int). 음수/0/무효 → None."""
    s = (s or "").strip().lower()
    if not s:
        return None
    try:
        if s[-1] in _UNITS:
            secs = int(float(s[:-1]) * _UNITS[s[-1]])
        else:
            secs = int(float(s))
    except (ValueError, IndexError):
        return None
    return secs if secs > 0 else None


def _iso(epoch):
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(epoch))


def _append(path, record):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "a", encoding="utf-8") as f:
        f.write(json.dumps(record, ensure_ascii=False) + "\n")


def _read_jsonl(path):
    if not os.path.exists(path):
        return []
    out = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                out.append(json.loads(line))
            except Exception:
                pass
    return out


def read_records(root):
    """감사 로그 전 레코드(파싱 실패 줄 skip). 부재 → []."""
    return _read_jsonl(audit_path(root))


def grant(root, reason, ttl_seconds, gate="all", user=None, now=None):
    """override grant 1건 발급 → 레코드 반환. reason·ttl 필수(상위에서 검증).

    감사 로그(커밋·영속)와 권한 캐시(로컬·집행) 양쪽에 기록한다. TTL 상한 초과는 거부(ValueError):
    라이브러리 레벨 불변식이라 CLI 를 우회해 직접 호출해도 시한부 보장이 깨지지 않는다."""
    if int(ttl_seconds) > MAX_TTL_SECONDS:
        # 방어적 재검증 — CLI 호출부(sage override)는 ttl 을 MAX_TTL_SECONDS 로 이미 검증한 뒤에만
        # 여기 도달한다. 화면 문구가 아니라 잘못된 프로그램 호출을 잡는 내부 계약 위반이라 영어로
        # 직접 고정(언어 배선 불필요, write_declaration 과 같은 처리).
        raise ValueError(f"TTL {int(ttl_seconds)}s exceeds the cap {MAX_TTL_SECONDS}s (24h) — "
                         "issue a shorter grant or re-issue after it expires")
    t = time.time() if now is None else now
    rec = {"event": "grant", "grant_id": uuid.uuid4().hex[:12], "ts": _iso(t), "epoch": int(t),
           "expires_epoch": int(t) + int(ttl_seconds), "expires_at": _iso(t + ttl_seconds),
           "ttl_seconds": int(ttl_seconds), "gate": gate, "reason": reason,
           "user": user or os.environ.get("USER") or "unknown"}
    _append(audit_path(root), rec)    # 추적용(커밋)
    _append(grants_path(root, create=True), rec)   # 집행용(로컬, 발급 시에만 정체성 마커 생성)
    return rec


def revoke(root, grant_id, reason=None, user=None, now=None):
    """활성 grant 를 만료 전에 회수한다 → revoke 레코드 반환, 대상이 없으면 None.

    append-only 를 유지하며 revoke 이벤트를 감사 로그와 권한 캐시 양쪽에 남긴다 — 이후
    active_grants 가 같은 grant_id 를 제외한다. 미존재/이미 만료/이미 회수된 id 는 무효(None)."""
    t = time.time() if now is None else now
    target = next((g for g in active_grants(root, now=t) if g.get("grant_id") == grant_id), None)
    if target is None:
        return None
    rec = {"event": "revoke", "ts": _iso(t), "epoch": int(t), "grant_id": grant_id,
           "gate": target.get("gate"), "reason": reason or "(no reason)",
           "user": user or os.environ.get("USER") or "unknown"}
    # 집행 캐시를 먼저 쓴다 — 이게 실패하면 권한이 그대로 살아 있으므로 예외로 알려야 한다.
    # (반대로 grant 는 집행 캐시를 마지막에 써서, 실패 시 권한이 안 생기는 안전한 방향이 된다.)
    # 여기서 감사부터 쓰면 "감사엔 회수, 집행엔 활성"인 상태가 생겨 회수가 무력화될 수 있다.
    _append(grants_path(root), rec)   # 집행(실패 시 권한 유지 — 예외 전파로 운영자 인지)
    _append(audit_path(root), rec)    # 추적(집행 성공 후 기록)
    return rec


def active_grants(root, gate=None, now=None):
    """미만료·미회수 grant 레코드. 권한 캐시(로컬)만 읽어 판정하므로 clone 시 남의 권한은 비활성이다.
    gate 지정 시 grant.gate ∈ {gate, 'all'} 만. 최신순."""
    t = time.time() if now is None else now
    records = _read_jsonl(grants_path(root))
    revoked = {r.get("grant_id") for r in records if r.get("event") == "revoke"}
    out = []
    for r in records:
        if r.get("event") != "grant":
            continue
        if r.get("grant_id") in revoked:
            continue
        if r.get("expires_epoch", 0) <= t:
            continue
        if gate is not None and r.get("gate") not in (gate, "all"):
            continue
        out.append(r)
    return sorted(out, key=lambda r: r.get("epoch", 0), reverse=True)


def is_override_active(root, gate, now=None):
    return bool(active_grants(root, gate=gate, now=now))


def _dedupe_scope(session_id, epoch):
    """선언 dedupe 의 상관키. session_id 부재 시 UTC 날짜로 대체(무한 dedupe 방지, 증가량 1/일 상한)."""
    sid = (session_id or "").strip()
    return sid if sid else "date:" + time.strftime("%Y-%m-%d", time.gmtime(epoch))


def record_cycle_stem_declaration(root, gate, stem, session_id, status="", now=None, origin=""):
    """선언된 cycle stem 이 게이트 판정에 쓰인 사실을 기록 → 레코드, 중복이면 None.

    통로가 둘이다 — env(`SAGE_CYCLE_STEM`) 와 `.sage/cycle.json`. 기원은 기록에도 **dedupe 키에도**
    들어간다. 빼면 한 세션에서 두 통로를 쓸 때 먼저 걸린 쪽만 남아, 세션을 넘겨 살아남는 파일 선언이
    일회성 env 선언으로 기록된다 — 구분하려고 넣은 필드가 정확히 뒤집힌다.

    선언 자체는 막지 않는다 — 장수 브랜치에서는 브랜치 leaf 추론이 영영 맞지 않으므로 이게 정상 경로다.
    문제는 흔적이었다: 이미 완결된 사이클의 stem 을 지목하면 phase 문서와 리뷰 증거가 모두 갖춰진
    상태로 판정되어 게이트 전체가 통과하는데, 그 통과가 아무 곳에도 남지 않았다. grant 처럼 권한을
    주는 행위가 아니라 사후 추적이므로 감사 로그에만 남긴다(권한 캐시는 건드리지 않는다).

    게이트는 편집마다 발동하므로 (gate, stem, session) 단위로 dedupe 한다 — 커밋되는 감사 로그가
    같은 사실로 부풀지 않으면서 "이 세션이 이 stem 을 선언했다" 는 사실은 남는다. session_id 가 없는
    입력은 상관키가 없어 날짜로 대체한다: 그대로 빈 문자열을 키로 쓰면 첫 레코드가 이후 모든 세션의
    선언을 영구히 dedupe 해서, 기록했다고 믿는 채로 실제로는 기록되지 않는 상태가 된다.
    """
    t = time.time() if now is None else now
    key = (gate, stem, origin or "", _dedupe_scope(session_id, t))
    for r in read_records(root):
        if r.get("event") != "cycle_stem_declared":
            continue
        if (r.get("gate"), r.get("cycle_stem"), r.get("origin") or "",
                _dedupe_scope(r.get("session_id"), r.get("epoch") or 0)) == key:
            return None
    rec = {"event": "cycle_stem_declared", "ts": _iso(t), "epoch": int(t), "gate": gate,
           "cycle_stem": stem, "origin": origin or "", "session_id": session_id or "",
           "status": status, "user": os.environ.get("USER") or "unknown"}
    _append(audit_path(root), rec)
    return rec


def record_bypass(root, gate, files, message_key, grant_rec, user=None, now=None):
    """grant 가 실제로 BLOCK 을 통과시킨 사실을 감사 로그에 기록 — 무엇을(message_key) 어느 파일에
    적용했는지 추적. 권한이 아니라 사후 추적이므로 감사 로그에만 남긴다.

    `user` 는 grant 를 **소비한** 주체다. 발급자(`grant.user`)와 별도로 남긴다 — 둘을 같다고 보면
    발급자와 사용자가 갈리는 상황에서 감사가 엉뚱한 사람을 우회자로 지목한다."""
    t = time.time() if now is None else now
    _append(audit_path(root), {"event": "bypass", "ts": _iso(t), "epoch": int(t), "gate": gate,
                               "message_key": message_key, "files": files or [],
                               "grant_id": (grant_rec or {}).get("grant_id"),
                               "grant_ts": (grant_rec or {}).get("ts"),
                               "grant_user": (grant_rec or {}).get("user"),
                               "user": user or os.environ.get("USER") or "unknown",
                               "reason": (grant_rec or {}).get("reason")})
