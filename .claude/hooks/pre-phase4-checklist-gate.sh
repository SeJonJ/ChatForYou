#!/bin/bash
# pre-phase4-checklist-gate.sh
# PreToolUse hook (Write|Edit|MultiEdit): Phase 3 → Phase 4 전환 게이트
#
# 04-analyze 문서 작성 = Phase 4 진입 신호.
# 이 시점에 해당 기능의 03-implementation + 컴포넌트 plan_docs 체크리스트에
# 미완료 항목(- [ ])이 있으면 차단한다.
#
# EXIT 2 → 차단 (미완료 체크리스트 존재)
# EXIT 0 → 통과

PROJECT_ROOT="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null)}"

INPUT=$(cat)
FILE_PATH=$(echo "$INPUT" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    print((d.get('tool_input', {}) or {}).get('file_path', '') or '')
except Exception:
    print('')
" 2>/dev/null)

# 04-analyze 문서 작성만 대상
case "$FILE_PATH" in
    */plan_docs/04-analyze/*.md) ;;
    *) exit 0 ;;
esac

python3 - "$PROJECT_ROOT" "$FILE_PATH" <<'PYEOF'
import os, sys, glob, re

ROOT, four_path = sys.argv[1], sys.argv[2]

stem = os.path.basename(four_path)
if stem.endswith('.md'):
    stem = stem[:-3]

# 04 파일명에서 기능 base 추출 (접미사 반복 제거)
SUFFIXES = [
    '_backend_eval','_frontend_eval','_external_eval','_qa_eval',
    '_backend_feedback','_frontend_feedback','_external_feedback','_qa_feedback',
    '-gap','_gap','-analysis','_analysis','-analyze','_analyze',
    '_eval','_feedback','_plan','_report',
]
base = stem
changed = True
while changed:
    changed = False
    for suf in SUFFIXES:
        if base.endswith(suf) and len(base) > len(suf):
            base = base[:-len(suf)]
            changed = True

def stem_of(p):
    b = os.path.basename(p)
    return b[:-3] if b.endswith('.md') else b

def find_match(directory):
    """base 와 가장 잘 맞는 .md 파일 경로 반환 (없으면 None)"""
    if not os.path.isdir(directory):
        return None
    exact = os.path.join(directory, base + '.md')
    if os.path.exists(exact):
        return exact
    # prefix 매칭: 양방향
    best = None
    for c in sorted(glob.glob(os.path.join(directory, '*.md'))):
        cs = stem_of(c)
        if base == cs or base.startswith(cs) or cs.startswith(base):
            best = c
            break
    return best

def unchecked_items(path):
    """미완료 체크박스 (line_no, text) 목록"""
    out = []
    try:
        with open(path, encoding='utf-8') as f:
            for i, line in enumerate(f, 1):
                if re.match(r'^\s*-\s*\[\s\]', line):
                    out.append((i, line.strip()))
    except Exception:
        pass
    return out

targets = {
    "03-implementation": find_match(os.path.join(ROOT, 'plan_docs', '03-implementation')),
    "backend plan_docs": find_match(os.path.join(ROOT, 'springboot-backend', 'plan_docs')),
    "frontend plan_docs": find_match(os.path.join(ROOT, 'nodejs-frontend', 'plan_docs')),
}

impl_match = targets["03-implementation"]

# 미완료 수집
total_unchecked = 0
report_lines = []
for label, path in targets.items():
    if not path:
        continue
    items = unchecked_items(path)
    if items:
        total_unchecked += len(items)
        report_lines.append(f"  ▸ {label}: {os.path.basename(path)} ({len(items)}건 미완료)")
        for ln, txt in items[:6]:
            short = txt if len(txt) <= 90 else txt[:87] + '...'
            report_lines.append(f"      L{ln}: {short}")
        if len(items) > 6:
            report_lines.append(f"      ... 외 {len(items) - 6}건")

# ─── 판정 ───
if total_unchecked > 0:
    print(f"⛔ [GATE BLOCK — Phase 3→4] 체크리스트 미완료 {total_unchecked}건")
    print(f"  기능: {base}")
    print(f"  04-analyze 작성 전 아래 항목을 완료(또는 N/A 사유와 함께 [x] 처리)하세요:")
    for l in report_lines:
        print(l)
    print("  (AGENT_GUIDE §4.1 — 검증 실행 후에만 체크박스 갱신)")
    sys.exit(2)

if impl_match is None:
    # 03 매칭 실패 — 차단까지는 아니지만 경고
    print(f"⚠️  [GATE WARN — Phase 3→4] '{base}' 의 03-implementation 문서를 찾지 못했습니다.")
    print(f"  04-analyze 작성 전 03-implementation 문서 존재/체크리스트 완료를 확인하세요.")
    print(f"  (파일명 규칙: plan_docs/03-implementation/{base}.md 권장)")
    sys.exit(0)

print(f"✅ [GATE OK — Phase 3→4] '{base}' 체크리스트 완료 확인 ({os.path.basename(impl_match)})")
sys.exit(0)
PYEOF
exit $?
