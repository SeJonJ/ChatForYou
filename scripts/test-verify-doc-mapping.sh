#!/usr/bin/env bash
# test-verify-doc-mapping.sh — verify-doc-mapping.sh smoke tests
# 격리된 임시 PROJECT_ROOT 에서 4가지 매핑 상태 판정을 확인한다.
# EXIT: 0=all passed / 1=failures

set -uo pipefail

PROJECT_ROOT="${CODEX_PROJECT_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null)}"
[ -z "$PROJECT_ROOT" ] && PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$PROJECT_ROOT/scripts/verify-doc-mapping.sh"

pass=0; fail=0
record_pass() { echo "✅ $1"; pass=$((pass + 1)); }
record_fail() { echo "❌ $1: $2"; [ -n "${3:-}" ] && echo "   output: ${3:0:400}"; fail=$((fail + 1)); }

# 임시 루트에 base-plan + 참조 파일 구성
make_root() {
    local root="$1" mapping="$2"
    mkdir -p "$root/plan_docs/00-base_plan/2026/06"
    mkdir -p "$root/plan_docs/01-plan" "$root/plan_docs/04-analyze"
    printf "# plan\n\n## 8. Document Mapping\n\n%s\n" "$mapping" \
        > "$root/plan_docs/00-base_plan/2026/06/x_plan.md"
}

# 1. syntax
out=$(bash -n "$SCRIPT" 2>&1) && record_pass "syntax_ok" || record_fail "syntax_ok" "bash -n" "$out"

# 2. 전부 일관([x]+존재) → PASS exit 0
tmp="$(mktemp -d)"; make_root "$tmp" "- [x] 요구사항: \`plan_docs/01-plan/feat.md\`"
printf "x\n" > "$tmp/plan_docs/01-plan/feat.md"
out=$(CODEX_PROJECT_ROOT="$tmp" bash "$SCRIPT" "$tmp/plan_docs/00-base_plan/2026/06/x_plan.md" 2>&1); rc=$?
{ [ "$rc" -eq 0 ] && echo "$out" | grep -q "DOC-MAPPING PASS"; } \
    && record_pass "all_consistent_pass" || record_fail "all_consistent_pass" "exit $rc" "$out"
rm -rf "$tmp"

# 3. 미체크+존재 → WARN(exit 0, 차단 아님)
tmp="$(mktemp -d)"; make_root "$tmp" "- [ ] 요구사항: \`plan_docs/01-plan/feat.md\`"
printf "x\n" > "$tmp/plan_docs/01-plan/feat.md"
out=$(CODEX_PROJECT_ROOT="$tmp" bash "$SCRIPT" "$tmp/plan_docs/00-base_plan/2026/06/x_plan.md" 2>&1); rc=$?
{ [ "$rc" -eq 0 ] && echo "$out" | grep -q "DOC-MAPPING WARN"; } \
    && record_pass "uncheck_but_exists_warn" || record_fail "uncheck_but_exists_warn" "exit $rc" "$out"
rm -rf "$tmp"

# 4. 체크+부재 → ERROR(exit 2, 허위 체크)
tmp="$(mktemp -d)"; make_root "$tmp" "- [x] 요구사항: \`plan_docs/01-plan/missing.md\`"
out=$(CODEX_PROJECT_ROOT="$tmp" bash "$SCRIPT" "$tmp/plan_docs/00-base_plan/2026/06/x_plan.md" 2>&1); rc=$?
{ [ "$rc" -eq 2 ] && echo "$out" | grep -q "허위 체크"; } \
    && record_pass "false_check_block" || record_fail "false_check_block" "exit $rc" "$out"
rm -rf "$tmp"

# 5. 미체크+부재 + 같은 feature stem 의 다른 이름 파일 존재 → ERROR(exit 2, 경로 불일치)
#    plan 이 x_plan.md → FEATURE_STEM=x. 매핑은 x-gap.md(부재), 디렉토리엔 x.md(존재).
tmp="$(mktemp -d)"; make_root "$tmp" "- [ ] 갭분석: \`plan_docs/04-analyze/x-gap.md\`"
printf "x\n" > "$tmp/plan_docs/04-analyze/x.md"   # 같은 stem(x) 의 다른 이름
out=$(CODEX_PROJECT_ROOT="$tmp" bash "$SCRIPT" "$tmp/plan_docs/00-base_plan/2026/06/x_plan.md" 2>&1); rc=$?
{ [ "$rc" -eq 2 ] && echo "$out" | grep -q "경로 불일치"; } \
    && record_pass "path_mismatch_block" || record_fail "path_mismatch_block" "exit $rc" "$out"
rm -rf "$tmp"

# 6. [codex P1 회귀] 미체크+부재 + 디렉토리에 "무관한 타 기능 파일만" → INFO(exit 0, false block 아님)
#    공유 phase 디렉토리(03/04-analyze)에 다른 기능 문서가 있어도, 다른 stem 이면 경로불일치로 오판하면 안 됨.
tmp="$(mktemp -d)"; make_root "$tmp" "- [ ] 갭분석: \`plan_docs/04-analyze/x.md\`"
printf "y\n" > "$tmp/plan_docs/04-analyze/unrelated_other.md"   # stem(x) 와 무관
out=$(CODEX_PROJECT_ROOT="$tmp" bash "$SCRIPT" "$tmp/plan_docs/00-base_plan/2026/06/x_plan.md" 2>&1); rc=$?
{ [ "$rc" -eq 0 ] && ! echo "$out" | grep -q "경로 불일치"; } \
    && record_pass "shared_dir_no_false_block (exit 0)" || record_fail "shared_dir_no_false_block" "exit $rc (expected 0, no false mismatch)" "$out"
rm -rf "$tmp"

echo ""
echo "=== verify-doc-mapping smoke: $pass passed, $fail failed ==="
[ "$fail" -eq 0 ]
