#!/bin/bash
# stop-compliance-report.sh
# Stop hook: Codex 세션 종료 시 compliance 리포트 생성.

PROJECT_ROOT="${CODEX_PROJECT_ROOT:-/Users/sejon/project/ChatForYou_v2}"
LOG_DIR="$PROJECT_ROOT/.codex/logs"
TODAY=$(date +%Y-%m-%d)
LOG_FILE="$LOG_DIR/session-$TODAY.jsonl"

[ -f "$LOG_FILE" ] || exit 0

REPORT="$LOG_DIR/compliance-$TODAY.md"
INPUT=$(cat)

TRANSCRIPT=$(CODEX_HOOK_INPUT="$INPUT" python3 - <<'PYEOF'
import json
import os
try:
    d = json.loads(os.environ.get("CODEX_HOOK_INPUT", "{}"))
    print(d.get("transcript_path", "") or "")
except Exception:
    print("")
PYEOF
)

VAULT_ROOT=""
LOCAL_GUIDE="$PROJECT_ROOT/.local/local_agent_guide.md"
if [ -f "$LOCAL_GUIDE" ]; then
    VAULT_ROOT=$(grep -iE "^Vault root:" "$LOCAL_GUIDE" 2>/dev/null | head -1 | sed 's/^[Vv]ault root:[[:space:]]*//')
fi

python3 - "$LOG_FILE" "$TODAY" "$TRANSCRIPT" "$VAULT_ROOT" <<'PYEOF' >> "$REPORT"
import calendar
import json
import os
import sys
import time
from collections import defaultdict

log_file, today, transcript, vault_root = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]

entries = []
with open(log_file, encoding="utf-8") as f:
    for line in f:
        line = line.strip()
        if not line:
            continue
        try:
            entries.append(json.loads(line))
        except Exception:
            pass

by_type = defaultdict(list)
for e in entries:
    by_type[e.get("type", "other")].append(e.get("file", ""))

backend_files = by_type["backend-main"]
test_files = by_type["backend-test"]
frontend_files = by_type["frontend-js"] + by_type["frontend-server"] + by_type["frontend-config"]
plan_files = by_type["plan-doc"]
code_files = backend_files + test_files + frontend_files

l3_patterns = ["kurento", "webrtc", "signaling", "roommanager", "datachannel", "icecandidate", "sdpoffer"]
l3_files = [f for f in backend_files + frontend_files if any(p in f.lower() for p in l3_patterns)]
branches = list({e.get("branch", "") for e in entries if e.get("branch")})
branch = branches[0] if branches else "unknown"

def epoch_of_iso(ts):
    try:
        t = time.strptime(ts.replace("Z", ""), "%Y-%m-%dT%H:%M:%S")
        return calendar.timegm(t)
    except Exception:
        return None

def text_from_obj(obj):
    if isinstance(obj, str):
        return obj
    if not isinstance(obj, dict):
        return ""
    if isinstance(obj.get("last_assistant_message"), str):
        return obj["last_assistant_message"]
    if obj.get("role") == "assistant":
        content = obj.get("content")
        if isinstance(content, str):
            return content
        if isinstance(content, list):
            texts = []
            for item in content:
                if isinstance(item, dict):
                    texts.append(item.get("text") or item.get("content") or "")
            return "\n".join(t for t in texts if t)
    if obj.get("type") in ("assistant", "message"):
        msg = obj.get("message", {})
        if isinstance(msg, dict):
            return text_from_obj({"role": "assistant", "content": msg.get("content", "")})
    item = obj.get("response_item")
    if isinstance(item, dict):
        return text_from_obj(item)
    return ""

def last_assistant_text(path):
    if not path or not os.path.exists(path):
        return ""
    try:
        with open(path, encoding="utf-8", errors="ignore") as f:
            lines = f.readlines()
    except Exception:
        return ""
    for line in reversed(lines):
        try:
            obj = json.loads(line)
        except Exception:
            continue
        text = text_from_obj(obj)
        if text:
            return text
    return ""

print(f"# Compliance Report — {today}")
print(f"Branch: {branch}  |  Total tool calls logged: {len(entries)}")
print()

