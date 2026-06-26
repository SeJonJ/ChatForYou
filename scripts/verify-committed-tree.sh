#!/usr/bin/env bash
# verify-committed-tree.sh — "CI가 보는 것"을 로컬에서 재현하는 게이트
#
# 목적: verify-changes.sh 는 워킹트리(untracked 포함)를 검증하므로
#       "파일을 만들었지만 커밋하지 않음(부분 커밋)" 결함을 구조적으로 못 잡는다.
#       이 스크립트는 지정한 커밋(기본 HEAD)을 격리된 git worktree 로 체크아웃해
#       untracked 워킹트리와 완전히 분리된 상태에서 컴파일한다 = CI clean checkout 과 동형.
#
# WHY worktree 인가:
#   stash 는 실패 중단 시 작업 유실 위험이 있다. worktree 는 메인 워킹트리를
#   건드리지 않고 커밋트리만 별도 디렉토리에 펼쳐 안전하게 빌드 후 폐기한다.
#
# 사용:
#   scripts/verify-committed-tree.sh                 # HEAD 커밋트리 컴파일
#   scripts/verify-committed-tree.sh <ref>           # 특정 커밋 검증 (회귀 테스트용)
#   scripts/verify-committed-tree.sh <ref> --base <ref>   # base..ref 변경분으로 컴포넌트 감지
#
# EXIT:
#   0  → PASS (커밋트리가 독립 컴파일됨)
#   2  → BLOCK (컴파일 실패 — 커밋 누락/파일 부재 의심)
#   3  → 검증 인프라 부재(java/node) — degrade

set -uo pipefail

PROJECT_ROOT="${CODEX_PROJECT_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null)}"
[ -z "$PROJECT_ROOT" ] && PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

REF="HEAD"
BASE_REF=""
while [ $# -gt 0 ]; do
    case "$1" in
        --base) BASE_REF="$2"; shift 2 ;;
        -*) echo "알 수 없는 인자: $1"; exit 1 ;;
        *) REF="$1"; shift ;;
    esac
done

# ref 유효성
RESOLVED="$(git -C "$PROJECT_ROOT" rev-parse --verify "${REF}^{commit}" 2>/dev/null || true)"
if [ -z "$RESOLVED" ]; then
    echo "⛔ ref 해석 실패: $REF"
    exit 1
fi

# ─── 격리 worktree 생성 + 정리 보장 ────────────────────────────
BASE_TMP="$(mktemp -d "${TMPDIR:-/tmp}/cfy-committed.XXXXXX")"
WT="$BASE_TMP/wt"
cleanup() {
    git -C "$PROJECT_ROOT" worktree remove --force "$WT" >/dev/null 2>&1 || true
    rm -rf "$BASE_TMP" >/dev/null 2>&1 || true
}
trap cleanup EXIT

if ! git -C "$PROJECT_ROOT" worktree add --detach "$WT" "$RESOLVED" >/dev/null 2>&1; then
    echo "⛔ git worktree 생성 실패 ($RESOLVED)"
    exit 1
fi

# ─── 검증할 컴포넌트 감지 ──────────────────────────────────────
# base 가 주어지면 변경분으로, 아니면 커밋트리에 존재하는 컴포넌트를 모두 컴파일(안전 기본값).
backend_target=false
frontend_target=false

