#!/bin/bash
# pre-phase4-checklist-gate.sh
# PreToolUse hook (apply_patch): 04-analyze 작성 전 체크리스트 미완료 차단.

PROJECT_ROOT="${CODEX_PROJECT_ROOT:-/Users/sejon/project/ChatForYou_v2}"
INPUT=$(cat)

CODEX_HOOK_INPUT="$INPUT" python3 - "$PROJECT_ROOT" <<'PYEOF'
import glob
import json
import os
import re
import sys

root = sys.argv[1]
try:
    d = json.loads(os.environ.get("CODEX_HOOK_INPUT", "{}"))
except Exception:
    sys.exit(0)

if (d.get("tool_name") or "") != "apply_patch":
    sys.exit(0)

command = ((d.get("tool_input") or {}).get("command") or "")
paths = []
for line in command.splitlines():
    m = re.match(r"^\*\*\* (?:Add|Update) File: (.+)$", line)
    if m:
        p = m.group(1).strip()
        if not os.path.isabs(p):
            p = os.path.join(root, p)
        paths.append(os.path.normpath(p))

targets = [p for p in paths if re.search(r"/plan_docs/04-analyze/[^/]+\.md$", p)]
if not targets:
    sys.exit(0)

suffixes = [
    "_backend_eval", "_frontend_eval", "_external_eval", "_qa_eval",
    "_backend_feedback", "_frontend_feedback", "_external_feedback", "_qa_feedback",
    "-gap", "_gap", "-analysis", "_analysis", "-analyze", "_analyze",
    "_eval", "_feedback", "_plan", "_report",
]

def base_from(path):
    stem = os.path.basename(path)
    if stem.endswith(".md"):
        stem = stem[:-3]
    changed = True
    base = stem
    while changed:
        changed = False
        for suf in suffixes:
            if base.endswith(suf) and len(base) > len(suf):
                base = base[:-len(suf)]
                changed = True
    return base

def stem_of(path):
    b = os.path.basename(path)
    return b[:-3] if b.endswith(".md") else b

def find_match(directory, base):
    if not os.path.isdir(directory):
        return None
    exact = os.path.join(directory, base + ".md")
    if os.path.exists(exact):
        return exact
    for c in sorted(glob.glob(os.path.join(directory, "*.md"))):
        cs = stem_of(c)
        if base == cs or base.startswith(cs) or cs.startswith(base):
            return c
    return None

def unchecked_items(path):
    out = []
    if not path:
        return out
    try:
        with open(path, encoding="utf-8") as f:
            for i, line in enumerate(f, 1):
                if re.match(r"^\s*-\s*\[\s\]", line):
                    out.append((i, line.strip()))
    except Exception:
        pass
    return out

for four_path in targets:
    base = base_from(four_path)
    checks = {
        "03-implementation": find_match(os.path.join(root, "plan_docs", "03-implementation"), base),
        "backend plan_docs": find_match(os.path.join(root, "springboot-backend", "plan_docs"), base),
        "frontend plan_docs": find_match(os.path.join(root, "nodejs-frontend", "plan_docs"), base),
    }
    total = 0
    report = []
    for label, path in checks.items():
        items = unchecked_items(path)
        if not items:
            continue
        total += len(items)
        report.append(f"  - {label}: {os.path.basename(path)} ({len(items)}건 미완료)")
        for ln, txt in items[:6]:
            short = txt if len(txt) <= 90 else txt[:87] + "..."
            report.append(f"      L{ln}: {short}")
        if len(items) > 6:
            report.append(f"      ... 외 {len(items) - 6}건")
    if total:
        print("\n".join([
            f"[GATE BLOCK - Phase 3->4] 체크리스트 미완료 {total}건",
            f"  기능: {base}",
            "  04-analyze 작성 전 아래 항목을 완료(또는 N/A 사유와 함께 [x] 처리)하세요:",
            *report,
            "  (AGENT_GUIDE §4.1 — 검증 실행 후에만 체크박스 갱신)",
        ]), file=sys.stderr)
        sys.exit(2)
    if checks["03-implementation"] is None:
        print(json.dumps({
            "hookSpecificOutput": {
                "hookEventName": "PreToolUse",
                "additionalContext": f"[GATE WARN - Phase 3->4] '{base}' 의 03-implementation 문서를 찾지 못했습니다. 04-analyze 작성 전 문서 존재와 체크리스트 완료를 확인하세요."
            }
        }, ensure_ascii=False))
        sys.exit(0)

print(json.dumps({
    "hookSpecificOutput": {
        "hookEventName": "PreToolUse",
        "additionalContext": "[GATE OK - Phase 3->4] 체크리스트 완료 확인"
    }
}, ensure_ascii=False))
PYEOF
exit $?
