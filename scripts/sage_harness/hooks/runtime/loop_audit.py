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
# 사이클 라운드 상한에서 사용자가 멈추기로 한 종료. `BUDGET_ITER` 로 닫으면 run 반복 상한에
# 닿지 않은 run 이라 검산이 모순으로 잡는다 — 멈춘 이유가 다르므로 어휘도 따로 둔다.
CYCLE_CAP_REASON = "CYCLE_CAP"
# 차단 지적(P0·P1·판단 안 된 크리티컬 P2)이 0 이고 비차단 잔여만 남은 승인. `converge_on: blocking`
# 을 켠 run 에서만 나온다. 잔여를 안고 닫으므로 일반 승인과 구분되게 보증 저하로 기록한다.
CONVERGED_RESIDUAL_REASON = "CONVERGED_RESIDUAL"
CLOSE_REASONS = ("CONVERGED", "DRY", "BUDGET_ITER", "BUDGET_TOK", "BLOCKED_ARCH",
                 CYCLE_CAP_REASON, EARLY_CLOSE_REASON, CONVERGED_RESIDUAL_REASON)
# 사람이 내린 루프 결정. run 의 hash-chain 에 같이 묶여 round·close 와 같은 무결성 검사를 받는다.
DECISION_EVENT = "decision"
DECISION_CYCLE_CAP = "cycle_cap"
# 크리티컬 P2 하나에 대한 개발자 결정(수정·수용). 지적 내용(id·주장 해시)에 묶인다.
DECISION_CRITICAL_P2 = "critical_p2"
CRITICAL_P2_REASON = "CRITICAL_P2"
CRITICAL_CHOICES = ("fix", "accept")
# 수렴 기준. `all` 은 생존 0 이어야 수렴(지금 동작), `blocking` 은 차단 지적 0 이면 잔여를 안고 수렴.
CONVERGE_MODES = ("all", "blocking")
# P2 를 크리티컬로 올리는 분류. profile `critical_p2` 가 없으면 이 여섯 개다.
DEFAULT_CRITICAL_P2 = ("unauthenticated_crash", "exposure_or_traversal", "wrong_content_served",
                       "deploy_breakage", "security_setting_regression", "silent_data_corruption")
DEFAULT_SEVERITY_BLOCK = ("P0", "P1")
# 사이클 전체 라운드 상한의 기본값. 키가 없는 profile 에도 적용한다 — 상한은 멈춤이 아니라
# 사람에게 묻는 지점이라, 키가 없다고 무제한으로 두면 run 을 새로 열 때마다 카운터가 0 으로
# 돌아가던 지금 상태가 그대로 남는다. 값을 바꿀 곳은 여기 한 곳이다.
DEFAULT_MAX_CYCLE_ROUNDS = {"L2": 3, "L3": 5}
SEVERITIES = ("P0", "P1", "P2", "P3")
# 조기 종료로 닫힌 run 은 일반 승인과 같은 토큰(APPROVED)을 쓰되 보증 수준이 다르다는 것을
# 이 값으로 드러낸다. 값이 없으면 두 승인이 구분되지 않는다.
REVIEW_ASSURANCE_REDUCED = "REDUCED_BY_USER_AUTHORIZATION"
# 정책(`converge_on: blocking`)으로 잔여를 안고 닫은 승인. 사용자가 그 자리에서 승인한 조기 종료와
# 값으로 구분한다. 06←05 게이트와 CI 가 사유별로 대조한다.
REVIEW_ASSURANCE_POLICY = "REDUCED_BY_POLICY"
REDUCED_ASSURANCE_BY_REASON = {EARLY_CLOSE_REASON: REVIEW_ASSURANCE_REDUCED,
                               CONVERGED_RESIDUAL_REASON: REVIEW_ASSURANCE_POLICY}
# 상한이 설정되지 않은 상태를 나타내는 명시 토큰. 예전에는 `-1` 을 썼는데, 그건 "상한 없음" 이
# 아니라 "라운드 -1 회" 로 읽힌다 — 대시보드에 `2/-1 rounds` 로 나갔다. 레코드 필드는 None 을
# 받을 수 없으므로(조기 종료 계약이 누락을 거부한다) 값 자체가 뜻을 말해야 한다.
UNBOUNDED_ITERATIONS = "unbounded"
_EARLY_CLOSE_FIELDS = ("authorization_reason", "confirmed_by", "completed_rounds",
                       "configured_max_iterations", "survived_by_severity", "actual_risk",
                       "mode")
_RESIDUAL_CLOSE_FIELDS = ("completed_rounds", "configured_max_iterations", "survived_by_severity",
                          "actual_risk", "mode", "residual", "accepted_decisions")


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
              cycle_stem=None, lenses=None, fast_minimum_rounds=None):
    """루프 시작 기록 → run_id 반환. risk ∈ {L2,L3}(호출자 검증). cfg=적용 설정 스냅샷(profile.pdca.review_loop).
    reviewer_requested=profile 이 의도한 리뷰어 모드(예: cross_model/same_runtime) — 실제값은 close 에 기록,
    불일치(degraded)는 audit_summary 가 파생(7차 배치3).
    fast_minimum_rounds=같은 사이클 Fast run 의 최소 라운드. 그보다 먼저 수렴하면 `next` 가 계속을 권한다."""
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
    if fast_minimum_rounds is not None:
        rec["fast_minimum_rounds"] = int(fast_minimum_rounds)
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


