#!/bin/bash
# post-tool-logger.sh
# PostToolUse hook: Write/Edit 완료 시 세션 로그에 기록
#
# 로그 형식: .claude/logs/session-YYYY-MM-DD.jsonl (JSONL)

PROJECT_ROOT="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null)}"
LOG_DIR="$PROJECT_ROOT/.claude/logs"
mkdir -p "$LOG_DIR"

INPUT=$(cat)

FILE_PATH=$(echo "$INPUT" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print(d.get('tool_input', {}).get('file_path', ''))
" 2>/dev/null)

TOOL_NAME=$(echo "$INPUT" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print(d.get('tool_name', ''))
" 2>/dev/null)

SESSION_ID=$(echo "$INPUT" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print(d.get('session_id', ''))
" 2>/dev/null)

# 소스 파일 / plan 파일만 기록 (node_modules 등 제외)
case "$FILE_PATH" in
    */springboot-backend/src/*|\
    */nodejs-frontend/static/js/*|\
    */nodejs-frontend/server.js|\
    */nodejs-frontend/config/*|\
    */plan_docs/*)
        ;;  # 기록 대상
    *)
        exit 0 ;;
esac

TODAY=$(date +%Y-%m-%d)
TIMESTAMP=$(date -u +%Y-%m-%dT%H:%M:%SZ)
BRANCH=$(git -C "$PROJECT_ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null)
FILE_SHORT="${FILE_PATH#$PROJECT_ROOT/}"

# 파일 유형 분류 (compliance report 에서 활용)
FILE_TYPE="other"
case "$FILE_PATH" in
    */springboot-backend/src/main/java/*) FILE_TYPE="backend-main" ;;
    */springboot-backend/src/test/java/*) FILE_TYPE="backend-test" ;;
    */nodejs-frontend/static/js/*)        FILE_TYPE="frontend-js"  ;;
    */nodejs-frontend/server.js)          FILE_TYPE="frontend-server" ;;
    */nodejs-frontend/config/*)           FILE_TYPE="frontend-config" ;;
    */plan_docs/*)                        FILE_TYPE="plan-doc"     ;;
esac

LOG_ENTRY="{\"ts\":\"$TIMESTAMP\",\"tool\":\"$TOOL_NAME\",\"file\":\"$FILE_SHORT\",\"type\":\"$FILE_TYPE\",\"branch\":\"$BRANCH\",\"session\":\"$SESSION_ID\"}"
echo "$LOG_ENTRY" >> "$LOG_DIR/session-$TODAY.jsonl"

exit 0
