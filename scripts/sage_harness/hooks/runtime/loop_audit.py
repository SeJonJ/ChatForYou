"""loop_audit — Phase 05 적대적 review-rework 루프(Loop A)의 라운드별 감사 추적.

sage-review 스킬이 호스트(claude/codex)에서 루프를 돌릴 때, 각 라운드(찾기→반박→분류→수정)와
종료를 append-only JSONL 로 남겨 "몇 라운드 돌았고, 무엇을 찾고 무엇이 반증으로 걸러졌고, 어떻게
수렴/차단됐는지"를 사후 추적·재현할 수 있게 한다. override_audit 와 같은 패턴 — `.sage/loop_audit.jsonl`
은 커밋 대상이라 동료·CI·리뷰어가 clone 후에도 루프 이력을 본다.

엔진 모듈(도메인값 0): 횟수·집계·종료 이유는 호출자(스킬/게이트)가 주입하고, 경로/시간/레코드
스키마·run별 strict hash-chain·원자 append만 여기서 결정한다. 라이브러리는 어휘에 대해서만 permissive —
어휘(CLOSE_REASONS/RESULTS) 강제는 호출 CLI/스킬 레이어가 담당한다.
"""
import hashlib
import json
import os
import stat
import time
import uuid
from contextlib import contextmanager

AUDIT_REL = os.path.join(".sage", "loop_audit.jsonl")   # 커밋되는 루프 감사 이력
CHAIN_VERSION = 1
GENESIS = "GENESIS"
_CHAIN_FIELDS = ("chain_version", "prev_hash", "record_hash")

# 종료 어휘(설계 §3) — 호출자가 close 에 넘기는 표준값. 라이브러리는 강제 아닌 참조용 상수로 노출.
CLOSE_RESULTS = ("APPROVED", "BLOCKED")
EARLY_CLOSE_REASON = "USER_AUTHORIZED_EARLY"
CLOSE_REASONS = ("CONVERGED", "DRY", "BUDGET_ITER", "BUDGET_TOK", "BLOCKED_ARCH",
                 EARLY_CLOSE_REASON)
SEVERITIES = ("P0", "P1", "P2", "P3")
# 조기 종료로 닫힌 run 은 일반 승인과 같은 토큰(APPROVED)을 쓰되 보증 수준이 다르다는 것을
# 이 값으로 드러낸다. 값이 없으면 두 승인이 구분되지 않는다.
REVIEW_ASSURANCE_REDUCED = "REDUCED_BY_USER_AUTHORIZATION"
# 상한이 설정되지 않은 상태를 나타내는 명시 토큰. 예전에는 `-1` 을 썼는데, 그건 "상한 없음" 이
# 아니라 "라운드 -1 회" 로 읽힌다 — 대시보드에 `2/-1 rounds` 로 나갔다. 레코드 필드는 None 을
# 받을 수 없으므로(조기 종료 계약이 누락을 거부한다) 값 자체가 뜻을 말해야 한다.
UNBOUNDED_ITERATIONS = "unbounded"
_EARLY_CLOSE_FIELDS = ("authorization_reason", "confirmed_by", "completed_rounds",
                       "configured_max_iterations", "survived_by_severity", "actual_risk",
                       "mode")


class AuditWriteError(RuntimeError):
    """Loop audit cannot be extended without losing its integrity contract."""


def audit_path(root):
    return os.path.join(root, AUDIT_REL)


def _iso(epoch):
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(epoch))


