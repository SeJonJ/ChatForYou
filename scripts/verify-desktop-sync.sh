#!/bin/bash
# verify-desktop-sync.sh — web↔desktop 동기화 freshness validator (CFY-FB-02)
#
# 목적: nodejs-frontend(web 원본)를 고쳤는데 chatforyou-desktop 재sync 를 빠뜨린 회귀,
#       또는 gitignore 된 desktop/src 산출물이 직접 수정되어 web 과 어긋난 상태를 감지한다.
#
# 배경: chatforyou-desktop/src/static·templates 는 .gitignore 대상(재생성 빌드 산출물)이라
#       git diff 로는 drift 를 볼 수 없다. 현재 산출물을 임시 target에 복제한 뒤 그 복제본에서
#       전체 sync를 실행하고 전후를 비교한다. 실제 Desktop target에서는 sync를 실행하지 않는다.
#
# 동기화 소스: nodejs-frontend/dist (vite build 산출물). dist 가 오래되면 web 원본을 고쳐도
#       비교가 통과하므로, 비교 전에 nodejs-frontend 를 먼저 빌드해 dist 를 원본과 맞춘다.
#       이 빌드는 파생물인 dist 만 다시 쓰고 Desktop 산출물은 건드리지 않는다.
#
# 사용:
#   scripts/verify-desktop-sync.sh
#
# EXIT:
#   0  → PASS (재sync 결과가 현재 산출물과 동일 = web↔desktop 동기 상태)
#   1  → FAIL (drift = 현재 산출물이 web 최신과 불일치, 재sync 필요)
#   3  → degrade (node·npm 부재 / 빌드 실패 / dist 부재 / sync 실행 실패)

set -uo pipefail

PROJECT_ROOT="${CODEX_PROJECT_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null)}"
[ -z "$PROJECT_ROOT" ] && PROJECT_ROOT="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "$0")/.." && pwd)}"

DESKTOP_DIR="$PROJECT_ROOT/chatforyou-desktop"
SRC="$DESKTOP_DIR/src"
FRONTEND_DIR="$PROJECT_ROOT/nodejs-frontend"
FRONTEND_DIST="$FRONTEND_DIR/dist"
EXTRA_BASELINE="$PROJECT_ROOT/scripts/desktop-sync-extra-baseline.txt"

if ! command -v node >/dev/null 2>&1; then
    echo "⚠️  node 부재 → desktop sync 검증 불가 (degrade)"
    exit 3
fi

echo "## Desktop Sync Freshness Validator (CFY-FB-02)"

if [ ! -d "$FRONTEND_DIR/static" ] || [ ! -d "$FRONTEND_DIR/templates" ]; then
    echo "- 판정: DEGRADE — nodejs-frontend 원본 디렉터리 누락"
    exit 3
fi

if ! command -v npm >/dev/null 2>&1; then
    echo "- 판정: DEGRADE — npm 부재로 nodejs-frontend 빌드 불가"
    exit 3
fi

BUILD_LOG=$(mktemp)
if ! (cd "$FRONTEND_DIR" && npm run build >"$BUILD_LOG" 2>&1); then
    echo "- 판정: DEGRADE — nodejs-frontend 빌드 실패 (npm run build)"
    tail -n 20 "$BUILD_LOG" | sed 's/^/  /'
    rm -f "$BUILD_LOG"
    exit 3
fi
rm -f "$BUILD_LOG"

if [ ! -d "$FRONTEND_DIST/static" ] || [ ! -d "$FRONTEND_DIST/templates" ] || [ ! -d "$FRONTEND_DIST/build" ]; then
    echo "- 판정: DEGRADE — 빌드 후에도 nodejs-frontend/dist 구성 누락"
    exit 3
fi

if [ ! -d "$SRC/static" ] || [ ! -d "$SRC/templates" ] || [ ! -d "$SRC/config" ] || [ ! -d "$SRC/build" ]; then
    echo "- 판정: FAIL — Desktop 동기화 산출물 누락"
    exit 1
fi

