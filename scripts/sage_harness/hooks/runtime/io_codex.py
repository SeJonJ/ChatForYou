"""io_codex — pre-implementation-gate 의 Codex 전용 IO (입력추출/declared/렌더). R1 분리.

런타임이 진짜 다른 부분만: apply_patch 다중파일 파싱, .codex/logs declared 읽기,
block→stderr / 그 외→hookSpecificOutput JSON 렌더(채널). 본문 로직은 hook_runtime 공유,
사용자 문구는 messages 모듈 공유(5-3 — io_claude 와의 테이블 중복 제거).
"""
import json
import os
import re
import sys

import messages

RUNTIME = "codex"
HOST_DIR = ".codex"
ROOT_ENV = "CODEX_PROJECT_ROOT"


def should_skip(raw):
    return (raw.get("tool_name") or "") != "apply_patch"


def extract_changes(raw, rel):
    # apply_patch 본문 → 파일별 {path, op, content(+라인), removed_content(-라인)}
    command = (raw.get("tool_input") or {}).get("command") or ""
    changes, content_targets = [], []
    for line in command.splitlines():
        m = re.match(r"^\*\*\* (Add|Update|Delete) File: (.+)$", line)
        if m:
            change = {"path": rel(m.group(2).strip()), "op": m.group(1).lower(), "content": ""}
            # Add File 은 모든 줄이 `+` 라 content 가 곧 변경 후 전체 본문이다. Update File 은
            # 추가된 줄뿐이므로 전체를 전제로 하는 판정이 이것을 문서로 오해하면 안 된다.
            if change["op"] == "add":
                change["full_content"] = True
            changes.append(change)
            content_targets = [change]
            continue
        move = re.match(r"^\*\*\* Move to: (.+)$", line)
        if move:
            source = content_targets[0] if content_targets else None
            destination = {"path": rel(move.group(1).strip()), "op": "move",
                           "content": source["content"] if source else ""}
            if source:
                # 판정은 **목적지 경로·stem** 기준이어야 한다. source 를 따라가면 이동 전 이름의
                # stem 으로 걸러져 사이클 밖으로 빠지고, 이동+수정 한 번에 문서 검사가 통째로
                # 우회된다. 그래서 목적지가 source 를 가리키게 해 되짚은 결과를 넘겨받는다.
                destination["source_path"] = source["path"]
                # source 는 최종 상태에 남지 않는다. 그 사실을 **경로**가 아니라 이 변경 객체에
                # 적어야 한다 — 같은 패치가 그 경로에 새 문서를 다시 만들면(Add File) 경로로
                # 기억한 배제가 그 새 문서까지 통째로 검사에서 빼버린다.
                source["move_destination"] = destination["path"]
                if source.get("full_content"):
                    destination["full_content"] = True
                if source.get("removed_content"):
                    destination["removed_content"] = source["removed_content"]
            changes.append(destination)
            content_targets.append(destination)
            continue
        if line.startswith("*** "):
            # `*** End of File` 만은 hunk 의 **위치 정보**라 버리면 안 된다 — 파일 끝이라는
            # 사실을 잃으면 반복 문맥에서 앞쪽 일치를 골라 실제와 다른 문서를 되짚는다.
            if line.strip() == "*** End of File":
                for target in content_targets:
                    if target["op"] == "update":
                        target.setdefault("patch_body", []).append("*** End of File")
            continue                      # End Patch 등 그 밖의 지시어는 hunk 본문이 아니다
        # patch_body: hunk 원문을 그대로 싣는다. content/removed_content 는 +/- 만 모은 집계라
        # 문맥줄이 빠져 있어 변경 후 전체 본문을 되짚을 수 없다 — 문서 전체를 전제로 하는
        # 판정(본문 언어 구조 smoke)은 되짚은 결과가 있어야만 성립한다.
        # update 에만 싣는다: add 는 content 가 이미 전체 본문이고, move 목적지는 디스크에 원본이
        # 없어 되짚기가 성립하지 않는다(있는 것처럼 실으면 정상 rename 이 차단된다).
        for target in content_targets:
            if target["op"] == "update":
                target.setdefault("patch_body", []).append(line)
        if line.startswith("+"):
            for target in content_targets:
                target["content"] += line[1:] + "\n"
        elif line.startswith("-"):
            for target in content_targets:
                target["removed_content"] = target.get("removed_content", "") + line[1:] + "\n"
    return changes


def read_declared_level(raw, root):
    sid = re.sub(r"[^A-Za-z0-9_-]", "_", raw.get("session_id", "") or "nosession")[:64]
    dp = os.path.join(root, HOST_DIR, "logs", f"declared-risk-{sid}.json")
    try:
        with open(dp, encoding="utf-8") as f:
            return json.load(f).get("level")
    except Exception:
        return None


def render_gate(decision, profile, root=None):
    # 문구는 messages 공유(SSOT), 채널은 Codex=block→stderr / 그 외→hookSpecific JSON.
    m = messages.gate_text(decision, profile, RUNTIME,
                           language=messages.display_language(root))
    if decision["status"] == "block":
        if m:
            print(m, file=sys.stderr)
    elif m:
        print(json.dumps({"hookSpecificOutput": {"hookEventName": "PreToolUse", "additionalContext": m}},
                         ensure_ascii=False))
    return decision["exit_code"]


def render_declared_capture(level):
    # Codex 출력 프로토콜: hookSpecificOutput JSON
    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "UserPromptSubmit",
            "additionalContext": messages.declared_capture_text(level, RUNTIME)
        }
    }, ensure_ascii=False))


def render_declared_ambiguous():
    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "UserPromptSubmit",
            "additionalContext": messages.declared_ambiguous_text(RUNTIME)
        }
    }, ensure_ascii=False))


