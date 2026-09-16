"""Explicit L3 acceptance waiver audit shared by the SAGE CLI and hook runtime.

The JSONL file is the authority and audit trail. A waiver is deliberately narrow:
one cycle stem, one required acceptance ID, and at most 24 hours. Any malformed,
duplicated, or conflicting authority record makes the summary invalid so consumers
can fail closed instead of guessing which grant should win.
"""
import json
import os
import re
import stat
import time
import uuid
from contextlib import contextmanager

AUDIT_REL = os.path.join(".sage", "acceptance-waivers.jsonl")
LOCK_SUFFIX = ".lock"
# Windows has no fcntl, no directory descriptors and no dir_fd, so the POSIX descriptor-bound
# path cannot run there. Tests flip this to drive the Windows branch on any host.
_NT_IO = os.name == "nt"
MAX_TTL_SECONDS = 24 * 3600
MAX_AUDIT_BYTES = 1024 * 1024
MAX_RECORDS = 10000
_ID_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*\Z")
_UNITS = {"s": 1, "m": 60, "h": 3600, "d": 86400}
_GRANT_TEXT_FIELDS = ("cycle_stem", "acceptance_id", "reason", "scope",
                      "remaining_evidence", "confirmed_by")


def audit_path(root):
    return os.path.join(root, AUDIT_REL)


def parse_ttl(value):
    raw = str(value or "").strip().lower()
    if not raw:
        return None
    try:
        seconds = int(float(raw[:-1]) * _UNITS[raw[-1]]) if raw[-1] in _UNITS else int(float(raw))
    except (ValueError, TypeError, IndexError, OverflowError):
        return None
    return seconds if seconds > 0 else None


def _iso(epoch):
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(epoch))


def _exact_id(value, field):
    raw = value.strip() if isinstance(value, str) else ""
    if not raw or not _ID_RE.fullmatch(raw):
        raise ValueError(f"{field} must be one exact ID using letters, digits, '.', '_' or '-'")
    return raw


def _required_text(value, field):
    raw = value.strip() if isinstance(value, str) else ""
    if not raw:
        raise ValueError(f"{field} is required")
    return raw


