#!/bin/bash
# pre-implementation-gate.sh
# PreToolUse hook: Write/Edit 전에 risk level별 게이트 체크
#
# EXIT 2  → tool 호출 차단 (Claude에게 이유 전달)
# EXIT 0  → 진행 허용 (stdout 메시지는 Claude에게 컨텍스트로 전달)

PROJECT_ROOT="/Users/sejon/project/ChatForYou_v2"

# stdin에서 JSON 파싱
INPUT=$(cat)
FILE_PATH=$(echo "$INPUT" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print(d.get('tool_input', {}).get('file_path', ''))
" 2>/dev/null)

# 빈 경로 또는 파싱 실패 → 통과
[ -z "$FILE_PATH" ] && exit 0

# ─── 소스 파일 분류 ───────────────────────────────────────────
is_backend_src=false
is_frontend_js=false
is_l3_pattern=false

# L3 패턴: WebRTC/Kurento/Signaling 관련 파일
case "$FILE_PATH" in
    *[Kk]urento*|*[Ww]eb[Rr]tc*|*[Ss]ignaling*|*[Rr]oomManager*|*[Kk]urt*|*DataChannel*|*IceCandidate*|*SdpOffer*)
        is_l3_pattern=true ;;
esac

# L2: 백엔드 소스 (test 제외)
case "$FILE_PATH" in
    */springboot-backend/src/main/java/*)
        is_backend_src=true ;;
esac

# L1~L2: 프론트 JS (CSS/HTML/templates 제외)
case "$FILE_PATH" in
    */nodejs-frontend/static/js/*.js|*/nodejs-frontend/server.js|*/nodejs-frontend/config/*)
        is_frontend_js=true ;;
esac

# L0/L1 파일: plan_docs, docs, CSS, SCSS, HTML → 게이트 없음
case "$FILE_PATH" in
    */plan_docs/*|*/docs/*|*.scss|*.css|*.html|*.md)
        exit 0 ;;
esac

# 소스 파일이 아니면 통과
$is_backend_src || $is_frontend_js || $is_l3_pattern || exit 0

# ─── Plan 존재 확인 ────────────────────────────────────────────
BRANCH=$(git -C "$PROJECT_ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null)
TICKET=$(echo "$BRANCH" | grep -oE '[0-9]+' | head -1)

PLAN_EXISTS=""

# 1차: 브랜치 티켓 번호로 plan 파일 탐색
if [ -n "$TICKET" ]; then
    PLAN_EXISTS=$(find "$PROJECT_ROOT/plan_docs/00-base_plan" -name "*.md" 2>/dev/null \
        | xargs grep -l "$TICKET" 2>/dev/null | head -1)
fi

# 2차: 최근 7일 내 수정된 plan 파일 (fallback)
if [ -z "$PLAN_EXISTS" ]; then
    PLAN_EXISTS=$(find "$PROJECT_ROOT/plan_docs/00-base_plan" -name "*.md" -mtime -7 2>/dev/null | head -1)
fi

# ─── L3 게이트: plan 없거나 WebRTC review 미확인 시 BLOCK ─────
if $is_l3_pattern; then
    if [ -z "$PLAN_EXISTS" ]; then
        echo "⛔ [GATE BLOCK — L3] WebRTC/Kurento 관련 파일 수정이 감지되었습니다."
        echo "  파일: $FILE_PATH"
        echo "  필요 조건:"
        echo "    1. plan_docs/00-base_plan/ 에 plan 문서 생성"
        echo "    2. docs/agent/webrtc-review-protocol.md 에 따른 2라운드 리뷰 완료"
        echo "  현재 브랜치: $BRANCH"
        exit 2
    fi

    # L3 review 문서 확인 (Round 1 / Round 2 키워드)
    L3_REVIEW=$(find "$PROJECT_ROOT/plan_docs" -name "*.md" -mtime -30 2>/dev/null \
        | xargs grep -l "Round 1\|Round 2\|L3.*[Rr]eview\|webrtc.*review" 2>/dev/null | head -1)

    if [ -z "$L3_REVIEW" ]; then
        echo "⚠️  [GATE WARN — L3] WebRTC/Kurento 파일이지만 2라운드 리뷰 문서가 확인되지 않습니다."
        echo "  파일: $FILE_PATH"
        echo "  plan_docs/02-design/ 또는 관련 phase 문서에 Round 1/Round 2 리뷰 결과를 기록하세요."
        echo "  (차단하지 않지만 docs/agent/webrtc-review-protocol.md 를 반드시 확인하세요)"
        exit 0
    fi

    echo "✅ [GATE OK — L3] plan: $(basename "$PLAN_EXISTS") | branch: $BRANCH"
    exit 0
fi

# ─── L2 게이트: plan 없으면 WARN (차단하지 않음) ─────────────
if $is_backend_src || $is_frontend_js; then
    if [ -z "$PLAN_EXISTS" ]; then
        FILE_SHORT="${FILE_PATH#$PROJECT_ROOT/}"
        echo "⚠️  [GATE WARN — L2] 소스 파일 수정인데 관련 plan 문서가 없습니다."
        echo "  파일: $FILE_SHORT"
        echo "  현재 브랜치: $BRANCH | 티켓: ${TICKET:-없음}"
        echo "  L2 이상은 plan_docs/00-base_plan/YYYY/MM/ 에 plan 문서 생성을 권장합니다."
        echo "  (AGENT_GUIDE §3 참조)"
        exit 0
    fi

    FILE_SHORT="${FILE_PATH#$PROJECT_ROOT/}"
    echo "✅ [GATE OK — L2] plan: $(basename "$PLAN_EXISTS") | file: $FILE_SHORT"
    exit 0
fi

exit 0
