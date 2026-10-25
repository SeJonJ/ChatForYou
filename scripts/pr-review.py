#!/usr/bin/env python3
"""PR 을 Claude 와 codex 로 한 번씩 리뷰하고 결과를 PR 리뷰로 게시한다.

사용법:
  scripts/pr-review.py <PR번호> [--since <commit>] [--context <file>] [--dry-run]
                       [--reviewers claude,codex] [--timeout <초>]

- 리뷰어 호출은 `sage cross-check` 에 맡긴다. `--host codex` 로 부르면 Claude 가, `--host claude` 로
  부르면 codex 가 리뷰한다. 실행 중인 호스트와 무관하게 두 모델을 한 번씩 돌리기 위해서다.
- 한 리뷰어가 사용 한도·시간 초과 등으로 실패하면 재시도하거나 다른 모델로 대체하지 않고 그 리뷰어만
  건너뛴다. 둘 다 실패하면 아무것도 게시하지 않고 1 로 끝난다.
- `--since` 를 주면 그 커밋 이후 변경만 리뷰한다(이미 리뷰한 PR 의 추가 커밋용).
- `--context` 파일은 패킷에 그대로 붙는다. 이미 판정한 지적을 적어 두면 다시 보고되지 않는다.
- sage 실행 파일은 `SAGE_BIN`(공백으로 나눈 명령) 으로 바꿀 수 있다. 기본값은 `sage`.
"""

import argparse
import concurrent.futures
import json
import os
import re
import shlex
import subprocess
import sys
import tempfile

# 리뷰어 이름 → cross-check 에 넘길 host. peer 는 그 반대 런타임이다.
REVIEWER_HOSTS = {"claude": "codex", "codex": "claude"}

# 리뷰 비용만 늘리고 결함을 찾을 일이 없는 경로. 생성 렌더는 원본(overlay)이 diff 에 남는다.
DEFAULT_EXCLUDES = [
    ":(exclude,glob)**/package-lock.json",
    ":(exclude,glob)plan_docs/**",
    ":(exclude,glob).claude/skills/**",
    ":(exclude,glob).codex/skills/**",
    ":(exclude)docs/sage_harness/.manifest.json",
]

SEVERITY_LABELS = {"P0": "🔴 P0", "P1": "🟠 P1", "P2": "🟡 P2", "P3": "⚪ P3"}

PACKET_TEMPLATE = """# PR 리뷰 요청

너는 이 PR 의 독립 리뷰어다. 아래 diff 는 바뀐 함수 본문 전체를 포함한다. 판단은 diff 로 먼저 하고,
특정 주장을 확인할 때만 저장소를 읽는다.

## 리뷰 계약

- 심각도: P0 = 데이터 손실·보안 침해·서비스 중단, P1 = 주요 기능 오동작, P2 = 제한된 조건의 오동작,
  P3 = 드문 조건의 작은 결함.
- 실제 결함만 보고한다. "어떤 입력·상태에서 어떤 잘못된 결과가 나는가"를 쓸 수 없으면 보고하지 않는다.
  스타일·이름·취향, 근거 없는 추측은 제외한다.
- 아래 "이미 판정된 항목" 에 있는 지적은 새 근거가 없으면 다시 보고하지 않는다.

## PR

- 제목: {title}
- 리뷰 범위: {scope}

{body}

## 이미 판정된 항목

{context}

## 변경 (diff)

```diff
{diff}
```

## 출력 형식

리뷰 마지막에 아래 형식의 json 블록을 정확히 하나 둔다. 결함이 없으면 findings 는 빈 배열이다.
`line` 은 변경 **후** 파일의 줄 번호다.

```json
{{"summary": "한두 문장 요약", "findings": [{{"severity": "P0|P1|P2|P3", "path": "파일 경로",
  "line": 123, "title": "한 줄 제목", "body": "무엇이 왜 문제인가", "scenario": "입력·상태 → 잘못된 결과"}}]}}
```
"""


def run(cmd, check=True, **kwargs):
    return subprocess.run(cmd, check=check, text=True, capture_output=True, **kwargs)


def gh_json(args):
    return json.loads(run(["gh"] + args).stdout)


