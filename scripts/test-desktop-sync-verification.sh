#!/bin/bash
# Desktop sync 검증 회귀 테스트.
# 실제 프로젝트 산출물 대신 임시 프로젝트에서 dry-run 및 freshness 검증의 비변형 계약을 확인한다.

set -uo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FIXTURE_ROOT=$(mktemp -d)
FIXTURE_DESKTOP="$FIXTURE_ROOT/chatforyou-desktop"
FIXTURE_FRONTEND="$FIXTURE_ROOT/nodejs-frontend"
FIXTURE_SCRIPTS="$FIXTURE_ROOT/scripts"

cleanup() {
    rm -rf "$FIXTURE_ROOT"
}
trap cleanup EXIT INT TERM

PASS=0
FAIL=0

ok() {
    PASS=$((PASS + 1))
    echo "  PASS: $1"
}

bad() {
    FAIL=$((FAIL + 1))
    echo "  FAIL: $1"
}

expect_rc() {
    if [ "$2" -eq "$3" ]; then
        ok "$1 (rc=$3)"
    else
        bad "$1 (expected rc=$2, got $3)"
    fi
}

tree_digest() {
    local target="$1"

    if [ ! -e "$target" ]; then
        printf 'MISSING\n'
        return
    fi

    (
        cd "$target" || exit 1
        find . -print | LC_ALL=C sort | while IFS= read -r entry; do
            if [ -L "$entry" ]; then
                printf 'L %s %s\n' "$entry" "$(readlink "$entry")"
            elif [ -f "$entry" ]; then
                if stat -f '%Sp %m' "$entry" >/dev/null 2>&1; then
                    metadata=$(stat -f '%Sp %m' "$entry")
                else
                    metadata=$(stat -c '%A %Y' "$entry")
                fi
                printf 'F %s %s ' "$entry" "$metadata"
                shasum -a 256 "$entry" | awk '{print $1}'
            elif [ -d "$entry" ]; then
                if stat -f '%Sp %m' "$entry" >/dev/null 2>&1; then
                    metadata=$(stat -f '%Sp %m' "$entry")
                else
                    metadata=$(stat -c '%A %Y' "$entry")
                fi
                printf 'D %s %s\n' "$entry" "$metadata"
            fi
        done
    ) | shasum -a 256 | awk '{print $1}'
}

mkdir -p \
    "$FIXTURE_DESKTOP/build-scripts" \
    "$FIXTURE_FRONTEND/static/js" \
    "$FIXTURE_FRONTEND/templates" \
    "$FIXTURE_FRONTEND/config" \
    "$FIXTURE_SCRIPTS"

cp -a "$PROJECT_ROOT/chatforyou-desktop/build-scripts/lib" "$FIXTURE_DESKTOP/build-scripts/lib"
cp "$PROJECT_ROOT/chatforyou-desktop/build-scripts/sync-frontend.js" "$FIXTURE_DESKTOP/build-scripts/sync-frontend.js"
cp "$PROJECT_ROOT/chatforyou-desktop/build-scripts/convert_path.json" "$FIXTURE_DESKTOP/build-scripts/convert_path.json"
cp "$PROJECT_ROOT/chatforyou-desktop/package.json" "$FIXTURE_DESKTOP/package.json"
cp "$PROJECT_ROOT/scripts/verify-desktop-sync.sh" "$FIXTURE_SCRIPTS/verify-desktop-sync.sh"
: > "$FIXTURE_SCRIPTS/desktop-sync-extra-baseline.txt"

printf 'console.log("fixture");\n' > "$FIXTURE_FRONTEND/static/js/app.js"
printf '<main>fixture</main>\n' > "$FIXTURE_FRONTEND/templates/index.html"
printf 'window.__CONFIG__ = { API_BASE_URL: "http://localhost" };\n' > "$FIXTURE_FRONTEND/config/config.js"
cp "$FIXTURE_FRONTEND/config/config.js" "$FIXTURE_FRONTEND/config/config.local.js"
cp "$FIXTURE_FRONTEND/config/config.js" "$FIXTURE_FRONTEND/config/config.prod.js"