def _tier_cap(cfg, risk):
    """cfg 스냅샷의 사이클 상한. 없거나 형식이 틀리면 기본값이다.

    형식이 틀린 값을 무제한으로 읽으면 상한이 조용히 꺼진다. 기본값이 더 엄격하므로 그쪽으로 접는다.
    """
    block = (cfg or {}).get("max_cycle_rounds") if isinstance(cfg, dict) else None
    value = block.get(risk) if isinstance(block, dict) else None
    if type(value) is int and value >= 1:
        return value
    if risk in DEFAULT_MAX_CYCLE_ROUNDS:
        return DEFAULT_MAX_CYCLE_ROUNDS[risk]
    return min(DEFAULT_MAX_CYCLE_ROUNDS.values())


def cycle_state(records, stem, cfg, risk):
    """사이클 전체 라운드 집계와 상한 판정. 레코드 목록만 받는 순수 함수다.

    `next`(권고), `round`·`decide` 의 잠금 안 재검사(거부), SessionStart 재진입 문맥(표시)이 모두
    이 함수를 쓴다. 셋이 각자 세면 갈린다. `cfg`·`risk` 는 사이클의 가장 최근 run 의 open 레코드에서
    온다(`run_cycle_state`). `tokens` 는 run 별 예산 총량(`budget_tokens_of`)의 합이다 — 표시용이고,
    예산 판정은 지금처럼 run 단위다.
    """
    stems = {}
    unbound = []
    for record in records or []:
        if not isinstance(record, dict) or record.get("event") != "loop_open":
            continue
        rid = record.get("run_id")
        bound = record.get("cycle_stem")
        if isinstance(bound, str) and bound:
            stems[rid] = bound
        elif isinstance(rid, str):
            unbound.append(rid)
    runs_in = [rid for rid, value in stems.items() if value == stem]
    members = set(runs_in)
    rounds = 0
    extended = 0
    for record in records or []:
        if not isinstance(record, dict) or record.get("run_id") not in members:
            continue
        if record.get("event") == "round":
            rounds += 1
        elif (record.get("event") == DECISION_EVENT
              and record.get("kind") == DECISION_CYCLE_CAP
              and record.get("choice") == "continue"):
            extend = record.get("extend")
            if type(extend) is int and extend > 0:
                extended += extend
    base = _tier_cap(cfg, risk)
    cap = base + extended
    tokens = sum(budget_tokens_of([record for record in records or []
                                   if isinstance(record, dict) and record.get("event") == "round"
                                   and record.get("run_id") == rid])
                 for rid in runs_in)
    return {"stem": stem, "runs": runs_in, "rounds": rounds, "base_cap": base,
            "extended": extended, "cap": cap, "reached": rounds >= cap,
            "tokens": tokens, "unbound_runs": unbound}


def run_cycle_state(records, run_id):
    """run 이 속한 사이클의 상태. 사이클에 묶이지 않은 run 이면 None 이다.

    업그레이드 전에 stem 없이 열린 run 에 상한을 소급 적용하지 않는다 — 그 run 은 open 시점의
    계약으로 끝까지 간다.
    """
    opened = next((record for record in records or []
                   if isinstance(record, dict) and record.get("event") == "loop_open"
                   and record.get("run_id") == run_id), None)
    if opened is None:
        return None
    stem = opened.get("cycle_stem")
    if not isinstance(stem, str) or not stem:
        return None
    # 상한은 사이클 하나에 하나다. 조회한 run 의 cfg 를 쓰면 같은 사이클이 run 마다 다른 상한을
    # 갖고, 상한이 큰 옛 run 으로 라운드를 더 붙일 수 있다. 가장 최근 run 의 cfg 가 기준이다.
    latest = opened
    for record in records or []:
        if (isinstance(record, dict) and record.get("event") == "loop_open"
                and record.get("cycle_stem") == stem):
            latest = record
    return cycle_state(records, stem, latest.get("cfg") or {}, latest.get("risk"))


def _is_count(value):
    return isinstance(value, int) and not isinstance(value, bool) and value >= 0


def budget_tokens_of(rounds):
    """예산 판정에 쓰는 총량 = 호스트 누적(tokens 의 최댓값) + 라운드별 peer 실측의 합.

    peer 는 캐시 밖 입력 + 출력만 센다. 보고 입력의 대부분은 같은 맥락을 턴마다 다시 실은 캐시
    읽기라, 그걸 더하면 예산 기본값이 가정한 "작업량"과 단위가 달라진다. 캐시 읽기는 라운드 기록에
    따로 남아 있어 한도 해석이 달라져도 다시 계산할 수 있다. 측정 안 된 라운드("unknown")는 0 이
    아니라 모르는 값이라, `next` 가 따로 경고한다.
    """
    host = max((int(r.get("tokens", 0) or 0) for r in rounds), default=0)  # tokens=누적 → max=총량
    peer = 0
    for r in rounds:
        usage = r.get("peer_usage")
        if isinstance(usage, dict):
            # 쓰기 때 검증하지만 감사 파일은 손으로 고쳐질 수 있다 — 숫자가 아니면 그 라운드는 모르는 값.
            for key in ("new_input", "output"):
                if _is_count(usage.get(key)):
                    peer += usage.get(key)
    return host + peer


