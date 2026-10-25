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

print(f"\n== 결과: PASS={passed} FAIL={failed} ==")
sys.exit(1 if failed else 0)
PY