# vite build 대역. 실제 빌드처럼 매번 dist 를 비우고 static·templates·config 와 build 청크를 만든다.
# 동기화와 검증 게이트는 dist 를 소비하기 전에 이 빌드를 먼저 부르므로 원본 변경이 곧바로 비교에 반영된다.
cat > "$FIXTURE_FRONTEND/fixture-build.js" <<'BUILD'
const fs = require('fs');
const path = require('path');
if (fs.existsSync(path.join(__dirname, 'FAIL_BUILD'))) {
    console.error('fixture build failure');
    process.exit(1);
}
const dist = path.join(__dirname, 'dist');
fs.rmSync(dist, { recursive: true, force: true });
// PARTIAL_BUILD 는 static 복사 도중 실패한 빌드를 흉내 낸다. app.js 가 빠지고 완료 표식이 없다.
if (fs.existsSync(path.join(__dirname, 'PARTIAL_BUILD'))) {
    fs.cpSync(path.join(__dirname, 'templates'), path.join(dist, 'templates'), { recursive: true });
    fs.cpSync(path.join(__dirname, 'static'), path.join(dist, 'static'), {
        recursive: true,
        filter: (source) => !source.endsWith(`${path.sep}app.js`)
    });
    fs.mkdirSync(path.join(dist, 'config'), { recursive: true });
    fs.copyFileSync(path.join(__dirname, 'config', 'config.js'), path.join(dist, 'config', 'config.js'));
    fs.mkdirSync(path.join(dist, 'build'), { recursive: true });
    fs.writeFileSync(path.join(dist, 'build', 'entry.js'), 'export {};\n');
    console.error('fixture partial build failure');
    process.exit(1);
}
// static/js/entry 는 번들에 흡수되는 모듈 소스 대역이라 dist 로 복사하지 않는다.
fs.cpSync(path.join(__dirname, 'static'), path.join(dist, 'static'), {
    recursive: true,
    filter: (source) => !source.includes(`${path.sep}static${path.sep}js${path.sep}entry`)
});
fs.cpSync(path.join(__dirname, 'templates'), path.join(dist, 'templates'), { recursive: true });
fs.mkdirSync(path.join(dist, 'config'), { recursive: true });
fs.copyFileSync(path.join(__dirname, 'config', 'config.js'), path.join(dist, 'config', 'config.js'));
fs.mkdirSync(path.join(dist, 'build'), { recursive: true });
fs.writeFileSync(path.join(dist, 'build', 'entry.js'), 'export {};\n');
fs.writeFileSync(path.join(dist, '.build-complete'), 'fixture\n');
BUILD
printf '{ "name": "fixture-frontend", "private": true, "scripts": { "build": "node fixture-build.js" } }\n' > "$FIXTURE_FRONTEND/package.json"

(
    cd "$FIXTURE_DESKTOP" &&
    node build-scripts/sync-frontend.js --skip-backup >/dev/null 2>&1
) || {
    echo "fixture 초기 sync 실패"
    exit 1
}

echo "## Desktop dry-run 비변형"
BEFORE_DRY_RUN=$(tree_digest "$FIXTURE_DESKTOP")
(
    cd "$FIXTURE_DESKTOP" &&
    node build-scripts/sync-frontend.js --dry-run --skip-backup >/dev/null 2>&1
)
DRY_RUN_RC=$?
AFTER_DRY_RUN=$(tree_digest "$FIXTURE_DESKTOP")
expect_rc "dry-run 실행 성공" 0 "$DRY_RUN_RC"
if [ "$BEFORE_DRY_RUN" = "$AFTER_DRY_RUN" ]; then
    ok "dry-run 후 Desktop 트리 불변"
else
    bad "dry-run 후 Desktop 트리가 변경됨"
fi

echo "## Desktop tree digest 메타데이터 감지"
BEFORE_MODE_CHANGE=$(tree_digest "$FIXTURE_DESKTOP")
chmod +x "$FIXTURE_DESKTOP/src/static/js/app.js"
AFTER_MODE_CHANGE=$(tree_digest "$FIXTURE_DESKTOP")
chmod -x "$FIXTURE_DESKTOP/src/static/js/app.js"
if [ "$BEFORE_MODE_CHANGE" != "$AFTER_MODE_CHANGE" ]; then
    ok "파일 mode 변경 감지"
else
    bad "파일 mode 변경을 digest가 놓침"
fi

echo "## Desktop sync check clean 비변형"
BEFORE_CLEAN_CHECK=$(tree_digest "$FIXTURE_DESKTOP")
CODEX_PROJECT_ROOT="$FIXTURE_ROOT" bash "$FIXTURE_SCRIPTS/verify-desktop-sync.sh" >/dev/null 2>&1
CLEAN_RC=$?
AFTER_CLEAN_CHECK=$(tree_digest "$FIXTURE_DESKTOP")
expect_rc "clean baseline PASS" 0 "$CLEAN_RC"
if [ "$BEFORE_CLEAN_CHECK" = "$AFTER_CLEAN_CHECK" ]; then
    ok "clean 검증 후 Desktop 트리 불변"
