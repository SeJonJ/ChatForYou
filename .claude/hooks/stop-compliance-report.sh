#!/bin/bash
# stop-compliance-report.sh
# Stop hook: 세션 종료 시 compliance 요약 생성
#
# 체크 항목:
#   1. 백엔드 소스 수정 vs plan 문서 활동
#   2. frontend-js 수정 vs plan 문서 활동
#   3. L3 패턴 파일 수정 감지
# 결과: .claude/logs/compliance-YYYY-MM-DD.md

PROJECT_ROOT="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null)}"
LOG_DIR="$PROJECT_ROOT/.claude/logs"
TODAY=$(date +%Y-%m-%d)
LOG_FILE="$LOG_DIR/session-$TODAY.jsonl"

# 로그 없으면 리포트 생략
[ -f "$LOG_FILE" ] || exit 0

REPORT="$LOG_DIR/compliance-$TODAY.md"

# python3 로 JSONL 분석
python3 - <<EOF >> "$REPORT"
import json, sys
from collections import defaultdict
from datetime import datetime

log_file = "$LOG_FILE"
today = "$TODAY"

entries = []
with open(log_file) as f:
    for line in f:
        line = line.strip()
        if not line:
            continue
        try:
            entries.append(json.loads(line))
        except:
            pass

# 유형별 집계
by_type = defaultdict(list)
for e in entries:
    by_type[e.get('type', 'other')].append(e.get('file', ''))

backend_files = by_type['backend-main']
test_files    = by_type['backend-test']
frontend_files= by_type['frontend-js'] + by_type['frontend-server'] + by_type['frontend-config']
plan_files    = by_type['plan-doc']

# L3 패턴 감지
L3_PATTERNS = ['kurento', 'webrtc', 'signaling', 'roommanager', 'datachannel', 'icecandidate', 'sdpoffer']
l3_files = [f for f in backend_files + frontend_files
            if any(p in f.lower() for p in L3_PATTERNS)]

# 브랜치
branches = list({e.get('branch', '') for e in entries if e.get('branch')})
branch = branches[0] if branches else 'unknown'

print(f"# Compliance Report — {today}")
print(f"Branch: {branch}  |  Total tool calls logged: {len(entries)}")
print()

print("## Activity Summary")
print(f"| 구분 | 파일 수 |")
print(f"|---|---|")
print(f"| Backend src/main | {len(set(backend_files))} |")
print(f"| Backend src/test | {len(set(test_files))} |")
print(f"| Frontend JS/server | {len(set(frontend_files))} |")
print(f"| Plan docs | {len(set(plan_files))} |")
print()

print("## Gate Compliance")

issues = []

# 백엔드 수정 + plan 없음
if backend_files and not plan_files:
    issues.append("⚠️  백엔드 소스 수정이 있었으나 plan_docs 활동 없음 (L2 gate 참조)")

# L3 파일 감지
if l3_files:
    issues.append(f"🔴 L3 패턴 파일 수정 감지: {', '.join(set(l3_files))}")
    issues.append("   → docs/agent/webrtc-review-protocol.md 2라운드 리뷰 완료 여부 확인 필요")

# convention checker 실행 여부는 로그로 추적 불가 → 안내 메시지
if backend_files:
    issues.append("ℹ️  백엔드 변경: convention checker 실행 여부를 수동으로 확인하세요")
    issues.append("   → backend-convention-checker 에이전트 실행 권장")

if not issues:
    print("✅ 감지된 위반 없음")
else:
    for issue in issues:
        print(issue)

print()
print("## Modified Files")

all_files = list({e.get('file', '') for e in entries if e.get('file')})
for f in sorted(all_files):
    print(f"- {f}")
EOF

echo "📋 Compliance report saved: .claude/logs/compliance-$TODAY.md"
exit 0