def right_side_lines(patch):
    """unified diff 에서 파일별로 변경 후 쪽에 존재하는 줄 번호 집합을 만든다.

    GitHub 은 diff 안에 보이는 줄에만 인라인 댓글을 받는다. 추가 줄과 문맥 줄이 그 대상이다.
    """
    lines = {}
    path = None
    right = 0
    for raw in patch.splitlines():
        if raw.startswith("diff --git "):
            path = None
        elif raw.startswith("+++ "):
            target = raw[4:].strip()
            path = None if target == "/dev/null" else re.sub(r"^b/", "", target)
            if path is not None:
                lines.setdefault(path, set())
        elif raw.startswith("@@"):
            match = re.match(r"@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@", raw)
            right = int(match.group(1)) if match else 0
        elif path is None or raw.startswith("--- ") or raw.startswith("\\"):
            continue
        elif raw.startswith("+") or raw.startswith(" "):
            lines[path].add(right)
            right += 1
    return lines


def extract_result(review_text):
    """리뷰 본문 끝의 json 블록을 읽는다. 없거나 깨지면 None 이다."""
    blocks = re.findall(r"```json\s*(.*?)```", review_text, re.DOTALL)
    for block in reversed(blocks):
        try:
            data = json.loads(block)
        except json.JSONDecodeError:
            continue
        if isinstance(data, dict) and isinstance(data.get("findings"), list):
            return data
    return None


def normalize_finding(raw):
    if not isinstance(raw, dict):
        return None
    severity = str(raw.get("severity", "")).upper()
    if severity not in SEVERITY_LABELS:
        severity = "P3"
    try:
        line = int(raw.get("line"))
    except (TypeError, ValueError):
        line = None
    return {
        "severity": severity,
        "path": re.sub(r"^(\./)+", "", str(raw.get("path") or "")) or None,
        "line": line,
        "title": str(raw.get("title") or "제목 없음").strip(),
        "body": str(raw.get("body") or "").strip(),
        "scenario": str(raw.get("scenario") or "").strip(),
    }


def format_finding(finding, reviewer, with_location=False):
    head = f"**{SEVERITY_LABELS[finding['severity']]} · {finding['title']}** ({reviewer})"
    if with_location and finding["path"]:
        location = finding["path"] + (f":{finding['line']}" if finding["line"] else "")
        head += f" — `{location}`"
    parts = [head]
    if finding["body"]:
        parts.append(finding["body"])
    if finding["scenario"]:
        parts.append(f"재현: {finding['scenario']}")
    return "\n\n".join(parts)


def split_findings(findings, commentable):
    """인라인으로 달 수 있는 지적과 본문으로 보낼 지적을 나눈다."""
    inline, rest = [], []
    for reviewer, finding in findings:
        path, line = finding["path"], finding["line"]
        if path and line and line in commentable.get(path, set()):
            inline.append((reviewer, finding))
        else:
            rest.append((reviewer, finding))
    return inline, rest


def parse_cross_check(stdout):
    """cross-check 출력에서 리뷰 본문과 상태를 꺼낸다."""
    status = re.findall(r"^REVIEWER_STATUS: (\S+)", stdout, re.MULTILINE)
    reason = re.findall(r"^REVIEWER_BLOCK_REASON: (\S+)", stdout, re.MULTILINE)
    body = re.search(r"^===== \w+ CROSS-MODEL REVIEW =====\n(.*?)^===== END \w+ REVIEW =====",
                     stdout, re.DOTALL | re.MULTILINE)
    return {
        "status": status[-1] if status else "BLOCKED",
        "reason": reason[-1] if reason else None,
        "review": body.group(1).strip() if body else "",
    }


def call_reviewer(reviewer, packet_path, timeout, root):
    """리뷰어 한 명을 실행한다. 실행 중 어떤 예외든 이 리뷰어만의 BLOCKED 로 돌려준다.

    예외가 밖으로 나가면 병렬로 끝난 다른 리뷰어 결과까지 게시되지 않는다.
    """
    sage = shlex.split(os.environ.get("SAGE_BIN", "sage"))
    cmd = sage + ["cross-check", "--packet-file", packet_path,
                  "--host", REVIEWER_HOSTS[reviewer], "--timeout", str(timeout)]
    try:
        proc = run(cmd, check=False, cwd=root, timeout=timeout + 120)
        result = parse_cross_check(proc.stdout)
        if not result["status"].startswith("COMPLETE"):
            result["reason"] = result["reason"] or f"exit {proc.returncode}"
            tail = proc.stderr.strip().splitlines()[-1:] if proc.stderr.strip() else []
            result["error"] = tail[0] if tail else ""
        return result
    except subprocess.TimeoutExpired:
        reason, error = "timeout", f"{timeout + 120}초 안에 끝나지 않음"
    except OSError as exc:
        reason, error = "exec_error", str(exc)
    except Exception as exc:
        reason, error = type(exc).__name__, str(exc)
    return {"status": "BLOCKED", "reason": reason, "review": "", "error": error}