else
    bad "clean 검증 후 Desktop 트리가 변경됨"
fi

echo "## Desktop sync check macOS 메타파일 제외"
printf 'metadata\n' > "$FIXTURE_DESKTOP/src/static/.DS_Store"
printf 'metadata\n' > "$FIXTURE_DESKTOP/src/config/.DS_Store"
CODEX_PROJECT_ROOT="$FIXTURE_ROOT" bash "$FIXTURE_SCRIPTS/verify-desktop-sync.sh" >/dev/null 2>&1
STATIC_DS_STORE_RC=$?
expect_rc "static/config .DS_Store drift 제외" 0 "$STATIC_DS_STORE_RC"
rm "$FIXTURE_DESKTOP/src/static/.DS_Store"
rm "$FIXTURE_DESKTOP/src/config/.DS_Store"

echo "## Desktop sync check drift 비변형"
printf 'console.log("drift");\n' > "$FIXTURE_FRONTEND/static/js/app.js"
BEFORE_DRIFT_CHECK=$(tree_digest "$FIXTURE_DESKTOP")
CODEX_PROJECT_ROOT="$FIXTURE_ROOT" bash "$FIXTURE_SCRIPTS/verify-desktop-sync.sh" >/dev/null 2>&1
DRIFT_RC=$?
AFTER_DRIFT_CHECK=$(tree_digest "$FIXTURE_DESKTOP")
expect_rc "source drift FAIL" 1 "$DRIFT_RC"
if [ "$BEFORE_DRIFT_CHECK" = "$AFTER_DRIFT_CHECK" ]; then
    ok "drift 검증 후 Desktop 트리 불변"
else
    bad "drift 검증 후 Desktop 트리가 변경됨"
fi

printf 'console.log("fixture");\n' > "$FIXTURE_FRONTEND/static/js/app.js"
(
    cd "$FIXTURE_DESKTOP" &&
    node build-scripts/sync-frontend.js --skip-backup >/dev/null 2>&1
) || exit 1

echo "## Desktop sync check source 삭제"
rm "$FIXTURE_FRONTEND/static/js/app.js"
CODEX_PROJECT_ROOT="$FIXTURE_ROOT" bash "$FIXTURE_SCRIPTS/verify-desktop-sync.sh" >/dev/null 2>&1
SOURCE_DELETE_RC=$?
expect_rc "source 삭제로 남은 stale target FAIL" 1 "$SOURCE_DELETE_RC"
printf 'console.log("fixture");\n' > "$FIXTURE_FRONTEND/static/js/app.js"

echo "## Desktop sync check target-only baseline"
printf 'console.log("electron-only");\n' > "$FIXTURE_DESKTOP/src/static/js/electron-only.js"
printf 'static/js/electron-only.js\n' > "$FIXTURE_SCRIPTS/desktop-sync-extra-baseline.txt"
CODEX_PROJECT_ROOT="$FIXTURE_ROOT" bash "$FIXTURE_SCRIPTS/verify-desktop-sync.sh" >/dev/null 2>&1
EXTRA_BASELINE_RC=$?
expect_rc "승인된 target-only 파일 PASS" 0 "$EXTRA_BASELINE_RC"

printf 'console.log("web-copy");\n' > "$FIXTURE_FRONTEND/static/js/electron-only.js"
CODEX_PROJECT_ROOT="$FIXTURE_ROOT" bash "$FIXTURE_SCRIPTS/verify-desktop-sync.sh" >/dev/null 2>&1
NON_DESKTOP_BASELINE_RC=$?
expect_rc "web에도 존재하는 baseline 항목 DEGRADE" 3 "$NON_DESKTOP_BASELINE_RC"
rm "$FIXTURE_FRONTEND/static/js/electron-only.js"
rm "$FIXTURE_DESKTOP/src/static/js/electron-only.js"

CODEX_PROJECT_ROOT="$FIXTURE_ROOT" bash "$FIXTURE_SCRIPTS/verify-desktop-sync.sh" >/dev/null 2>&1
STALE_BASELINE_RC=$?
expect_rc "실제 파일이 없는 stale baseline DEGRADE" 3 "$STALE_BASELINE_RC"
: > "$FIXTURE_SCRIPTS/desktop-sync-extra-baseline.txt"

