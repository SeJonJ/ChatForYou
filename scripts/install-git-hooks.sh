#!/usr/bin/env bash
# install-git-hooks.sh — 버전관리되는 hooks/ 디렉토리를 git 훅 경로로 등록
#
# core.hooksPath 를 레포의 hooks/ 로 지정한다. 이렇게 하면 훅이 git 추적되어
# 팀 전체가 동일 훅을 쓰고, hooks/ 수정이 즉시 반영된다(복사 불필요).
#
# 우회: git push --no-verify
# 해제: git config --unset core.hooksPath

set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || { echo "git 저장소가 아닙니다."; exit 1; }
HOOKS_DIR="$ROOT/hooks"

[ -d "$HOOKS_DIR" ] || { echo "hooks/ 디렉토리 없음: $HOOKS_DIR"; exit 1; }

chmod +x "$HOOKS_DIR"/* 2>/dev/null || true
git -C "$ROOT" config core.hooksPath "$HOOKS_DIR"

echo "✅ core.hooksPath = $HOOKS_DIR"
echo "   등록된 훅:"
for h in "$HOOKS_DIR"/*; do
    [ -f "$h" ] && echo "   - $(basename "$h")"
done
echo ""
echo "확인: git config core.hooksPath"
echo "해제: git config --unset core.hooksPath"
