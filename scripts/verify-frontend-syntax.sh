#!/bin/bash
# verify-frontend-syntax.sh — 프론트엔드 전체 구문 검증 기준선 (CFY-FB-01)
#
# 목적: 변경 파일 한정(diff-scoped)이 아니라 프론트엔드 진입점 전체를 대상으로
#       node --check 구문 검증을 결정론적으로 반복 실행한다.
#       기존 오류는 baseline 으로 고정하고, baseline 에 없는 신규 오류만 실패로 보고한다.
#
# 대상: nodejs-frontend/server.js + nodejs-frontend/static/js/**/*.js
#       (nodejs-frontend/routes/ 는 존재하지 않아 실질 진입점만 검사)
#
# 모듈/스크립트 혼재: static/js 아래에는 ES 모듈로 전환된 파일과 아직 전역 <script>
#       로 로드되는 파일이 섞여 있다. node --check 는 확장자(.mjs/.cjs)로 파싱 목표를
#       정하므로, 각 파일을 두 확장자로 복사해 한쪽이라도 파싱되면 PASS 로 본다.
#
# 사용:
#   scripts/verify-frontend-syntax.sh              # 검증
#   scripts/verify-frontend-syntax.sh --update-baseline   # 현재 실패 목록을 baseline 으로 재고정
#
# EXIT:
#   0  → PASS (baseline 대비 신규 구문 오류 없음)
#   1  → 신규 구문 오류 검출 (baseline 에 없는 실패)
#   3  → node 부재 (검증 불가, degrade)

set -uo pipefail

PROJECT_ROOT="${CODEX_PROJECT_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null)}"
[ -z "$PROJECT_ROOT" ] && PROJECT_ROOT="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "$0")/.." && pwd)}"

FRONTEND_DIR="$PROJECT_ROOT/nodejs-frontend"
BASELINE_FILE="$PROJECT_ROOT/scripts/frontend-syntax-baseline.txt"

UPDATE_BASELINE=false
[ "${1:-}" = "--update-baseline" ] && UPDATE_BASELINE=true

if ! command -v node >/dev/null 2>&1; then
    echo "⚠️  node 부재 → 프론트 구문 검증 불가 (degrade)"
    exit 3
fi

# 대상 파일 목록: 정렬 고정으로 나열 순서 비결정성 제거. PROJECT_ROOT 기준 상대경로.
# (bash 3.2 호환 — mapfile/배열 미사용, 개행 구분 문자열로 처리)
TARGETS=$(
    { find "$FRONTEND_DIR/static/js" -type f -name '*.js';
      [ -f "$FRONTEND_DIR/server.js" ] && echo "$FRONTEND_DIR/server.js"; } \
    | while IFS= read -r absolute_path; do
          printf '%s\n' "${absolute_path#"$PROJECT_ROOT"/}"
      done \
    | LC_ALL=C sort
)
TARGET_COUNT=$(printf '%s\n' "$TARGETS" | grep -c . || true)

if [ "$TARGET_COUNT" -eq 0 ]; then
    echo "⚠️  검사 대상 프론트 JS 파일 없음"
    exit 3
fi

CHECK_DIR=$(mktemp -d)
trap 'rm -rf "$CHECK_DIR"' EXIT INT TERM

# ES 모듈 목표(.mjs)로 먼저 파싱하고, 실패하면 스크립트 목표(.cjs)로 재시도한다.
# 둘 다 실패해야 구문 오류로 판정한다.
check_syntax() {
    source_file="$1"
    cp "$source_file" "$CHECK_DIR/candidate.mjs"
    if node --check "$CHECK_DIR/candidate.mjs" >/dev/null 2>&1; then
        return 0
    fi
    cp "$source_file" "$CHECK_DIR/candidate.cjs"
    node --check "$CHECK_DIR/candidate.cjs" >/dev/null 2>&1
}

# 현재 구문 실패 파일 집합 수집 (상대경로, 정렬)
CURRENT_FAILS=""
while IFS= read -r rel; do
    [ -z "$rel" ] && continue
    if ! check_syntax "$PROJECT_ROOT/$rel"; then
        CURRENT_FAILS="$CURRENT_FAILS$rel
"
    fi
done <<EOF
$TARGETS
EOF
CURRENT_FAILS=$(printf '%s' "$CURRENT_FAILS" | sed '/^$/d' | LC_ALL=C sort)

if [ "$UPDATE_BASELINE" = true ]; then
    printf '%s\n' "$CURRENT_FAILS" | sed '/^$/d' > "$BASELINE_FILE"
    echo "✅ baseline 갱신: $BASELINE_FILE ($(printf '%s' "$CURRENT_FAILS" | grep -c . || true) 파일)"
    exit 0
fi

# baseline 로드 (없으면 빈 baseline = 모든 실패가 신규)
BASELINE=""
[ -f "$BASELINE_FILE" ] && BASELINE=$(grep -vE '^\s*(#|$)' "$BASELINE_FILE" | LC_ALL=C sort)

# 신규 실패 = 현재 실패 - baseline
NEW_FAILS=$(comm -23 <(printf '%s\n' "$CURRENT_FAILS" | sed '/^$/d') <(printf '%s\n' "$BASELINE" | sed '/^$/d'))

echo "## Frontend Syntax Baseline (CFY-FB-01)"
echo "- 대상: $TARGET_COUNT 파일 (server.js + static/js/**/*.js)"
echo "- baseline 등록 실패: $(printf '%s' "$BASELINE" | grep -c . || true)"
echo "- 현재 실패: $(printf '%s' "$CURRENT_FAILS" | grep -c . || true)"

if [ -n "$NEW_FAILS" ]; then
    echo "- 판정: FAIL — baseline 에 없는 신규 구문 오류"
    echo "$NEW_FAILS" | sed 's/^/  ✗ /'
    exit 1
fi

echo "- 판정: PASS"
exit 0