echo "## Desktop sync check CRLF 및 점 포함 baseline 경로"
printf 'console.log("version");\n' > "$FIXTURE_DESKTOP/src/static/js/version..desktop.js"
printf 'static/js/version..desktop.js\r\n' > "$FIXTURE_SCRIPTS/desktop-sync-extra-baseline.txt"
CODEX_PROJECT_ROOT="$FIXTURE_ROOT" bash "$FIXTURE_SCRIPTS/verify-desktop-sync.sh" >/dev/null 2>&1
CRLF_BASELINE_RC=$?
expect_rc "CRLF 및 점 포함 정상 경로 PASS" 0 "$CRLF_BASELINE_RC"
rm "$FIXTURE_DESKTOP/src/static/js/version..desktop.js"
: > "$FIXTURE_SCRIPTS/desktop-sync-extra-baseline.txt"

echo "## Desktop sync check config drift"
printf 'window.__CONFIG__ = { API_BASE_URL: "http://changed" };\n' > "$FIXTURE_FRONTEND/config/config.local.js"
CODEX_PROJECT_ROOT="$FIXTURE_ROOT" bash "$FIXTURE_SCRIPTS/verify-desktop-sync.sh" >/dev/null 2>&1
CONFIG_DRIFT_RC=$?
expect_rc "시간 필드 외 config 변경 FAIL" 1 "$CONFIG_DRIFT_RC"
cp "$FIXTURE_FRONTEND/config/config.js" "$FIXTURE_FRONTEND/config/config.local.js"

echo "## Desktop sync check 빌드 실패"
touch "$FIXTURE_FRONTEND/FAIL_BUILD"
BEFORE_BUILD_FAIL=$(tree_digest "$FIXTURE_DESKTOP")
CODEX_PROJECT_ROOT="$FIXTURE_ROOT" bash "$FIXTURE_SCRIPTS/verify-desktop-sync.sh" >/dev/null 2>&1
BUILD_FAIL_RC=$?
AFTER_BUILD_FAIL=$(tree_digest "$FIXTURE_DESKTOP")
expect_rc "빌드 실패 DEGRADE" 3 "$BUILD_FAIL_RC"
if [ "$BEFORE_BUILD_FAIL" = "$AFTER_BUILD_FAIL" ]; then
    ok "빌드 실패 후 Desktop 트리 불변"
else
    bad "빌드 실패 검증이 Desktop 트리를 변경함"
fi
(
    cd "$FIXTURE_DESKTOP" &&
    node build-scripts/sync-frontend.js --skip-backup >/dev/null 2>&1
)
expect_rc "빌드 실패 시 sync 도 중단" 1 $?
rm "$FIXTURE_FRONTEND/FAIL_BUILD"

echo "## Desktop sync 번들로 대체된 사본 정리"
# 모듈 전환 전에 동기화된 classic 사본이 남아 있는 Desktop 트리를 재현한다.
mkdir -p "$FIXTURE_FRONTEND/static/js/entry" "$FIXTURE_DESKTOP/src/static/js/entry"
printf 'export const entry = 1;\n' > "$FIXTURE_FRONTEND/static/js/entry/main.js"
printf 'console.log("classic");\n' > "$FIXTURE_DESKTOP/src/static/js/entry/main.js"
printf 'console.log("electron-only");\n' > "$FIXTURE_DESKTOP/src/static/js/electron-only.js"
printf 'static/js/electron-only.js\n' > "$FIXTURE_SCRIPTS/desktop-sync-extra-baseline.txt"
CODEX_PROJECT_ROOT="$FIXTURE_ROOT" bash "$FIXTURE_SCRIPTS/verify-desktop-sync.sh" >/dev/null 2>&1
expect_rc "남은 classic 사본 drift FAIL" 1 $?
(
    cd "$FIXTURE_DESKTOP" &&
    node build-scripts/sync-frontend.js --dry-run --skip-backup >/dev/null 2>&1
)
if [ -f "$FIXTURE_DESKTOP/src/static/js/entry/main.js" ]; then
    ok "dry-run 은 사본을 지우지 않음"
else
    bad "dry-run 이 사본을 지움"
fi
(
    cd "$FIXTURE_DESKTOP" &&
    node build-scripts/sync-frontend.js --skip-backup >/dev/null 2>&1
)
expect_rc "사본 정리 sync 성공" 0 $?
if [ ! -e "$FIXTURE_DESKTOP/src/static/js/entry" ]; then
    ok "번들로 대체된 사본과 빈 디렉터리 삭제"
else
    bad "번들로 대체된 사본이 남음"
fi
if [ -f "$FIXTURE_DESKTOP/src/static/js/electron-only.js" ]; then
    ok "Desktop 전용 파일 보존"
else
    bad "Desktop 전용 파일이 삭제됨"