def unmeasured_peer_rounds(rounds):
    """peer 사용량을 모르는 라운드 수 — `unknown` 이거나 기록이 숫자가 아닌 경우."""
    count = 0
    for r in rounds:
        usage = r.get("peer_usage")
        if usage == "unknown":
            count += 1
        elif isinstance(usage, dict) and not all(
                isinstance(usage.get(k), int) and not isinstance(usage.get(k), bool)
                for k in ("new_input", "output")):
            count += 1
    return count


def _tier_int(cfg, section, risk):
    block = cfg.get(section) if isinstance(cfg, dict) and isinstance(cfg.get(section), dict) else {}
    value = block.get(risk)
    return value if isinstance(value, int) and not isinstance(value, bool) else None


def _open_record(records, run_id):
    return next((record for record in records or []
                 if isinstance(record, dict) and record.get("event") == "loop_open"
                 and record.get("run_id") == run_id), None)


def _rounds(records, run_id):
    return [record for record in records or []
            if isinstance(record, dict) and record.get("event") == "round"
            and record.get("run_id") == run_id]


def run_policy(records, run_id):
    """run 의 수렴 정책. open 레코드의 설정 스냅샷에서만 읽는다.

    진행 중 run 에서 profile 을 바꿔도 수렴 기준이 바뀌지 않게 한다. 모르는 `converge_on` 은 `all`
    로 읽는다 — profile 검증이 따로 FAIL 로 드러내고, `all` 이 더 엄격한 쪽이다.
    """
    opened = _open_record(records, run_id) or {}
    cfg = opened.get("cfg") if isinstance(opened.get("cfg"), dict) else {}
    mode = cfg.get("converge_on")
    critical = cfg.get("critical_p2")
    if not (isinstance(critical, list) and critical
            and all(isinstance(item, str) and item for item in critical)):
        critical = list(DEFAULT_CRITICAL_P2)
    block = cfg.get("severity_block")
    # 빈 목록도 기본값으로 읽는다(조기 종료 경로와 같다). 빈 목록을 그대로 쓰면 P0·P1 이 비차단 잔여로
    # 승인된다.
    if not (isinstance(block, list) and block and all(item in SEVERITIES for item in block)):
        block = list(DEFAULT_SEVERITY_BLOCK)
    # 잔여 승인(blocking)에서 P0·P1 은 설정과 무관하게 차단이다. `severity_block` 에서 빼면 사람이 묻지도
    # 않은 채 P0 가 비차단 잔여로 승인된다(조기 종료는 매번 사용자가 승인하므로 그 경로와 다르다).
    if mode == "blocking":
        block = [key for key in SEVERITIES if key in set(block) | set(DEFAULT_SEVERITY_BLOCK)]
    fast_min = opened.get("fast_minimum_rounds")
    return {"converge_on": mode if mode in CONVERGE_MODES else "all",
            "critical_p2": list(critical), "severity_block": list(block),
            "fast_minimum_rounds": fast_min if _is_count(fast_min) and fast_min > 0 else None}


def _critical_listed(round_record):
    """마지막 라운드 영수증의 크리티컬 목록. 목록을 믿을 수 없으면 None(판정 불가)."""
    receipt = round_record.get("receipt") if isinstance(round_record, dict) else None
    if not isinstance(receipt, dict):
        return None
    listed = receipt.get("critical_findings")
    if listed is None:
        # 2단계 전 사이드카 영수증. 크리티컬이 하나도 없었으면 빈 목록과 같다.
        return [] if receipt.get("critical") == 0 else None
    if not isinstance(listed, list):
        return None
    out = []
    for item in listed:
        if not (isinstance(item, dict) and isinstance(item.get("id"), str)
                and isinstance(item.get("claim_sha256"), str)):
            return None
        out.append({"id": item["id"], "claim_sha256": item["claim_sha256"],
                    "category": item.get("category")})
    return out


def critical_state(records, run_id):
    """마지막 라운드의 크리티컬 P2 와 거기에 묶인 결정.

    결정은 (run, 라운드, 지적 id, 주장 해시) 가 모두 같을 때만 적용한다. 다음 라운드에 같은 id 로
    다른 주장이 와도, 같은 주장이 다시 살아남아도 다시 묻는다. 라운드는 그 레코드의 해시로 가린다 —
    iteration 번호는 같은 값이 두 번 기록될 수 있다(소급 거부를 피하려고 단조성을 강제하지 않는다).
    """
    rounds = _rounds(records, run_id)
    state = {"iteration": None, "listed": [], "known": True, "pending": [], "fix": [],
             "accept": [], "decisions": []}
    if not rounds:
        return state
    last = rounds[-1]
    state["iteration"] = last.get("iteration")
    listed = _critical_listed(last)
    if listed is None:
        state["known"] = False
        return state
    state["listed"] = listed
    applied = {}
    round_hash = last.get("record_hash")
    for record in records or []:
        if not (isinstance(record, dict) and record.get("event") == DECISION_EVENT
                and record.get("run_id") == run_id
                and record.get("kind") == DECISION_CRITICAL_P2
                and record.get("iteration") == state["iteration"]
                and record.get("round_hash") == round_hash):
            continue
        key = (record.get("finding_id"), record.get("claim_sha256"))
        if key not in applied and record.get("choice") in CRITICAL_CHOICES:
            applied[key] = record
    for item in listed:
        decision = applied.get((item["id"], item["claim_sha256"]))
        if decision is None:
            state["pending"].append(item["id"])
        else:
            state[decision["choice"]].append(item["id"])
            state["decisions"].append(decision)
    return state


