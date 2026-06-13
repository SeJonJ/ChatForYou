#!/bin/bash
# pre-implementation-gate.sh
# PreToolUse hook (Write|Edit|MultiEdit): risk level별 게이트 체크
#
# EXIT 2  → tool 호출 차단 (Claude에게 이유 전달) — Non-Negotiable Boundary / L3 plan 없음
# EXIT 0  → 진행 허용 (stdout 메시지는 Claude에게 컨텍스트로 전달)
#
# 분류 단계:
#   1. Desktop 직접수정 → HARD BLOCK (AGENT_GUIDE Non-Negotiable Boundary)
#   2. 경로 기반 1차 분류 (L0/L1/L2/L3)
#   3. 내용 기반 위험도 상향 (Redis/JWT/WebRTC 키워드)
#   4. 게이트 적용 (L3 filename = BLOCK / 나머지 = WARN)

PROJECT_ROOT="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null)}"
LOG_DIR="$PROJECT_ROOT/.claude/logs"

INPUT=$(cat)

# ─── 파일 경로 + 변경 내용 + session_id 추출 ───
PARSED=$(echo "$INPUT" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    print('|||'); sys.exit(0)
ti = d.get('tool_input', {}) or {}
fp = ti.get('file_path', '') or ''
sid = d.get('session_id', '') or ''
# 변경 내용: Write=content, Edit=new_string, MultiEdit=edits[].new_string
blob = ti.get('content', '') or ti.get('new_string', '') or ''
for e in (ti.get('edits') or []):
    blob += '\n' + (e.get('new_string', '') or '')
blob = blob.replace(chr(10), ' ').replace(chr(13), ' ')
print(fp + '|||' + sid + '|||' + blob)
" 2>/dev/null)

FILE_PATH="${PARSED%%|||*}"
REST="${PARSED#*|||}"
SESSION="${REST%%|||*}"
CONTENT="${REST#*|||}"

[ -z "$FILE_PATH" ] && exit 0

BRANCH=$(git -C "$PROJECT_ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null)
FILE_SHORT="${FILE_PATH#$PROJECT_ROOT/}"

# ─── 유저 선언 레벨 읽기 (capture-declared-risk.sh 가 저장) ───
DECLARED=""
if [ -n "$SESSION" ]; then
    SAFE_SID=$(printf '%s' "$SESSION" | tr -c 'A-Za-z0-9_-' '_' | cut -c1-64)
    DECL_FILE="$LOG_DIR/declared-risk-$SAFE_SID.json"
    if [ -f "$DECL_FILE" ]; then
        DECLARED=$(python3 -c "
import json,sys
try: print(json.load(open('$DECL_FILE')).get('level',''))
except Exception: print('')
" 2>/dev/null)
    fi
fi

# ═══ 1. Desktop 직접수정 HARD BLOCK ════════════════════════════
# AGENT_GUIDE Non-Negotiable Boundary: chatforyou-desktop/src 직접 수정 금지
# (nodejs-frontend 수정 후 npm run sync 로 동기화)
case "$FILE_PATH" in
    */chatforyou-desktop/src/*)
        echo "⛔ [GATE BLOCK — Desktop] chatforyou-desktop/src 는 직접 수정 금지입니다."
        echo "  파일: $FILE_SHORT"
        echo "  이 디렉토리는 nodejs-frontend 동기화 결과물입니다."
        echo "  → nodejs-frontend/ 에서 수정 후 'npm run sync' + 'scss:build' 로 동기화하세요."
        echo "  (AGENT_GUIDE Non-Negotiable Safety Boundaries / docs/chatforyou_desktop.md)"
        exit 2 ;;
esac

# ═══ 2. 경로 기반 1차 분류 ═════════════════════════════════════
risk="none"   # none | L1 | L2 | L3
reason=""

# L0 즉시 통과: 문서/plan
case "$FILE_PATH" in
    */plan_docs/*|*/docs/*|*.md)
        exit 0 ;;
esac

# L3 filename 패턴 (WebRTC/Kurento/Signaling) — 강신호
is_l3_filename=false
case "$FILE_PATH" in
    *[Kk]urento*|*[Ww]eb[Rr]tc*|*[Ss]ignaling*|*[Rr]oomManager*|*DataChannel*|*IceCandidate*|*SdpOffer*)
        is_l3_filename=true; risk="L3"; reason="WebRTC/Kurento filename 패턴" ;;
esac

# 백엔드 소스 (test 제외)
case "$FILE_PATH" in
    */springboot-backend/src/main/java/*)
        [ "$risk" = "none" ] && { risk="L2"; reason="백엔드 소스 (src/main)"; } ;;
esac

# 백엔드 resources/config (application.properties, yml, xml, gradle)
case "$FILE_PATH" in
    */springboot-backend/src/main/resources/*.properties|\
    */springboot-backend/src/main/resources/*.yml|\
    */springboot-backend/src/main/resources/*.yaml|\
    */springboot-backend/*.gradle|\
    */springboot-backend/src/main/resources/*.xml)
        [ "$risk" = "none" ] && { risk="L2"; reason="백엔드 설정/리소스"; } ;;
esac

# 프론트 JS (CSS/HTML 제외)
case "$FILE_PATH" in
    */nodejs-frontend/static/js/*.js|*/nodejs-frontend/server.js|*/nodejs-frontend/config/*)
        [ "$risk" = "none" ] && { risk="L1"; reason="프론트 JS"; } ;;
esac

# 프론트 UI (scss/css/html) — L1, 내용 escalation만 적용
case "$FILE_PATH" in
    */nodejs-frontend/*.scss|*/nodejs-frontend/*.css|*/nodejs-frontend/*.html|*/nodejs-frontend/*.ejs)
        [ "$risk" = "none" ] && { risk="L1"; reason="프론트 UI 마크업/스타일"; } ;;
esac

# 분류 안 된 파일 → 통과
[ "$risk" = "none" ] && exit 0

# ═══ 3. 내용 기반 위험도 상향 (escalation only) ════════════════
content_escalated=""

# L3 내용 키워드 (시그널링/미디어 파이프라인)
case "$CONTENT" in
    *WebRtcEndpoint*|*IceCandidate*|*addIceCandidate*|*onicecandidate*|*SdpOffer*|*processOffer*|*MediaPipeline*|*RTCPeerConnection*|*createOffer*|*createAnswer*|*KurentoClient*|*DataChannel*)
        if [ "$risk" = "L1" ] || [ "$risk" = "L2" ]; then
            content_escalated="L1/L2 → L3 (내용에 WebRTC 시그널링 키워드)"
            risk="L3"
        fi ;;
esac

# L2 내용 키워드 (Redis/JWT/JPA/Auth) — L1만 상향
case "$CONTENT" in
    *RedisTemplate*|*redisTemplate*|*"@Cacheable"*|*JwtTokenProvider*|*"jwt."*|*"@Entity"*|*JpaRepository*|*SecurityContext*|*setAccessToken*|*refreshToken*)
        if [ "$risk" = "L1" ]; then
            content_escalated="L1 → L2 (내용에 Redis/JWT/JPA/Auth 키워드)"
            risk="L2"
        fi ;;
esac

# ═══ 3-1. 유저 선언 레벨 반영 (effective = max(감지, 선언), 상향만) ═
declared_l3=false
if [ -n "$DECLARED" ]; then
    rank() { case "$1" in L0) echo 0;; L1) echo 1;; L2) echo 2;; L3) echo 3;; *) echo -1;; esac; }
    cur=$(rank "$risk"); dec=$(rank "$DECLARED")
    if [ "$dec" -gt "$cur" ]; then
        echo "ℹ️  [DECLARED] 유저 선언 레벨 $DECLARED 적용 (감지: $risk → effective: $DECLARED)"
        risk="$DECLARED"
        reason="${reason:+$reason + }유저 선언 $DECLARED"
    fi
    [ "$DECLARED" = "L3" ] && declared_l3=true
fi

# ═══ Plan 존재 확인 ════════════════════════════════════════════
TICKET=$(echo "$BRANCH" | grep -oE '[0-9]+' | head -1)
PLAN_EXISTS=""
if [ -n "$TICKET" ]; then
    PLAN_EXISTS=$(find "$PROJECT_ROOT/plan_docs/00-base_plan" -name "*.md" 2>/dev/null \
        | xargs grep -l "$TICKET" 2>/dev/null | head -1)
fi
if [ -z "$PLAN_EXISTS" ]; then
    PLAN_EXISTS=$(find "$PROJECT_ROOT/plan_docs/00-base_plan" -name "*.md" -mtime -7 2>/dev/null | head -1)
fi

# ═══ 4. 게이트 적용 ════════════════════════════════════════════
[ -n "$content_escalated" ] && echo "↑ [ESCALATION] $content_escalated"

if [ "$risk" = "L3" ]; then
    # 강신호(filename 패턴 OR 유저 선언 L3) + plan 없음 → BLOCK
    if { $is_l3_filename || $declared_l3; } && [ -z "$PLAN_EXISTS" ]; then
        echo "⛔ [GATE BLOCK — L3] L3 작업 + plan 문서 없음"
        echo "  파일: $FILE_SHORT"
        echo "  분류 근거: $reason"
        echo "  필요 조건:"
        echo "    1. plan_docs/00-base_plan/ 에 plan 문서 생성"
        echo "    2. docs/agent/webrtc-review-protocol.md 2라운드 리뷰"
        echo "  현재 브랜치: $BRANCH"
        exit 2
    fi

    # L3 (filename or content) → 리뷰 문서 확인
    L3_REVIEW=$(find "$PROJECT_ROOT/plan_docs" -name "*.md" -mtime -30 2>/dev/null \
        | xargs grep -l "Round 1\|Round 2\|L3.*[Rr]eview\|webrtc.*review" 2>/dev/null | head -1)
    if [ -z "$L3_REVIEW" ]; then
        echo "⚠️  [GATE WARN — L3] WebRTC 관련 변경이지만 2라운드 리뷰 문서 미확인"
        echo "  파일: $FILE_SHORT | 근거: $reason"
        echo "  docs/agent/webrtc-review-protocol.md 의 Round 1/Round 2 리뷰를 plan_docs 에 기록하세요."
        exit 0
    fi
    echo "✅ [GATE OK — L3] plan: $(basename "${PLAN_EXISTS:-?}") | review 확인됨 | $FILE_SHORT"
    exit 0
fi

if [ "$risk" = "L2" ]; then
    if [ -z "$PLAN_EXISTS" ]; then
        echo "⚠️  [GATE WARN — L2] 소스/설정 변경인데 관련 plan 문서가 없습니다."
        echo "  파일: $FILE_SHORT | 근거: $reason"
        echo "  현재 브랜치: $BRANCH | 티켓: ${TICKET:-없음}"
        echo "  L2 이상은 plan_docs/00-base_plan/YYYY/MM/ 에 plan 문서 생성 권장 (AGENT_GUIDE §3)"
        exit 0
    fi
    echo "✅ [GATE OK — L2] plan: $(basename "$PLAN_EXISTS") | $FILE_SHORT"
    exit 0
fi

# L1 — 통과 (escalation 메시지만 출력됨)
exit 0
