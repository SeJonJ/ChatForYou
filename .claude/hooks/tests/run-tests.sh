#!/usr/bin/env bash
# run-tests.sh — Hook 회귀 테스트 러너 (python3 단독, 외부 의존 0)
#
# 각 케이스를 격리된 임시 PROJECT_ROOT(sandbox 복사본)에서 실행해
# exit code / stdout 포함·미포함 / 생성 파일을 검증한다.
# bats/shellcheck 불필요 — python3 만 있으면 동작.
#
# 사용: bash .claude/hooks/tests/run-tests.sh
# EXIT: 0=전체 통과 / 1=실패 존재

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"   # .../.claude/hooks/tests
HOOKS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"     # .../.claude/hooks

exec python3 - "$SCRIPT_DIR" "$HOOKS_DIR" <<'PYEOF'
import os, sys, subprocess, tempfile, shutil, datetime

test_dir, hooks_dir = sys.argv[1], sys.argv[2]
today = datetime.date.today().strftime('%Y-%m-%d')
cases_path = os.path.join(test_dir, 'cases.tsv')
fixtures_dir = os.path.join(test_dir, 'fixtures')
sandbox_dir = os.path.join(test_dir, 'sandbox')

def dash(v):
    return None if v.strip() == '-' else v

passed, failed = 0, 0
failures = []

with open(cases_path, encoding='utf-8') as f:
    for raw in f:
        line = raw.rstrip('\n')
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        cols = line.split('\t')
        if len(cols) < 8:
            print(f"⚠️  형식 오류(8컬럼 필요): {line[:60]}")
            failed += 1
            continue
        name, hook, sandbox, fixture, exp_exit, must, mustnot, expect_file = [c.strip() for c in cols[:8]]
        sandbox, must, mustnot, expect_file = map(dash, (sandbox, must, mustnot, expect_file))

        tmp = tempfile.mkdtemp(prefix='hooktest-')
        try:
            if sandbox:
                shutil.copytree(os.path.join(sandbox_dir, sandbox), tmp, dirs_exist_ok=True)
            os.makedirs(os.path.join(tmp, '.claude', 'logs'), exist_ok=True)
            # os.walk 사용 — glob('**')는 .claude 등 숨김 디렉토리를 건너뛴다
            for root, _dirs, files in os.walk(tmp):
                for fn in files:
                    p = os.path.join(root, fn)
                    if 'TODAY' in fn:
                        os.rename(p, os.path.join(root, fn.replace('TODAY', today)))
                    elif fn.endswith('.md'):
                        os.utime(p, None)

            with open(os.path.join(fixtures_dir, fixture), 'rb') as ff:
                stdin_bytes = ff.read()

            env = dict(os.environ, CLAUDE_PROJECT_DIR=tmp)
            proc = subprocess.run(
                ['bash', os.path.join(hooks_dir, hook)],
                input=stdin_bytes, env=env,
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60,
            )
            out = proc.stdout.decode('utf-8', 'replace')

            errs = []
            if proc.returncode != int(exp_exit):
                errs.append(f"exit {proc.returncode} != {exp_exit}")
            if must and must not in out:
                errs.append(f"stdout 에 '{must}' 없음")
            if mustnot and mustnot in out:
                errs.append(f"stdout 에 '{mustnot}' 있으면 안 됨")
            if expect_file:
                rel, _, sub = expect_file.partition('::')
                rel = rel.replace('{TODAY}', today)
                fpath = os.path.join(tmp, rel)
                if not os.path.exists(fpath):
                    errs.append(f"파일 미생성: {rel}")
                elif sub and sub != '-':
                    content = open(fpath, encoding='utf-8', errors='replace').read()
                    if sub not in content:
                        errs.append(f"{rel} 에 '{sub}' 없음")

            if errs:
                failed += 1
                failures.append((name, errs, out.strip()[:200]))
                print(f"❌ {name}: {'; '.join(errs)}")
            else:
                passed += 1
                print(f"✅ {name}")
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

print()
print(f"=== 결과: {passed} passed, {failed} failed ===")
if failures:
    print("\n실패 상세:")
    for name, errs, out in failures:
        print(f"  • {name}: {'; '.join(errs)}")
        if out:
            print(f"    stdout: {out}")
sys.exit(1 if failed else 0)
PYEOF
