#!/usr/bin/env bash
# test-verify-changes.sh — verify-changes.sh deterministic smoke tests
#
# Each behavioral case runs against a fresh temporary git repository through
# CODEX_PROJECT_ROOT, so local dirty worktree state cannot affect the result.
#
# EXIT: 0=all passed / 1=failures

set -uo pipefail

PROJECT_ROOT="${CODEX_PROJECT_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null)}"
[ -z "$PROJECT_ROOT" ] && PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$PROJECT_ROOT/scripts/verify-changes.sh"

pass=0
fail=0

record_pass() {
    echo "✅ $1"
    pass=$((pass + 1))
}

record_fail() {
    local name="$1" detail="$2" out="${3:-}"
    echo "❌ $name: $detail"
    [ -n "$out" ] && echo "   output: ${out:0:300}"
    fail=$((fail + 1))
}

check_exit() {
    local name="$1" exp="$2"; shift 2
    local out rc
    out=$("$@" 2>&1); rc=$?
    if [ "$rc" -eq "$exp" ]; then
        record_pass "$name"
    else
        record_fail "$name" "exit $rc (expected $exp)" "$out"
    fi
}

make_repo() {
    local dir="$1"
    mkdir -p "$dir/docs" "$dir/nodejs-frontend/static/js" "$dir/nodejs-frontend/static/scss"
    git -C "$dir" init -q
    git -C "$dir" config user.name "verify smoke"
    git -C "$dir" config user.email "verify-smoke@example.invalid"
    printf "# fixture\n" > "$dir/README.md"
    printf "console.log('base');\n" > "$dir/nodejs-frontend/static/js/app.js"
    printf ".base { color: #111; }\n" > "$dir/nodejs-frontend/static/scss/main.scss"
    git -C "$dir" add .
    local tree commit
    tree="$(git -C "$dir" write-tree)"
    commit="$(printf "initial fixture\n" | git -C "$dir" commit-tree "$tree")"
    git -C "$dir" update-ref refs/heads/main "$commit"
    git -C "$dir" symbolic-ref HEAD refs/heads/main
}

with_repo() {
    local name="$1"; shift
    local tmp
    tmp="$(mktemp -d "${TMPDIR:-/tmp}/verify-changes.XXXXXX")"
    make_repo "$tmp"
    "$@" "$tmp"
    local rc=$?
    rm -rf "$tmp"
    return "$rc"
}

case_no_changes() {
    local tmp="$1" out rc
    out=$(CODEX_PROJECT_ROOT="$tmp" bash "$SCRIPT" 2>&1); rc=$?
    if [ "$rc" -eq 0 ] && echo "$out" | grep -q "Verification Evidence" && echo "$out" | grep -q "검증 대상 없음"; then
        record_pass "no_changes_exit0"
    else
        record_fail "no_changes_exit0" "exit $rc (expected 0 with evidence)" "$out"
    fi
}

case_docs_only() {
    local tmp="$1" out rc
    printf "\n문서 변경\n" >> "$tmp/docs/process.md"
    out=$(CODEX_PROJECT_ROOT="$tmp" bash "$SCRIPT" 2>&1); rc=$?
    if [ "$rc" -eq 0 ] && echo "$out" | grep -q "PASS/N/A"; then
        record_pass "docs_settings_only_exit0"
    else
        record_fail "docs_settings_only_exit0" "exit $rc (expected 0 PASS/N/A)" "$out"
    fi
}

case_l1_force() {
    local tmp="$1" out rc
    printf "const answer = 42;\n" >> "$tmp/nodejs-frontend/static/js/app.js"
    out=$(CODEX_PROJECT_ROOT="$tmp" bash "$SCRIPT" --level L1 2>&1); rc=$?
    if [ "$rc" -eq 0 ] || [ "$rc" -eq 3 ]; then
        record_pass "l1_force_exit0_or_degrade (exit $rc)"
    else
        record_fail "l1_force_exit0_or_degrade" "exit $rc (expected 0 or 3)" "$out"
    fi
}