fi
CODEX_PROJECT_ROOT="$FIXTURE_ROOT" bash "$FIXTURE_SCRIPTS/verify-desktop-sync.sh" >/dev/null 2>&1
expect_rc "정리 후 PASS" 0 $?
rm -rf "$FIXTURE_FRONTEND/static/js/entry"
rm "$FIXTURE_DESKTOP/src/static/js/electron-only.js"
: > "$FIXTURE_SCRIPTS/desktop-sync-extra-baseline.txt"

echo "## Desktop sync 완성되지 않은 dist 거부"
touch "$FIXTURE_FRONTEND/PARTIAL_BUILD"
(cd "$FIXTURE_FRONTEND" && npm run build >/dev/null 2>&1)
BEFORE_PARTIAL=$(tree_digest "$FIXTURE_DESKTOP")
(
    cd "$FIXTURE_DESKTOP" &&
    node build-scripts/sync-frontend.js --skip-build --skip-backup >/dev/null 2>&1
)
expect_rc "완료 표식 없는 dist 로 sync 중단" 1 $?
AFTER_PARTIAL=$(tree_digest "$FIXTURE_DESKTOP")
if [ "$BEFORE_PARTIAL" = "$AFTER_PARTIAL" ]; then
    ok "부분 빌드 dist 로 Desktop 트리 불변"
else
    bad "부분 빌드 dist 가 Desktop 트리를 변경함"
fi
rm "$FIXTURE_FRONTEND/PARTIAL_BUILD"
(cd "$FIXTURE_FRONTEND" && npm run build >/dev/null 2>&1) || exit 1

echo "## Desktop sync 동기화 중 dist 재빌드 감지"
# 1단계 동기화 직후 다른 빌드가 dist 를 다시 쓰기 시작한 상황: 표식이 새로 생기고 app.js 가 아직 없다.
cat > "$FIXTURE_DESKTOP/race-harness.js" <<'HARNESS'
const fs = require('fs');
const path = require('path');
const FrontendSyncScript = require('./build-scripts/sync-frontend.js');
const script = new FrontendSyncScript();
const originalSync = script.syncEngine.sync.bind(script.syncEngine);
script.syncEngine.sync = async (...args) => {
    const result = await originalSync(...args);
    const dist = path.join(script.frontendDistPath);
    fs.rmSync(path.join(dist, '.build-complete'), { force: true });
    fs.rmSync(path.join(dist, 'static', 'js', 'app.js'), { force: true });
    fs.writeFileSync(path.join(dist, '.build-complete'), 'rebuilt\n');
    return result;
};
script.run();
HARNESS
(
    cd "$FIXTURE_DESKTOP" &&
    node race-harness.js --skip-backup >/dev/null 2>&1
)
expect_rc "동기화 중 재빌드된 dist 로 sync 중단" 1 $?
if [ -f "$FIXTURE_DESKTOP/src/static/js/app.js" ]; then
    ok "재빌드 중인 dist 로 Desktop 사본을 지우지 않음"
else
    bad "재빌드 중인 dist 때문에 Desktop 사본이 삭제됨"
fi
rm "$FIXTURE_DESKTOP/race-harness.js"
(cd "$FIXTURE_FRONTEND" && npm run build >/dev/null 2>&1) || exit 1
(
    cd "$FIXTURE_DESKTOP" &&
    node build-scripts/sync-frontend.js --skip-backup >/dev/null 2>&1
) || exit 1

echo "## Desktop sync 정리 목록 확정 후 dist 재빌드 감지"
# 삭제 목록을 만드는 도중 다른 빌드가 dist 를 비운 상황: 목록에는 살아 있어야 할 파일까지 담긴다.
cat > "$FIXTURE_DESKTOP/prune-race-harness.js" <<'HARNESS'
const fs = require('fs');
const path = require('path');
const FrontendSyncScript = require('./build-scripts/sync-frontend.js');
const script = new FrontendSyncScript();
const originalPrune = script.pruneBundledSources.bind(script);
script.pruneBundledSources = async () => {
    const originalExistsSync = fs.existsSync;
    let fired = false;
    // 목록을 만드는 첫 dist 조회 시점에 재빌드가 시작된 것으로 꾸민다.
    fs.existsSync = (target) => {
        if (!fired && String(target).startsWith(path.join(script.frontendDistPath, 'static'))) {
            fired = true;
            fs.rmSync(path.join(script.frontendDistPath, 'static'), { recursive: true, force: true });
            fs.rmSync(path.join(script.frontendDistPath, '.build-complete'), { force: true });
            fs.writeFileSync(path.join(script.frontendDistPath, '.build-complete'), 'rebuilt\n');
        }
        return originalExistsSync(target);
    };
    try {
        return await originalPrune();
    } finally {
        fs.existsSync = originalExistsSync;
    }
};
script.run();
HARNESS
(
    cd "$FIXTURE_DESKTOP" &&
    node prune-race-harness.js --skip-backup >/dev/null 2>&1
)
expect_rc "정리 도중 재빌드된 dist 로 sync 중단" 1 $?
if [ -f "$FIXTURE_DESKTOP/src/static/js/app.js" ]; then
    ok "정리 도중 재빌드에도 Desktop 사본 보존"
