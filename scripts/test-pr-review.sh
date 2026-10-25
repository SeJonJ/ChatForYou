#!/bin/bash
# scripts/pr-review.py 회귀 테스트. 네트워크·리뷰어 호출 없이 파싱·위치 판정·실패 처리를 확인한다.

set -uo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

python3 - "$PROJECT_ROOT/scripts/pr-review.py" <<'PY'
import importlib.util
import sys

spec = importlib.util.spec_from_file_location("pr_review", sys.argv[1])
pr = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pr)

passed = failed = 0


def check(name, condition):
    global passed, failed
    if condition:
        passed += 1
        print(f"  PASS: {name}")
    else:
        failed += 1
        print(f"  FAIL: {name}")


print("## 인라인 가능 줄 계산")
patch = """diff --git a/a.js b/a.js
--- a/a.js
+++ b/a.js
@@ -10,3 +10,4 @@ function f() {
 keep
-old
+new1
+new2
 tail
diff --git a/gone.js b/gone.js
--- a/gone.js
+++ /dev/null
@@ -1,1 +0,0 @@
-bye
diff --git a/.github/w.yml b/.github/w.yml
--- a/.github/w.yml
+++ b/.github/w.yml
@@ -1 +1 @@
-a
+b
\\ No newline at end of file
"""
lines = pr.right_side_lines(patch)
check("문맥·추가 줄이 변경 후 줄 번호로 잡힌다", lines.get("a.js") == {10, 11, 12, 13})
check("삭제 줄은 변경 후 줄 번호를 늘리지 않는다", 14 not in lines.get("a.js", set()))
check("삭제된 파일은 인라인 대상이 없다", "gone.js" not in lines)
check("점으로 시작하는 경로를 유지한다", lines.get(".github/w.yml") == {1})

print("## 결과 json 추출")
text = 'note\n```json\n{"summary": "s", "findings": [{"severity": "p1", "path": "./a.js", "line": "11"}]}\n```\n'
data = pr.extract_result(text)
check("마지막 json 블록을 읽는다", data is not None and data["summary"] == "s")
check("json 블록이 없으면 None", pr.extract_result("결함 없음") is None)
check("깨진 json 은 None", pr.extract_result("```json\n{broken\n```") is None)
check("findings 가 없는 json 은 None", pr.extract_result('```json\n{"summary": "s"}\n```') is None)
two = '```json\n{"summary": "old", "findings": []}\n```\n```json\n{"summary": "new", "findings": []}\n```'
check("여러 블록이면 마지막을 쓴다", pr.extract_result(two)["summary"] == "new")

print("## 지적 정규화")
finding = pr.normalize_finding(data["findings"][0])
check("심각도 대소문자를 맞춘다", finding["severity"] == "P1")
check("앞의 ./ 를 지운다", finding["path"] == "a.js")
check("문자열 줄 번호를 숫자로 바꾼다", finding["line"] == 11)
check("모르는 심각도는 P3", pr.normalize_finding({"severity": "critical"})["severity"] == "P3")
check("점 경로는 그대로", pr.normalize_finding({"path": ".github/w.yml"})["path"] == ".github/w.yml")
check("숫자가 아닌 줄 번호는 None", pr.normalize_finding({"line": "abc"})["line"] is None)
check("객체가 아니면 버린다", pr.normalize_finding("text") is None)

print("## 인라인/본문 분리")
items = [
    ("codex", {"path": "a.js", "line": 11}),
    ("claude", {"path": "a.js", "line": 99}),
    ("claude", {"path": "other.js", "line": 1}),
    ("codex", {"path": None, "line": None}),
]
inline, rest = pr.split_findings(items, lines)
check("diff 안 줄만 인라인", [f["line"] for _, f in inline] == [11])
check("diff 밖·다른 파일·위치 없음은 본문", len(rest) == 3)