if [ -n "$BASE_REF" ]; then
    if git -C "$PROJECT_ROOT" rev-parse --verify "${BASE_REF}^{commit}" >/dev/null 2>&1; then
        CHANGED="$(git -C "$PROJECT_ROOT" diff --name-only "${BASE_REF}..${RESOLVED}" 2>/dev/null || true)"
        case "$CHANGED" in
            *springboot-backend/*) backend_target=true ;;
        esac
        case "$CHANGED" in
            *nodejs-frontend/*) frontend_target=true ;;
        esac
    else
        echo "⚠️  BASE_REF('$BASE_REF')를 커밋으로 해석하지 못했습니다. 전체 트리 기반 검증으로 fallback합니다." >&2
        [ -d "$WT/springboot-backend/src/main" ] && backend_target=true
        [ -d "$WT/nodejs-frontend/static/js" ] && frontend_target=true
    fi
else
    [ -d "$WT/springboot-backend/src/main" ] && backend_target=true
    [ -d "$WT/nodejs-frontend/static/js" ] && frontend_target=true
fi

# ─── 검증 실행 ────────────────────────────────────────────────
declare -a RESULTS
block=false
degrade=false

run_check() {
    local name="$1"; shift
    local out rc
    out=$("$@" 2>&1); rc=$?
    if [ $rc -eq 0 ]; then
        RESULTS+=("$name|PASS|ok")
    else
        local tail; tail=$(echo "$out" | grep -iE "error|does not exist|cannot find symbol|FAIL" | head -3 | tr '\n' ' ')
        [ -z "$tail" ] && tail=$(echo "$out" | tail -3 | tr '\n' ' ')
        RESULTS+=("$name|FAIL|$tail")
        block=true
    fi
}

# backend: compile main + test (CI 의 ./gradlew test 가 잡는 컴파일 단계와 동형, 단 test 실행 전 compile 만)
if $backend_target; then
    if ! command -v java >/dev/null 2>&1; then
        RESULTS+=("backend:compile|DEGRADE|java 부재"); degrade=true
    else
        run_check "backend:compile" bash -c "cd '$WT/springboot-backend' && ./gradlew compileJava compileTestJava -q --no-daemon"
    fi
fi

# frontend: node --check (구문) — 커밋트리 내 JS 전체
if $frontend_target; then
    if ! command -v node >/dev/null 2>&1; then
        RESULTS+=("frontend:syntax|DEGRADE|node 부재"); degrade=true
    else
        while IFS= read -r jf; do
            [ -z "$jf" ] && continue
            run_check "frontend:syntax:$(basename "$jf")" node --check "$jf"
        done < <(find "$WT/nodejs-frontend/static/js" -name '*.js' -type f 2>/dev/null; [ -f "$WT/nodejs-frontend/server.js" ] && echo "$WT/nodejs-frontend/server.js")
    fi
fi

# ─── 증거 출력 ────────────────────────────────────────────────
echo "## Committed-Tree Verification (CI 재현 게이트)"
echo ""
echo "- 시각: $(date '+%Y-%m-%d %H:%M:%S')"
echo "- 검증 커밋: ${RESOLVED:0:12} (${REF})${BASE_REF:+  base=$BASE_REF}"
echo "- 격리 worktree: $WT (검증 후 폐기)"
echo "- 대상: backend=$backend_target, frontend=$frontend_target"
echo ""
echo "| 검사 | 결과 | 비고 |"
echo "|------|------|------|"
if [ ${#RESULTS[@]} -gt 0 ]; then
    for r in "${RESULTS[@]}"; do
        name="${r%%|*}"; rest="${r#*|}"; status="${rest%%|*}"; detail="${rest#*|}"
        icon="✅"; [ "$status" = "FAIL" ] && icon="❌"; [ "$status" = "DEGRADE" ] && icon="⚠️"
        echo "| $name | $icon $status | ${detail:0:90} |"
    done
else
    echo "| committed-target | ✅ PASS/N/A | 컴파일 대상 컴포넌트 변경 없음 |"
fi
echo ""

if $block; then
    echo "⛔ [COMMITTED-TREE BLOCK] 커밋트리가 독립 컴파일되지 않습니다."
    echo "   → 워킹트리엔 있으나 커밋에서 빠진 파일(부분 커밋)일 가능성이 높습니다."
    echo "   → git status / git add 로 신규 소스 파일이 모두 스테이징됐는지 확인하세요."
    exit 2
fi

if $degrade; then
    echo "⚠️  [COMMITTED-TREE DEGRADE] 검증 인프라 부재. 사람이 직접 확인 권장."
    exit 3
fi

echo "✅ [COMMITTED-TREE PASS] 커밋트리 독립 컴파일 통과 (CI 동형)."
exit 0
