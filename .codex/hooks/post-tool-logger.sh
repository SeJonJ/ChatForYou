#!/bin/bash
# post-tool-logger.sh
# PostToolUse hook: apply_patch 완료 시 소스/plan 파일 변경을 세션 로그에 기록.

PROJECT_ROOT="${CODEX_PROJECT_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null)}"
LOG_DIR="$PROJECT_ROOT/.codex/logs"
mkdir -p "$LOG_DIR"

INPUT=$(cat)

CODEX_HOOK_INPUT="$INPUT" python3 - "$PROJECT_ROOT" "$LOG_DIR" <<'PYEOF'
import json
import os
import re
import subprocess
import sys
import time

root, log_dir = sys.argv[1], sys.argv[2]
try:
    d = json.loads(os.environ.get("CODEX_HOOK_INPUT", "{}"))
except Exception:
    sys.exit(0)

if (d.get("tool_name") or "") != "apply_patch":
    sys.exit(0)

command = ((d.get("tool_input") or {}).get("command") or "")
session = d.get("session_id", "") or ""

paths = []
for line in command.splitlines():
    m = re.match(r"^\*\*\* (?:Add|Update|Delete) File: (.+)$", line)
    if not m:
        m = re.match(r"^\*\*\* Move to: (.+)$", line)
    if m:
        p = m.group(1).strip()
        if not os.path.isabs(p):
            p = os.path.join(root, p)
        paths.append(os.path.normpath(p))

def rel(p):
    try:
        return os.path.relpath(p, root)
    except Exception:
        return p

def file_type(short):
    if re.match(r"springboot-backend/src/main/java/", short):
        return "backend-main"
    if re.match(r"springboot-backend/src/test/java/", short):
        return "backend-test"
    if re.match(r"nodejs-frontend/static/js/", short):
        return "frontend-js"
    if short == "nodejs-frontend/server.js":
        return "frontend-server"
    if re.match(r"nodejs-frontend/config/", short):
        return "frontend-config"
    if re.match(r"plan_docs/", short):
        return "plan-doc"
    return ""

try:
    if os.environ.get("CODEX_GATE_BRANCH"):
        raise RuntimeError("branch override")
    branch = subprocess.check_output(
        ["git", "-C", root, "rev-parse", "--abbrev-ref", "HEAD"],
        stderr=subprocess.DEVNULL,
        text=True,
    ).strip()
except Exception:
    branch = os.environ.get("CODEX_GATE_BRANCH", "unknown")

today = time.strftime("%Y-%m-%d")
ts = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
out_path = os.path.join(log_dir, f"session-{today}.jsonl")

with open(out_path, "a", encoding="utf-8") as out:
    for p in paths:
        short = rel(p)
        typ = file_type(short)
        if not typ:
            continue
        out.write(json.dumps({
            "ts": ts,
            "tool": "apply_patch",
            "file": short,
            "type": typ,
            "branch": branch,
            "session": session,
        }, ensure_ascii=False) + "\n")
PYEOF
exit 0