case_commit_risk_advisory() {
    # 워킹트리에 있으나 미추적인 소스 파일 → COMMIT-RISK 경보(차단 아님, exit 0)
    local tmp="$1" out rc
    printf "console.log('orphan not committed');\n" > "$tmp/nodejs-frontend/static/js/orphan.js"
    out=$(CODEX_PROJECT_ROOT="$tmp" bash "$SCRIPT" 2>&1); rc=$?
    if { [ "$rc" -eq 0 ] || [ "$rc" -eq 3 ]; } \
        && echo "$out" | grep -q "COMMIT-RISK" \
        && echo "$out" | grep -q "orphan.js"; then
        record_pass "commit_risk_advisory (exit $rc)"
    else
        record_fail "commit_risk_advisory" "exit $rc (expected 0/3 + COMMIT-RISK + orphan.js)" "$out"
    fi
}

# ── 1. Syntax check ────────────────────────────────────────────────────────
check_exit "syntax_ok" 0 bash -n "$SCRIPT"

# ── 2. Unknown argument → exit 1 ───────────────────────────────────────────
check_exit "unknown_arg_exits_1" 1 bash "$SCRIPT" --invalid-arg

# ── 3. Behavior in isolated repos ─────────────────────────────────────────
case_url_prefix_block() {
    # API_BASE_URL 뒤에 /chatforyou/api 중복 → 레벨 무관 BLOCK(exit 2)
    local tmp="$1" out rc
    printf "fetch(window.__CONFIG__.API_BASE_URL + '/chatforyou/api/turn/credential');\n" \
        >> "$tmp/nodejs-frontend/static/js/app.js"
    git -C "$tmp" add . >/dev/null 2>&1
    out=$(CODEX_PROJECT_ROOT="$tmp" bash "$SCRIPT" 2>&1); rc=$?
    if [ "$rc" -eq 2 ] && echo "$out" | grep -q "url-prefix"; then
        record_pass "url_prefix_block (exit 2)"
    else
        record_fail "url_prefix_block" "exit $rc (expected 2 BLOCK + url-prefix)" "$out"
    fi
}

case_url_prefix_template_block() {
    # 템플릿 리터럴 형태의 prefix 중복 ${API_BASE_URL}/chatforyou/api → BLOCK(exit 2)
    local tmp="$1" out rc
    printf 'fetch(`${window.__CONFIG__.API_BASE_URL}/chatforyou/api/turn/credential`);\n' \
        >> "$tmp/nodejs-frontend/static/js/app.js"
    git -C "$tmp" add . >/dev/null 2>&1
    out=$(CODEX_PROJECT_ROOT="$tmp" bash "$SCRIPT" 2>&1); rc=$?
    if [ "$rc" -eq 2 ] && echo "$out" | grep -q "url-prefix"; then
        record_pass "url_prefix_template_block (exit 2)"
    else
        record_fail "url_prefix_template_block" "exit $rc (expected 2 BLOCK + url-prefix)" "$out"
    fi
}

case_url_prefix_ok() {
    # 올바른 호출(리소스 경로만) → url-prefix PASS, 차단 없음
    local tmp="$1" out rc
    printf "fetch(window.__CONFIG__.API_BASE_URL + '/turn/credential');\n" \
        >> "$tmp/nodejs-frontend/static/js/app.js"
    git -C "$tmp" add . >/dev/null 2>&1
    out=$(CODEX_PROJECT_ROOT="$tmp" bash "$SCRIPT" 2>&1); rc=$?
    if { [ "$rc" -eq 0 ] || [ "$rc" -eq 3 ]; } && ! echo "$out" | grep -q "url-prefix.*FAIL"; then
        record_pass "url_prefix_ok (exit $rc)"
    else
        record_fail "url_prefix_ok" "exit $rc (expected 0/3, no url-prefix FAIL)" "$out"
    fi
}

with_repo "no_changes_exit0" case_no_changes
with_repo "docs_settings_only_exit0" case_docs_only
with_repo "l1_force_exit0_or_degrade" case_l1_force
with_repo "commit_risk_advisory" case_commit_risk_advisory
with_repo "url_prefix_block" case_url_prefix_block
with_repo "url_prefix_template_block" case_url_prefix_template_block
with_repo "url_prefix_ok" case_url_prefix_ok

echo ""
echo "=== verify-changes smoke: $pass passed, $fail failed ==="
[ "$fail" -eq 0 ]
