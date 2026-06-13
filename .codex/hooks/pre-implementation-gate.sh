#!/bin/bash
# pre-implementation-gate.sh
# PreToolUse hook (apply_patch): risk level별 게이트 체크.

PROJECT_ROOT="${CODEX_PROJECT_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
LOG_DIR="$PROJECT_ROOT/.codex/logs"

INPUT=$(cat)

CODEX_HOOK_INPUT="$INPUT" python3 - "$PROJECT_ROOT" "$LOG_DIR" <<'PYEOF'
import glob
import json
import os
import re
import subprocess
import sys
import time

root, log_dir = sys.argv[1], sys.argv[2]
try:
    d = json.loads(os.environ.get("CODEX_HOOK_INPUT", "{}"))
except Exception:
    sys.exit(0)

tool_name = d.get("tool_name", "") or ""
tool_input = d.get("tool_input", {}) or {}
command = tool_input.get("command", "") or ""
session = d.get("session_id", "") or ""

if tool_name != "apply_patch":
    sys.exit(0)

def git_branch():
    if os.environ.get("CODEX_GATE_BRANCH"):
        return os.environ["CODEX_GATE_BRANCH"]
    try:
        return subprocess.check_output(
            ["git", "-C", root, "rev-parse", "--abbrev-ref", "HEAD"],
            stderr=subprocess.DEVNULL,
            text=True,
        ).strip()
    except Exception:
        return "unknown"

def safe_session(s):
    return re.sub(r"[^A-Za-z0-9_-]", "_", s or "")[:64]

def parse_patch(text):
    paths = []
    added = []
    for line in text.splitlines():
        m = re.match(r"^\*\*\* (?:Add|Update|Delete) File: (.+)$", line)
        if m:
            paths.append(m.group(1).strip())
            continue
        m = re.match(r"^\*\*\* Move to: (.+)$", line)
        if m:
            paths.append(m.group(1).strip())
            continue
        if line.startswith("+") and not line.startswith("+++") and not line.startswith("+***"):
            added.append(line[1:])
    return paths, "\n".join(added)

def normalize_path(p):
    p = p.strip()
    if not p:
        return ""
    if os.path.isabs(p):
        return os.path.normpath(p)
    return os.path.normpath(os.path.join(root, p))

def rel(p):
    try:
        return os.path.relpath(p, root)
    except Exception:
        return p

def rank(level):
    return {"L0": 0, "L1": 1, "L2": 2, "L3": 3}.get(level, -1)

def classify(path, content):
    risk = "none"
    reason = ""
    is_l3_filename = False

    short = rel(path)
    if short.startswith("plan_docs/") or short.startswith("docs/") or short.endswith(".md"):
        return "none", reason, is_l3_filename, ""

    if re.search(r"(?i)(kurento|webrtc|signaling|roommanager|datachannel|icecandidate|sdpoffer)", path):
        risk = "L3"
        reason = "WebRTC/Kurento filename 패턴"
        is_l3_filename = True
    elif "/springboot-backend/src/main/java/" in path:
        risk = "L2"
        reason = "백엔드 소스 (src/main)"
    elif re.search(r"/springboot-backend/(src/main/resources/.*\.(properties|ya?ml|xml)|.*\.gradle)$", path):
        risk = "L2"
        reason = "백엔드 설정/리소스"
    elif re.search(r"/nodejs-frontend/(static/js/.*\.js|server\.js|config/.*)$", path):
        risk = "L1"
        reason = "프론트 JS"
    elif re.search(r"/nodejs-frontend/.*\.(scss|css|html|ejs)$", path):
        risk = "L1"
        reason = "프론트 UI 마크업/스타일"

    content_escalated = ""
    if risk in ("L1", "L2") and re.search(r"WebRtcEndpoint|IceCandidate|addIceCandidate|onicecandidate|SdpOffer|processOffer|MediaPipeline|RTCPeerConnection|createOffer|createAnswer|KurentoClient|DataChannel", content):
        content_escalated = "L1/L2 -> L3 (내용에 WebRTC 시그널링 키워드)"
        risk = "L3"
    elif risk == "L1" and re.search(r"RedisTemplate|redisTemplate|@Cacheable|JwtTokenProvider|jwt\.|@Entity|JpaRepository|SecurityContext|setAccessToken|refreshToken", content):
        content_escalated = "L1 -> L2 (내용에 Redis/JWT/JPA/Auth 키워드)"
        risk = "L2"
    return risk, reason, is_l3_filename, content_escalated