def convergence(records, run_id, policy=None, critical=None):
    """마지막 라운드가 수렴인가. `all` 은 생존 0, `blocking` 은 통일 수렴식이다.

    `blocking_open` = 차단 심각도 생존 + 결정 없는 크리티컬 + 「수정」으로 정한 크리티컬,
    `accepted_residual` = 「수용」으로 정한 크리티컬, `nonblocking` = 나머지 생존.
    `blocking` 수렴은 `blocking_open == 0` 이고 미결 반박·미탐색이 0 이며 영수증이 있을 때다.
    영수증이 없으면 크리티컬 0 을 증명할 수 없으므로 수렴으로 보지 않는다.
    """
    policy = run_policy(records, run_id) if policy is None else policy
    rounds = _rounds(records, run_id)
    out = {"survived": 0, "receipt": False, "blocking_open": 0, "accepted_residual": 0,
           "nonblocking": 0, "refute_pending": 0, "unexplored": 0, "converged": False,
           "reason": None}
    if not rounds:
        return out
    last = rounds[-1]
    survived = int(last.get("survived", 0) or 0)
    out["survived"] = survived
    if survived == 0:
        out.update({"converged": True, "reason": "CONVERGED"})
        return out
    if policy["converge_on"] != "blocking":
        return out
    critical = critical_state(records, run_id) if critical is None else critical
    receipt = last.get("receipt")
    by_severity = last.get("survived_by_severity")
    if not (isinstance(receipt, dict) and isinstance(by_severity, dict) and critical["known"]
            and not severity_receipt_issues(by_severity, survived)):
        return out
    out["receipt"] = True
    severity_open = sum(int(by_severity.get(key, 0)) for key in policy["severity_block"])
    # 크리티컬은 P2 다. P2 가 이미 차단 심각도면 크리티컬은 그 합에 들어 있고, 수용 대상도 아니다.
    p2_blocks = "P2" in policy["severity_block"]
    pending, fix, accept = (len(critical["pending"]), len(critical["fix"]),
                            len(critical["accept"]))
    blocking_open = severity_open + (0 if p2_blocks else pending + fix)
    accepted = 0 if p2_blocks else accept
    nonblocking = max(survived - severity_open - (0 if p2_blocks else len(critical["listed"])), 0)
    refute_pending = receipt.get("refute_pending") if _is_count(receipt.get("refute_pending")) else 1
    unexplored = receipt.get("unexplored") if _is_count(receipt.get("unexplored")) else 1
    out.update({"blocking_open": blocking_open, "accepted_residual": accepted,
                "nonblocking": nonblocking, "refute_pending": refute_pending,
                "unexplored": unexplored})
    if blocking_open == 0 and refute_pending == 0 and unexplored == 0:
        out.update({"converged": True, "reason": CONVERGED_RESIDUAL_REASON})
    return out


def pending_critical(records, run_id):
    """`blocking` run 에서 개발자 결정을 기다리는 크리티컬 id. 다른 run 은 빈 목록이다.

    P2 가 이미 차단 심각도면 크리티컬 P2 도 고쳐야 수렴한다 — 수용할 수 없으니 물을 것이 없다.
    """
    policy = run_policy(records, run_id)
    if policy["converge_on"] != "blocking" or "P2" in policy["severity_block"]:
        return []
    return critical_state(records, run_id)["pending"]