def render_declared_clear(existed=True):
    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "UserPromptSubmit",
            "additionalContext": messages.declared_clear_text(RUNTIME, existed)
        }
    }, ensure_ascii=False))


# --- post-tool-logger IO (Codex: apply_patch 본문 다중파일 + Move) ---
def logger_tool_name(raw):
    return "apply_patch"


def extract_logged_changes(raw, rel):
    command = (raw.get("tool_input") or {}).get("command") or ""
    changes = []
    for line in command.splitlines():
        op = path = None
        m = re.match(r"^\*\*\* (Add|Update|Delete) File: (.+)$", line)
        if m:
            op = m.group(1).lower()
            path = m.group(2).strip()
        else:
            m2 = re.match(r"^\*\*\* Move to: (.+)$", line)
            if m2:
                op = "move"
                path = m2.group(1).strip()
        if path:
            changes.append({"path": rel(path), "op": op})
    return changes


# --- pre-phase4-checklist-gate IO (Codex: 파일 블록 단위 — 문서가 "생기는" 경로만) ---
def extract_phase4_changes(raw, rel):
    # Move 는 직전 Update 블록의 속성이다: 문서는 목적지에만 생기고 원본 경로에는 아무것도
    # 쓰이지 않는다. 원본을 남기면 04-analyze 밖으로 빼는 작업이 Phase 04 작성으로 오인돼
    # 차단되고(J-10 실측), 목적지를 빠뜨리면 이동만으로 게이트를 지나간다(J-8 실측).
    # Delete 는 증거를 요구할 대상이 아니라 제외하되 블록 경계는 리셋한다.
    changes = []
    current = None   # Move 가 대체할 수 있는 직전 Add|Update 블록
    for line in ((raw.get("tool_input") or {}).get("command") or "").splitlines():
        m = re.match(r"^\*\*\* (Add|Update) File: (.+)$", line)
        if m:
            current = {"path": rel(m.group(2).strip()), "op": m.group(1).lower()}
            changes.append(current)
            continue
        if re.match(r"^\*\*\* Delete File: ", line):
            current = None
            continue
        move = re.match(r"^\*\*\* Move to: (.+)$", line)
        if move:
            if current is not None:
                current["path"] = rel(move.group(1).strip())
                current["op"] = "move"
                current = None
            else:
                changes.append({"path": rel(move.group(1).strip()), "op": "move"})
    return changes


def render_phase4(decision):
    dec = decision
    s = dec["status"]
    if s == "block":
        lines = [messages.phase4_block_header(dec['total_unchecked'], dec['base'], RUNTIME)]
        for ev in dec["evidence"]:
            lines.append(f"  - {ev['label']}: {ev['file']} ({len(ev['unchecked'])}건 미완료)")
        msg = "\n".join(lines)
    elif s == "warn":
        msg = messages.phase4_warn(dec['base'], RUNTIME)
    elif s == "ok":
        msg = messages.phase4_ok(dec['base'], RUNTIME)
    else:
        msg = ""
    if dec["status"] == "block":
        if msg:
            print(msg, file=sys.stderr)
    elif msg:
        print(json.dumps({"hookSpecificOutput": {"hookEventName": "PreToolUse", "additionalContext": msg}},
                         ensure_ascii=False))
    return dec["exit_code"]


# --- stop-compliance-report IO (Codex) ---
def _last_assistant_text(path):
    if not path or not os.path.exists(path):
        return ""
    last = ""
    seen = 0
    try:
        with open(path, encoding="utf-8", errors="ignore") as fh:
            for line in fh:
                seen += 1
                if seen > 200000:   # audit P1: 거대 transcript DoS 방지 (라인 캡)
                    break
                line = line.strip()
                if not line:
                    continue
                try:
                    d = json.loads(line)
                except Exception:
                    continue
                role = d.get("role") or (d.get("message") or {}).get("role")
                if role == "assistant":
                    c = d.get("content") or (d.get("message") or {}).get("content") or ""
                    last = c if isinstance(c, str) else json.dumps(c, ensure_ascii=False)
    except Exception:
        return ""
    return last


def attach_policy_results(model, profile, entries, raw_text, kc_result):
    # Codex 전용 output_contract 먼저, 그 다음 공유 knowledge_capture (원본 순서 보존).
    import hook_runtime as hr
    import output_contract_check
    try:
        raw = json.loads(raw_text or "{}")
    except Exception:
        raw = {}
    transcript = raw.get("transcript_path", "") or ""
    has_code = any(e.get("type") in hr.code_types_of(profile) for e in entries)
    markers = (profile.get("output_contract") or {}).get("markers")   # EH-2: 프로젝트 고유 마커 주입(없으면 중립 기본)
    oc = output_contract_check.check(_last_assistant_text(transcript), has_code, markers)
    model["sections"]["policy_results"].append(oc)
    model["sections"]["policy_results"].append(kc_result)


def render_stop_result(today, block_reason=None):
    """Codex Stop wire는 단일 JSON 객체만 허용한다.

    차단 시 decision/reason을 반환하면 Codex가 같은 turn을 재실행하고 다음 Stop 입력에
    stop_hook_active=true를 보낸다. Stop 이벤트는 hookSpecificOutput.additionalContext를 허용하지 않고,
    decision과 결합하거나 단독으로 출력해도 hook failure가 된다. 차단 reason에 리포트 경로를 포함하고
    통과 시에는 아무것도 출력하지 않는다. 리포트 자체는 호출 전에 파일로 저장된다.
    """
    report_text = messages.report_saved_text(HOST_DIR, today, RUNTIME)
    if block_reason:
        print(json.dumps({"decision": "block", "reason": f"{block_reason}\n{report_text}"},
                         ensure_ascii=False))
    return 0