TEMP_ROOT=$(mktemp -d)
TEMP_DESKTOP="$TEMP_ROOT/chatforyou-desktop"
TEMP_BUILD_SCRIPTS="$TEMP_DESKTOP/build-scripts"
TEMP_SRC="$TEMP_DESKTOP/src"
SYNC_LOG="$TEMP_ROOT/sync.log"
DIFF_LOG="$TEMP_ROOT/diff.log"
EXPECTED_CONFIG="$TEMP_ROOT/expected-config"
ACTUAL_CONFIG="$TEMP_ROOT/actual-config"

cleanup() {
    rm -rf "$TEMP_ROOT"
}
trap cleanup EXIT INT TERM

# sync 실행에 필요한 코드만 임시 프로젝트로 복제한다. 실제 원본은 읽기 전용 심볼릭 링크로 연결한다.
mkdir -p "$TEMP_BUILD_SCRIPTS"
cp -a "$DESKTOP_DIR/build-scripts/lib" "$TEMP_BUILD_SCRIPTS/lib"
cp "$DESKTOP_DIR/build-scripts/sync-frontend.js" "$TEMP_BUILD_SCRIPTS/sync-frontend.js"
cp "$DESKTOP_DIR/build-scripts/convert_path.json" "$TEMP_BUILD_SCRIPTS/convert_path.json"
cp "$DESKTOP_DIR/package.json" "$TEMP_DESKTOP/package.json"
mkdir -p "$TEMP_SRC"
ln -s "$FRONTEND_DIR" "$TEMP_ROOT/nodejs-frontend"
# sync 는 이 목록에 있는 Desktop 전용 파일을 정리 대상에서 뺀다. 실제 sync 와 같은 목록을 보게 한다.
mkdir -p "$TEMP_ROOT/scripts"
if [ -f "$EXTRA_BASELINE" ]; then
    cp "$EXTRA_BASELINE" "$TEMP_ROOT/scripts/desktop-sync-extra-baseline.txt"
else
    : > "$TEMP_ROOT/scripts/desktop-sync-extra-baseline.txt"
fi

# Desktop 전용 산출물은 명시된 baseline만 예상 결과에 포함한다.
if [ -f "$EXTRA_BASELINE" ]; then
    while IFS= read -r relative_path || [ -n "$relative_path" ]; do
        relative_path="${relative_path%$'\r'}"
        case "$relative_path" in
            ''|\#*) continue ;;
            /*|.|..|./*|../*|*/./*|*/../*|*/.|*/..)
                echo "- 판정: DEGRADE — 허용되지 않는 Desktop 전용 baseline 경로: $relative_path"
                exit 3
                ;;
        esac

        source_path="$SRC/$relative_path"
        target_path="$TEMP_SRC/$relative_path"
        # 빌드 산출물과 겹치면 sync 가 덮어쓰고, web 원본과 겹치면 전환된 원본 경로를 Desktop 전용으로 숨긴다.
        if [ -e "$FRONTEND_DIST/$relative_path" ] || [ -e "$FRONTEND_DIR/$relative_path" ]; then
            echo "- 판정: DEGRADE — Desktop 전용 baseline이 web 원본과 중복됨: $relative_path"
            exit 3
        fi
        if [ ! -f "$source_path" ]; then
            echo "- 판정: DEGRADE — 실제 파일이 없는 stale Desktop 전용 baseline: $relative_path"
            exit 3
        fi

        mkdir -p "$(dirname "$target_path")"
        cp -p "$source_path" "$target_path"
    done < "$EXTRA_BASELINE"
fi

# 복제한 임시 target에 전체 sync 실행. 실제 Desktop src와 backup은 접근하지 않는다.
if ! (cd "$TEMP_DESKTOP" && node build-scripts/sync-frontend.js --skip-backup --skip-build >"$SYNC_LOG" 2>&1); then
    echo "- 판정: DEGRADE — 임시 target sync 실행 실패"
    sed -n '1,40p' "$SYNC_LOG" | sed 's/^/  /'
    exit 3
fi

