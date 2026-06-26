#!/usr/bin/env bash
# verify-doc-mapping.sh — 00-base_plan 의 Document Mapping 체크박스 ↔ 실제 파일 대조
#
# 목적: Document Mapping 박스는 수기 유지라 "작업 완료됐는데 체크 누락" 또는
#       "매핑 경로와 실제 생성 파일명 불일치"(예: -gap.md 로 적고 실제는 다른 이름)가 발생한다.
#       이 게이트가 매핑 경로를 파일시스템과 자동 대조해 4가지 상태로 분류한다.
#
# 사용:
#   scripts/verify-doc-mapping.sh                    # 가장 최근 00-base_plan 자동 선택
#   scripts/verify-doc-mapping.sh <plan.md>          # 특정 base-plan 지정
#
# 판정:
#   - [x] + 파일 존재        → OK
#   - [ ] + 파일 존재        → WARN (작업 완료, 박스 갱신 필요 — advisory)
#   - [x] + 파일 부재        → ERROR (허위 체크)
#   - [ ] + 파일 부재 + 같은 디렉토리에 다른 파일 존재 → ERROR (경로 불일치 의심)
#   - [ ] + 파일 부재 + 디렉토리 비어있음/없음          → INFO (미작성 — 진행 중 정상)
#
# EXIT: 0 = 일관(또는 advisory/info만) / 2 = ERROR(허위체크 또는 경로불일치)

set -uo pipefail

PROJECT_ROOT="${CODEX_PROJECT_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null)}"
[ -z "$PROJECT_ROOT" ] && PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

DOC="${1:-}"
if [ -z "$DOC" ]; then
    DOC=$(ls -t "$PROJECT_ROOT"/plan_docs/00-base_plan/*/*/*_plan.md 2>/dev/null | head -1)
fi
if [ -z "$DOC" ] || [ ! -f "$DOC" ]; then
    echo "⛔ base-plan 문서를 찾을 수 없습니다: ${1:-(자동탐색 실패)}"
    exit 1
fi

# feature stem: base-plan 파일명에서 _plan 접미사 제거 (예: coturn_kurento_improvement_plan → coturn_kurento_improvement).
# 경로 불일치 판정에 사용 — phase 디렉토리는 여러 기능이 공유하는 flat 구조라
# "디렉토리에 아무 파일"이 아니라 "같은 feature stem 의 다른 파일"이 있을 때만 불일치로 본다.
PLAN_BASE=$(basename "$DOC" .md)
FEATURE_STEM=${PLAN_BASE%_plan}

# Document Mapping 섹션만 추출 (다음 ## 헤딩 전까지)
SECTION=$(awk '/^#{2,} .*Document Mapping/{f=1;next} /^#{2,} /{f=0} f' "$DOC")
if [ -z "$SECTION" ]; then
    echo "ℹ️  Document Mapping 섹션 없음: $DOC (검증 대상 아님)"
    exit 0
fi

errors=0
warns=0
declare -a ROWS

while IFS= read -r line; do
    case "$line" in
        "- ["*) ;;   # 체크박스 항목만
        *) continue ;;
    esac
    box=$(printf '%s' "$line" | sed -E 's/^- \[([ xX])\].*/\1/')
    path=$(printf '%s' "$line" | grep -oE '(plan_docs|springboot-backend|nodejs-frontend)/[A-Za-z0-9_./-]+\.md' | head -1)
    [ -z "$path" ] && continue

    abs="$PROJECT_ROOT/$path"
    dir=$(dirname "$abs")
    if [ -f "$abs" ]; then
        if [ "$box" = "x" ] || [ "$box" = "X" ]; then
            ROWS+=("✅ OK|$box|$path|체크+존재")
        else
            ROWS+=("⚠️ WARN|$box|$path|파일 존재하나 미체크 → 박스 갱신 필요")
            warns=$((warns + 1))
        fi
    else
        if [ "$box" = "x" ] || [ "$box" = "X" ]; then
            ROWS+=("❌ ERROR|$box|$path|체크됐으나 파일 부재(허위 체크)")
            errors=$((errors + 1))
        else
            # 같은 feature stem 으로 시작하는 "다른 이름" 파일이 같은 디렉토리에 있으면 경로 불일치(예: -gap 접미사 누락).
            # 공유 phase 디렉토리(03-implementation 등)의 무관한 타 기능 파일은 무시 → 미작성(진행 중)으로 본다.
            exp_base=$(basename "$path")
            sib=""
            if [ -d "$dir" ]; then
                for cand in "$dir/$FEATURE_STEM"*; do
                    [ -e "$cand" ] || continue
                    cb=$(basename "$cand")
                    [ "$cb" = "$exp_base" ] && continue
                    sib="${sib}${cb},"
                done
            fi
            if [ -n "$sib" ]; then
                ROWS+=("❌ ERROR|$box|$path|파일 부재 + 같은 feature($FEATURE_STEM) 다른 파일 존재 → 경로 불일치 의심($sib)")
                errors=$((errors + 1))
            else
                ROWS+=("ℹ️ INFO|$box|$path|미작성(진행 중 정상)")
            fi
        fi
    fi
done <<< "$SECTION"

# ─── 출력 ─────────────────────────────────────────────────────
echo "## Document Mapping Verification"
echo ""
echo "- 시각: $(date '+%Y-%m-%d %H:%M:%S')"
echo "- 대상: ${DOC#$PROJECT_ROOT/}"
echo ""
echo "| 판정 | box | 경로 | 비고 |"
echo "|------|-----|------|------|"
if [ ${#ROWS[@]} -gt 0 ]; then
    for r in "${ROWS[@]}"; do
        IFS='|' read -r verdict box path note <<< "$r"
        echo "| $verdict | [$box] | \`$path\` | $note |"
    done
else
    echo "| ℹ️ | - | - | 매핑 항목 없음 |"
fi
echo ""

if [ "$errors" -gt 0 ]; then
    echo "⛔ [DOC-MAPPING BLOCK] 허위 체크 또는 경로 불일치 ${errors}건. 매핑 경로를 실제 파일명으로 교정하세요."
    exit 2
fi
if [ "$warns" -gt 0 ]; then
    echo "⚠️  [DOC-MAPPING WARN] 작업 완료됐으나 미체크 ${warns}건. 박스를 [x] 로 갱신하세요(차단 아님)."
    exit 0
fi
echo "✅ [DOC-MAPPING PASS] 매핑↔파일 일관."
exit 0