def _audit_directory_fd(root, create=False):
    root_path = os.path.realpath(root)
    directory_flags = (os.O_RDONLY | getattr(os, "O_DIRECTORY", 0)
                       | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_CLOEXEC", 0))
    root_fd = os.open(root_path, directory_flags)
    try:
        try:
            return os.open(".sage", directory_flags, dir_fd=root_fd)
        except FileNotFoundError:
            if not create:
                return None
            try:
                os.mkdir(".sage", mode=0o700, dir_fd=root_fd)
            except FileExistsError:
                pass
            return os.open(".sage", directory_flags, dir_fd=root_fd)
    finally:
        os.close(root_fd)


def _ensure_audit_directory(root):
    if _NT_IO:
        _nt_audit_directory(root, create=True)
        return
    directory_fd = _audit_directory_fd(root, create=True)
    if directory_fd is not None:
        os.close(directory_fd)


def _append(root, record):
    encoded = (json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")
    if len(encoded) > 65536:
        raise ValueError("acceptance waiver record is too large")
    if _NT_IO:
        _nt_append(root, encoded)
        return
    import fcntl
    directory_fd = _audit_directory_fd(root, create=True)
    fcntl.flock(directory_fd, fcntl.LOCK_EX)
    flags = os.O_WRONLY | os.O_CREAT | os.O_APPEND
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    flags |= getattr(os, "O_CLOEXEC", 0)
    fd = None
    try:
        fd = os.open(os.path.basename(AUDIT_REL), flags, 0o600, dir_fd=directory_fd)
        if not stat.S_ISREG(os.fstat(fd).st_mode):
            raise OSError("acceptance waiver audit must be a regular file")
        written = os.write(fd, encoded)
        if written != len(encoded):
            raise OSError(f"short append: {written}/{len(encoded)} bytes")
        os.fsync(fd)
    finally:
        if fd is not None:
            os.close(fd)
        if directory_fd is not None:
            os.close(directory_fd)


def _read_lines(root):
    if _NT_IO:
        return _nt_read_lines(root)
    directory_fd = None
    fd = None
    try:
        import fcntl
        directory_fd = _audit_directory_fd(root, create=False)
        if directory_fd is None:
            return [], []
        fcntl.flock(directory_fd, fcntl.LOCK_SH)
        leaf = os.path.basename(AUDIT_REL)
        try:
            before = os.stat(leaf, dir_fd=directory_fd, follow_symlinks=False)
        except FileNotFoundError:
            return [], []
        if not stat.S_ISREG(before.st_mode):
            return [], ["audit path is not a regular non-symlink file"]
        if before.st_size > MAX_AUDIT_BYTES:
            return [], [f"audit exceeds {MAX_AUDIT_BYTES} bytes"]
        flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_CLOEXEC", 0)
        fd = os.open(leaf, flags, dir_fd=directory_fd)
        return _read_opened(fd, before, ("st_size", "st_mtime_ns", "st_ctime_ns"))
    except Exception as exc:
        return [], [f"audit read failed: {type(exc).__name__}: {exc}"]
    finally:
        if fd is not None:
            os.close(fd)
        if directory_fd is not None:
            os.close(directory_fd)


def _read_opened(fd, before, change_fields):
    """Read an already-opened audit descriptor and parse it. Shared by both platforms.

    `change_fields` differs per platform because Windows `st_ctime_ns` is creation time, not a
    metadata-change time, so it cannot reveal a concurrent rewrite there.
    """
    opened = os.fstat(fd)
    if (not stat.S_ISREG(opened.st_mode)
            or (opened.st_dev, opened.st_ino) != (before.st_dev, before.st_ino)):
        return [], ["audit changed during secure open"]
    chunks = []
    total = 0
    while total <= MAX_AUDIT_BYTES:
        chunk = os.read(fd, min(65536, MAX_AUDIT_BYTES + 1 - total))
        if not chunk:
            break
        chunks.append(chunk)
        total += len(chunk)
    if total > MAX_AUDIT_BYTES:
        return [], [f"audit exceeds {MAX_AUDIT_BYTES} bytes"]
    after = os.fstat(fd)
    if tuple(getattr(after, name) for name in change_fields) != tuple(
            getattr(opened, name) for name in change_fields):
        return [], ["audit changed while being read"]
    return _parse_lines(b"".join(chunks))


def _parse_lines(data):
    try:
        lines = data.decode("utf-8").splitlines()
    except UnicodeDecodeError:
        return [], ["audit is not valid UTF-8"]
    records, issues = [], []
    for line_no, line in enumerate(lines, 1):
        if line_no > MAX_RECORDS:
            issues.append(f"audit exceeds {MAX_RECORDS} records")
            break
        raw = line.strip()
        if not raw:
            continue
        try:
            records.append((line_no, json.loads(raw)))
        except Exception:
            issues.append(f"line {line_no}: malformed JSON")
    return records, issues


# --- Windows -----------------------------------------------------------------------------
# Path-based I/O: every leaf is checked with lstat before open and the opened descriptor must be
# the object that was checked. A link planted between that check and the open is not caught here;
# the audit is self-asserted local state, and a failed check still leaves the gate blocked.

def _nt_flags(*flags):
    value = getattr(os, "O_BINARY", 0) | getattr(os, "O_NOINHERIT", 0)
    for flag in flags:
        value |= flag
    return value


def _is_link_or_reparse(st):
    return (stat.S_ISLNK(st.st_mode)
            or bool(getattr(st, "st_file_attributes", 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT))


def _nt_audit_directory(root, create):
    """Return the real `.sage` directory, None when a read finds none, or raise."""
    root_path = os.path.realpath(root)
    # A missing root must not look like an empty audit, so check it before looking for `.sage`.
    if not stat.S_ISDIR(os.stat(root_path).st_mode):
        raise NotADirectoryError(f"project root is not a directory: {root_path}")
    path = os.path.join(root_path, os.path.dirname(AUDIT_REL))
    try:
        st = os.lstat(path)
    except FileNotFoundError:
        if not create:
            return None
        try:
            os.mkdir(path, 0o700)
        except FileExistsError:
            pass
        st = os.lstat(path)
    if _is_link_or_reparse(st) or not stat.S_ISDIR(st.st_mode):
        raise OSError("acceptance waiver audit directory must be a real directory, "
                      "not a link or reparse point")
    return path


def _nt_checked_leaf(path):
    """lstat a leaf that is about to be opened. None when absent."""
    try:
        st = os.lstat(path)
    except FileNotFoundError:
        return None
    if _is_link_or_reparse(st) or not stat.S_ISREG(st.st_mode):
        raise OSError(f"{os.path.basename(path)} must be a regular non-link file")
    return st


def _nt_verify_opened(fd, before, path):
    opened = os.fstat(fd)
    if not stat.S_ISREG(opened.st_mode):
        raise OSError(f"{os.path.basename(path)} must be a regular file")
    if before is not None and (opened.st_dev, opened.st_ino) != (before.st_dev, before.st_ino):
        raise OSError(f"{os.path.basename(path)} changed during open")
    return opened


@contextmanager
def _nt_lock(directory):
    """Exclusive lock on a sibling `.lock` file. msvcrt has no shared lock, so reads take it too."""
    import msvcrt
    path = os.path.join(directory, os.path.basename(AUDIT_REL) + LOCK_SUFFIX)
    before = _nt_checked_leaf(path)
    fd = os.open(path, _nt_flags(os.O_CREAT, os.O_RDWR), 0o600)
    locked = False
    try:
        _nt_verify_opened(fd, before, path)
        if os.fstat(fd).st_size == 0:
            os.write(fd, b"\0")
        os.lseek(fd, 0, os.SEEK_SET)
        msvcrt.locking(fd, msvcrt.LK_LOCK, 1)
        locked = True
        yield
    finally:
        try:
            if locked:
                os.lseek(fd, 0, os.SEEK_SET)
                msvcrt.locking(fd, msvcrt.LK_UNLCK, 1)
        finally:
            os.close(fd)


def _nt_append(root, encoded):
    directory = _nt_audit_directory(root, create=True)
    with _nt_lock(directory):
        path = os.path.join(directory, os.path.basename(AUDIT_REL))
        before = _nt_checked_leaf(path)
        # O_BINARY keeps LF: text mode would store every record with CRLF.
        fd = os.open(path, _nt_flags(os.O_WRONLY, os.O_CREAT, os.O_APPEND), 0o600)
        try:
            _nt_verify_opened(fd, before, path)
            written = os.write(fd, encoded)
            if written != len(encoded):
                raise OSError(f"short append: {written}/{len(encoded)} bytes")
            os.fsync(fd)
        finally:
            os.close(fd)


def _nt_read_lines(root):
    try:
        directory = _nt_audit_directory(root, create=False)
        if directory is None:
            return [], []
        with _nt_lock(directory):
            path = os.path.join(directory, os.path.basename(AUDIT_REL))
            try:
                before = os.lstat(path)
            except FileNotFoundError:
                return [], []
            if _is_link_or_reparse(before) or not stat.S_ISREG(before.st_mode):
                return [], ["audit path is not a regular non-symlink file"]
            if before.st_size > MAX_AUDIT_BYTES:
                return [], [f"audit exceeds {MAX_AUDIT_BYTES} bytes"]
            fd = os.open(path, _nt_flags(os.O_RDONLY))
            try:
                return _read_opened(fd, before, ("st_size", "st_mtime_ns"))
            finally:
                os.close(fd)
    except Exception as exc:
        return [], [f"audit read failed: {type(exc).__name__}: {exc}"]


def read_records(root):
    """Return parsed JSON records. Use audit_summary for authority decisions."""
    records, _ = _read_lines(root)
    return [record for _, record in records if isinstance(record, dict)]


def _validate_grant(record):
    waiver_id = _exact_id(record.get("waiver_id"), "waiver_id")
    if not waiver_id.startswith("aw-"):
        raise ValueError("waiver_id must start with aw-")
    values = {field: (_exact_id(record.get(field), field) if field in ("cycle_stem", "acceptance_id")
                      else _required_text(record.get(field), field)) for field in _GRANT_TEXT_FIELDS}
    epoch = record.get("epoch")
    expires = record.get("expires_epoch")
    if not isinstance(epoch, int) or isinstance(epoch, bool):
        raise ValueError("epoch must be an integer")
    if not isinstance(expires, int) or isinstance(expires, bool) or expires <= epoch:
        raise ValueError("expires_epoch must be an integer after epoch")
    if expires - epoch > MAX_TTL_SECONDS:
        raise ValueError("grant TTL exceeds 24h")
    if record.get("attestation") != "self_asserted_local":
        raise ValueError("attestation must be self_asserted_local")
    return dict(record, waiver_id=waiver_id, **values)


def audit_summary(root, now=None):
    return summarize_records(*_read_lines(root), now=now)


def summarize_records(parsed, file_issues, now=None):
    """`(line_no, record)` 목록 하나를 요약으로 접는다. 파일도 락도 모르는 순수 함수.

    락을 쥐고 읽는 `audit_summary` 와 락 없이 읽는 조회 경로가 **같은 이 함수**를 써야 한다.
    의미 검증을 두 벌로 만들면 같은 감사가 writer 에게는 정상, 조회에게는 손상으로 보이는
    상태가 생기고, 그때 어느 쪽이 옳은지 판정할 근거가 없다.

    `file_issues` 는 복사해서 쓴다 — 호출부의 리스트에 판정 결과를 덧붙이면 같은 입력으로
    두 번 부르는 것만으로 issue 가 불어난다.
    """
    current = time.time() if now is None else now
    issues = list(file_issues)
    grants, revoked, revoked_once = {}, set(), set()
    for line_no, record in parsed:
        if not isinstance(record, dict):
            issues.append(f"line {line_no}: record must be an object")
            continue
        event = record.get("event")
        try:
            if event == "grant":
                grant_rec = _validate_grant(record)
                waiver_id = grant_rec["waiver_id"]
                if waiver_id in grants:
                    issues.append(f"line {line_no}: duplicate waiver_id {waiver_id}")
                else:
                    grants[waiver_id] = grant_rec
            elif event == "revoke":
                waiver_id = _exact_id(record.get("waiver_id"), "waiver_id")
                _required_text(record.get("reason"), "reason")
                _required_text(record.get("confirmed_by"), "confirmed_by")
                revoke_epoch = record.get("epoch")
                if not isinstance(revoke_epoch, int) or isinstance(revoke_epoch, bool):
                    raise ValueError("epoch must be an integer")
                if waiver_id not in grants:
                    issues.append(f"line {line_no}: revoke references unknown waiver_id {waiver_id}")
                elif waiver_id in revoked_once:
                    issues.append(f"line {line_no}: duplicate revoke for {waiver_id}")
                revoked.add(waiver_id)
                revoked_once.add(waiver_id)
            elif event == "use":
                waiver_id = _exact_id(record.get("waiver_id"), "waiver_id")
                cycle = _exact_id(record.get("cycle_stem"), "cycle_stem")
                acceptance_id = _exact_id(record.get("acceptance_id"), "acceptance_id")
                _required_text(record.get("report_path"), "report_path")
                use_epoch = record.get("epoch")
                if not isinstance(use_epoch, int) or isinstance(use_epoch, bool):
                    raise ValueError("epoch must be an integer")
                grant_rec = grants.get(waiver_id)
                if grant_rec is None:
                    issues.append(f"line {line_no}: use references unknown waiver_id {waiver_id}")
                elif waiver_id in revoked:
                    issues.append(f"line {line_no}: use occurs after revoke for {waiver_id}")
                elif (grant_rec["cycle_stem"], grant_rec["acceptance_id"]) != (cycle, acceptance_id):
                    issues.append(f"line {line_no}: use scope differs from grant {waiver_id}")
                elif not grant_rec["epoch"] <= use_epoch < grant_rec["expires_epoch"]:
                    issues.append(f"line {line_no}: use occurs outside grant lifetime {waiver_id}")
            else:
                issues.append(f"line {line_no}: unknown event {event!r}")
        except ValueError as exc:
            issues.append(f"line {line_no}: malformed {event or 'record'}: {exc}")

    active = [grant_rec for waiver_id, grant_rec in grants.items()
              if waiver_id not in revoked and grant_rec["expires_epoch"] > current]
    by_scope = {}
    for grant_rec in active:
        by_scope.setdefault((grant_rec["cycle_stem"], grant_rec["acceptance_id"]), []).append(grant_rec)
    for scope, matches in sorted(by_scope.items()):
        if len(matches) > 1:
            issues.append(f"conflicting active grants for {scope[0]}/{scope[1]}")
    candidates = sorted(active, key=lambda r: r["waiver_id"])
    valid = not issues
    return {"valid": valid, "issues": issues, "active": sorted(active, key=lambda r: r["waiver_id"]) if valid else [],
            "candidates": candidates, "has_any_records": bool(parsed)}


def grant(root, cycle_stem, acceptance_id, reason, scope, remaining_evidence,
          confirmed_by, ttl_seconds=MAX_TTL_SECONDS, now=None):
    current = time.time() if now is None else now
    values = {
        "cycle_stem": _exact_id(cycle_stem, "cycle_stem"),
        "acceptance_id": _exact_id(acceptance_id, "acceptance_id"),
        "reason": _required_text(reason, "reason"),
        "scope": _required_text(scope, "scope"),
        "remaining_evidence": _required_text(remaining_evidence, "remaining_evidence"),
        "confirmed_by": _required_text(confirmed_by, "confirmed_by"),
    }
    if not isinstance(ttl_seconds, int) or isinstance(ttl_seconds, bool) or not 0 < ttl_seconds <= MAX_TTL_SECONDS:
        raise ValueError("ttl_seconds must be an integer between 1 and 86400")
    _ensure_audit_directory(root)
    before = audit_summary(root, now=current)
    if not before["valid"]:
        raise ValueError("existing acceptance waiver audit is invalid: " + "; ".join(before["issues"][:3]))
    if any((g["cycle_stem"], g["acceptance_id"]) == (values["cycle_stem"], values["acceptance_id"])
           for g in before["active"]):
        raise ValueError("an active waiver already exists for this cycle/acceptance ID")
    record = {"event": "grant", "waiver_id": "aw-" + uuid.uuid4().hex[:16],
              "epoch": int(current), "created_at": _iso(current),
              "expires_epoch": int(current) + ttl_seconds, "expires_at": _iso(current + ttl_seconds),
              "ttl_seconds": ttl_seconds, "attestation": "self_asserted_local", **values}
    _append(root, record)
    after = audit_summary(root, now=current)
    if not after["valid"]:
        compensation = {"event": "revoke", "waiver_id": record["waiver_id"], "epoch": int(current),
                        "ts": _iso(current), "reason": "automatic concurrent-grant recovery",
                        "confirmed_by": record["confirmed_by"]}
        _append(root, compensation)
        recovered = audit_summary(root, now=current)
        if recovered["valid"]:
            raise ValueError("concurrent grant conflict detected; this grant was automatically revoked")
        raise ValueError("grant appended but audit is invalid after compensating revoke: "
                         + "; ".join(recovered["issues"][:3]))
    return record


def revoke(root, waiver_id, reason, confirmed_by, now=None):
    current = time.time() if now is None else now
    exact_id = _exact_id(waiver_id, "waiver_id")
    why = _required_text(reason, "reason")
    who = _required_text(confirmed_by, "confirmed_by")
    summary = audit_summary(root, now=current)
    conflict_only = (not summary["valid"] and summary["issues"]
                     and all(issue.startswith("conflicting active grants") for issue in summary["issues"]))
    if not summary["valid"] and not conflict_only:
        raise ValueError("acceptance waiver audit is invalid: " + "; ".join(summary["issues"][:3]))
    pool = summary["candidates"] if conflict_only else summary["active"]
    target = next((g for g in pool if g["waiver_id"] == exact_id), None)
    if target is None:
        return None
    record = {"event": "revoke", "waiver_id": exact_id, "epoch": int(current), "ts": _iso(current),
              "reason": why, "confirmed_by": who}
    _append(root, record)
    after = audit_summary(root, now=current)
    if not after["valid"] and not all(issue.startswith("conflicting active grants") for issue in after["issues"]):
        raise ValueError("revoke appended but audit is now invalid: " + "; ".join(after["issues"][:3]))
    return record


def record_use(root, grant_record, report_path, now=None):
    current = time.time() if now is None else now
    waiver_id = _exact_id((grant_record or {}).get("waiver_id"), "waiver_id")
    summary = audit_summary(root, now=current)
    if not summary["valid"]:
        raise ValueError("acceptance waiver audit is invalid: " + "; ".join(summary["issues"][:3]))
    active = next((g for g in summary["active"] if g["waiver_id"] == waiver_id), None)
    if active is None:
        raise ValueError(f"waiver {waiver_id} is not active")
    if (active["cycle_stem"], active["acceptance_id"]) != (
            grant_record.get("cycle_stem"), grant_record.get("acceptance_id")):
        raise ValueError("waiver use scope does not match active grant")
    record = {"event": "use", "waiver_id": waiver_id, "cycle_stem": active["cycle_stem"],
              "acceptance_id": active["acceptance_id"], "report_path": _required_text(report_path, "report_path"),
              "epoch": int(current), "ts": _iso(current)}
    _append(root, record)
    after = audit_summary(root, now=current)
    if not after["valid"]:
        raise ValueError("use appended but audit is now invalid: " + "; ".join(after["issues"][:3]))
    return record
