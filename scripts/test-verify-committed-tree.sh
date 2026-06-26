#!/usr/bin/env bash
# test-verify-committed-tree.sh — verify-committed-tree.sh smoke tests
#
# 격리된 임시 git 저장소에서 커밋트리 검증의 PASS/BLOCK 동작을 확인한다.
# (백엔드 컴파일 경로는 실 사고 커밋 297e46f/bb4eddc 로 별도 검증됨 — 여기선 프론트 구문 경로로 결정론 확인)
#
# EXIT: 0=all passed / 1=failures

set -uo pipefail

PROJECT_ROOT="${CODEX_PROJECT_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null)}"
[ -z "$PROJECT_ROOT" ] && PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$PROJECT_ROOT/scripts/verify-committed-tree.sh"

pass=0
fail=0
record_pass() { echo "✅ $1"; pass=$((pass + 1)); }
record_fail() { echo "❌ $1: $2"; [ -n "${3:-}" ] && echo "   output: ${3:0:300}"; fail=$((fail + 1)); }

make_repo() {
    local dir="$1"
    mkdir -p "$dir/nodejs-frontend/static/js"
    git -C "$dir" init -q
    git -C "$dir" config user.name "committed smoke"
    git -C "$dir" config user.email "committed-smoke@example.invalid"
    printf "console.log('valid');\n" > "$dir/nodejs-frontend/static/js/app.js"
    git -C "$dir" add .
    git -C "$dir" commit -q -m "valid commit"
}

# ── 1. Syntax ──────────────────────────────────────────────────────────────
out=$(bash -n "$SCRIPT" 2>&1) && record_pass "syntax_ok" || record_fail "syntax_ok" "bash -n failed" "$out"

# ── 2. 정상 커밋트리 → PASS(exit 0) ─────────────────────────────────────────
tmp="$(mktemp -d "${TMPDIR:-/tmp}/committed-pass.XXXXXX")"
make_repo "$tmp"
out=$(CODEX_PROJECT_ROOT="$tmp" bash "$SCRIPT" HEAD 2>&1); rc=$?
if [ "$rc" -eq 0 ] && echo "$out" | grep -q "COMMITTED-TREE PASS"; then
    record_pass "clean_commit_pass (exit 0)"
else
    record_fail "clean_commit_pass" "exit $rc (expected 0 PASS)" "$out"
fi
rm -rf "$tmp"

# ── 3. 구문 깨진 JS 커밋 → BLOCK(exit 2) ────────────────────────────────────
tmp="$(mktemp -d "${TMPDIR:-/tmp}/committed-block.XXXXXX")"
make_repo "$tmp"
printf "function broken( {\n" > "$tmp/nodejs-frontend/static/js/broken.js"   # 구문 오류
git -C "$tmp" add . >/dev/null 2>&1
git -C "$tmp" commit -q -m "broken commit"
out=$(CODEX_PROJECT_ROOT="$tmp" bash "$SCRIPT" HEAD 2>&1); rc=$?
if [ "$rc" -eq 2 ] && echo "$out" | grep -q "COMMITTED-TREE BLOCK"; then
    record_pass "broken_commit_block (exit 2)"
else
    record_fail "broken_commit_block" "exit $rc (expected 2 BLOCK)" "$out"
fi
rm -rf "$tmp"

# ── 4. 잘못된 ref → exit 1 ──────────────────────────────────────────────────
out=$(bash "$SCRIPT" definitely-not-a-ref 2>&1); rc=$?
if [ "$rc" -eq 1 ]; then
    record_pass "bad_ref_exit1"
else
    record_fail "bad_ref_exit1" "exit $rc (expected 1)" "$out"
fi

echo ""
echo "=== verify-committed-tree smoke: $pass passed, $fail failed ==="
[ "$fail" -eq 0 ]