print("## 1. Activity Summary")
print("| 구분 | 파일 수 |")
print("|---|---|")
print(f"| Backend src/main | {len(set(backend_files))} |")
print(f"| Backend src/test | {len(set(test_files))} |")
print(f"| Frontend JS/server | {len(set(frontend_files))} |")
print(f"| Plan docs | {len(set(plan_files))} |")
print()

print("## 2. Gate Compliance")
issues = []
if backend_files and not plan_files:
    issues.append("WARN: 백엔드 소스 수정이 있었으나 plan_docs 활동 없음 (L2 gate 참조)")
if l3_files:
    issues.append(f"L3 패턴 파일 수정 감지: {', '.join(sorted(set(l3_files)))}")
    issues.append("   -> docs/agent/webrtc-review-protocol.md 2라운드 리뷰 완료 여부 확인 필요")
if backend_files:
    issues.append("INFO: 백엔드 변경: backend-convention-checker 실행 여부 확인 권장")
if not issues:
    print("OK: 감지된 위반 없음")
else:
    for issue in issues:
        print(issue)
print()

print("## 3. Output Contract")
if not code_files:
    print("N/A — 코드 변경 없음 (output contract 적용 대상 아님)")
elif not transcript or not os.path.exists(transcript):
    print("N/A — Codex transcript 접근 불가 또는 transcript_path 없음")
else:
    last_text = last_assistant_text(transcript)
    if not last_text:
        print("N/A — Codex transcript 마지막 assistant 텍스트 추출 실패")
    else:
        markers = {
            "Task Summary / 요약": ["task summary", "작업 요약", "요약"],
            "Risk Level": ["risk level", "risk", "위험", "l0", "l1", "l2", "l3"],
            "Impact (BE/FE/Desktop)": ["impact", "영향", "backend", "frontend", "desktop"],
            "Modified Files": ["modified files", "변경 파일", "수정 파일", "변경된 파일"],
            "Validation": ["validation", "검증", "build", "test", "gradlew"],
        }
        lt = last_text.lower()
        present = {k: any(t in lt for t in v) for k, v in markers.items()}
        missing = [k for k, ok in present.items() if not ok]
        hit = len(present) - len(missing)
        if hit >= 4:
            print(f"OK: 코드 변경 세션 — output contract 핵심 섹션 {hit}/5 충족")
        else:
            print(f"WARN: 코드 변경 세션인데 output contract 섹션 {hit}/5 만 감지")
            print(f"   누락 추정: {', '.join(missing)}")
            print("   -> docs/agent/output-contract.md 형식으로 최종 보고 작성 권장")
print()

print("## 4. Wiki Knowledge Capture")
if not code_files:
    print("N/A — 코드 변경 없음 (vault 캡처 대상 아님)")
elif not vault_root or not os.path.isdir(vault_root):
    print("N/A — vault 경로 접근 불가 (.local/local_agent_guide.md 의 Vault root 미설정)")
else:
    wiki_log = os.path.join(vault_root, "wiki", "log.md")
    code_ts = [
        epoch_of_iso(e.get("ts", ""))
        for e in entries
        if e.get("type") in ("backend-main", "backend-test", "frontend-js", "frontend-server", "frontend-config")
    ]
    code_ts = [t for t in code_ts if t]
    earliest = min(code_ts) if code_ts else None
    if not os.path.exists(wiki_log):
        print("WARN: vault wiki/log.md 없음 — 지식 캡처 상태 확인 불가")
    elif earliest is None:
        print("N/A — 코드 변경 시각 파싱 불가")
    elif os.path.getmtime(wiki_log) >= earliest:
        print("OK: 코드 변경 이후 vault wiki/log.md 갱신됨 (지식 캡처 추정 완료)")
    else:
        print("WARN: 코드 변경 후 vault wiki/log.md 미갱신 — 지식 캡처 누락 가능")
        print("   -> BUG/TECH/SPEC/POSTMORTEM trigger 해당 시 vault 노트 작성 필요")
print()

print("## Modified Files")
for f in sorted({e.get("file", "") for e in entries if e.get("file")}):
    print(f"- {f}")
print()
print("---")
PYEOF

python3 - "$TODAY" <<'PYEOF'
import json
import sys
today = sys.argv[1]
print(json.dumps({"systemMessage": f"Compliance report updated: .codex/logs/compliance-{today}.md"}, ensure_ascii=False))
PYEOF
exit 0