def run_reviewers(reviewers, packet_path, timeout, root):
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(reviewers)) as pool:
        futures = {name: pool.submit(call_reviewer, name, packet_path, timeout, root)
                   for name in reviewers}
        return {name: future.result() for name, future in futures.items()}


def commentable_lines(base_ref, head, root):
    """리뷰한 head 의 PR 전체 diff 에서 인라인 댓글을 달 수 있는 줄을 구한다.

    GitHub 인라인 위치는 `--since` 와 무관하게 PR 전체 diff(merge-base..head) 기준이다. 리뷰가 끝난 뒤
    `gh pr diff` 로 다시 받으면 그 사이 push 된 커밋의 diff 로 위치를 판정하게 되므로 head 로 고정한다.
    문맥 줄 수는 GitHub 과 같은 3줄로 고정한다. 개인 설정(diff.context)을 따르면 GitHub diff 에 없는 줄을
    인라인 대상으로 잡아 리뷰 전체가 거부된다.
    """
    merge_base = run(["git", "merge-base", f"origin/{base_ref}", head], cwd=root).stdout.strip()
    patch = run(["git", "diff", "--no-color", "-U3", "--src-prefix=a/", "--dst-prefix=b/",
                 f"{merge_base}..{head}"], cwd=root).stdout
    return right_side_lines(patch)


def fetch_pr(base_ref, number, root):
    """base 브랜치와 PR head 를 받는다. base 는 원격 추적 ref 까지 명시해 갱신한다.

    `git fetch origin <base>` 만으로는 fetch 설정을 좁힌 클론에서 origin/<base> 가 옛 값으로 남고, 그 값으로
    merge-base 를 구하면 이미 병합된 변경까지 리뷰하게 된다.
    """
    run(["git", "fetch", "--quiet", "origin", f"+refs/heads/{base_ref}:refs/remotes/origin/{base_ref}",
         f"pull/{number}/head"], cwd=root)


def post_review(repo, number, payload, fallback):
    """리뷰를 게시한다. 실패하면 gh 오류를 보여 주고 인라인 없는 `fallback` 으로 한 번 더 게시한다.

    GitHub 은 인라인 댓글 하나만 잘못돼도 리뷰 전체를 거부한다. 그때 리뷰 결과를 버리지 않기 위해서다.
    게시에 쓴 시도 이름을 돌려주고, 둘 다 실패하면 payload 를 임시 파일로 남기고 None 을 돌려준다.
    """
    endpoint = ["gh", "api", "--method", "POST", f"repos/{repo}/pulls/{number}/reviews", "--input", "-"]
    attempts = [("인라인 포함", payload)]
    if payload.get("comments"):
        attempts.append(("인라인을 본문으로 옮겨", fallback))
    for label, body in attempts:
        proc = run(endpoint, check=False, input=json.dumps(body))
        if proc.returncode == 0:
            return label
        detail = (proc.stderr or proc.stdout or "").strip()
        print(f"게시 실패({label}): {detail or f'exit {proc.returncode}'}", file=sys.stderr)
    with tempfile.NamedTemporaryFile("w", suffix=".json", prefix="pr-review-", delete=False,
                                     encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2)
    print(f"리뷰 결과를 {handle.name} 에 남겼습니다.", file=sys.stderr)
    return None