def declared_level():
    if not session:
        return ""
    path = os.path.join(log_dir, f"declared-risk-{safe_session(session)}.json")
    try:
        with open(path, encoding="utf-8") as f:
            return (json.load(f).get("level") or "")
    except Exception:
        return ""

def find_plan(branch):
    ticket_match = re.search(r"[0-9]+", branch or "")
    if ticket_match:
        ticket = ticket_match.group(0)
        for path in glob.glob(os.path.join(root, "plan_docs", "00-base_plan", "**", "*.md"), recursive=True):
            try:
                with open(path, encoding="utf-8", errors="ignore") as f:
                    if ticket in f.read():
                        return path
            except Exception:
                pass
    cutoff = time.time() - 7 * 86400
    recent = [
        p for p in glob.glob(os.path.join(root, "plan_docs", "00-base_plan", "**", "*.md"), recursive=True)
        if os.path.getmtime(p) >= cutoff
    ]
    return sorted(recent)[0] if recent else ""

def tokens(value):
    parts = re.split(r"[^A-Za-z0-9가-힣]+", value or "")
    return {p.lower() for p in parts if len(p) >= 3}

GENERIC_SIGNAL_TOKENS = {
    "src", "main", "test", "java", "static", "nodejs", "frontend",
    "springboot", "backend", "service", "controller", "config",
    "js", "scss", "html", "json", "true", "false",
    "plan", "plans", "docs", "base", "design", "review",
}

def specific_tokens(value):
    return {token for token in tokens(value) if token not in GENERIC_SIGNAL_TOKENS}

def current_feature_signals(branch, plan_path, changed_paths):
    signals = {"tickets": set(), "plan": set(), "files": set()}

    for m in re.findall(r"[0-9]+", branch or ""):
        signals["tickets"].add(m)

    if plan_path:
        stem = os.path.splitext(os.path.basename(plan_path))[0]
        signals["plan"].update(specific_tokens(stem))
        try:
            rel_plan = os.path.relpath(plan_path, root)
            signals["plan"].update(specific_tokens(rel_plan))
        except Exception:
            pass

    for p in changed_paths:
        short = rel(p)
        signals["files"].update(specific_tokens(short))
    return signals

def score_review_candidate(path, content, signals):
    haystack = f"{os.path.relpath(path, root)}\n{content}".lower()
    score = 0
    reasons = []

    for ticket in signals["tickets"]:
        if ticket and ticket in haystack:
            score += 4
            reasons.append(f"branch-ticket:{ticket}")

    plan_hits = sorted(t for t in signals["plan"] if t in haystack)
    if plan_hits:
        score += min(4, len(plan_hits) * 2)
        reasons.append("plan:" + ",".join(plan_hits[:3]))

    file_hits = sorted(t for t in signals["files"] if t in haystack)
    if file_hits:
        score += min(3, len(file_hits))
        reasons.append("files:" + ",".join(file_hits[:3]))

    return score, "; ".join(reasons)

