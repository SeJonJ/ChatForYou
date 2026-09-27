#!/bin/bash
# verify-frontend-test.sh — 프론트엔드 단위 테스트 게이트
#
# 목적: nodejs-frontend 의 Vitest 단위 테스트를 한 명령으로 결정론적으로 실행한다.
#       SAGE 프로필 verification.commands.test 에 배선되어 게이트로 동작한다.
#
# 사용:
#   scripts/verify-frontend-test.sh
#
# EXIT:
#   0  → PASS (모든 단위 테스트 통과)
#   1  → FAIL (테스트 실패)
#   3  → degrade (node 부재 또는 의존성 미설치)

set -uo pipefail

PROJECT_ROOT="${CODEX_PROJECT_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null)}"
[ -z "$PROJECT_ROOT" ] && PROJECT_ROOT="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "$0")/.." && pwd)}"

FRONTEND_DIR="$PROJECT_ROOT/nodejs-frontend"

echo "## Frontend Unit Test Gate"

if ! command -v node >/dev/null 2>&1; then
    echo "- 판정: DEGRADE — node 부재"
    exit 3
fi

if [ ! -x "$FRONTEND_DIR/node_modules/.bin/vitest" ]; then
    echo "- 판정: DEGRADE — vitest 미설치 (nodejs-frontend 에서 npm install 필요)"
    exit 3
fi

# vitest 는 테스트 파일이 하나도 없으면 비 0 으로 종료한다. 테스트 부재는 게이트가
# 판정할 대상이 아니라 구성 오류이므로 degrade 로 분리한다.
TEST_FILE_COUNT=$(find "$FRONTEND_DIR/test" -type f -name '*.test.js' 2>/dev/null | grep -c . || true)
if [ "$TEST_FILE_COUNT" -eq 0 ]; then
    echo "- 판정: DEGRADE — nodejs-frontend/test 에 테스트 파일 없음"
    exit 3
fi

echo "- 대상: $TEST_FILE_COUNT 개 테스트 파일"

( cd "$FRONTEND_DIR" && ./node_modules/.bin/vitest run )
RC=$?

if [ "$RC" -eq 0 ]; then
    echo "- 판정: PASS"
    exit 0
fi

echo "- 판정: FAIL — Vitest 단위 테스트 실패 (exit $RC)"
exit 1