# config 생성 시각만 제거하고 나머지 설정 내용은 비교한다.
normalize_config_tree() {
    source_dir="$1"
    target_dir="$2"

    mkdir -p "$target_dir"
    if ! node - "$source_dir" "$target_dir" <<'NODE'
const fs = require('fs');
const path = require('path');

const [sourceDir, targetDir] = process.argv.slice(2);
// sync가 생성하는 config.js/config.local.js/config.prod.js만 비교한다.
// src/config/.backup 같은 보관용 하위 디렉터리는 동기화 freshness 대상이 아니다.
for (const entry of fs.readdirSync(sourceDir, { withFileTypes: true })) {
  if (!entry.isFile()) {
    continue;
  }

  const sourcePath = path.join(sourceDir, entry.name);
  const targetPath = path.join(targetDir, entry.name);
  const normalized = fs.readFileSync(sourcePath, 'utf8')
    .replace(
      /^\/\/ Auto-generated from web config on .*$/m,
      '// Auto-generated from web config on <normalized>'
    )
    .replace(
      /"CONVERSION_DATE":\s*"[^"]+"/g,
      '"CONVERSION_DATE": "<normalized>"'
    );
  fs.writeFileSync(targetPath, normalized);
}
NODE
    then
        return 1
    fi
}

if ! normalize_config_tree "$TEMP_SRC/config" "$EXPECTED_CONFIG" ||
   ! normalize_config_tree "$SRC/config" "$ACTUAL_CONFIG"; then
    echo "- 판정: DEGRADE — config 정규화 실패"
    exit 3
fi

DRIFT_FOUND=0
COMPARE_ERROR=0

compare_tree() {
    label="$1"
    expected="$2"
    actual="$3"
    exclude_pattern="${4:-}"
    second_exclude_pattern="${5:-}"
    output_file="$TEMP_ROOT/diff-$label.log"

    if [ -n "$second_exclude_pattern" ]; then
        diff -r -x "$exclude_pattern" -x "$second_exclude_pattern" "$expected" "$actual" >"$output_file" 2>&1
    elif [ -n "$exclude_pattern" ]; then
        diff -r -x "$exclude_pattern" "$expected" "$actual" >"$output_file" 2>&1
    else
        diff -r "$expected" "$actual" >"$output_file" 2>&1
    fi
    diff_rc=$?

    case "$diff_rc" in
        0)
            return
            ;;
        1)
            DRIFT_FOUND=1
            printf '[%s]\n' "$label" >> "$DIFF_LOG"
            cat "$output_file" >> "$DIFF_LOG"
            ;;
        *)
            COMPARE_ERROR=1
            printf '[%s] diff exit %s\n' "$label" "$diff_rc" >> "$DIFF_LOG"
            cat "$output_file" >> "$DIFF_LOG"
            ;;
    esac
}

# sync-engine 자체가 *.map을 복사 대상에서 제외하므로 비교에서도 방어적으로 제외한다.
compare_tree static "$TEMP_SRC/static" "$SRC/static" '*.map' '.DS_Store'
compare_tree templates "$TEMP_SRC/templates" "$SRC/templates" '.DS_Store'
compare_tree build "$TEMP_SRC/build" "$SRC/build" '.DS_Store'
compare_tree config "$EXPECTED_CONFIG" "$ACTUAL_CONFIG" '.DS_Store'

if [ "$DRIFT_FOUND" -ne 0 ]; then
    echo "- 판정: FAIL — 재sync 결과가 현재 산출물과 불일치 (web↔desktop drift, 재sync 필요)"
    sed -n '1,40p' "$DIFF_LOG" | sed 's/^/  /'
    exit 1
fi

if [ "$COMPARE_ERROR" -ne 0 ]; then
    echo "- 판정: DEGRADE — 산출물 비교 중 I/O 오류"
    sed -n '1,40p' "$DIFF_LOG" | sed 's/^/  /'
    exit 3
fi

echo "- 판정: PASS — 재sync 해도 변경 없음 (web↔desktop 동기 상태)"
exit 0