def build_review_body(scope, outcomes, rest):
    lines = ["## 자동 교차 리뷰 (Claude · codex)", "", f"리뷰 범위: {scope}", ""]
    for reviewer, outcome in outcomes.items():
        if outcome["status"].startswith("COMPLETE"):
            data = outcome.get("data")
            if data is None:
                lines.append(f"- **{reviewer}**: 완료, 결과 형식을 읽지 못해 원문을 아래에 붙임")
            else:
                count = len(data.get("findings") or [])
                lines.append(f"- **{reviewer}**: 완료 · 지적 {count}건 — {data.get('summary', '').strip()}")
        else:
            lines.append(f"- **{reviewer}**: 건너뜀 ({outcome['reason']})")
    if rest:
        lines += ["", "### diff 밖 또는 위치 없는 지적", ""]
        lines += [format_finding(finding, reviewer, with_location=True) + "\n" for reviewer, finding in rest]
    for reviewer, outcome in outcomes.items():
        if outcome["status"].startswith("COMPLETE") and outcome.get("data") is None:
            lines += ["", f"<details><summary>{reviewer} 리뷰 원문</summary>", "", outcome["review"], "",
                      "</details>"]
    lines += ["", "<sub>scripts/pr-review.py 로 생성</sub>"]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description="PR 을 Claude·codex 로 한 번씩 리뷰하고 게시한다")
    parser.add_argument("pr", type=int)
    parser.add_argument("--since", help="이 커밋 이후 변경만 리뷰")
    parser.add_argument("--context", help="이미 판정된 항목을 적은 파일")
    parser.add_argument("--reviewers", default="claude,codex")
    parser.add_argument("--timeout", type=int, default=540)
    parser.add_argument("--dry-run", action="store_true", help="게시하지 않고 리뷰 payload 를 출력")
    args = parser.parse_args()

    reviewers = [name.strip() for name in args.reviewers.split(",") if name.strip()]
    unknown = [name for name in reviewers if name not in REVIEWER_HOSTS]
    if unknown or not reviewers:
        parser.error(f"--reviewers 는 claude, codex 중에서 고른다: {args.reviewers}")

    root = run(["git", "rev-parse", "--show-toplevel"]).stdout.strip()
    pr = gh_json(["pr", "view", str(args.pr), "--json", "title,body,baseRefName,headRefOid"])
    repo = gh_json(["repo", "view", "--json", "nameWithOwner"])["nameWithOwner"]
    head = pr["headRefOid"]

    fetch_pr(pr["baseRefName"], args.pr, root)
    run(["git", "cat-file", "-e", head], cwd=root)
    base = args.since or run(["git", "merge-base", f"origin/{pr['baseRefName']}", head], cwd=root).stdout.strip()
    scope = f"`{base[:7]}..{head[:7]}`" + (" (추가 커밋만)" if args.since else " (PR 전체)")

    diff = run(["git", "diff", "--no-color", "-W", f"{base}..{head}", "--", "."] + DEFAULT_EXCLUDES,
               cwd=root).stdout
    if not diff.strip():
        print("리뷰할 변경이 없습니다.", file=sys.stderr)
        return 1
    context = "없음"
    if args.context:
        with open(args.context, encoding="utf-8") as handle:
            context = handle.read().strip() or "없음"
    packet = PACKET_TEMPLATE.format(title=pr["title"], scope=scope, body=(pr["body"] or "").strip(),
                                    context=context, diff=diff)

    with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False, encoding="utf-8") as handle:
        handle.write(packet)
        packet_path = handle.name
    commentable = commentable_lines(pr["baseRefName"], head, root)
    print(f"패킷 {len(packet):,}자, 리뷰어 {', '.join(reviewers)} 실행 중…", file=sys.stderr)
    try:
        outcomes = run_reviewers(reviewers, packet_path, args.timeout, root)
    finally:
        os.unlink(packet_path)

    findings = []
    for reviewer, outcome in outcomes.items():
        if outcome["status"].startswith("COMPLETE"):
            outcome["data"] = extract_result(outcome["review"])
            for raw in (outcome["data"] or {}).get("findings") or []:
                finding = normalize_finding(raw)
                if finding:
                    findings.append((reviewer, finding))
        else:
            print(f"[{reviewer}] 건너뜀: {outcome['reason']} {outcome.get('error', '')}".rstrip(),
                  file=sys.stderr)
    if not any(outcome["status"].startswith("COMPLETE") for outcome in outcomes.values()):
        print("모든 리뷰어가 실패해 게시하지 않습니다.", file=sys.stderr)
        return 1

    # 리뷰 중 push 가 있어도 commit_id 가 리뷰한 커밋을 가리키므로 위치는 그 커밋 기준으로 맞는다.
    latest = gh_json(["pr", "view", str(args.pr), "--json", "headRefOid"])["headRefOid"]
    if latest != head:
        print(f"리뷰 중 새 커밋이 push 됨 — 리뷰는 {head[:7]} 기준이다(현재 {latest[:7]}).", file=sys.stderr)
    inline, rest = split_findings(findings, commentable)
    payload = {
        "commit_id": head,
        "event": "COMMENT",
        "body": build_review_body(scope, outcomes, rest),
        "comments": [{"path": f["path"], "line": f["line"], "side": "RIGHT", "body": format_finding(f, r)}
                     for r, f in inline],
    }
    if args.dry_run:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
        return 0
    fallback = dict(payload, comments=[], body=build_review_body(scope, outcomes, inline + rest))
    posted = post_review(repo, args.pr, payload, fallback)
    if posted is None:
        return 1
    counts = (f"인라인 {len(inline)}건, 본문 {len(rest)}건" if posted == "인라인 포함"
              else f"본문 {len(inline) + len(rest)}건")
    print(f"게시 완료({posted}): {counts}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
