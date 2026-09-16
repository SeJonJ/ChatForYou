"""Deterministic Phase 00 Done Criteria parsing and approval binding helpers."""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass
from typing import Literal


_FENCE = re.compile(r"^\s{0,3}(`{3,}|~{3,})")
_TASK = re.compile(r"^\s*-\s+\[([ xX~])\]\s*(.*?)\s*$")
_TASK_LIKE = re.compile(r"^\s*(?:[-*+]|[0-9]+\.)\s*\[([^\]]*)\]")
_REVISION = re.compile(r"^Done-Criteria-Revision:\s*([1-9][0-9]*)\s*$")
_REVISION_PREFIX = re.compile(r"^Done-Criteria-Revision:")
_NA = re.compile(r"^(.*?)\s+\(N/A:\s*(.*?)\)\s*$")
_REVISION_FIELDS = ("Changed-At", "Reason", "Affected-Phases", "Summary")


@dataclass(frozen=True)
class DoneCriteriaItem:
    line: int
    state: Literal["pending", "done", "na"]
    text: str
    na_reason: str | None = None


@dataclass(frozen=True)
class DoneCriteriaRevision:
    number: int
    changed_at: str
    reason: str
    affected_phases: tuple[str, ...]
    summary: str


@dataclass(frozen=True)
class DoneCriteriaResult:
    status: Literal["valid", "invalid"]
    items: tuple[DoneCriteriaItem, ...]
    unresolved: tuple[DoneCriteriaItem, ...]
    issues: tuple[str, ...]
    revision: int | None
    latest_revision: DoneCriteriaRevision | None


def phase00_text_hash(content: str) -> str:
    """Hash the whole Phase 00 text, normalizing only transport line endings."""
    if not isinstance(content, str):
        raise TypeError("Phase 00 content must be text")
    normalized = content.replace("\r\n", "\n").replace("\r", "\n")
    return "sha256:" + hashlib.sha256(normalized.encode("utf-8")).hexdigest()


def _without_html_comments(line: str, in_comment: bool) -> tuple[str, bool]:
    output = []
    rest = line
    while rest:
        if in_comment:
            end = rest.find("-->")
            if end < 0:
                return "".join(output), True
            rest = rest[end + 3:]
            in_comment = False
            continue
        start = rest.find("<!--")
        if start < 0:
            output.append(rest)
            break
        output.append(rest[:start])
        rest = rest[start + 4:]
        in_comment = True
    return "".join(output), in_comment


def _visible_lines(content: str) -> list[tuple[int, str]]:
    visible = []
    in_fence = False
    fence_char = ""
    fence_len = 0
    in_comment = False
    for line_no, raw in enumerate(content.splitlines(), 1):
        fence = _FENCE.match(raw)
        if fence:
            token = fence.group(1)
            if not in_fence:
                in_fence, fence_char, fence_len = True, token[0], len(token)
            elif token[0] == fence_char and len(token) >= fence_len:
                in_fence = False
            continue
        if in_fence:
            continue
        line, in_comment = _without_html_comments(raw, in_comment)
        if not line or line.startswith("\t") or re.match(r"^ {4,}\S", line):
            continue
        visible.append((line_no, line.rstrip()))
    return visible


def _section(lines: list[tuple[int, str]], heading: str, max_level: int,
             *, start: int = 0, end: int | None = None) -> tuple[list[tuple[int, str]], list[str]]:
    end = len(lines) if end is None else end
    matches = [index for index in range(start, end) if lines[index][1] == heading]
    if len(matches) != 1:
        return [], [f"{heading!r} must appear exactly once outside Markdown fences (found {len(matches)})"]
    begin = matches[0] + 1
    finish = end
    heading_re = re.compile(rf"^#{{1,{max_level}}}\s+")
    for index in range(begin, end):
        if heading_re.match(lines[index][1]):
            finish = index
            break
    return lines[begin:finish], []


def _revision_value(lines: list[tuple[int, str]]) -> tuple[int | None, list[str]]:
    exact = []
    malformed = []
    for line_no, line in lines:
        match = _REVISION.fullmatch(line)
        if match:
            exact.append((line_no, int(match.group(1))))
        elif _REVISION_PREFIX.match(line):
            malformed.append(line_no)
    issues = []
    if len(exact) != 1:
        issues.append("Done-Criteria-Revision must appear exactly once as a positive integer "
                      f"(found {len(exact)})")
    if malformed:
        issues.append("Done-Criteria-Revision is malformed at line(s) " +
                      ", ".join(str(line) for line in malformed))
    return (exact[0][1] if len(exact) == 1 else None), issues


def document_revision(content: str) -> tuple[int | None, tuple[str, ...]]:
    """Read one exact revision declaration from any bound phase document."""
    if not isinstance(content, str):
        return None, ("phase document content must be text",)
    revision, issues = _revision_value(_visible_lines(content))
    return revision, tuple(issues)


