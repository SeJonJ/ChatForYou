#!/bin/bash
# capture-declared-risk.sh
# UserPromptSubmit hook: 유저 프롬프트에서 명시적 risk level 선언을 포착해 세션별 저장.

PROJECT_ROOT="${CODEX_PROJECT_ROOT:-/Users/sejon/project/ChatForYou_v2}"
LOG_DIR="$PROJECT_ROOT/.codex/logs"
mkdir -p "$LOG_DIR"

INPUT=$(cat)

CODEX_HOOK_INPUT="$INPUT" python3 - "$LOG_DIR" <<'PYEOF'
import glob
import json
import os
import re
import sys
import time

log_dir = sys.argv[1]
try:
    d = json.loads(os.environ.get("CODEX_HOOK_INPUT", "{}"))
except Exception:
    sys.exit(0)

prompt = d.get("prompt", "") or ""
session = (d.get("session_id", "") or "nosession")
session = re.sub(r"[^A-Za-z0-9_-]", "_", session)[:64]

now = time.time()
for f in glob.glob(os.path.join(log_dir, "declared-risk-*.json")):
    try:
        if now - os.path.getmtime(f) > 2 * 86400:
            os.remove(f)
    except Exception:
        pass

levels = []
for m in re.finditer(r"(?i)\bL([0-3])\s*(으?로|로|레벨|수준|작업|개발|진행|이야|야|입니다|처리|로\s*개발|로\s*진행)", prompt):
    levels.append(int(m.group(1)))
for m in re.finditer(r"(?i)(?:risk\s*level|리스크\s*(?:레벨)?|레벨)\s*([0-3])", prompt):
    levels.append(int(m.group(1)))

if not levels:
    sys.exit(0)

level = max(levels)
state = {
    "level": f"L{level}",
    "ts": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    "excerpt": prompt[:120].replace("\n", " "),
}
path = os.path.join(log_dir, f"declared-risk-{session}.json")
try:
    with open(path, "w", encoding="utf-8") as f:
        json.dump(state, f, ensure_ascii=False)
    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "UserPromptSubmit",
            "additionalContext": f"[Risk 선언 포착] 이번 세션 작업 레벨: L{level}. 소스 수정 시 해당 레벨 게이트가 적용됩니다."
        }
    }, ensure_ascii=False))
except Exception:
    pass
PYEOF
exit 0
