"""Pure deterministic PDCA cycle identity binding.

The canonical identity is the phase document basename plus one matching
``Cycle-Stem: <stem>`` declaration. Branch numbers and file mtimes are never
cycle identity signals.
"""

import fnmatch
import os
import posixpath
import re


_DECL_RE = re.compile(r"(?im)^\s*Cycle-Stem\s*:\s*(.+?)\s*$")
_DECL_LABEL_RE = re.compile(r"(?im)^\s*Cycle-Stem\s*:")


def non_fenced_lines(content):
    """Yield Markdown lines outside backtick/tilde fenced code blocks."""
    in_fence = False
    fence_char = ""
    fence_len = 0
    for raw in (content or "").splitlines():
        marker = re.match(r"^\s{0,3}(`{3,}|~{3,})", raw)
        if marker:
            token = marker.group(1)
            if not in_fence:
                in_fence, fence_char, fence_len = True, token[0], len(token)
            elif token[0] == fence_char and len(token) >= fence_len:
                in_fence = False
            continue
        if in_fence:
            continue
        # Markdown also treats a tab or four leading spaces as an indented code
        # block. Structured governance declarations must never bind from code.
        if raw.startswith("\t") or re.match(r"^ {4,}", raw):
            continue
        yield raw


def declaration_label_present(content):
    return any(_DECL_LABEL_RE.match(line) for line in non_fenced_lines(content))


def normalize_stem(value):
    """Return a safe canonical stem or ``None``."""
    if not isinstance(value, str):
        return None
    stem = value.strip()
    if len(stem) >= 2 and stem[0] == stem[-1] == "`":
        stem = stem[1:-1].strip()
    if (not stem or len(stem) > 160 or stem in (".", "..")
            or "/" in stem or "\\" in stem
            or any(ord(ch) < 32 or ord(ch) == 127 for ch in stem)):
        return None
    return stem


def declared_stem(content):
    """Parse exactly one valid Cycle-Stem declaration -> (stem, error)."""
    matches = _DECL_RE.findall("\n".join(non_fenced_lines(content)))
    if not matches:
        return None, "Cycle-Stem declaration missing"
    if len(matches) != 1:
        return None, f"Cycle-Stem declaration must appear exactly once (found {len(matches)})"
    stem = normalize_stem(matches[0])
    if stem is None:
        return None, f"invalid Cycle-Stem: {matches[0]!r}"
    return stem, None


def path_stem(path):
    name = os.path.basename((path or "").rstrip("/"))
    if name.lower().endswith(".md"):
        name = name[:-3]
    return normalize_stem(name)


def matches_glob(path, pattern):
    """Match path globs with filesystem-style path segments and recursive ``**``."""
    def canonical(value):
        raw = (value or "").replace("\\", "/")
        if not raw or raw.startswith("/"):
            return ""
        normalized = posixpath.normpath(raw)
        if normalized in ("", ".", "..") or normalized.startswith("../"):
            return ""
        return normalized

    value = canonical(path)
    candidate = canonical(pattern)
    if not value or not candidate:
        return False
    path_parts = value.split("/")
    pattern_parts = candidate.split("/")
    memo = {}

    def match(pi, vi):
        key = (pi, vi)
        if key in memo:
            return memo[key]
        if pi == len(pattern_parts):
            result = vi == len(path_parts)
        elif pattern_parts[pi] == "**":
            result = match(pi + 1, vi) or (vi < len(path_parts) and match(pi, vi + 1))
        else:
            result = (vi < len(path_parts)
                      and fnmatch.fnmatchcase(path_parts[vi], pattern_parts[pi])
                      and match(pi + 1, vi + 1))
        memo[key] = result
        return result

    return match(0, 0)


def _snapshot_doc(snapshot, path):
    matches = []
    for docs in (snapshot.get("phase_docs") or {}).values():
        matches.extend(doc for doc in (docs or []) if doc.get("path") == path)
    if len(matches) > 1:
        return None, f"multiple snapshot documents for changed path: {path}"
    return (matches[0], None) if matches else (None, None)


def document_identity(doc):
    """Validate one phase document's path/declaration -> (stem, error)."""
    path = (doc or {}).get("path") or ""
    from_path = path_stem(path)
    declared, error = declared_stem((doc or {}).get("content") or "")
    if error:
        return None, f"{path or '<unknown>'}: {error}"
    if from_path is None:
        return None, f"{path or '<unknown>'}: invalid markdown path stem"
    if from_path != declared:
        return None, f"{path}: path stem {from_path!r} != Cycle-Stem {declared!r}"
    return declared, None