print("## cross-check 출력 해석")
ok = """[sage cross-check] codex 직접 호출 중
===== CODEX CROSS-MODEL REVIEW =====
리뷰 본문
===== END CODEX REVIEW =====
REVIEWER_ACTUAL: cross_model
REVIEWER_STATUS: COMPLETE
"""
parsed = pr.parse_cross_check(ok)
check("완료 상태와 본문을 읽는다", parsed["status"] == "COMPLETE" and parsed["review"] == "리뷰 본문")
blocked = "REVIEWER_BLOCK_REASON: usage_limit\nREVIEWER_STATUS: BLOCKED\n"
parsed = pr.parse_cross_check(blocked)
check("한도 실패는 BLOCKED 와 사유", parsed["status"] == "BLOCKED" and parsed["reason"] == "usage_limit")
check("상태 줄이 없으면 BLOCKED", pr.parse_cross_check("crash")["status"] == "BLOCKED")
partial = "REVIEWER_BLOCK_REASON: timeout\nREVIEWER_STATUS: PARTIAL\n"
check("잘린 리뷰는 완료로 보지 않는다", not pr.parse_cross_check(partial)["status"].startswith("COMPLETE"))

print("## 리뷰어 방향")
check("claude 리뷰어는 --host codex", pr.REVIEWER_HOSTS["claude"] == "codex")
check("codex 리뷰어는 --host claude", pr.REVIEWER_HOSTS["codex"] == "claude")

print("## 본문 구성")
outcomes = {
    "claude": {"status": "COMPLETE", "review": "", "data": {"summary": "요약", "findings": []}},
    "codex": {"status": "BLOCKED", "reason": "usage_limit", "review": ""},
}
body = pr.build_review_body("`a..b`", outcomes, [])
check("완료 리뷰어의 요약이 들어간다", "claude**: 완료 · 지적 0건 — 요약" in body)
check("실패 리뷰어는 건너뜀 사유가 들어간다", "codex**: 건너뜀 (usage_limit)" in body)
raw = {"claude": {"status": "COMPLETE", "review": "원문 리뷰", "data": None}}
check("형식을 못 읽으면 원문을 붙인다", "원문 리뷰" in pr.build_review_body("s", raw, []))

print("## 리뷰어 실행 예외 격리")
import subprocess
from types import SimpleNamespace

real_run, real_parse = pr.run, pr.parse_cross_check
COMPLETE_OUT = "===== CODEX CROSS-MODEL REVIEW =====\n본문\n===== END CODEX REVIEW =====\nREVIEWER_STATUS: COMPLETE\n"


def failing_run(exc):
    def fake(cmd, **kwargs):
        raise exc
    return fake


def guarded(name, fn):
    try:
        return fn()
    except Exception as exc:
        check(f"{name} (예외 {type(exc).__name__})", False)
        return None


cases = [
    ("하드 타임아웃", subprocess.TimeoutExpired(["sage"], 660), "timeout"),
    ("실행 파일 없음", FileNotFoundError("sage"), "exec_error"),
]
for name, exc, reason in cases:
    pr.run = failing_run(exc)
    result = guarded(name, lambda: pr.call_reviewer("claude", "/p", 540, "/r"))
    if result is not None:
        check(f"{name} 은 그 리뷰어만 BLOCKED({reason})",
              result["status"] == "BLOCKED" and result["reason"] == reason)
pr.run = lambda cmd, **kwargs: SimpleNamespace(stdout=COMPLETE_OUT, stderr="", returncode=0)
pr.parse_cross_check = lambda stdout: (_ for _ in ()).throw(ValueError("bad"))
result = guarded("출력 해석 예외", lambda: pr.call_reviewer("claude", "/p", 540, "/r"))
if result is not None:
    check("출력 해석 예외도 BLOCKED 와 예외 이름", result["status"] == "BLOCKED" and result["reason"] == "ValueError")
pr.parse_cross_check = real_parse


def one_side_times_out(cmd, **kwargs):
    if cmd[cmd.index("--host") + 1] == "codex":
        raise subprocess.TimeoutExpired(cmd, 660)
    return SimpleNamespace(stdout=COMPLETE_OUT, stderr="", returncode=0)


pr.run = one_side_times_out
outcomes = guarded("한쪽 타임아웃", lambda: pr.run_reviewers(["claude", "codex"], "/p", 540, "/r"))
if outcomes is not None:
    check("한쪽이 타임아웃이어도 다른 리뷰어 결과가 남는다",
          outcomes["claude"]["status"] == "BLOCKED" and outcomes["codex"]["status"] == "COMPLETE")