def find_l3_review(branch, plan_path, changed_paths):
    cutoff = time.time() - 30 * 86400
    pat = re.compile(r"Round 1|Round 2|L3.*[Rr]eview|webrtc.*review")
    signals = current_feature_signals(branch, plan_path, changed_paths)
    candidates = []
    for path in glob.glob(os.path.join(root, "plan_docs", "**", "*.md"), recursive=True):
        try:
            if os.path.getmtime(path) < cutoff:
                continue
            with open(path, encoding="utf-8", errors="ignore") as f:
                content = f.read()
                if pat.search(content):
                    score, reason = score_review_candidate(path, content, signals)
                    candidates.append((score, os.path.getmtime(path), path, reason))
        except Exception:
            pass
    if not candidates:
        return "", False, ""
    candidates.sort(key=lambda item: (item[0], item[1]), reverse=True)
    score, _mtime, path, reason = candidates[0]
    return path, score < 2, reason or "no current feature signal matched"

def deny(msg):
    print(msg, file=sys.stderr)
    sys.exit(2)

def context(msg):
    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "additionalContext": msg
        }
    }, ensure_ascii=False))
    sys.exit(0)

paths, content = parse_patch(command)
paths = [normalize_path(p) for p in paths if normalize_path(p)]
if not paths:
    sys.exit(0)

for path in paths:
    short = rel(path)
    if "/chatforyou-desktop/src/" in path:
        deny("\n".join([
            "[GATE BLOCK - Desktop] chatforyou-desktop/src 는 직접 수정 금지입니다.",
            f"  파일: {short}",
            "  이 디렉토리는 nodejs-frontend 동기화 결과물입니다.",
            "  -> nodejs-frontend/ 에서 수정 후 'npm run sync' + 'scss:build' 로 동기화하세요.",
            "  (AGENT_GUIDE Non-Negotiable Safety Boundaries / docs/chatforyou_desktop.md)",
        ]))

branch = git_branch()
plan_exists = find_plan(branch)
declared = declared_level()
messages = []

for path in paths:
    short = rel(path)
    risk, reason, is_l3_filename, escalated = classify(path, content)
    if risk == "none":
        continue

    declared_l3 = declared == "L3"
    if declared and rank(declared) > rank(risk):
        messages.append(f"[DECLARED] 유저 선언 레벨 {declared} 적용 (감지: {risk} -> effective: {declared})")
        risk = declared
        reason = f"{reason + ' + ' if reason else ''}유저 선언 {declared}"

    if escalated:
        messages.append(f"[ESCALATION] {escalated}")

    if risk == "L3":
        if (is_l3_filename or declared_l3) and not plan_exists:
            deny("\n".join(messages + [
                "[GATE BLOCK - L3] L3 작업 + plan 문서 없음",
                f"  파일: {short}",
                f"  분류 근거: {reason}",
                "  필요 조건:",
                "    1. plan_docs/00-base_plan/ 에 plan 문서 생성",
                "    2. docs/agent/webrtc-review-protocol.md 2라운드 리뷰",
                f"  현재 브랜치: {branch}",
            ]))
        review_path, review_weak, review_reason = find_l3_review(branch, plan_exists, paths)
        if not review_path:
            messages.append(f"[GATE WARN - L3] WebRTC 관련 변경이지만 2라운드 리뷰 문서 미확인 | 파일: {short} | 근거: {reason}")
        elif review_weak:
            messages.append(f"[GATE WARN - L3] 2라운드 리뷰 후보는 있으나 현재 feature/plan과의 매칭이 약함 | review: {os.path.basename(review_path)} | 파일: {short} | 근거: {review_reason}")
        else:
            messages.append(f"[GATE OK - L3] plan: {os.path.basename(plan_exists) if plan_exists else '?'} | review: {os.path.basename(review_path)} | 매칭: {review_reason} | {short}")
    elif risk == "L2":
        if not plan_exists:
            messages.append(f"[GATE WARN - L2] 소스/설정 변경인데 관련 plan 문서가 없습니다. 파일: {short} | 근거: {reason} | 브랜치: {branch}")
        else:
            messages.append(f"[GATE OK - L2] plan: {os.path.basename(plan_exists)} | {short}")

if messages:
    context("\n".join(messages))
sys.exit(0)
PYEOF
exit $?