def _record_hash(record):
    """Canonical SHA-256 for one record, excluding only its stored self-hash."""
    payload = {key: value for key, value in record.items() if key != "record_hash"}
    canonical = json.dumps(payload, ensure_ascii=False, sort_keys=True,
                           separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _chain_states(records):
    """Return strict per-run chain states: True, False, or None for legacy-only."""
    states = {}
    previous = {}
    started = set()
    for record in records or []:
        if not isinstance(record, dict):
            continue
        run_id = record.get("run_id")
        if not isinstance(run_id, str) or not run_id:
            continue

        has_chain_field = any(field in record for field in _CHAIN_FIELDS)
        if not has_chain_field:
            if run_id in started:
                states[run_id] = False
            else:
                states.setdefault(run_id, None)
            previous[run_id] = record
            continue

        if run_id not in started:
            states[run_id] = True
        started.add(run_id)
        expected_prev = (_record_hash(previous[run_id])
                         if run_id in previous else GENESIS)
        stored_hash = record.get("record_hash")
        valid = (
            type(record.get("chain_version")) is int
            and record.get("chain_version") == CHAIN_VERSION
            and isinstance(record.get("prev_hash"), str)
            and record.get("prev_hash") == expected_prev
            and isinstance(stored_hash, str)
            and len(stored_hash) == 64
            and all(char in "0123456789abcdef" for char in stored_hash)
            and stored_hash == _record_hash(record)
        )
        if not valid:
            states[run_id] = False
        previous[run_id] = record
    return states


def _stamp_record(prior, record):
    stamped = dict(record)
    run_id = stamped.get("run_id")
    same_run = [item for item in prior if item.get("run_id") == run_id]
    stamped["seq"] = len(same_run)
    stamped["chain_version"] = CHAIN_VERSION
    stamped["prev_hash"] = _record_hash(same_run[-1]) if same_run else GENESIS
    stamped["record_hash"] = _record_hash(stamped)
    return stamped


@contextmanager
def _audit_lock(path):
    """OS-owned process lock; process exit releases ownership without stale takeover."""
    lock_path = path + ".lock"
    os.makedirs(os.path.dirname(lock_path), exist_ok=True)
    flags = os.O_CREAT | os.O_RDWR | getattr(os, "O_CLOEXEC", 0)
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    fd = None
    backend = None
    try:
        fd = os.open(lock_path, flags, 0o600)
        if os.name == "nt":
            import msvcrt
            if os.fstat(fd).st_size == 0:
                os.write(fd, b"\0")
            os.lseek(fd, 0, os.SEEK_SET)
            msvcrt.locking(fd, msvcrt.LK_LOCK, 1)
            backend = "msvcrt"
        else:
            import fcntl
            fcntl.flock(fd, fcntl.LOCK_EX)
            backend = "fcntl"
    except (ImportError, OSError) as exc:
        if fd is not None:
            os.close(fd)
        raise AuditWriteError(f"loop audit lock acquisition failed: {type(exc).__name__}: {exc}") from exc
    try:
        yield
    finally:
        try:
            if backend == "msvcrt":
                import msvcrt
                os.lseek(fd, 0, os.SEEK_SET)
                msvcrt.locking(fd, msvcrt.LK_UNLCK, 1)
            elif backend == "fcntl":
                import fcntl
                fcntl.flock(fd, fcntl.LOCK_UN)
        finally:
            if fd is not None:
                os.close(fd)


def _parse_bytes(data):
    try:
        lines = data.decode("utf-8").splitlines()
    except UnicodeDecodeError as exc:
        return [], [f"audit is not valid UTF-8: {exc}"]
    records = []
    issues = []
    for line_no, line in enumerate(lines, 1):
        raw = line.strip()
        if not raw:
            continue
        try:
            record = json.loads(raw)
        except Exception:
            issues.append(f"line {line_no}: malformed JSON")
            continue
        if not isinstance(record, dict):
            issues.append(f"line {line_no}: record must be an object")
            continue
        records.append(record)
    return records, issues


def _read_fd(fd):
    os.lseek(fd, 0, os.SEEK_SET)
    chunks = []
    while True:
        chunk = os.read(fd, 65536)
        if not chunk:
            break
        chunks.append(chunk)
    return _parse_bytes(b"".join(chunks))


def _open_audit(path, flags):
    flags |= getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_BINARY", 0)
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    fd = os.open(path, flags, 0o600)
    if not stat.S_ISREG(os.fstat(fd).st_mode):
        os.close(fd)
        raise OSError("loop audit must be a regular file")
    return fd


# 조회 전용 상한. 감사는 append-only 라 무한히 자랄 수 있고, 조회 명령이 그 크기에
# 비례해 느려지면 안 된다. 넘치면 잘라 읽어 절반만 판정하지 말고 oversized 로 올린다 —
# 잘린 꼬리에 terminal 이벤트가 있으면 끝난 run 을 active 로 볼 수 있기 때문이다.
READ_ONLY_MAX_BYTES = 4 * 1024 * 1024


def _read_status_unlocked(path, max_bytes=READ_ONLY_MAX_BYTES):
    """(status, records, issues). 락을 잡지 않고 아무것도 만들지 않는 조회 전용 경로.

    `_read_status` 와 나뉘어 있는 이유는 순수하게 부작용 때문이다. 락 경로는 `.sage/` 와
    `.lock` 파일을 **만든다** — 쓰기다. 읽기만 하겠다고 약속한 명령이 그걸 부르면 약속을
    깨고, 진행 중인 전이와 락 경쟁까지 한다.

    락이 없으므로 동시 append 중간을 볼 수 있다. 그 상태를 정상으로 위장하지 않으려고
    status 를 함께 돌려준다 — 호출부는 `damaged` 를 안전한 기본값으로 접으면 안 된다.
    """
    parent = os.path.dirname(path)
    if not os.path.isdir(parent):
        return "absent", [], []
    if not os.path.lexists(path):
        return "absent", [], []
    try:
        fd = _open_audit(path, os.O_RDONLY)
    except OSError as exc:
        # symlink(O_NOFOLLOW)·비정규 파일·권한 — 전부 "읽었는데 비어 있다" 가 아니다.
        return "damaged", [], [f"audit read refused: {type(exc).__name__}: {exc}"]
    try:
        size = os.fstat(fd).st_size
        if size > max_bytes:
            return "damaged", [], [f"audit exceeds read-only limit: {size} > {max_bytes} bytes"]
        os.lseek(fd, 0, os.SEEK_SET)
        chunks = []
        remaining = max_bytes
        while remaining > 0:
            chunk = os.read(fd, min(65536, remaining))
            if not chunk:
                break
            chunks.append(chunk)
            remaining -= len(chunk)
    except OSError as exc:
        return "damaged", [], [f"audit read failed: {type(exc).__name__}: {exc}"]
    finally:
        os.close(fd)
    records, issues = _parse_bytes(b"".join(chunks))
    return ("damaged" if issues else "valid"), records, issues


def _read_status(path):
    parent = os.path.dirname(path)
    if not os.path.isdir(parent):
        return [], []
    try:
        with _audit_lock(path):
            if not os.path.lexists(path):
                return [], []
            fd = _open_audit(path, os.O_RDONLY)
            try:
                return _read_fd(fd)
            finally:
                os.close(fd)
    except (AuditWriteError, OSError) as exc:
        return [], [f"audit read failed: {type(exc).__name__}: {exc}"]


def _write_once(fd, payload):
    return os.write(fd, payload)


def _needs_line_separator(fd, size):
    if size <= 0:
        return False
    os.lseek(fd, -1, os.SEEK_END)
    return os.read(fd, 1) != b"\n"


def _append(path, record, validator=None):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with _audit_lock(path):
        fd = None
        original_size = 0
        attempted_write = False
        try:
            fd = _open_audit(path, os.O_RDWR | os.O_CREAT | os.O_APPEND)
            original_size = os.fstat(fd).st_size
            needs_separator = _needs_line_separator(fd, original_size)
            prior, issues = _read_fd(fd)
            if issues:
                raise AuditWriteError("loop audit contains invalid lines: " + "; ".join(issues[:3]))
            run_id = record.get("run_id")
            if _chain_states(prior).get(run_id) is False:
                raise AuditWriteError(f"run {run_id!r} strict hash-chain is invalid")
            if validator is not None:
                validator(prior, record)

            stamped = _stamp_record(prior, record)
            if _chain_states(prior + [stamped]).get(run_id) is False:
                raise AuditWriteError(f"run {run_id!r} strict hash-chain stamping failed")
            separator = b"\n" if needs_separator else b""
            encoded = separator + (json.dumps(stamped, ensure_ascii=False) + "\n").encode("utf-8")
            attempted_write = True
            written = _write_once(fd, encoded)
            if written != len(encoded):
                raise AuditWriteError(f"short append: {written}/{len(encoded)} bytes")
            os.fsync(fd)
            return stamped
        except BaseException as exc:
            rollback_error = None
            if fd is not None and attempted_write:
                try:
                    os.ftruncate(fd, original_size)
                    os.fsync(fd)
                except OSError as rollback_exc:
                    rollback_error = rollback_exc
            if isinstance(exc, (KeyboardInterrupt, SystemExit)):
                raise
            if rollback_error is not None:
                raise AuditWriteError(
                    f"loop audit append failed and rollback failed: {rollback_error}") from exc
            if isinstance(exc, AuditWriteError):
                raise
            raise AuditWriteError(f"loop audit append failed: {type(exc).__name__}: {exc}") from exc
        finally:
            if fd is not None:
                os.close(fd)


def _read_jsonl(path):
    """JSONL 레코드(dict) 목록. 부재 → []. 견고성(codex S2): 파싱 실패 줄뿐 아니라
    valid-but-non-dict(`42`·`[]`·`"junk"`)도 skip — 소비자(runs/rounds_of/retro/시각화)가 매 레코드에
    .get() 하므로, 비-dict 가 섞이면 AttributeError 크래시. 레코드는 항상 dict 라는 계약을 리더에서 강제."""
    return _read_status(path)[0]


def read_records(root):
    return _read_jsonl(audit_path(root))


def new_run_id():
    return "rl-" + uuid.uuid4().hex[:12]


def open_loop(root, risk, cfg=None, run_id=None, now=None, reviewer_requested=None,
              cycle_stem=None, lenses=None):
    """루프 시작 기록 → run_id 반환. risk ∈ {L2,L3}(호출자 검증). cfg=적용 설정 스냅샷(profile.pdca.review_loop).
    reviewer_requested=profile 이 의도한 리뷰어 모드(예: cross_model/same_runtime) — 실제값은 close 에 기록,
    불일치(degraded)는 audit_summary 가 파생(7차 배치3)."""
    t = time.time() if now is None else now
    rid = run_id or new_run_id()
    rec = {"event": "loop_open", "run_id": rid, "ts": _iso(t), "epoch": int(t),
           "risk": risk, "cfg": cfg or {}}
    if reviewer_requested is not None:
        rec["reviewer_requested"] = reviewer_requested
    if cycle_stem is not None:
        rec["cycle_stem"] = cycle_stem
    if lenses is not None:
        rec["lenses"] = list(lenses)
    _append(audit_path(root), rec)
    return rid


def _severity_total(receipt):
    """검산용 합계. 손상된 값은 여기서 예외로 만들지 않고 `severity_receipt_issues` 가 진단한다."""
    if not isinstance(receipt, dict):
        return 0
    return sum(value for key in SEVERITIES
               for value in [receipt.get(key)] if type(value) is int and value >= 0)


def severity_receipt_issues(receipt, survived):
    """심각도별 잔여 영수증 검산. 합계가 `survived` 와 정확히 같아야 한다.

    합계를 강제하지 않으면 "P0=0" 만 적어 넣고 실제 차단 finding 을 숨긴 채 조기 종료를 통과시킬 수
    있다. bool 은 int 의 하위형이라 따로 막는다 — `True` 가 1 로 세어지면 개수가 조용히 틀어진다.
    """
    if not isinstance(receipt, dict):
        return ["survived_by_severity must be a mapping"]
    issues = []
    unknown = sorted(set(receipt) - set(SEVERITIES), key=str)
    if unknown:
        issues.append(f"unknown severities: {unknown}")
    missing = sorted(set(SEVERITIES) - set(receipt), key=str)
    if missing:
        issues.append(f"missing severities: {missing}")
    total = 0
    for key in SEVERITIES:
        value = receipt.get(key)
        if type(value) is not int or value < 0:
            issues.append(f"{key} must be a non-negative integer")
            continue
        total += value
    if not issues and total != int(survived):
        issues.append(f"severity total {total} does not equal survived {int(survived)}")
    return issues


def record_round(root, run_id, iteration, found, survived, accepted, arch=0, tokens=0, now=None,
                 lens_receipts=None, survived_by_severity=None):
    """라운드 1건 기록.
    found=FIND 발견수, survived=REFUTE 생존수, accepted=REWORK 채택수, arch=아키텍처 에스컬레이션수, tokens=누적 토큰.
    seq=append 순 단조 번호(라이브러리 stamp, 수기 위조·순서조작 탐지용 — 7차 배치3)."""
    t = time.time() if now is None else now
    record = {
        "event": "round", "run_id": run_id, "ts": _iso(t), "epoch": int(t),
        "iteration": int(iteration), "found": int(found), "survived": int(survived),
        "accepted": int(accepted), "arch": int(arch), "tokens": int(tokens),
    }
    if lens_receipts is not None:
        record["lens_receipts"] = list(lens_receipts)
    if survived_by_severity is not None:
        issues = severity_receipt_issues(survived_by_severity, survived)
        if issues:
            raise AuditWriteError("survived_by_severity invalid: " + "; ".join(issues))
        record["survived_by_severity"] = {key: int(survived_by_severity[key])
                                          for key in SEVERITIES}

    # close 와 같은 이유로 lock 안에서 다시 본다. CLI 는 orphan(open 없음)과 종료된 run 을 이미
    # 거부하지만 그 검사는 lock 밖이라, round 와 close 가 경합하면 둘 다 통과해 종료 뒤에 라운드가
    # 붙는다. 그 줄은 해시 체인의 일부라 지울 수 없고, `integrity_issues` 가 영구히 붉어진다 —
    # 우회가 아니라 복구 불가능한 손상이다. 판정은 CLI 와 **같은 두 가지**만 옮긴다: iteration
    # 단조성 같은 새 규칙을 여기서 켜면 지금 통과하던 기록이 소급 거부된다.
    # (주석인 이유: 중첩 함수의 한국어 docstring 은 판정 문자열 오라클에 판정으로 잡힌다.)
    def _open_and_not_closed(prior, _record):
        mine = [item for item in prior if item.get("run_id") == run_id]
        if not any(item.get("event") == "loop_open" for item in mine):
            raise AuditWriteError(f"run {run_id!r} was never opened")
        if any(item.get("event") == "loop_close" for item in mine):
            raise AuditWriteError(f"run {run_id!r} is already closed")

    return _append(audit_path(root), record, validator=_open_and_not_closed)


def close_loop(root, run_id, result, reason, iterations, now=None, reviewer_actual=None,
               phase00_hash=None, authorization=None):
    """루프 종료 기록. result ∈ CLOSE_RESULTS, reason ∈ CLOSE_REASONS(호출 레이어가 강제).
    reviewer_actual=실제 수행된 리뷰어 모드(예: cross_model/same_runtime) — open 의 reviewer_requested 와
    비교해 audit_summary 가 degraded 를 파생(7차 배치3: cross-model 폴백 침묵 차단)."""
    t = time.time() if now is None else now
    rec = {"event": "loop_close", "run_id": run_id, "ts": _iso(t), "epoch": int(t),
           "result": result, "reason": reason, "iterations": int(iterations)}
    if reviewer_actual is not None:
        rec["reviewer_actual"] = reviewer_actual
    if phase00_hash is not None:
        rec["phase00_hash"] = phase00_hash
    # 조기 종료와 일반 종료는 같은 terminal 레코드를 쓰되 서로의 필드를 가질 수 없다. 섞이면
    # 어느 쪽 계약으로 닫혔는지가 사후에 판별되지 않는다.
    if reason == EARLY_CLOSE_REASON:
        if not isinstance(authorization, dict):
            raise AuditWriteError(f"{EARLY_CLOSE_REASON} close requires an authorization record")
        missing = [field for field in _EARLY_CLOSE_FIELDS if authorization.get(field) is None]
        if missing:
            raise AuditWriteError(f"authorization record is missing {missing}")
        # 합계를 인자로 만들면서 영수증을 건드리면 검산기의 가드에 닿기 전에 터진다. 합계 계산은
        # 손상을 견디고, 손상 자체의 진단은 검산기가 만든다. 여기서 넘기는 합계는 영수증에서 파생한
        # 값이라 총계 대조는 항등식이다 — 라운드 기록과의 실제 대조는 CLI 의 조기 종료 검사가 한다.
        receipt = authorization["survived_by_severity"]
        receipt_issues = severity_receipt_issues(receipt, _severity_total(receipt))
        if receipt_issues:
            raise AuditWriteError("authorization severity receipt invalid: "
                                  + "; ".join(receipt_issues))
        rec.update({key: authorization[key] for key in _EARLY_CLOSE_FIELDS})
        rec["lens_receipts"] = list(authorization.get("lens_receipts") or [])
        if authorization.get("fast_run_id") is not None:
            rec["fast_run_id"] = authorization["fast_run_id"]
        if authorization.get("done_criteria_revision") is not None:
            rec["done_criteria_revision"] = authorization["done_criteria_revision"]
        rec["attestation"] = "self_asserted_local"
        rec["review_assurance"] = REVIEW_ASSURANCE_REDUCED
    elif authorization is not None:
        raise AuditWriteError("authorization record is only valid for "
                              f"{EARLY_CLOSE_REASON} closes")

    # lock 안에서 다시 확인하는 것은 둘이다 — terminal 단일성과, 조기 종료 판정이 아직 유효한가.
    # 호출부의 선검사는 lock 밖이라 두 close 가 경합하면 둘 다 통과할 수 있다. 사후에는
    # `audit_summary` 의 `clean`(closes<=1)이 잡아 게이트가 막지만, 그건 이미 기록이 두 줄 남은
    # 뒤다.
    #
    # 조기 종료는 반대 순서가 더 나쁘다. close 가 1라운드 기준으로 판정을 끝낸 사이 2라운드가
    # 먼저 append 되면, 최신 P0 finding 을 무시한 승인이 남는데 그 감사는 무결성·체인·seq 가 전부
    # 정상이라 어느 층도 잡지 못한다. `record_round` 의 in-lock 검증은 close→round 한 방향만
    # 막았다. 여기서 반대 방향을 막는다.
    #
    # 옮기는 것은 **CLI 가 이미 강제하던 네 판정**뿐이고, 넷 다 CLI 가 같은 한 번의 읽기에서
    # 파생시킨 값이다(`completed_rounds`·영수증·lens 는 마지막 라운드에서, `iterations` 는 라운드
    # 수에서). 그래서 이 검증은 새 규칙이 아니라 같은 판정을 한 순간 뒤에 다시 보는 것이다.
    # 일반 close 에는 걸지 않는다 — `iterations` 가 라운드 수와 다른 정상 호출이 이미 있고,
    # 수렴 판정은 프로젝트 mode 에 따라 advisory 로 통과하는 것이 계약이다.
    #
    # 나머지 검증(Done Criteria·profile)까지 lock 안으로 옮기지는 않는다 — CLI 가 profile 과
    # phase 문서를 lock 을 쥔 채 읽는다는 뜻이라 대기 시간이 파일시스템에 묶인다.
    # (설명을 docstring 이 아니라 주석으로 두는 이유: 중첩 함수의 한국어 docstring 은 runtime
    #  판정 문자열 오라클에 "한국어 문장을 돌려주는 판정" 으로 잡힌다.)
    def _terminal_once(prior, _record):
        mine = [item for item in prior if item.get("run_id") == run_id]
        if any(item.get("event") == "loop_close" for item in mine):
            raise AuditWriteError(f"run {run_id!r} is already closed")
        if reason != EARLY_CLOSE_REASON:
            return
        rounds = [item for item in mine if item.get("event") == "round"]
        if len(rounds) != int(iterations):
            raise AuditWriteError(
                f"run {run_id!r} now has {len(rounds)} round(s), not the {int(iterations)} this "
                "close was authorized against")
        if len(rounds) != rec.get("completed_rounds"):
            raise AuditWriteError(
                f"run {run_id!r} now has {len(rounds)} round(s), not the "
                f"{rec.get('completed_rounds')} recorded in the authorization")
        last = rounds[-1] if rounds else {}
        if last.get("survived_by_severity") != rec.get("survived_by_severity"):
            raise AuditWriteError(
                f"run {run_id!r} last round severity receipt changed after the authorization: "
                f"{last.get('survived_by_severity')!r} != {rec.get('survived_by_severity')!r}")
        if list(last.get("lens_receipts") or []) != list(rec.get("lens_receipts") or []):
            raise AuditWriteError(
                f"run {run_id!r} last round lens receipts changed after the authorization")

    return _append(audit_path(root), rec, validator=_terminal_once)


def runs(root):
    """감사 로그의 run_id 목록(loop_open 기준, 시간순)."""
    return [r.get("run_id") for r in read_records(root) if r.get("event") == "loop_open"]


def rounds_of(root, run_id):
    """특정 run_id 의 round 레코드(append 순)."""
    return [r for r in read_records(root)
            if r.get("event") == "round" and r.get("run_id") == run_id]


def close_of(root, run_id):
    """특정 run_id 의 loop_close 레코드(없으면 None — 미종료/진행중)."""
    closes = [r for r in read_records(root)
              if r.get("event") == "loop_close" and r.get("run_id") == run_id]
    return closes[-1] if closes else None


def _seq_ok(seq_list):
    """run 의 레코드 seq 값(append 순) sanity 검산 → True/False/None.
    None = 모두 seq 부재(레거시/구버전 기록) → 검사 skip(하위호환). 일부라도 seq 가 있으면
    정확히 [0,1,...,n-1] 연속이어야 True. 누락(수기 append)·중복·순서조작(재정렬)·레거시+신규 혼합은 False.
    단독 위변조 방지가 아닌 구조 sanity 검사이며, strict hash-chain 검증과 함께 소비한다."""
    if not seq_list or all(s is None for s in seq_list):
        return None
    return seq_list == list(range(len(seq_list)))


def audit_summary(root):
    """게이트 주입용 결정론 요약(2층 불변식: adapter 가 fs 읽고 core 는 이 dict 만 소비).
    {runs: {run_id: {closed, result, clean, seq_ok, chain_ok, reviewer_requested,
    reviewer_actual, degraded}}, has_any_records, file_ok}.
    `clean`(codex 코드 R2-P1): run_id 가 정확히 1회 open + 최대 1회 close 일 때만 True. 재사용/중복 open·
    close 나 고아 close(open 0)는 clean=False → 게이트가 stale/모호 증거로 통과되는 것을 차단.
    `seq_ok`: 라운드 seq 연속성. `chain_ok`: run별 strict hash-chain(True/False, legacy=None).
    `file_ok`: 손상/비-object 줄 없는 원문 파싱 무결성. `degraded`: 의도한 reviewer(open) ≠ 실제
    reviewer(close) → cross-model 폴백 침묵 차단."""
    return summarize_records(*_read_status(audit_path(root)))


def summarize_records(recs, file_issues):
    """레코드 목록 하나를 요약으로 접는다. 파일도 락도 모르는 순수 함수.

    락을 잡는 `audit_summary` 와 잡지 않는 `snapshot` 이 **같은 이 함수**를 쓴다. 접기 로직을
    복사하면 감사 형식의 해석기가 둘이 되고, 갈렸을 때 어느 쪽이 옳은지 판정할 근거가 없어진다.
    `fast_cycle_audit` 이 먼저 같은 모양으로 갈라졌고 여기도 그 모양을 따른다.
    """
    chain_states = _chain_states(recs)
    summary = {}
    seqs = {}   # rid -> [seq, ...] (append 순, 모든 이벤트 포함 — seq 연속성 검산용)
    for r in recs:
        rid = r.get("run_id")
        if not rid:
            continue
        seqs.setdefault(rid, []).append(r.get("seq"))
        ev = r.get("event")
        if ev == "loop_open":
            e = summary.setdefault(rid, _new_summary_entry())
            e["opens"] += 1
            if r.get("reviewer_requested") is not None:
                e["reviewer_requested"] = r.get("reviewer_requested")
        elif ev == "loop_close":
            e = summary.setdefault(rid, _new_summary_entry())
            e["closed"] = True
            e["result"] = r.get("result")
            e["closes"] += 1
            if r.get("reviewer_actual") is not None:
                e["reviewer_actual"] = r.get("reviewer_actual")
            if r.get("phase00_hash") is not None:
                e["phase00_hash"] = r.get("phase00_hash")
            # 조기 종료는 일반 승인과 같은 result 토큰을 쓴다. 게이트가 둘을 구분하려면 종료
            # 사유와 보증 수준이 요약에 실려야 한다 — 없으면 06 이 두 승인을 같게 본다.
            e["close_reason"] = r.get("reason")
            e["review_assurance"] = r.get("review_assurance")
            e["completed_rounds"] = r.get("completed_rounds")
            e["configured_max_iterations"] = r.get("configured_max_iterations")
            e["survived_by_severity"] = r.get("survived_by_severity")
    for rid, e in summary.items():
        e["clean"] = (e["opens"] == 1 and e["closes"] <= 1)
        e["seq_ok"] = _seq_ok(seqs.get(rid) or [])
        e["chain_ok"] = chain_states.get(rid)
        req, act = e["reviewer_requested"], e["reviewer_actual"]
        # degraded(7차 배치3, codex R1b P1 반영): 의도한 reviewer 가 명시됐는데 실제가 *다르거나*
        # close 시점에 *기록조차 안 됨*(act is None)이면 degraded. 후자 = cross-model 요청이 실제
        # 수행을 확인받지 못한 정황(폴백 의심) → 침묵 통과 차단. closed 인 run 에만 적용(진행중 run 은
        # 아직 actual 미확정이 정상). req 미설정(legacy/미사용)이면 False(오탐 없음).
        e["degraded"] = bool(e["closed"] and req is not None and (act is None or req != act))
        del e["opens"]; del e["closes"]
    return {
        "runs": summary,
        "has_any_records": bool(recs),
        "file_ok": not file_issues,
        "file_issues": file_issues,
    }


def _new_summary_entry():
    return {"closed": False, "result": None, "opens": 0, "closes": 0,
            "reviewer_requested": None, "reviewer_actual": None,
            "close_reason": None, "review_assurance": None, "completed_rounds": None,
            "configured_max_iterations": None, "survived_by_severity": None}


def _diagnostic(code, evidence="", **arguments):
    """언어 중립 진단 하나. 이 모듈은 어느 catalog 도 알 수 없다.

    설치본에서 이 runtime 은 엔진(`sage` 패키지) 없이 단독 실행되므로 `sage.diagnostics` 를
    import 할 수 없다. 그래서 진단을 그 모듈이 받아주는 매핑 형태로 올리고, 문장은 CLI 든
    hook 이든 부른 쪽의 catalog 가 만든다. 여기서 완성 문장을 만들면 그 언어를 호출부가
    고를 수 없어 영어 화면에도 한국어가 실린다.

    `evidence` 는 파서가 돌려준 원문이라 번역하지 않는다.
    """
    return {"code": code, "arguments": arguments, "evidence": evidence}


def integrity_issues(root):
    """감사 트레일 구조 무결성 검사 → [언어 중립 진단] (비면 정상). run_id 는 join key 이므로
    무결성이 깨지면 트레일 자체가 malformed입니다. writer는 락 안에서 원문과 target run 체인을
    검증하고, 소비자와 테스트도 같은 불변식을 재검증합니다.

    run_id 계약: 호출자가 open_loop()(또는 new_run_id())로 1회 발급하고 그 id 로만 round/close 한다.
    검출(codex S3/S4 강화): ① loop_open 없는 round/close(orphan) ② loop_open 중복 ③ loop_close 중복
    ④ loop_close 이후의 round/close(종료 후 활동) ⑤ 손상/비-dict 줄(읽기 시 silent drop → 증거 불완전).
    append 순서를 그대로 따라 한 패스로 판정."""
    return integrity_from_records(*_read_status(audit_path(root)))


def integrity_from_records(recs, file_issues):
    """`integrity_issues` 의 판정부. 파일을 모르는 순수 함수."""
    issues = [_diagnostic("loop_audit.malformed_line", evidence=issue) for issue in file_issues]
    opens, closes = {}, {}
    for r in recs:
        if r.get("event") == "loop_open":
            opens[r.get("run_id")] = opens.get(r.get("run_id"), 0) + 1
    for rid, n in opens.items():
        if n > 1:
            issues.append(_diagnostic("loop_audit.duplicate_open", run_id=repr(rid), count=n))
    for r in recs:
        ev, rid = r.get("event"), r.get("run_id")
        if ev in ("round", "loop_close") and rid not in opens:
            issues.append(_diagnostic("loop_audit.orphan_event", event=ev, run_id=repr(rid)))
            continue
        if ev in ("round", "loop_close") and closes.get(rid):
            issues.append(_diagnostic("loop_audit.event_after_close", event=ev, run_id=repr(rid)))
        if ev == "loop_close":
            closes[rid] = closes.get(rid, 0) + 1
            if closes[rid] > 1:
                issues.append(_diagnostic("loop_audit.duplicate_close", run_id=repr(rid),
                                          count=closes[rid]))
    # ⑥ 시퀀스 무결성(7차 배치3): seq 누락/불연속/순서조작 — 수기 JSONL append·재정렬 탐지.
    #    라이브러리가 seq 를 stamp 하므로(open=0, append 순 +1), CLI/lib 우회 기록은 seq 부재/불연속으로 걸린다.
    seqs = {}
    for r in recs:
        rid = r.get("run_id")
        if rid:
            seqs.setdefault(rid, []).append(r.get("seq"))
    for rid, sl in seqs.items():
        if _seq_ok(sl) is False:
            issues.append(_diagnostic("loop_audit.sequence_broken", run_id=repr(rid),
                                      sequence=sl))
    for rid, state in _chain_states(recs).items():
        if state is False:
            issues.append(_diagnostic("loop_audit.hash_chain_mismatch", run_id=repr(rid)))
    return issues


def snapshot(root):
    """락도 쓰기도 없는 조회용 요약. `audit_summary` 와 같은 접기 함수를 쓴다.

    `status` 키는 absent/valid/damaged 셋이고, 셋을 하나로 접지 않는 것이 이 API 의 요점이다.
    락이 없으니 append 중간을 볼 수 있는데 그 절반짜리 파일을 "기록이 없다" 로 읽으면 진행 중인
    run 이 사라지고, 반대로 부재를 손상으로 올리면 감사를 한 번도 쓰지 않은 정상 프로젝트가
    붉어진다. 어느 쪽도 호출부가 접어서는 안 되므로 사실 그대로 함께 돌려준다.
    """
    state, records, issues = _read_status_unlocked(audit_path(root))
    summary = summarize_records(records, issues)
    summary["status"] = state
    return summary
