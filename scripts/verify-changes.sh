#!/bin/bash
# verify-changes.sh — 결정론 검증 게이트 (build / test / syntax)
#
# 목적: 변경된 컴포넌트를 감지해 build/test/syntax 를 실제로 실행하고
#       레벨별 차등 강제(L2/L3=block, L1=advisory)로 판정한다.
#       "문서 존재 확인"에 그치는 hook 게이트의 빈틈(정확성 미검증)을 메운다.
#
# WHY hook 이 아니라 스크립트인가:
#   build/test 는 분(min) 단위라 hook timeout(5~15s)을 초과한다.
#   따라서 결정론 검증은 hook 이 아닌 워크플로우/에이전트 스텝 또는 수동 실행으로 분리한다.
#   (docs/agent/verification-protocol.md 의 단일 출처 정책)
#
# 사용:
#   scripts/verify-changes.sh                 # 작업트리(unstaged+staged) 변경분, 레벨 자동감지
#   scripts/verify-changes.sh --level L3       # 레벨 강제 지정
#   scripts/verify-changes.sh --base chatforyou_v2   # base 대비 커밋 diff 포함
#
# EXIT:
#   0  → PASS 또는 advisory(L1) — 추론 단계 진입 허용
#   2  → BLOCK (L2/L3 필수 검증 FAIL) — 수정 후 재실행 전까지 추론 단계 진입 금지
#   3  → 검증 인프라 부재(java/node/gradlew 등) — degrade, 사람 확인 필요

set -uo pipefail

# ─── 경로 해석 (하드코딩 0 / env화 — B4) ───────────────────────
PROJECT_ROOT="${CODEX_PROJECT_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null)}"
[ -z "$PROJECT_ROOT" ] && PROJECT_ROOT="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "$0")/.." && pwd)}"

# ─── 인자 파싱 ─────────────────────────────────────────────────
FORCE_LEVEL=""
BASE_REF=""
while [ $# -gt 0 ]; do
    case "$1" in
        --level) FORCE_LEVEL="$2"; shift 2 ;;
        --base)  BASE_REF="$2";  shift 2 ;;
        *) echo "알 수 없는 인자: $1"; exit 1 ;;
    esac
done

# ─── 변경 파일 수집 ────────────────────────────────────────────
collect_changes() {
    git -C "$PROJECT_ROOT" diff --name-only HEAD 2>/dev/null
    git -C "$PROJECT_ROOT" diff --name-only --cached 2>/dev/null
    # 신규(untracked) 파일도 포함 — diff HEAD 는 추적 파일만 잡으므로 누락 방지
    git -C "$PROJECT_ROOT" ls-files --others --exclude-standard 2>/dev/null
    if [ -n "$BASE_REF" ]; then
        git -C "$PROJECT_ROOT" diff --name-only "${BASE_REF}...HEAD" 2>/dev/null
    fi
}
CHANGED=$(collect_changes | sort -u | grep -v '^$' || true)

if [ -z "$CHANGED" ]; then
    echo "## Verification Evidence (deterministic gate)"
    echo ""
    echo "- 시각: $(date '+%Y-%m-%d %H:%M:%S')"
    echo "- 감지 레벨: N/A → 적용 레벨: ${FORCE_LEVEL:-N/A}"
    echo "- 강제 정책: L1=advisory / L2·L3=block(build+test+syntax 필수)"
    echo "- 변경 컴포넌트: backend=false, frontend_js=0건, scss=false"
    echo ""
    echo "| 검사 | 결과 | 비고 |"
    echo "|------|------|------|"
    echo "| deterministic-target | ✅ PASS/N/A | 변경된 파일이 없어 실행 가능한 결정론 검증 대상이 없습니다. |"
    echo ""
    echo "ℹ️  변경된 파일이 없습니다. 검증 대상 없음. exit 0."
    exit 0
fi

# ─── 분류 + 레벨 감지 ──────────────────────────────────────────
backend_changed=false
frontend_js_files=()
scss_changed=false
detected_level="L1"

bump() { # 더 높은 레벨로만 상향
    case "$1:$detected_level" in
        L3:*) detected_level="L3" ;;
        L2:L1) detected_level="L2" ;;
    esac
}

while IFS= read -r f; do
    [ -z "$f" ] && continue
    case "$f" in
        # L3 강신호 — WebRTC/Kurento/Signaling
        *[Kk]urento*|*[Ww]eb[Rr]tc*|*[Ss]ignaling*|*[Rr]oomManager*|*DataChannel*|*IceCandidate*|*SdpOffer*)
            bump L3 ;;
    esac
    case "$f" in
        springboot-backend/src/main/*) backend_changed=true; bump L2 ;;
        springboot-backend/src/test/*) backend_changed=true ;;
        springboot-backend/*.gradle)   backend_changed=true; bump L2 ;;
        nodejs-frontend/static/js/*.js|nodejs-frontend/server.js)
            frontend_js_files+=("$PROJECT_ROOT/$f"); bump L1 ;;
        nodejs-frontend/*.scss|nodejs-frontend/**/*.scss)
            scss_changed=true; bump L1 ;;
    esac
done <<< "$CHANGED"

LEVEL="${FORCE_LEVEL:-$detected_level}"

# ─── 검증 실행 ────────────────────────────────────────────────
declare -a RESULTS   # "name|status|detail"
RESULTS_COUNT=0
infra_missing=false