else
    bad "정리 도중 재빌드로 Desktop 사본이 삭제됨"
fi
rm "$FIXTURE_DESKTOP/prune-race-harness.js"
(cd "$FIXTURE_FRONTEND" && npm run build >/dev/null 2>&1) || exit 1
(
    cd "$FIXTURE_DESKTOP" &&
    node build-scripts/sync-frontend.js --skip-backup >/dev/null 2>&1
) || exit 1

echo "## Desktop sync 빌드 청크 복사 실패 시 기존 청크 보존"
# 청크 복사가 도중에 실패하면 이전 sync 의 src/build 가 그대로 남아야 한다. 먼저 지우고 복사하면 비어 버린다.
printf 'previous\n' > "$FIXTURE_DESKTOP/src/build/entry.js"
cat > "$FIXTURE_DESKTOP/copy-fail-harness.js" <<'HARNESS'
const fs = require('fs');
const path = require('path');
const FrontendSyncScript = require('./build-scripts/sync-frontend.js');
const script = new FrontendSyncScript();
const sourceBuildDir = path.join(script.frontendDistPath, 'build');
const originalCpSync = fs.cpSync;
fs.cpSync = (source, destination, options) => {
    if (path.resolve(String(source)) === sourceBuildDir) {
        fs.mkdirSync(destination, { recursive: true });
        fs.writeFileSync(path.join(destination, 'partial.js'), 'partial\n');
        throw Object.assign(new Error('ENOSPC: no space left on device'), { code: 'ENOSPC' });
    }
    return originalCpSync(source, destination, options);
};
script.run();
HARNESS
(
    cd "$FIXTURE_DESKTOP" &&
    node copy-fail-harness.js --skip-backup >/dev/null 2>&1
)
expect_rc "청크 복사 실패로 sync 중단" 1 $?
if [ "$(cat "$FIXTURE_DESKTOP/src/build/entry.js" 2>/dev/null)" = "previous" ] && [ ! -e "$FIXTURE_DESKTOP/src/build/partial.js" ]; then
    ok "복사 실패 후 기존 src/build 그대로 유지"
else
    bad "복사 실패로 기존 src/build 가 지워지거나 일부만 바뀜"
fi
if ls -d "$FIXTURE_DESKTOP"/src/build.* >/dev/null 2>&1; then
    bad "복사 실패 후 임시 청크 디렉터리가 남음"
else
    ok "복사 실패 후 임시 청크 디렉터리 정리"
fi
rm "$FIXTURE_DESKTOP/copy-fail-harness.js"

echo "## Desktop sync 빌드 청크 대상이 없거나 이전 임시 디렉터리가 남은 경우"
# 처음 sync 하는 트리(src/build 없음)와, 강제 종료된 sync 가 남긴 임시 디렉터리가 있는 트리 모두 정상 교체돼야 한다.
rm -rf "$FIXTURE_DESKTOP/src/build"
mkdir -p "$FIXTURE_DESKTOP/src/build.sync-staging"
printf 'stale\n' > "$FIXTURE_DESKTOP/src/build.sync-staging/stale.js"
(
    cd "$FIXTURE_DESKTOP" &&
    node build-scripts/sync-frontend.js --skip-backup >/dev/null 2>&1
)
expect_rc "src/build 없는 트리 sync 성공" 0 $?
if [ -f "$FIXTURE_DESKTOP/src/build/entry.js" ] && [ ! -e "$FIXTURE_DESKTOP/src/build/stale.js" ] && ! ls -d "$FIXTURE_DESKTOP"/src/build.* >/dev/null 2>&1; then
    ok "dist 청크로 src/build 생성, 남은 임시 디렉터리 제거"
else
    bad "src/build 생성 또는 남은 임시 디렉터리 정리 실패"
fi