def _revision_log(lines: list[tuple[int, str]], revision: int | None,
                  mode: str) -> tuple[DoneCriteriaRevision | None, list[str]]:
    if revision is None or revision == 1:
        return None, []
    log_heading = "## 6. Done Criteria Revision Log" if mode == "standard" else "### Done Criteria Revision Log"
    entry_heading = ("###" if mode == "standard" else "####") + f" Revision {revision}"
    log_indexes = [index for index, (_line_no, line) in enumerate(lines) if line == log_heading]
    if len(log_indexes) != 1:
        return None, [f"{log_heading!r} must appear exactly once for revision {revision}"]
    entry_indexes = [index for index, (_line_no, line) in enumerate(lines) if line == entry_heading]
    if len(entry_indexes) != 1 or entry_indexes[0] <= log_indexes[0]:
        return None, [f"latest revision entry {entry_heading!r} must appear exactly once in the revision log"]

    start = entry_indexes[0] + 1
    level = 3 if mode == "standard" else 4
    stop_re = re.compile(rf"^#{{1,{level}}}\s+")
    fields: dict[str, str] = {}
    issues = []
    for line_no, line in lines[start:]:
        if stop_re.match(line):
            break
        match = re.fullmatch(r"- (Changed-At|Reason|Affected-Phases|Summary):\s*(.*?)\s*", line)
        if not match:
            continue
        key, value = match.groups()
        if key in fields:
            issues.append(f"duplicate revision field {key} at line {line_no}")
        fields[key] = value.strip()
    for key in _REVISION_FIELDS:
        if not fields.get(key):
            issues.append(f"revision {revision} requires non-empty {key}")

    affected: tuple[str, ...] = ()
    if fields.get("Affected-Phases"):
        values = tuple(value.strip() for value in fields["Affected-Phases"].split(","))
        allowed = {f"{number:02d}" for number in range(1, 6)}
        if (any(value not in allowed for value in values)
                or len(values) != len(set(values))
                or values != tuple(sorted(values))):
            issues.append("Affected-Phases must be unique ascending values from 01 through 05")
        else:
            affected = values
    if fields.get("Changed-At") and not re.fullmatch(r"Phase (00|01|02|03|04|05)", fields["Changed-At"]):
        issues.append("Changed-At must be exactly 'Phase 00' through 'Phase 05'")
    if issues:
        return None, issues
    return DoneCriteriaRevision(
        number=revision,
        changed_at=fields["Changed-At"],
        reason=fields["Reason"],
        affected_phases=affected,
        summary=fields["Summary"],
    ), []


def _items(section: list[tuple[int, str]]) -> tuple[list[DoneCriteriaItem], list[str]]:
    items = []
    issues = []
    identities = set()
    for line_no, line in section:
        match = _TASK.fullmatch(line)
        if not match:
            task_like = _TASK_LIKE.match(line)
            if task_like:
                state = task_like.group(1)
                if state not in (" ", "x", "X", "~"):
                    issues.append(f"line {line_no}: unknown task state [{state}]")
                else:
                    issues.append(
                        f"line {line_no}: task item must use '- [ ]', '- [x]', or '- [~]' syntax")
            continue
        marker, body = match.groups()
        body = body.strip()
        if not body:
            issues.append(f"line {line_no}: Done Criteria item text is empty")
            continue
        state: Literal["pending", "done", "na"]
        reason = None
        text = body
        if marker == " ":
            state = "pending"
        elif marker in ("x", "X"):
            state = "done"
        else:
            state = "na"
            na = _NA.fullmatch(body)
            if not na or not na.group(1).strip() or not na.group(2).strip():
                issues.append(f"line {line_no}: [~] requires same-line non-empty (N/A: reason)")
                continue
            text, reason = na.group(1).strip(), na.group(2).strip()
        identity = " ".join(text.split()).casefold()
        if identity in identities:
            issues.append(f"line {line_no}: duplicate Done Criteria item {text!r}")
            continue
        identities.add(identity)
        items.append(DoneCriteriaItem(line_no, state, text, reason))
    if not items:
        issues.append("Done Criteria section must contain at least one valid task item")
    return items, issues


def parse_done_criteria(content: str, *, mode: Literal["standard", "fast"]) -> DoneCriteriaResult:
    issues = []
    if not isinstance(content, str):
        return DoneCriteriaResult("invalid", (), (), ("Phase 00 content must be text",), None, None)
    if mode not in ("standard", "fast"):
        return DoneCriteriaResult("invalid", (), (), (f"unknown Done Criteria mode: {mode!r}",), None, None)

    lines = _visible_lines(content)
    revision, revision_issues = _revision_value(lines)
    issues.extend(revision_issues)
    latest_revision, log_issues = _revision_log(lines, revision, mode)
    issues.extend(log_issues)

    if mode == "standard":
        section, section_issues = _section(lines, "## 5. Done Criteria", 2)
    else:
        from fast_cycle_contract import parse_fast_plan
        plan, fast_issues = parse_fast_plan(content)
        if fast_issues:
            issues.extend("Fast Plan: " + issue for issue in fast_issues)
            section = []
            section_issues = []
        else:
            # The Fast parser owns Phase boundaries and intentionally accepts the
            # shipped `## Phase 00 — Base Plan` style headings. Parse only its
            # projected Phase 00 body so this contract cannot drift from it.
            phase00_lines = _visible_lines(plan.sections.get("00", ""))
            section, section_issues = _section(
                phase00_lines, "### Done Criteria", 3)
    issues.extend(section_issues)
    items, item_issues = _items(section) if not section_issues else ([], [])
    issues.extend(item_issues)
    unresolved = tuple(item for item in items if item.state == "pending")
    return DoneCriteriaResult(
        "invalid" if issues else "valid",
        tuple(items), unresolved, tuple(issues), revision, latest_revision)
