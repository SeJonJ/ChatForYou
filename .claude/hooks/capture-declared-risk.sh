#!/bin/bash
# capture-declared-risk.sh
# UserPromptSubmit hook: 유저 프롬프트에서 명시적 risk level 선언을 포착해 세션별 저장.
#
# 포착된 레벨은 pre-implementation-gate.sh 가 읽어 effective level = max(감지, 선언)
# 으로 게이트를 적용한다. (선언은 상향만 가능 — 안전 바닥 유지)
#
# 저장: .claude/logs/declared-risk-<session>.json  ({level, ts, excerpt})

PROJECT_ROOT="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null)}"
LOG_DIR="$PROJECT_ROOT/.claude/logs"
mkdir -p "$LOG_DIR"

INPUT=$(cat)

CLAUDE_HOOK_INPUT="$INPUT" python3 - "$LOG_DIR" <<'PYEOF'
import sys, json, os, re, time, glob

log_dir = sys.argv[1]
try:
    d = json.loads(os.environ.get('CLAUDE_HOOK_INPUT', '{}'))
except Exception:
    sys.exit(0)

prompt = d.get('prompt', '') or ''
session = (d.get('session_id', '') or 'nosession')
session = re.sub(r'[^A-Za-z0-9_-]', '_', session)[:64]

# 오래된 선언 파일 정리 (2일 경과)
now = time.time()
for f in glob.glob(os.path.join(log_dir, 'declared-risk-*.json')):
    try:
        if now - os.path.getmtime(f) > 2 * 86400:
            os.remove(f)
    except Exception:
        pass

# ─── 명시적 레벨 선언 탐지 (보수적 패턴: 개발 의도 맥락 동반 시에만) ───
# 메타 대화("L3 게이트", "L2, L3 등")에서의 오탐을 피하기 위해
# 레벨 토큰 뒤에 개발/선언 의도 어구가 붙은 경우만 인정한다.
levels = []

# 패턴 1: L3로 / L2 작업 / L1 으로 개발 / L3야 ...
for m in re.finditer(r'(?i)\bL([0-3])\s*(으?로|로|레벨|수준|작업|개발|진행|이야|야|입니다|처리|로\s*개발|로\s*진행)', prompt):
    levels.append(int(m.group(1)))

# 패턴 2: "레벨 3", "리스크 레벨 2", "risk level 3"
for m in re.finditer(r'(?i)(?:risk\s*level|리스크\s*(?:레벨)?|레벨)\s*([0-3])', prompt):
    levels.append(int(m.group(1)))

if not levels:
    sys.exit(0)

level = max(levels)
state = {
    "level": f"L{level}",
    "ts": time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
    "excerpt": prompt[:120].replace('\n', ' '),
}
path = os.path.join(log_dir, f'declared-risk-{session}.json')
try:
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(state, f, ensure_ascii=False)
    # Claude 에게 컨텍스트로 전달
    print(f"ℹ️  [Risk 선언 포착] 이번 세션 작업 레벨: L{level} — 소스 수정 시 해당 레벨 게이트가 적용됩니다.")
except Exception:
    pass
PYEOF
exit 0