echo "## Desktop sync web 에서 삭제된 파일 정리"
# web 원본에서 지운 파일의 Desktop 사본은 다시 sync 하면 사라져야 한다. 남으면 검증이 영구히 drift 를 낸다.
printf 'console.log("retired");\n' > "$FIXTURE_FRONTEND/static/js/retired.js"
(cd "$FIXTURE_FRONTEND" && npm run build >/dev/null 2>&1) || exit 1
(cd "$FIXTURE_DESKTOP" && node build-scripts/sync-frontend.js --skip-backup >/dev/null 2>&1) || exit 1
rm "$FIXTURE_FRONTEND/static/js/retired.js"
(cd "$FIXTURE_DESKTOP" && node build-scripts/sync-frontend.js --skip-backup >/dev/null 2>&1)
expect_rc "web 삭제 후 재sync 성공" 0 $?
if [ -e "$FIXTURE_DESKTOP/src/static/js/retired.js" ]; then
    bad "web 에서 삭제된 파일의 Desktop 사본이 남음"
else
    ok "web 에서 삭제된 파일의 Desktop 사본 정리"
fi
CODEX_PROJECT_ROOT="$FIXTURE_ROOT" bash "$FIXTURE_SCRIPTS/verify-desktop-sync.sh" >/dev/null 2>&1
expect_rc "web 삭제 후 재sync 한 트리 PASS" 0 $?

echo "## Desktop sync Desktop 전용 baseline 보존"
printf 'console.log("electron-only");\n' > "$FIXTURE_DESKTOP/src/static/js/electron-only.js"
printf 'static/js/electron-only.js\n' > "$FIXTURE_SCRIPTS/desktop-sync-extra-baseline.txt"
(cd "$FIXTURE_DESKTOP" && node build-scripts/sync-frontend.js --skip-backup >/dev/null 2>&1)
expect_rc "baseline 파일이 있는 트리 sync 성공" 0 $?
if [ -f "$FIXTURE_DESKTOP/src/static/js/electron-only.js" ]; then
    ok "baseline 에 있는 Desktop 전용 파일 보존"
else
    bad "baseline 에 있는 Desktop 전용 파일이 삭제됨"
fi
mv "$FIXTURE_SCRIPTS/desktop-sync-extra-baseline.txt" "$FIXTURE_SCRIPTS/baseline.moved"
(cd "$FIXTURE_DESKTOP" && node build-scripts/sync-frontend.js --skip-backup >/dev/null 2>&1)
expect_rc "baseline 목록 없이 sync 중단" 1 $?
if [ -f "$FIXTURE_DESKTOP/src/static/js/electron-only.js" ]; then
    ok "baseline 목록이 없어도 Desktop 전용 파일 보존"
else
    bad "baseline 목록이 없을 때 Desktop 전용 파일이 삭제됨"
fi
mv "$FIXTURE_SCRIPTS/baseline.moved" "$FIXTURE_SCRIPTS/desktop-sync-extra-baseline.txt"
rm "$FIXTURE_DESKTOP/src/static/js/electron-only.js"
: > "$FIXTURE_SCRIPTS/desktop-sync-extra-baseline.txt"

echo "## Desktop sync check dist 에 없는 web 원본 baseline"
# 번들에 흡수돼 dist 에 없는 web 원본 경로를 Desktop 전용으로 등록하면 전환된 원본의 사본을 숨기게 된다.
mkdir -p "$FIXTURE_FRONTEND/static/js/entry" "$FIXTURE_DESKTOP/src/static/js/entry"
printf 'export const entry = 1;\n' > "$FIXTURE_FRONTEND/static/js/entry/bundled.js"
cp "$FIXTURE_FRONTEND/static/js/entry/bundled.js" "$FIXTURE_DESKTOP/src/static/js/entry/bundled.js"
printf 'static/js/entry/bundled.js\n' > "$FIXTURE_SCRIPTS/desktop-sync-extra-baseline.txt"
CODEX_PROJECT_ROOT="$FIXTURE_ROOT" bash "$FIXTURE_SCRIPTS/verify-desktop-sync.sh" >/dev/null 2>&1
expect_rc "dist 에 없는 web 원본 baseline DEGRADE" 3 $?
: > "$FIXTURE_SCRIPTS/desktop-sync-extra-baseline.txt"
rm -rf "$FIXTURE_FRONTEND/static/js/entry" "$FIXTURE_DESKTOP/src/static/js/entry"
(cd "$FIXTURE_FRONTEND" && npm run build >/dev/null 2>&1) || exit 1

