"""io_claude — pre-implementation-gate 의 Claude 전용 IO (입력추출/declared/렌더). R1 분리.

런타임이 진짜 다른 부분만: Write/Edit/MultiEdit 입력추출, .claude/logs declared 읽기,
채널 렌더(BLOCK=stderr / PreToolUse 비차단=hookSpecificOutput JSON / UserPromptSubmit=평문).
본문 로직(snapshot/전략/decide)은 hook_runtime 공유,
사용자 문구는 messages 모듈 공유(5-3 — io_codex 와의 테이블 중복 제거).
"""
import json
import os
import re
import sys

import messages

RUNTIME = "claude"
HOST_DIR = ".claude"
ROOT_ENV = "CLAUDE_PROJECT_DIR"


def should_skip(raw):
    return False   # claude: 모든 Write/Edit/MultiEdit 대상


def extract_changes(raw, rel):
    ti = raw.get("tool_input") or {}
    fp = ti.get("file_path") or ""
    tool_name = raw.get("tool_name") or ""
    blob = (ti.get("content") or "") or (ti.get("new_string") or "")
    removed = ti.get("old_string") or ""
    # edits: 치환쌍을 순서대로 따로 싣는다. blob/removed 는 "\n" 으로 이어붙인 집계라
    # 그것만으로는 변경 후 전체 본문을 되짚을 수 없다 — 문서 전체를 전제로 하는 판정
    # (본문 언어 구조 smoke)이 부분 조각을 전체로 오해하지 않으려면 순서쌍이 필요하다.
    # `all` 은 host 가 실제로 하는 동작이다(replace_all). 빠뜨리면 되짚은 문서가 실제 결과와
    # 달라져, 모든 한국어를 걷어내는 편집이 "아직 남아 있다" 로 읽힌다.
    edits = []
    if tool_name != "Write" and (ti.get("old_string") or ti.get("new_string")):
        edits.append({"old": ti.get("old_string") or "", "new": ti.get("new_string") or "",
                      "all": bool(ti.get("replace_all"))})
    for e in (ti.get("edits") or []):
        blob += "\n" + (e.get("new_string") or "")
        removed += "\n" + (e.get("old_string") or "")
        edits.append({"old": e.get("old_string") or "", "new": e.get("new_string") or "",
                      "all": bool(e.get("replace_all"))})
    if not fp:
        return []
    change = {"path": rel(fp), "op": "write" if tool_name == "Write" else "update",
              "content": blob}
    if tool_name == "Write":
        change["full_content"] = True
    if removed:
        change["removed_content"] = removed
    if edits:
        change["edits"] = edits
    return [change]


def read_declared_level(raw, root):
    sid = re.sub(r"[^A-Za-z0-9_-]", "_", raw.get("session_id", "") or "nosession")[:64]
    dp = os.path.join(root, HOST_DIR, "logs", f"declared-risk-{sid}.json")
    try:
        with open(dp, encoding="utf-8") as f:
            return json.load(f).get("level")
    except Exception:
        return None


def _pre_tool_context(text):
    """PreToolUse 비차단 메시지의 컨텍스트 봉투.

    Claude Code 는 exit 0 hook 의 평문 stdout 을 디버그 로그에만 쓴다 — 컨텍스트로 승격되는
    이벤트는 UserPromptSubmit/UserPromptExpansion/SessionStart 뿐이다. 그래서 PreToolUse 는
    hookSpecificOutput.additionalContext 로 실어야 모델과 사용자에게 닿는다.
    ensure_ascii=False: 이스케이프되면 디버그 로그에서 한글을 읽을 수 없다.
    """
    return json.dumps({"hookSpecificOutput": {
        "hookEventName": "PreToolUse", "additionalContext": text}}, ensure_ascii=False)


def render_gate(decision, profile, root=None):
    # 문구는 messages 공유(SSOT), 채널만 여기 소유.
    # BLOCK 은 stderr 평문 — Claude Code 는 exit 2 의 차단 사유를 stderr 에서 읽고 stdout 을
    # 무시하므로, 여기에 JSON 을 얹으면 무의미하고 형식 오류 시 진단만 흐려진다.
    m = messages.gate_text(decision, profile, RUNTIME,
                           language=messages.display_language(root))
    if not m:
        return decision["exit_code"]
    if decision["status"] == "block":
        print(m, file=sys.stderr)
    else:
        print(_pre_tool_context(m))
    return decision["exit_code"]


def render_declared_capture(level):
    # UserPromptSubmit 은 exit 0 평문 stdout 이 그대로 컨텍스트가 되는 세 이벤트 중 하나라
    # 봉투가 필요 없다(PreToolUse 와 다르다). JSON 도 파싱되지만 바꿀 이유가 없으므로
    # 기존 동작을 유지한다 — 이 사이클의 범위는 '닿지 않는 메시지'를 닿게 하는 것이다.
    print(messages.declared_capture_text(level, RUNTIME))


def render_declared_ambiguous():
    print(messages.declared_ambiguous_text(RUNTIME))


def render_declared_clear(existed=True):
    print(messages.declared_clear_text(RUNTIME, existed))


# --- post-tool-logger IO (Claude: tool_input.file_path 단일) ---
def logger_tool_name(raw):
    return raw.get("tool_name", "") or ""


def extract_logged_changes(raw, rel):
    fp = (raw.get("tool_input") or {}).get("file_path") or ""
    return [{"path": rel(fp), "op": "write"}] if fp else []


# --- pre-phase4-checklist-gate IO (Claude) ---
def extract_phase4_changes(raw, rel):
    fp = (raw.get("tool_input") or {}).get("file_path") or ""
    return [{"path": rel(fp), "op": "write"}] if fp else []


def render_phase4(decision):
    dec = decision
    s = dec["status"]
    if s == "block":
        lines = [messages.phase4_block_header(dec['total_unchecked'], dec['base'], RUNTIME),
                 "  04-analyze 작성 전 아래 항목을 완료(또는 N/A 사유와 함께 [x])하세요:"]
        for ev in dec["evidence"]:
            lines.append(f"  ▸ {ev['label']}: {ev['file']} ({len(ev['unchecked'])}건 미완료)")
            for it in ev["unchecked"][:6]:
                t = it["text"]
                lines.append(f"      L{it['line']}: {t if len(t) <= 90 else t[:87] + '...'}")
            extra = len(ev["unchecked"]) - 6
            if extra > 0:
                lines.append(f"      ... 외 {extra}건")
        msg = "\n".join(lines)
    elif s == "warn":
        msg = messages.phase4_warn(dec['base'], RUNTIME)
    elif s == "ok":
        msg = messages.phase4_ok(dec['base'], RUNTIME)
    else:
        msg = ""
    if msg:
        if s == "block":
            print(msg, file=sys.stderr)
        else:
            print(_pre_tool_context(msg))
    return dec["exit_code"]


# --- stop-compliance-report IO (Claude) ---
def attach_policy_results(model, profile, entries, raw_text, kc_result):
    # F7: claude 도 knowledge_capture 주입. output_contract 는 미적용(Codex-only 설계 + 마커 비독립).
    model["sections"]["policy_results"].append(kc_result)


def render_stop_result(today, block_reason=None):
    # 저장 알림은 차단 사유가 아니므로 stdout 유지. 차단 사유만 stderr — Stop hook 도
    # exit 2 일 때 host 가 읽는 채널은 stderr 다.
    print(messages.report_saved_text(HOST_DIR, today, RUNTIME))
    if block_reason:
        print(f"[stop-compliance-report] ❌ {block_reason}", file=sys.stderr)
        return 2
    return 0
