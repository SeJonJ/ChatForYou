#!/usr/bin/env python3
"""run_hook — 설치된 thin 어댑터의 단일 진입점 (외부검토 R1 / P0-1).

shell 어댑터에 임베드돼 복제되던 Python 을 hook_runtime + io_{runtime} 로 들어올린 뒤,
어댑터 셸은 이 파일을 exec 만 한다(본문 단일소스). 입력: --runtime/--hook/--root/--core-dir, raw=stdin.

branch 는 SAGE_GATE_BRANCH 우선, 없으면 root 기준 git 으로 해석(원본 셸 BRANCH 로직 이식).
"""
import argparse
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))   # .../<sage 트리>/hooks/runtime
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))
import hook_runtime as hr   # noqa: E402
import io_claude            # noqa: E402
import io_codex             # noqa: E402

_IO = {"claude": io_claude, "codex": io_codex}


def dispatch(runtime, hook, root, core_dir, raw_text, direct_path=None):
    """hook id → hook_runtime 실행 (단일소스). 셸 어댑터(run_hook.main)와 `sage-hook`
    콘솔 엔트리포인트(sage.hook_entry)가 공유한다.

    모든 설치 hook은 Python hook_runtime 함수로 실행한다. 미지원 hook id만 안전 통과(rc 0)."""
    io = _IO[runtime]
    if hook == "pre-implementation-gate":
        return hr.run_pre_implementation_gate(io, root, core_dir, raw_text)
    if hook == "capture-declared-risk":
        return hr.run_capture_declared_risk(io, root, core_dir, raw_text)
    if hook == "post-tool-logger":
        return hr.run_post_tool_logger(io, root, core_dir, raw_text)
    if hook == "pre-phase4-checklist-gate":
        return hr.run_pre_phase4_checklist_gate(io, root, core_dir, raw_text)
    if hook == "stop-compliance-report":
        return hr.run_stop_compliance_report(io, root, core_dir, raw_text)
    if hook == "session-start-snapshot":
        return hr.run_session_start_snapshot(io, root, core_dir, raw_text)
    if hook == "generated-artifact-write-guard":
        return hr.run_generated_artifact_write_guard(raw_text, core_dir, direct_path=direct_path)
    return hr.run_project_hook(io, root, core_dir, hook, raw_text)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runtime", required=True, choices=["claude", "codex"])
    ap.add_argument("--hook", required=True)
    ap.add_argument("--root", required=True)
    ap.add_argument("--core-dir", required=True)
    ap.add_argument("--path", default=None,
                    help="write-guard 직접호출 호환 경로(stdin JSON보다 우선)")
    a = ap.parse_args()
    sys.exit(dispatch(
        a.runtime, a.hook, a.root, a.core_dir, sys.stdin.read(), direct_path=a.path))


if __name__ == "__main__":
    main()