echo "## Desktop sync 완료 표식이 계속 없는 dist 거부"
# 표식 확인 직후 다른 빌드가 표식을 지우고 계속 쓰는 상황: 표식이 없는 상태끼리 같다고 보면 끝까지 통과한다.
cat > "$FIXTURE_DESKTOP/marker-gap-harness.js" <<'HARNESS'
const fs = require('fs');
const path = require('path');
const FrontendSyncScript = require('./build-scripts/sync-frontend.js');
const script = new FrontendSyncScript();
const marker = path.join(script.frontendDistPath, '.build-complete');
const originalExistsSync = fs.existsSync;
const originalValidate = script.validateEnvironment.bind(script);
script.validateEnvironment = async () => {
    const originalBuild = script.buildFrontend.bind(script);
    script.buildFrontend = () => {
        originalBuild();
        fs.rmSync(marker, { force: true });
        fs.existsSync = (target) => (target === marker ? true : originalExistsSync(target));
    };
    try {
        return await originalValidate();
    } finally {
        fs.existsSync = originalExistsSync;
    }
};
script.run();
HARNESS
printf 'console.log("keep");\n' > "$FIXTURE_DESKTOP/src/static/js/keep-me.js"
(cd "$FIXTURE_DESKTOP" && node marker-gap-harness.js --skip-backup >/dev/null 2>&1)
expect_rc "완료 표식이 사라진 dist 로 sync 중단" 1 $?
if [ -f "$FIXTURE_DESKTOP/src/static/js/keep-me.js" ]; then
    ok "완료 표식이 사라진 dist 로 Desktop 사본을 지우지 않음"
else
    bad "완료 표식이 사라진 dist 로 Desktop 사본이 삭제됨"
fi
rm -f "$FIXTURE_DESKTOP/marker-gap-harness.js" "$FIXTURE_DESKTOP/src/static/js/keep-me.js"
(cd "$FIXTURE_FRONTEND" && npm run build >/dev/null 2>&1) || exit 1
(cd "$FIXTURE_DESKTOP" && node build-scripts/sync-frontend.js --skip-backup >/dev/null 2>&1) || exit 1

echo "## Desktop sync check config 산출물 누락"
rm -rf "$FIXTURE_DESKTOP/src/config"
CODEX_PROJECT_ROOT="$FIXTURE_ROOT" bash "$FIXTURE_SCRIPTS/verify-desktop-sync.sh" >/dev/null 2>&1
CONFIG_MISSING_RC=$?
expect_rc "config 산출물 누락 FAIL" 1 "$CONFIG_MISSING_RC"
(
    cd "$FIXTURE_DESKTOP" &&
    node build-scripts/sync-frontend.js --skip-backup >/dev/null 2>&1
) || exit 1

echo "## Desktop sync check 비교 오류"
chmod 000 "$FIXTURE_DESKTOP/src/templates/index.html"
CODEX_PROJECT_ROOT="$FIXTURE_ROOT" bash "$FIXTURE_SCRIPTS/verify-desktop-sync.sh" >/dev/null 2>&1
DIFF_ERROR_RC=$?
chmod 644 "$FIXTURE_DESKTOP/src/templates/index.html"
expect_rc "I/O 오류 DEGRADE" 3 "$DIFF_ERROR_RC"

echo "## Desktop sync check drift와 비교 오류 동시 발생"
printf 'console.log("combined-drift");\n' > "$FIXTURE_FRONTEND/static/js/app.js"
chmod 000 "$FIXTURE_DESKTOP/src/templates/index.html"
CODEX_PROJECT_ROOT="$FIXTURE_ROOT" bash "$FIXTURE_SCRIPTS/verify-desktop-sync.sh" >/dev/null 2>&1
COMBINED_ERROR_RC=$?
chmod 644 "$FIXTURE_DESKTOP/src/templates/index.html"
printf 'console.log("fixture");\n' > "$FIXTURE_FRONTEND/static/js/app.js"
expect_rc "확정 drift가 있으면 FAIL 우선" 1 "$COMBINED_ERROR_RC"

echo "## Desktop sync check 산출물 누락 비변형"
rm -rf "$FIXTURE_DESKTOP/src/static"
BEFORE_MISSING_CHECK=$(tree_digest "$FIXTURE_DESKTOP")
CODEX_PROJECT_ROOT="$FIXTURE_ROOT" bash "$FIXTURE_SCRIPTS/verify-desktop-sync.sh" >/dev/null 2>&1
MISSING_RC=$?
AFTER_MISSING_CHECK=$(tree_digest "$FIXTURE_DESKTOP")
expect_rc "산출물 누락 FAIL" 1 "$MISSING_RC"
if [ "$BEFORE_MISSING_CHECK" = "$AFTER_MISSING_CHECK" ]; then
    ok "산출물 누락 검증 후 Desktop 트리 불변"
else
    bad "산출물 누락 검증이 Desktop 트리를 생성함"
fi

echo ""
echo "== 결과: PASS=$PASS FAIL=$FAIL =="
[ "$FAIL" -eq 0 ]
