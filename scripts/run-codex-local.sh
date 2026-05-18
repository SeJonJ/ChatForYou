#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_CODEX_HOME="$ROOT/.codex"
HOME_CODEX_HOME="${HOME}/.codex"

mkdir -p "$PROJECT_CODEX_HOME"
mkdir -p "$PROJECT_CODEX_HOME/sessions"

if [ ! -e "$PROJECT_CODEX_HOME/auth.json" ] && [ -f "$HOME_CODEX_HOME/auth.json" ]; then
  if ! ln -s "$HOME_CODEX_HOME/auth.json" "$PROJECT_CODEX_HOME/auth.json" 2>/dev/null; then
    echo "warning: could not link auth.json into project .codex; run 'codex login' with CODEX_HOME set if needed" >&2
  fi
fi

CODEX_HOME="$PROJECT_CODEX_HOME" codex "$@"