def loop_verdict(records, run_id, cfg, risk):
    """`next` 의 판정. 레코드 목록만 받는 순수 함수다.

    `next`(권고), `decide`·close 의 CLI 검사와 잠금 안 검사, SessionStart 재진입 문맥이 같은 판정을
    써야 한다. 각자 일부 조건만 보면 `next` 가 STOP 인데 `decide` 가 연장을 받거나, 훅이 STOP 을
    ASK 로 보여 준다.

    우선순위: 아키텍처 > 예산 > 크리티컬 P2(ASK) > run 반복 상한 > 사이클 상한(ASK) > 수렴 > 계속.
    `cfg`·`risk` 는 예산·반복 상한에만 쓴다. 수렴 정책은 open 스냅샷(`run_policy`)이다.
    반환 `basis` 는 판정 근거의 언어 중립 이름이고, 문장은 호출하는 쪽이 만든다.
    """
    rounds = _rounds(records, run_id)
    budget = _tier_int(cfg, "budget_tokens", risk)
    max_iter = _tier_int(cfg, "max_iterations", risk)
    cycle = run_cycle_state(records, run_id)
    policy = run_policy(records, run_id)
    verdict = {"action": "CONTINUE", "result": None, "reason": None, "basis": "continue",
               "iterations": len(rounds), "budget": budget, "max_iterations": max_iter,
               "tokens": budget_tokens_of(rounds), "survived": None, "converged": False,
               "unmeasured": unmeasured_peer_rounds(rounds), "cycle": cycle,
               "policy": policy, "critical": None, "convergence": None}

    def _set(action, result, reason, basis):
        verdict.update({"action": action, "result": result, "reason": reason, "basis": basis})
        return verdict

    if not rounds:
        # 앞 run 들이 사이클 상한을 다 쓴 뒤 새 run 을 열었다. 라운드를 열기 전에 사람이 정해야 한다.
        if cycle is not None and cycle["reached"]:
            return _set("ASK", None, CYCLE_CAP_REASON, "cycle_cap")
        return _set("CONTINUE", None, None, "no_rounds")
    critical = critical_state(records, run_id)
    state = convergence(records, run_id, policy, critical)
    converged = state["converged"]
    # Fast run 의 최소 라운드보다 먼저 수렴하면 Fast review 가 그 Loop 를 거부한다. 승인으로 닫으면
    # 다시 열 수 없으니, 최소치까지는 수렴으로 보지 않는다.
    fast_min = policy["fast_minimum_rounds"]
    effective = converged and (fast_min is None or len(rounds) >= fast_min)
    verdict.update({"survived": state["survived"], "converged": converged,
                    "critical": critical, "convergence": state})
    if any(int(r.get("arch", 0) or 0) > 0 for r in rounds):
        return _set("STOP", "BLOCKED", "BLOCKED_ARCH", "arch_escalated")
    if budget is not None and verdict["tokens"] >= budget:
        return _set("STOP", "BLOCKED", "BUDGET_TOK", "over_budget")
    if (policy["converge_on"] == "blocking" and "P2" not in policy["severity_block"]
            and critical["pending"]):
        return _set("ASK", None, CRITICAL_P2_REASON, "critical_pending")
    if max_iter is not None and len(rounds) >= max_iter:
        if converged:
            return _set("STOP", "APPROVED", state["reason"], "max_iter_converged")
        return _set("STOP", "BLOCKED", "BUDGET_ITER", "max_iter_unresolved")
    # 사이클 상한은 run 반복 상한 다음이다. 둘 다 닿았으면 run 이 먼저 BLOCKED 로 끝나고, 새 run 이
    # 라운드 0 에서 이 질문을 받는다. 상한 라운드에서 수렴했으면 묻지 않고 정상 승인이다 — run 반복
    # 상한의 처리와 같다. Fast 최소 미만의 수렴은 여기서 수렴으로 보지 않는다(계속을 권하면 상한이라
    # 라운드를 열 수 없다).
    if cycle is not None and cycle["reached"] and not effective:
        return _set("ASK", None, CYCLE_CAP_REASON, "cycle_cap")
    if effective:
        basis = "converged" if state["reason"] == "CONVERGED" else "converged_residual"
        return _set("STOP", "APPROVED", state["reason"], basis)
    if converged:
        return _set("CONTINUE", None, None, "fast_minimum")
    if policy["converge_on"] == "blocking" and not state["receipt"]:
        return _set("CONTINUE", None, None, "receipt_missing")
    return _set("CONTINUE", None, None, "continue")


