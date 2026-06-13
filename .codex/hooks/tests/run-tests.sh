#!/usr/bin/env bash
# run-tests.sh - Codex hook regression test runner (python3 only, no deps)
#
# Runs each case in an isolated temporary CODEX_PROJECT_ROOT copied from a
# sandbox fixture, then verifies exit code, output substrings, and generated
# files. Uses os.walk because glob('**') skips hidden directories like .codex/.
#
# Usage: bash .codex/hooks/tests/run-tests.sh
# EXIT: 0=all passed / 1=failures

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"   # .../.codex/hooks/tests
HOOKS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"     # .../.codex/hooks

exec python3 - "$SCRIPT_DIR" "$HOOKS_DIR" <<'PYEOF'
import datetime
import os
import shutil
import subprocess
import sys
import tempfile

test_dir, hooks_dir = sys.argv[1], sys.argv[2]
today = datetime.date.today().strftime("%Y-%m-%d")
cases_path = os.path.join(test_dir, "cases.tsv")
fixtures_dir = os.path.join(test_dir, "fixtures")
sandbox_dir = os.path.join(test_dir, "sandbox")


def dash(v):
    return None if v.strip() == "-" else v


passed, failed = 0, 0
failures = []

with open(cases_path, encoding="utf-8") as f:
    for raw in f:
        line = raw.rstrip("\n")
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        cols = line.split("\t")
        if len(cols) < 8:
            print(f"format error (8 columns required): {line[:60]}")
            failed += 1
            continue
        name, hook, sandbox, fixture, exp_exit, must, mustnot, expect_file = [c.strip() for c in cols[:8]]
        sandbox, must, mustnot, expect_file = map(dash, (sandbox, must, mustnot, expect_file))

        tmp = tempfile.mkdtemp(prefix="codex-hooktest-")
        try:
            if sandbox:
                shutil.copytree(os.path.join(sandbox_dir, sandbox), tmp, dirs_exist_ok=True)
            os.makedirs(os.path.join(tmp, ".codex", "logs"), exist_ok=True)

            # os.walk includes hidden directories (.codex/logs); glob('**') does not.
            for root, _dirs, files in os.walk(tmp):
                for fn in files:
                    p = os.path.join(root, fn)
                    if "TODAY" in fn:
                        os.rename(p, os.path.join(root, fn.replace("TODAY", today)))
                    elif fn.endswith(".md"):
                        os.utime(p, None)

            with open(os.path.join(fixtures_dir, fixture), "rb") as ff:
                stdin_bytes = ff.read()

            env = dict(os.environ, CODEX_PROJECT_ROOT=tmp, CODEX_GATE_BRANCH="test")
            proc = subprocess.run(
                ["bash", os.path.join(hooks_dir, hook)],
                input=stdin_bytes,
                env=env,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=60,
            )
            stdout = proc.stdout.decode("utf-8", "replace")
            stderr = proc.stderr.decode("utf-8", "replace")
            output = stdout + stderr

            errs = []
            if proc.returncode != int(exp_exit):
                errs.append(f"exit {proc.returncode} != {exp_exit}")
            if must and must not in output:
                errs.append(f"output missing '{must}'")
            if mustnot and mustnot in output:
                errs.append(f"output must not contain '{mustnot}'")
            if expect_file:
                rel, _, sub = expect_file.partition("::")
                rel = rel.replace("{TODAY}", today)
                fpath = os.path.join(tmp, rel)
                if not os.path.exists(fpath):
                    errs.append(f"file not generated: {rel}")
                elif sub and sub != "-":
                    content = open(fpath, encoding="utf-8", errors="replace").read()
                    if sub not in content:
                        errs.append(f"{rel} missing '{sub}'")

            if errs:
                failed += 1
                failures.append((name, errs, output.strip()[:300]))
                print(f"FAIL {name}: {'; '.join(errs)}")
            else:
                passed += 1
                print(f"PASS {name}")
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

print()
print(f"=== Codex hook regression: {passed} passed, {failed} failed ===")
if failures:
    print("\nFailure details:")
    for name, errs, out in failures:
        print(f"  - {name}: {'; '.join(errs)}")
        if out:
            print(f"    output: {out}")
sys.exit(1 if failed else 0)
PYEOF