def effective_content(change):
    """이 변경 후의 문서 본문. post-image → 변경이 실은 전체 본문인 경우 → 조각 순서.

    조각(부분 diff)만 읽으면 hunk 없는 rename 의 목적지가 빈 문자열이 되어, 내용이 그대로
    옮겨가는 정상 rename 이 `Cycle-Stem:` 을 읽지 못해 결속에서 막힌다. 오케스트레이터가
    되짚어 만든 전체 본문이 있으면 그것이 이 변경 뒤의 사실이므로 먼저 쓴다. 재구성이
    실패했으면 post-image 자체가 없고, 그 실패는 본문 게이트가 fail-closed 로 잡는다.
    """
    image = (change or {}).get("post_image")
    if image is not None:
        return image
    return (change or {}).get("content") or ""


def resolve(event, snapshot, pdca):
    """Resolve exactly one current cycle -> {stem, error, source}.

    Phase writes bind from the changed path and declaration. Existing phase
    files may use their snapshot declaration when an update patch does not
    contain that unchanged line. Non-phase writes use an explicit event stem,
    otherwise the exact final branch segment.
    """
    candidates = set()
    errors = []
    sources = []
    phase_globs = [item.get("glob") or "" for item in ((pdca or {}).get("phases") or [])]
    phase_changes = []
    for change in event.get("changes") or []:
        path = change.get("path") or ""
        if any(matches_glob(path, pattern) for pattern in phase_globs if pattern):
            phase_changes.append(change)

    explicit = normalize_stem(event.get("cycle_stem")) if event.get("cycle_stem") else None
    if event.get("cycle_stem") and explicit is None:
        errors.append(f"invalid explicit cycle stem: {event.get('cycle_stem')!r}")

    if phase_changes:
        if explicit:
            candidates.add(explicit)
            sources.append("event")
        for change in phase_changes:
            path = change.get("path") or ""
            from_path = path_stem(path)
            if from_path is None:
                errors.append(f"{path}: invalid phase document path stem")
                continue
            content = effective_content(change)
            removed = change.get("removed_content") or ""
            event_decl, event_error = declared_stem(content)
            if event_error:
                declaration_touched = (declaration_label_present(content)
                                       or declaration_label_present(removed))
                full_write = change.get("op") in ("add", "write") or bool(change.get("full_content"))
                if change.get("op") != "delete" and (full_write or declaration_touched):
                    errors.append(f"{path}: {event_error}")
                    continue
                existing, snapshot_error = _snapshot_doc(snapshot, path)
                if snapshot_error:
                    errors.append(snapshot_error)
                    continue
                if existing is None:
                    errors.append(f"{path}: {event_error}")
                    continue
                event_decl, snapshot_error = document_identity(existing)
                if snapshot_error:
                    errors.append(snapshot_error)
                    continue
            if from_path != event_decl:
                errors.append(f"{path}: path stem {from_path!r} != Cycle-Stem {event_decl!r}")
                continue
            candidates.add(from_path)
            sources.append(path)
    else:
        if explicit:
            candidates.add(explicit)
            sources.append("event")
        else:
            branch = (event.get("branch") or "").strip().rstrip("/")
            leaf = normalize_stem(branch.rsplit("/", 1)[-1]) if branch else None
            if leaf:
                candidates.add(leaf)
                sources.append("branch-leaf")
            else:
                errors.append("explicit cycle stem and valid branch leaf are unavailable")

    if errors:
        return {"stem": None, "error": "; ".join(errors[:4]), "source": sources}
    if len(candidates) != 1:
        return {"stem": None,
                "error": f"cycle stem candidate count must be 1 (found {sorted(candidates)!r})",
                "source": sources}
    return {"stem": next(iter(candidates)), "error": None, "source": sources}


def select_document(docs, stem):
    """Select exactly one phase doc bound to ``stem`` -> (doc, error)."""
    candidates = [doc for doc in (docs or []) if path_stem(doc.get("path") or "") == stem]
    if not candidates:
        return None, f"no phase document for Cycle-Stem {stem!r}"
    if len(candidates) != 1:
        paths = sorted(doc.get("path") or "" for doc in candidates)
        return None, f"ambiguous phase documents for Cycle-Stem {stem!r}: {paths}"
    identity, error = document_identity(candidates[0])
    if error:
        return None, error
    if identity != stem:
        return None, f"document Cycle-Stem {identity!r} != current {stem!r}"
    return candidates[0], None


def any_document(docs, stem):
    """Whether at least one valid document binds to stem (cross-phase list)."""
    for doc in docs or []:
        if path_stem(doc.get("path") or "") != stem:
            continue
        identity, error = document_identity(doc)
        if error is None and identity == stem:
            return True
    return False