pr.run = real_run

print("## 인라인 위치 diff 스냅샷")
calls = []


def recording_run(cmd, **kwargs):
    calls.append(cmd)
    if cmd[:2] == ["git", "merge-base"]:
        return SimpleNamespace(stdout="mb123\n", stderr="", returncode=0)
    return SimpleNamespace(stdout="diff --git a/x.js b/x.js\n--- a/x.js\n+++ b/x.js\n@@ -1 +1 @@\n-a\n+b\n",
                           stderr="", returncode=0)


pr.run = recording_run
lines_at_head = guarded("스냅샷 diff", lambda: pr.commentable_lines("main", "head456", "/r"))
pr.run = real_run
if lines_at_head is not None:
    check("인라인 줄을 그 head 의 diff 로 계산한다", lines_at_head == {"x.js": {1}})
    check("merge-base 를 리뷰한 head 로 구한다", ["git", "merge-base", "origin/main", "head456"] in calls)
    check("diff 범위가 merge-base..head 다", any(c[:2] == ["git", "diff"] and "mb123..head456" in c for c in calls))
    check("현재 PR diff(gh pr diff)를 다시 받지 않는다", not any(c[0] == "gh" for c in calls))

    check("문맥 줄 수를 GitHub 과 같은 3줄로 고정한다", any(c[:2] == ["git", "diff"] and "-U3" in c for c in calls))

print("## base 원격 추적 ref 갱신")
calls = []
pr.run = recording_run
guarded("fetch", lambda: pr.fetch_pr("main", 158, "/r"))
pr.run = real_run
fetch = next((c for c in calls if c[:2] == ["git", "fetch"]), [])
check("base 를 원격 추적 ref 까지 명시해 받는다", "+refs/heads/main:refs/remotes/origin/main" in fetch)
check("PR head 도 받는다", "pull/158/head" in fetch)

print("## 리뷰 게시 실패 처리")
import contextlib
import io
import json as _json
import os as _os
import re

payload = {"body": "b", "comments": [{"path": "a.js", "line": 1, "body": "c"}]}
fallback = {"body": "b + c", "comments": []}


def scripted_post(codes):
    sent = []

    def fake(cmd, **kwargs):
        sent.append(_json.loads(kwargs["input"]))
        code = codes[len(sent) - 1]
        return SimpleNamespace(stdout="", stderr="" if code == 0 else "HTTP 422: line could not be resolved",
                               returncode=code)
    return fake, sent


pr.run, sent = scripted_post([1, 0])
err = io.StringIO()
with contextlib.redirect_stderr(err):
    posted = guarded("인라인 거부", lambda: pr.post_review("o/r", 158, payload, fallback))
check("인라인이 거부되면 본문 버전으로 다시 게시한다", posted == "인라인을 본문으로 옮겨" and sent[-1] == fallback)
check("거부 사유(gh 오류)를 보여 준다", "HTTP 422" in err.getvalue())

pr.run, sent = scripted_post([1, 1])
err = io.StringIO()
with contextlib.redirect_stderr(err):
    posted = guarded("둘 다 거부", lambda: pr.post_review("o/r", 158, payload, fallback))
saved = re.search(r"(\S+\.json) 에 남겼습니다", err.getvalue()) if posted is None else None
check("둘 다 실패하면 None 과 결과 파일", saved is not None and _os.path.exists(saved.group(1)))
if saved:
    kept = guarded("결과 파일 읽기", lambda: _json.load(open(saved.group(1))))
    check("남긴 파일에 인라인 지적이 그대로 있다", kept is not None and kept["comments"] == payload["comments"])
    _os.unlink(saved.group(1))

pr.run, sent = scripted_post([1])
with contextlib.redirect_stderr(io.StringIO()):
    guarded("인라인 없음", lambda: pr.post_review("o/r", 158, fallback, fallback))
check("인라인이 없으면 같은 내용으로 다시 보내지 않는다", len(sent) == 1)
pr.run = real_run

print(f"\n== 결과: PASS={passed} FAIL={failed} ==")
sys.exit(1 if failed else 0)
PY