def record_round(root, run_id, iteration, found, survived, accepted, arch=0, tokens=0, now=None,
                 lens_receipts=None, survived_by_severity=None, peer_usage=None,
                 sidecar=None, receipt=None, tree=None):
    """라운드 1건 기록.
    found=FIND 발견수, survived=REFUTE 생존수, accepted=REWORK 채택수, arch=아키텍처 에스컬레이션수, tokens=누적 토큰.
    peer_usage=이 라운드 peer 리뷰어 실측(`sage cross-check` 의 REVIEWER_TOKENS) dict 또는 "unknown".
    sidecar=라운드 사이드카 참조(경로·sha256·스키마) 또는 "absent". receipt=승인 판단 값(사이드카 산출).
    tree=작업 트리 식별자·직전 대비 delta 참조. 셋 다 None 이면 필드를 쓰지 않는다(옛 호출 호환).
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
    if peer_usage is not None:
        record["peer_usage"] = peer_usage if peer_usage == "unknown" else dict(peer_usage)
    if sidecar is not None:
        record["sidecar"] = sidecar if sidecar == "absent" else dict(sidecar)
    if receipt is not None:
        record["receipt"] = dict(receipt)
    if tree is not None:
        record["tree"] = dict(tree)

    # close 와 같은 이유로 lock 안에서 다시 본다. CLI 는 orphan(open 없음)과 종료된 run 을 이미
    # 거부하지만 그 검사는 lock 밖이라, round 와 close 가 경합하면 둘 다 통과해 종료 뒤에 라운드가
    # 붙는다. 그 줄은 해시 체인의 일부라 지울 수 없고, `integrity_issues` 가 영구히 붉어진다 —
    # 우회가 아니라 복구 불가능한 손상이다. 판정은 CLI 와 **같은 두 가지**만 옮긴다: iteration
    # 단조성 같은 새 규칙을 여기서 켜면 지금 통과하던 기록이 소급 거부된다.
    # (주석인 이유: 중첩 함수의 한국어 docstring 은 판정 문자열 오라클에 판정으로 잡힌다.)
    # 사이클 상한도 같은 이유로 잠금 안에서 다시 본다. CLI 가 `next` 로 막아도, 두 세션이 같은
    # 사이클에 라운드를 붙이면 둘 다 상한 밖에서 통과할 수 있다. 판정은 CLI 와 같은 `cycle_state`.
    def _open_and_not_closed(prior, _record):
        mine = [item for item in prior if item.get("run_id") == run_id]
        if not any(item.get("event") == "loop_open" for item in mine):
            raise AuditWriteError(f"run {run_id!r} was never opened")
        if any(item.get("event") == "loop_close" for item in mine):
            raise AuditWriteError(f"run {run_id!r} is already closed")
        state = run_cycle_state(prior, run_id)
        if state is not None and state["reached"]:
            raise AuditWriteError(
                f"cycle {state['stem']!r} reached its round cap "
                f"({state['rounds']}/{state['cap']}); record a decision first")
        # 크리티컬 P2 는 개발자가 정하기 전에는 다음 라운드로 가지 않는다(자동 수정 금지).
        pending = pending_critical(prior, run_id)
        if pending:
            raise AuditWriteError(
                f"run {run_id!r} has critical P2 finding(s) awaiting a decision: {pending}; "
                "record each with `sage review-loop decide --finding <id> --claim <hash> --fix|--accept`")

    return _append(audit_path(root), record, validator=_open_and_not_closed)


def record_decision(root, run_id, kind, choice, reason, decided_by, extend=None, now=None,
                    cfg=None, risk=None, finding_id=None, expected_round_hash=None,
                    expected_claim_sha256=None):
    """사람이 내린 루프 결정 1건. 받는 결정은 둘이다.

    - `cycle_cap`/`continue`: 사이클 상한에서 계속(상한 +extend).
    - `critical_p2`/`fix|accept`: 마지막 라운드의 크리티컬 P2 하나를 고칠지 잔여로 받을지.
      라운드 번호·주장 해시는 잠금 안에서 마지막 라운드 기록으로 채운다 — 결정이 그 내용에 묶인다.
      `expected_*` 는 사용자에게 보여 준 라운드·주장이다. 선검사와 쓰기 사이에 라운드가 바뀌어 같은 id 에
      다른 주장이 오면, 그 답을 새 주장에 붙이지 않고 거부한다.

    「잔여 승인」과 「멈춤」은 각각 조기 종료 close 와 `CYCLE_CAP` close 가 결정 기록이다. 같은
    결정을 두 레코드에 쓰면 둘이 어긋날 수 있다.

    `cfg`·`risk` 는 `next` 가 판정에 쓴 값이다(CLI 가 넘긴다). 없으면 run 의 open 스냅샷을 쓴다.
    """
    if kind == DECISION_CYCLE_CAP:
        if choice != "continue":
            raise AuditWriteError(f"unsupported decision {kind!r}/{choice!r}")
        if type(extend) is not int or extend < 1:
            raise AuditWriteError("cycle continue decision needs a positive integer extend")
    elif kind == DECISION_CRITICAL_P2:
        if choice not in CRITICAL_CHOICES:
            raise AuditWriteError(f"unsupported decision {kind!r}/{choice!r}")
        if not isinstance(finding_id, str) or not finding_id:
            raise AuditWriteError("critical decision needs a finding id")
        if not (isinstance(expected_round_hash, str) and isinstance(expected_claim_sha256, str)):
            raise AuditWriteError("critical decision needs the round and claim the user answered")
    else:
        raise AuditWriteError(f"unsupported decision {kind!r}/{choice!r}")
    for label, value in (("reason", reason), ("decided_by", decided_by)):
        if not isinstance(value, str) or not value.strip():
            raise AuditWriteError(f"decision {label} must be a non-empty line")
    t = time.time() if now is None else now
    record = {"event": DECISION_EVENT, "run_id": run_id, "ts": _iso(t), "epoch": int(t),
              "kind": kind, "choice": choice}
    if kind == DECISION_CYCLE_CAP:
        record["extend"] = extend
    else:
        record["finding_id"] = finding_id
    record.update({"reason": reason, "decided_by": decided_by})

    def _verdict(prior, mine):
        opened = next(item for item in mine if item.get("event") == "loop_open")
        return loop_verdict(prior, run_id,
                            opened.get("cfg") or {} if cfg is None else cfg,
                            opened.get("risk") if risk is None else risk)

    # 결정은 그 순간 상한에 닿아 있을 때만 의미가 있다. 상한 전에 미리 늘려 두면 「상한에서 묻는다」
    # 는 계약이 사라진다. 사이클 값은 잠금 안에서 계산해 레코드에 같이 남긴다.
    def _cap_reached(prior, rec):
        mine = [item for item in prior if item.get("run_id") == run_id]
        if not any(item.get("event") == "loop_open" for item in mine):
            raise AuditWriteError(f"run {run_id!r} was never opened")
        if any(item.get("event") == "loop_close" for item in mine):
            raise AuditWriteError(f"run {run_id!r} is already closed")
        if kind == DECISION_CRITICAL_P2:
            _critical_open(prior, mine, rec)
            return
        state = run_cycle_state(prior, run_id)
        if state is None:
            raise AuditWriteError(f"run {run_id!r} is not bound to a cycle")
        if not state["reached"]:
            raise AuditWriteError(
                f"cycle {state['stem']!r} has not reached its round cap "
                f"({state['rounds']}/{state['cap']})")
        # 상한에 닿았어도 판정이 STOP(아키텍처·예산·run 반복 상한·수렴)이거나 크리티컬 P2 를 먼저
        # 정해야 하는 상태면 연장할 자리가 아니다.
        verdict = _verdict(prior, mine)
        if (verdict["action"], verdict["reason"]) != ("ASK", CYCLE_CAP_REASON):
            raise AuditWriteError(
                f"run {run_id!r} is at {verdict['action']} {verdict['reason']}, not ASK "
                f"{CYCLE_CAP_REASON}; nothing to decide")
        rec.update({"cycle_stem": state["stem"], "cycle_rounds": state["rounds"],
                    "cap_before": state["cap"], "cap_after": state["cap"] + extend})

    def _critical_open(prior, mine, rec):
        verdict = _verdict(prior, mine)
        if (verdict["action"], verdict["reason"]) != ("ASK", CRITICAL_P2_REASON):
            raise AuditWriteError(
                f"run {run_id!r} is at {verdict['action']} {verdict['reason']}, not ASK "
                f"{CRITICAL_P2_REASON}; nothing to decide")
        critical = verdict["critical"]
        if finding_id not in critical["pending"]:
            raise AuditWriteError(
                f"finding {finding_id!r} is not a critical P2 awaiting a decision in round "
                f"{critical['iteration']} (pending: {critical['pending']})")
        item = next(entry for entry in critical["listed"] if entry["id"] == finding_id)
        last = _rounds(prior, run_id)[-1]
        if (last.get("record_hash") != expected_round_hash
                or item["claim_sha256"] != expected_claim_sha256):
            raise AuditWriteError(
                f"finding {finding_id!r} changed since it was shown to the user (a new round was "
                "recorded); show the current finding and ask again")
        sidecar = last.get("sidecar") if isinstance(last.get("sidecar"), dict) else {}
        rec.update({"iteration": critical["iteration"], "round_hash": last.get("record_hash"),
                    "claim_sha256": item["claim_sha256"], "category": item.get("category"),
                    "sidecar_sha256": sidecar.get("sha256")})

    return _append(audit_path(root), record, validator=_cap_reached)


def close_loop(root, run_id, result, reason, iterations, now=None, reviewer_actual=None,
               phase00_hash=None, authorization=None, residual=None, cfg=None, risk=None):
    """루프 종료 기록. result ∈ CLOSE_RESULTS, reason ∈ CLOSE_REASONS(호출 레이어가 강제).
    reviewer_actual=실제 수행된 리뷰어 모드(예: cross_model/same_runtime) — open 의 reviewer_requested 와
    비교해 audit_summary 가 degraded 를 파생(7차 배치3: cross-model 폴백 침묵 차단).
    residual=`CONVERGED_RESIDUAL` close 의 잔여 기록(그 사유에만 필수). cfg·risk=`next` 가 판정에 쓴
    값(잔여 승인의 잠금 안 재판정용, 없으면 open 스냅샷)."""
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
        # 어느 판정에서 승인했는가(`CONTINUE`·`STOP:BUDGET_ITER`·`ASK:CYCLE_CAP`). 선택 필드라
        # 옛 기록에는 없다 — 그때는 `CONTINUE` 에서만 허용됐다.
        if authorization.get("stopped_at") is not None:
            rec["stopped_at"] = authorization["stopped_at"]
        rec["attestation"] = "self_asserted_local"
        rec["review_assurance"] = REVIEW_ASSURANCE_REDUCED
    elif authorization is not None:
        raise AuditWriteError("authorization record is only valid for "
                              f"{EARLY_CLOSE_REASON} closes")
    if reason == CONVERGED_RESIDUAL_REASON:
        if not isinstance(residual, dict):
            raise AuditWriteError(f"{CONVERGED_RESIDUAL_REASON} close requires a residual record")
        missing = [field for field in _RESIDUAL_CLOSE_FIELDS if residual.get(field) is None]
        if missing:
            raise AuditWriteError(f"residual record is missing {missing}")
        receipt = residual["survived_by_severity"]
        receipt_issues = severity_receipt_issues(receipt, _severity_total(receipt))
        if receipt_issues:
            raise AuditWriteError("residual severity receipt invalid: " + "; ".join(receipt_issues))
        rec.update({key: residual[key] for key in _RESIDUAL_CLOSE_FIELDS})
        rec["lens_receipts"] = list(residual.get("lens_receipts") or [])
        for key in ("sidecar_sha256", "fast_run_id", "done_criteria_revision"):
            if residual.get(key) is not None:
                rec[key] = residual[key]
        rec["review_assurance"] = REVIEW_ASSURANCE_POLICY
    elif residual is not None:
        raise AuditWriteError("residual record is only valid for "
                              f"{CONVERGED_RESIDUAL_REASON} closes")

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
        # 크리티컬 P2 결정을 기다리는 동안에는 아키텍처·예산 STOP 말고는 닫지 않는다. 그 둘은 판정
        # 순위가 크리티컬 질문보다 앞이라 정상 종료다.
        pending = pending_critical(prior, run_id)
        if pending and reason not in ("BLOCKED_ARCH", "BUDGET_TOK"):
            raise AuditWriteError(
                f"run {run_id!r} has critical P2 finding(s) awaiting a decision: {pending}; "
                "record each with `sage review-loop decide --finding <id> --claim <hash> --fix|--accept`")
        # `blocking` run 에서 생존이 남은 승인은 잔여 승인뿐이다. `CONVERGED`·`DRY` 로 닫으면 잔여를 안은
        # 승인이 보증 저하 없이 일반 승인으로 남는다 — 종료 검산 mode(advisory)와 무관하게 막는다.
        if (result == "APPROVED" and reason in ("CONVERGED", "DRY")
                and run_policy(prior, run_id)["converge_on"] == "blocking"):
            rounds_now = _rounds(prior, run_id)
            if rounds_now and int(rounds_now[-1].get("survived", 0) or 0) > 0:
                raise AuditWriteError(
                    f"run {run_id!r} was opened with converge_on: blocking and findings survive; "
                    f"approve it with {CONVERGED_RESIDUAL_REASON} (reduced assurance), not {reason}")
        if reason == CONVERGED_RESIDUAL_REASON:
            _residual_still_holds(prior, mine)
            return
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

    # 잔여 승인은 판정 자체를 다시 계산한다. close 가 판정을 끝낸 사이 라운드나 결정이 붙으면 옛
    # 판정으로 승인이 남는데, 그 감사는 체인이 정상이라 어느 층도 잡지 못한다.
    def _residual_still_holds(prior, mine):
        opened = next((item for item in mine if item.get("event") == "loop_open"), {})
        verdict = loop_verdict(prior, run_id,
                               opened.get("cfg") or {} if cfg is None else cfg,
                               opened.get("risk") if risk is None else risk)
        if (verdict["action"], verdict["reason"]) != ("STOP", CONVERGED_RESIDUAL_REASON):
            raise AuditWriteError(
                f"run {run_id!r} is now at {verdict['action']} {verdict['reason']}, not STOP "
                f"{CONVERGED_RESIDUAL_REASON}")
        rounds = _rounds(prior, run_id)
        if len(rounds) != int(iterations) or len(rounds) != rec.get("completed_rounds"):
            raise AuditWriteError(
                f"run {run_id!r} now has {len(rounds)} round(s), not the {int(iterations)} this "
                "close was judged against")
        last = rounds[-1]
        if last.get("survived_by_severity") != rec.get("survived_by_severity"):
            raise AuditWriteError(
                f"run {run_id!r} last round severity receipt changed after the judgement")
        if list(last.get("lens_receipts") or []) != list(rec.get("lens_receipts") or []):
            raise AuditWriteError(
                f"run {run_id!r} last round lens receipts changed after the judgement")
        sidecar = last.get("sidecar") if isinstance(last.get("sidecar"), dict) else {}
        if sidecar.get("sha256") != rec.get("sidecar_sha256"):
            raise AuditWriteError(f"run {run_id!r} last round sidecar changed after the judgement")
        state = verdict["convergence"]
        expected = {"blocking_open": state["blocking_open"],
                    "accepted_residual": state["accepted_residual"],
                    "nonblocking": state["nonblocking"]}
        if rec.get("residual") != expected:
            raise AuditWriteError(
                f"run {run_id!r} residual changed after the judgement: "
                f"{rec.get('residual')!r} != {expected!r}")
        if accepted_decision_refs(verdict["critical"]) != rec.get("accepted_decisions"):
            raise AuditWriteError(
                f"run {run_id!r} accepted critical decisions changed after the judgement")

    return _append(audit_path(root), rec, validator=_terminal_once)


def accepted_decision_refs(critical):
    """잔여 승인 close 에 남길 「수용한 크리티컬」 참조. 판정과 close 가 같은 함수로 만든다."""
    return [{"finding_id": record.get("finding_id"), "claim_sha256": record.get("claim_sha256"),
             "iteration": record.get("iteration")}
            for record in (critical or {}).get("decisions") or []
            if record.get("choice") == "accept"]


def runs(root):
    """감사 로그의 run_id 목록(loop_open 기준, 시간순)."""
    return [r.get("run_id") for r in read_records(root) if r.get("event") == "loop_open"]


def rounds_of(root, run_id):
    """특정 run_id 의 round 레코드(append 순)."""
    return [r for r in read_records(root)
            if r.get("event") == "round" and r.get("run_id") == run_id]


def decisions_of(root, run_id):
    """특정 run_id 의 decision 레코드(append 순)."""
    return [r for r in read_records(root)
            if r.get("event") == DECISION_EVENT and r.get("run_id") == run_id]


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


# open 뒤에만, close 전에만 올 수 있는 이벤트. `decision` 도 라운드와 같은 축이다 — 닫힌 run 에
# 결정이 붙거나 open 없는 결정이 있으면 그 결정이 어느 루프의 것인지 말할 수 없다.
_RUN_EVENTS = ("round", "loop_close", DECISION_EVENT)


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
        if ev in _RUN_EVENTS and rid not in opens:
            issues.append(_diagnostic("loop_audit.orphan_event", event=ev, run_id=repr(rid)))
            continue
        if ev in _RUN_EVENTS and closes.get(rid):
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