run_check() {
    local name="$1"; shift
    local out rc
    out=$("$@" 2>&1); rc=$?
    if [ $rc -eq 0 ]; then
        RESULTS+=("$name|PASS|ok")
    else
        local tail; tail=$(echo "$out" | tail -3 | tr '\n' ' ')
        RESULTS+=("$name|FAIL|$tail")
    fi
    RESULTS_COUNT=$((RESULTS_COUNT + 1))
    return $rc
}

add_result() {
    RESULTS+=("$1|$2|$3")
    RESULTS_COUNT=$((RESULTS_COUNT + 1))
}

# backend: compile + test (external 태그는 build.gradle 에서 자동 제외)
if $backend_changed; then
    if ! command -v java >/dev/null 2>&1; then
        add_result "backend" "DEGRADE" "java 부재 → 검증 불가 (V6)"; infra_missing=true
    else
        run_check "backend:compile" bash -c "cd '$PROJECT_ROOT/springboot-backend' && ./gradlew compileJava compileTestJava -q"
        run_check "backend:test"    bash -c "cd '$PROJECT_ROOT/springboot-backend' && ./gradlew test -q"
    fi
fi

# frontend JS: node --check (구문)
if [ ${#frontend_js_files[@]} -gt 0 ]; then
    if ! command -v node >/dev/null 2>&1; then
        add_result "frontend:syntax" "DEGRADE" "node 부재 → 검증 불가 (V6)"; infra_missing=true
    else
        for jf in "${frontend_js_files[@]}"; do
            run_check "frontend:syntax:$(basename "$jf")" node --check "$jf"
        done
    fi
fi

# scss: 컴파일 확인 (advisory)
if $scss_changed; then
    if (cd "$PROJECT_ROOT/nodejs-frontend" && npx --no-install sass --version >/dev/null 2>&1); then
        run_check "frontend:scss" bash -c "cd '$PROJECT_ROOT/nodejs-frontend' && npx --no-install sass static/scss/main.scss /tmp/sage-scss-check.css --no-source-map"
    else
        add_result "frontend:scss" "DEGRADE" "sass 부재 → 검증 생략"
    fi
fi

# ─── 판정 (레벨별 차등 — A2-a) ─────────────────────────────────
# 필수(block) 대상: L2/L3 = backend compile/test + frontend syntax
# advisory: L1 전체, scss 전체
required_fail=false
advisory_fail=false

is_required() {
    case "$LEVEL" in
        L2|L3)
            case "$1" in
                backend:*|frontend:syntax:*) return 0 ;;
            esac ;;
    esac
    return 1
}

# bash 3.2: empty arrays with set -u trigger "unbound variable" on [@] expansion.
if [ "$RESULTS_COUNT" -gt 0 ]; then
for r in "${RESULTS[@]}"; do
    name="${r%%|*}"; rest="${r#*|}"; status="${rest%%|*}"
    if [ "$status" = "FAIL" ]; then
        if is_required "$name"; then required_fail=true; else advisory_fail=true; fi
    fi
done
fi

# ─── 증거 블록 출력 (plan_docs 03-implementation 에 붙여넣기용) ──
echo "## Verification Evidence (deterministic gate)"
echo ""
echo "- 시각: $(date '+%Y-%m-%d %H:%M:%S')"
echo "- 감지 레벨: ${detected_level}${FORCE_LEVEL:+ (강제 지정 $FORCE_LEVEL)}  → 적용 레벨: $LEVEL"
echo "- 강제 정책: L1=advisory / L2·L3=block(build+test+syntax 필수)"
echo "- 변경 컴포넌트: backend=$backend_changed, frontend_js=${#frontend_js_files[@]}건, scss=$scss_changed"
echo ""
echo "| 검사 | 결과 | 비고 |"
echo "|------|------|------|"
if [ "$RESULTS_COUNT" -gt 0 ]; then
for r in "${RESULTS[@]}"; do
    name="${r%%|*}"; rest="${r#*|}"; status="${rest%%|*}"; detail="${rest#*|}"
    icon="✅"; [ "$status" = "FAIL" ] && icon="❌"; [ "$status" = "DEGRADE" ] && icon="⚠️"
    req=""; is_required "$name" && req=" *(필수)*"
    echo "| $name$req | $icon $status | ${detail:0:80} |"
done
else
    echo "| deterministic-target | ✅ PASS/N/A | 실행 가능한 결정론 검증 대상이 없습니다 (문서/설정 변경 등). |"
fi
echo ""

if [ "$RESULTS_COUNT" -eq 0 ]; then
    echo "ℹ️  실행 가능한 결정론 검증 대상이 없습니다 (문서/설정 변경 등). exit 0."
    exit 0
fi

if $infra_missing && ! $required_fail; then
    echo "⚠️  [VERIFY DEGRADE] 검증 인프라 일부 부재(V6). 사람이 직접 build/test 확인 권장."
    exit 3
fi

if $required_fail; then
    echo "⛔ [VERIFY BLOCK — $LEVEL] 필수 검증 FAIL. 수정 후 재실행 전까지 추론 단계(05 cross-model) 진입 금지."
    echo "   (docs/agent/verification-protocol.md — 결정론 먼저, 추론 나중)"
    exit 2
fi

if $advisory_fail; then
    echo "⚠️  [VERIFY WARN — $LEVEL] advisory 검증 FAIL. 차단하지 않으나 03-implementation 에 기록 필요."
    exit 0
fi

echo "✅ [VERIFY PASS — $LEVEL] 결정론 검증 통과. 추론 단계 진입 가능."
exit 0
