"""hook_runtime — 어댑터에 복제돼 있던 IO 오케스트레이션의 공유 단일소스 (외부검토 R1 / P0-1).

claude/codex 어댑터가 바이트 단위로 복제하던 profile 로드·rel·snapshot 빌드·L3 전략 로드·
core.decide 호출을 여기로 1회만 들어올린다(verbatim lift — 동작 무변경). 런타임이 진짜 다른
입력추출/declared 읽기/출력렌더만 io_claude/io_codex 로 분리.

보존 원칙(원본 어댑터와 동일):
- 입력 JSON 파싱 실패 = transient 글리치 → fail-open(exit0) 하되 stderr surface(silent 금지).
- profile 파싱 실패 = 게이트 무력화 → fail-open 하되 LOUD surface(조용한 gate-disable = Pattern A 방지).
- root 밖/절대경로 glob 거부(독립성). L3 전략 크래시는 surface + fail-closed(None → core BLOCK 유지, F8b).
"""
import calendar
import errno
import fnmatch
import glob
import hashlib
import importlib.util
import json
import os
import posixpath
import re
import stat
import subprocess
import sys
import time

HOOKS_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if HOOKS_DIR not in sys.path:
    sys.path.insert(0, HOOKS_DIR)
import cycle_binding
import risk_declaration
import checklist_contract
import cycle_state


class ProfileLoadError(RuntimeError):
    pass


class ProjectHookError(RuntimeError):
    pass


def resolve_branch(root, default=""):
    """현재 브랜치. SAGE_GATE_BRANCH 우선, 없으면 root 기준 git. git 실패 → default.

    default 는 hook 별 원본 fallback 보존용(pre-impl="" / post-tool-logger="unknown").
    git 성공 시 stdout 그대로(분리HEAD 등 빈 문자열 가능 — 원본 충실).
    """
    b = os.environ.get("SAGE_GATE_BRANCH")
    if b:
        return b
    try:
        return subprocess.run(["git", "-C", root, "rev-parse", "--abbrev-ref", "HEAD"],
                              capture_output=True, text=True, check=True).stdout.strip()
    except Exception:
        return default


def load_profile_fail_open(hook_id):
    """SAGE_PROFILE 로드. 미설정/부재 → None(게이트 통과). 파싱실패 → None + LOUD surface."""
    prof_path = os.environ.get("SAGE_PROFILE", "")
    if not prof_path or not os.path.exists(prof_path):
        return None
    try:
        with open(prof_path, encoding="utf-8") as f:
            return json.load(f)
    except Exception as e:
        print(f"⛔ [{hook_id}] profile 파싱 실패 → 위험 게이트 무력화(SAGE_PROFILE 수정 필요): "
              f"{type(e).__name__}: {e}", file=sys.stderr)
        return None


def load_profile_fail_closed(hook_id, root=None):
    """Load a required compiled profile and preserve absence as the legacy no-op policy."""
    prof_path = os.environ.get("SAGE_PROFILE", "")
    if not prof_path or not os.path.exists(prof_path):
        return None
    try:
        with open(prof_path, encoding="utf-8") as stream:
            profile = json.load(stream)
    except Exception as exc:
        raise ProfileLoadError(
            f"compiled profile 로드 실패: {type(exc).__name__}: {exc}") from exc
    if not isinstance(profile, dict):
        raise ProfileLoadError("compiled profile 루트는 mapping이어야 함")
    issues = checklist_contract.checklist_target_issues(profile)
    if issues:
        raise ProfileLoadError("; ".join(_overlay_say(root, issue) for issue in issues))
    return profile


def parse_input_fail_open(hook_id, raw_text, surface=True):
    """stdin raw → dict. 실패 → None(호출자가 exit0).

    surface=True(게이트 hook): malformed 입력을 stderr surface(silent 금지 — Pattern A 방지).
    surface=False(비게이트 hook, 예: capture-declared-risk): 원본 어댑터가 silent exit0 이었으므로 보존.
    """
    try:
        return json.loads(raw_text or "{}")
    except Exception as e:
        if surface:
            print(f"[{hook_id}] hook 입력 JSON 파싱 실패 → 이번 호출 게이트 skip: {type(e).__name__}",
                  file=sys.stderr)
        return None


def make_rel(root):
    """모든 도구 경로를 정규화된 root 상대 경로로 변환한다.

    상대경로도 root 기준 절대경로로 해석한 뒤 다시 상대화하므로 ``.``, ``..``,
    중복 구분자로 같은 파일을 다르게 표현해 게이트 applicability를 우회할 수 없다.
    실제 root 밖 대상은 프로젝트 상대 namespace에서 명시적으로 제외한다.
    """
    root_abs = os.path.abspath(root)

    def rel(p):
        if not p:
            return ""
        try:
            supplied = str(p).replace("\\", os.sep)
            target = supplied if os.path.isabs(supplied) else os.path.join(root_abs, supplied)
            target_abs = os.path.abspath(target)
            if os.path.commonpath((root_abs, target_abs)) != root_abs:
                return "<outside-project>"
            relative = os.path.relpath(target_abs, root_abs)
            return "" if relative == "." else _canon_relkey(relative)
        except Exception:
            return "<outside-project>"
    return rel


def build_snapshot(profile, root, rel):
    """plan_files / review_candidates / phase_docs 스냅샷. glob 은 profile 주입(경로 하드코딩 없음)."""
    pg = (profile.get("risk") or {}).get("plan_glob", "")   # 미설정/무효 → plan scan 없음(graceful)
    if pg and (os.path.isabs(pg) or ".." in pg.split("/")):  # root 밖 glob 거부 → 빈 scan
        pg = ""
    paths = sorted(glob.glob(os.path.join(root, pg), recursive=True),
                   key=lambda p: -os.path.getmtime(p)) if pg else []
    now = time.time()
    plan_files = []
    for p in paths:
        try:
            with open(p, encoding="utf-8", errors="ignore") as f:
                c = f.read()
        except Exception:
            c = ""
        plan_files.append({"path": rel(p), "content": c, "recent": (now - os.path.getmtime(p)) <= 7 * 86400})
    review_candidates = [pf for pf, p in zip(plan_files, paths) if (now - os.path.getmtime(p)) <= 30 * 86400]

    review_glob = (profile.get("risk") or {}).get("l3_review_glob", "")
    if review_glob and (os.path.isabs(review_glob) or ".." in review_glob.split("/")):
        review_glob = ""
    l3_review_docs = []
    for p in sorted(glob.glob(os.path.join(root, review_glob), recursive=True)) if review_glob else []:
        try:
            with open(p, encoding="utf-8", errors="ignore") as fh:
                content = fh.read()
        except Exception:
            content = ""
        l3_review_docs.append({"path": rel(p), "content": content})

    phase_docs = {}
    pdca = profile.get("pdca") or {}
    if pdca.get("enabled"):
        for ph in (pdca.get("phases") or []):
            pid, pglob = ph.get("id"), ph.get("glob") or ""
            if not pid or not pglob or os.path.isabs(pglob) or ".." in pglob.split("/"):
                continue   # root 밖/무효 glob 거부
            docs = []
            for p in glob.glob(os.path.join(root, pglob), recursive=True):
                try:
                    with open(p, encoding="utf-8", errors="ignore") as f:
                        cc = f.read()
                except Exception:
                    cc = ""
                docs.append({"path": rel(p), "content": cc, "recent": (now - os.path.getmtime(p)) <= 7 * 86400})
            phase_docs[pid] = docs

        # Fast Cycle uses one physical 00 document as deterministic virtual 01..04 documents.
        # The shared parser is also used by the CLI and server-side consumers.
        try:
            from fast_cycle_contract import parse_fast_plan
            for doc in list(phase_docs.get("00") or []):
                plan, issues = parse_fast_plan(doc.get("content") or "")
                if plan is None or issues or plan.metadata.get("Cycle-Mode") != "FAST":
                    continue
                header = (f"Cycle-Stem: `{plan.metadata.get('Cycle-Stem', '')}`\n"
                          f"Risk Level: {plan.metadata.get('Risk Level', '')}\n")
                for virtual_id in ("01", "02", "03", "04"):
                    phase_docs.setdefault(virtual_id, []).append({
                        "path": doc.get("path"),
                        "content": header + plan.sections.get(virtual_id, ""),
                        "recent": doc.get("recent", False),
                        "virtual_fast": True,
                    })
        except Exception:
            # Core receives parser/audit failures separately and closes only active Fast runs.
            pass

    # 9.5 report←approve audit 증거: loop_audit 요약을 core 에 주입(2층 — adapter 가 .sage 읽기, core 순수).
    # fail-open: audit 없음/손상이어도 빈 요약(게이트가 advisory/enforce 로 처리, snapshot 빌드는 안 깸).
    try:
        import loop_audit
        la = loop_audit.audit_summary(root)
    except Exception as exc:
        # 파일을 읽어 요약하지 못한 상태를 "정상적으로 빈 로그"와 구분한다. 선택 run도 사라지므로
        # 기존 gate가 닫히지만, 원문 파싱 실패와 adapter/module 실패의 원인은 구분한다(10-g).
        la = {
            "runs": {},
            "has_any_records": False,
            "file_ok": False,
            "file_issues": [],
            "snapshot_error": f"{type(exc).__name__}: {exc}",
        }
    try:
        import fast_cycle_audit
        fast_audit = fast_cycle_audit.audit_summary(root)
    except Exception as exc:
        fast_audit = {
            "runs": {}, "active": [], "has_any_records": False,
            "file_ok": False, "file_issues": [],
            "snapshot_error": f"{type(exc).__name__}: {exc}",
        }
    acceptance = ((profile.get("verification") or {}).get("acceptance") or {})
    waiver_cfg = acceptance.get("waiver") if isinstance(acceptance, dict) else {}
    if isinstance(waiver_cfg, dict) and waiver_cfg.get("enabled") is True:
        try:
            import acceptance_waiver
            acceptance_waivers = acceptance_waiver.audit_summary(root)
        except Exception as exc:
            acceptance_waivers = {"valid": False, "active": [],
                                  "issues": [f"waiver snapshot failed: {type(exc).__name__}: {exc}"],
                                  "has_any_records": False}
    else:
        acceptance_waivers = {"valid": True, "active": [], "issues": [], "has_any_records": False}
    return {"plan_files": plan_files, "review_candidates": review_candidates,
            "l3_review_docs": l3_review_docs,
            "phase_docs": phase_docs, "loop_audit": la, "fast_cycle_audit": fast_audit,
            "acceptance_waivers": acceptance_waivers}


def _matched_domains(profile, changes):
    matched = set()
    for domain in (profile.get("risk") or {}).get("domains") or []:
        did = domain.get("id") or ""
        if not did:
            continue
        globs = domain.get("path_globs") or []
        keywords = [str(k).lower() for k in (domain.get("content_keywords") or []) if str(k)]
        for change in changes:
            path = change.get("path") or ""
            content = (change.get("content") or "").lower()
            if any(fnmatch.fnmatch(path.lower(), str(pattern).lower()) for pattern in globs):
                matched.add(did)
                break
            if any(keyword in content for keyword in keywords):
                matched.add(did)
                break
    return matched


def run_strategy(hook_id, profile, core_dir, changes, event, snapshot):
    """L3 review 매칭 전략(profile.risk.l3_review_strategy) 로드·실행. 미설정 → None(안전 BLOCK).

    크래시를 조용히 None 처리하면 '전략 미선택'으로 둔갑해 진짜 원인이 숨음 → surface + fail-closed(F8b).
    """
    strat = (profile.get("risk") or {}).get("l3_review_strategy", "")
    if not strat:
        return None
    # 전략은 SAGE 코어 자산 → CORE_DIR 기준(타겟 프로젝트 root 아님)
    sys.path.insert(0, os.path.join(core_dir, "strategies", "pre_implementation_gate"))
    try:
        smod = importlib.import_module(strat)
        rk = profile.get("risk") or {}
        ftoks = set()
        for c in changes:                       # whole-path 아닌 토큰으로(전략이 토큰 겹침 비교)
            cp = c["path"]
            ftoks |= {t.lower() for t in re.split(r"[^A-Za-z0-9가-힣]+", cp + " " + os.path.basename(cp)) if len(t) >= 3}
        binding = cycle_binding.resolve(event, snapshot, profile.get("pdca") or {})
        signals = {"plan": set(), "files": ftoks,
                   "cycle_stem": binding.get("stem"),
                   "cycle_binding_error": binding.get("error"),
                   "matched_domains": _matched_domains(profile, changes),
                   "generic_tokens": rk.get("generic_tokens") or [],   # 전략 확장(profile 주입)
                   "review_patterns": rk.get("review_patterns") or []}
        return smod.find_l3_review(signals, snapshot)
    except Exception as e:
        print(f"[{hook_id}] L3 전략 '{strat}' 실행 오류 → fail-closed BLOCK: "
              f"{type(e).__name__}: {e}", file=sys.stderr)
        return None


_NON_OVERRIDABLE_BLOCKS = {
    "block_cycle_risk_declaration",
    "block_cycle_risk_reconciliation",
    "block_report_without_acceptance",
    "block_report_waiver_audit_failure",
    "block_invalid_done_criteria",
    "block_phase00_mixed_evidence",
    "block_report_without_done_criteria",
    "block_stale_done_criteria_revision",
    "block_stale_done_criteria_approval",
    "block_gate_runtime_error",
    # 감사 기록 실패로 생긴 BLOCK 은 override 대상이 아니다 — override 는 그 자체가 감사에 남는 우회인데,
    # 감사를 못 쓰는 상태에서 감사로 우회한다는 건 성립하지 않는다. waiver 기록 실패와 같은 취급.
    "block_cycle_stem_audit_failure",
    "block_fast_cycle_audit",
}


def _maybe_override(hook_id, root, decision, changes):
    """게이트 BLOCK 을 활성 override(미만료)로 합법 우회 → 통과(True) 전 bypass 를 감사로그에 기록 (P1-5).

    순수 코어(IO 0)는 정책만 판정하고, 우회는 런타임/운영 관심사이므로 여기서 처리(코어 불변).
    안전: 정확히 이 게이트(또는 'all') 대상 미만료 grant 가 있을 때만 우회하며, 무엇을(message_key)
    어느 파일에 적용했는지 .sage/override.jsonl 에 남긴다. override_audit 미가용/비-block → False(원래 흐름).
    """
    if (decision or {}).get("status") != "block":
        return False
    if decision.get("message_key") in _NON_OVERRIDABLE_BLOCKS:
        return False
    try:
        import override_audit as ov
    except Exception:
        return False
    # 조회 실패는 예외로 흘리지 않는다. 여기서 raise 하면 어댑터 경로(run_hook.main)에 예외 처리가
    # 없어 traceback rc=1 로 죽고, 호스트가 이를 non-blocking 오류로 분류해 **도구 호출이 그대로
    # 진행된다** — 10-d 에서 고친 write-guard fail-open 과 같은 유형이다. override 를 확인할 수
    # 없으면 우회하지 않고(False) 원래 BLOCK 을 그대로 렌더링하는 것이 fail-closed 다.
    try:
        grants = ov.active_grants(root, gate=hook_id)
    except Exception as exc:
        # override_audit 은 sage.i18n 을 import 할 수 없어(엔진 없이 단독 실행) 진단을 code+arguments
        # dict 로 올린다(StateHomeError.diagnostic) — 있으면 hook catalog 로 렌더, 없으면(범용
        # 예외) 원문 그대로. 감싸는 문장 자체는 이 파일의 다른 fail-safe 안내와 같이 고정 한국어다.
        detail = _overlay_say(root, exc.diagnostic) if hasattr(exc, "diagnostic") else f"{type(exc).__name__}: {exc}"
        print(f"⚠️  [{hook_id}] override 조회 실패 → 우회 없이 원래 판정을 유지합니다: {detail}",
              file=sys.stderr)
        return False
    if not grants:
        return False
    files = [c.get("path") for c in (changes or []) if c.get("path")]
    g = grants[0]
    try:
        ov.record_bypass(root, hook_id, files, decision.get("message_key"), g)
    except Exception as exc:
        # 감사 기록 없이 우회를 적용하면 무감사 통과가 된다 — 우회를 포기하고 BLOCK 을 유지한다.
        print(f"⚠️  [{hook_id}] override bypass 감사 기록 실패 → 우회를 적용하지 않습니다: "
              f"{type(exc).__name__}: {exc}", file=sys.stderr)
        return False
    print(f"⚠️  [{hook_id}] GATE BLOCK override 적용 — 사유: {g.get('reason')} "
          f"(만료 {g.get('expires_at')}, .sage/override.jsonl 감사). "
          f"우회: {decision.get('message_key')} | 파일: {', '.join(files) or '(미상)'}",
          file=sys.stderr)
    return True


def _record_acceptance_waiver_uses(hook_id, root, decision):
    """Persist every waiver consumption before allowing the report write.

    A pure core decision cannot perform IO. The adapter owns the append, and any append
    failure replaces the advisory result with a BLOCK so no unaudited waiver is consumed.
    """
    uses = (decision or {}).get("waiver_uses") or []
    if not uses:
        return decision
    try:
        import acceptance_waiver
        for grant in uses:
            acceptance_waiver.record_use(root, grant, grant.get("report_path") or "")
        return decision
    except Exception as exc:
        print(f"⛔ [{hook_id}] acceptance waiver use 감사 기록 실패 → fail-closed BLOCK: "
              f"{type(exc).__name__}: {exc}", file=sys.stderr)
        return {"status": "block", "exit_code": 2, "risk": "PDCA",
                "message_key": "block_report_waiver_audit_failure",
                "reason": f"acceptance waiver use 감사 기록 실패: {type(exc).__name__}: {exc}",
                "file_short": (decision or {}).get("file_short", "")}


def _record_declared_cycle_stem(hook_id, root, decision, session_id):
    """선언된 cycle stem 이 판정에 쓰인 사실을 감사에 남긴다. 기록 실패 시 통과는 허용하지 않는다.

    BLOCK 은 기록 실패로 바꿀 것이 없으므로 그대로 둔다. 통과(ok/warn)는 다르다 — 선언 stem 은
    완결된 과거 사이클을 지목해 게이트 전체를 통과시킬 수 있어서, 기록하지 못한 통과는 waiver 소비를
    기록하지 못한 것과 같은 무감사 통과다. 그래서 같은 방식으로 fail-closed 한다.
    """
    if not (decision or {}).get("cycle_stem_declared"):
        return decision
    try:
        import override_audit
        override_audit.record_cycle_stem_declaration(
            root, hook_id, decision.get("cycle_stem") or "", session_id,
            status=decision.get("status") or "", origin=decision.get("cycle_stem_origin") or "")
        return decision
    except Exception as exc:
        if (decision or {}).get("status") == "block":
            print(f"[{hook_id}] 선언 cycle stem 감사 기록 실패(이미 BLOCK 이라 판정 유지): "
                  f"{type(exc).__name__}: {exc}", file=sys.stderr)
            return decision
        print(f"⛔ [{hook_id}] 선언 cycle stem 감사 기록 실패 → fail-closed BLOCK: "
              f"{type(exc).__name__}: {exc}", file=sys.stderr)
        return {"status": "block", "exit_code": 2, "risk": "PDCA",
                "message_key": "block_cycle_stem_audit_failure",
                "reason": f"선언 cycle stem 감사 기록 실패: {type(exc).__name__}: {exc}",
                "file_short": (decision or {}).get("file_short", "")}


def _decide_pre_implementation_fail_closed(hook_id, core, event, profile, snapshot, strategy_result):
    """Convert any unexpected core exception into the host's blocking exit contract."""
    try:
        return core.decide(event, profile, snapshot, strategy_result)
    except Exception as exc:
        print(f"⛔ [{hook_id}] core 판정 오류 → fail-closed BLOCK: "
              f"{type(exc).__name__}: {exc}", file=sys.stderr)
        files = [change.get("path") for change in (event.get("changes") or [])
                 if isinstance(change, dict) and change.get("path")]
        return {"status": "block", "exit_code": 2, "risk": "PDCA",
                "message_key": "block_gate_runtime_error", "safety_degraded": True,
                "reason": f"core 판정 오류: {type(exc).__name__}: {exc}",
                "file_short": ", ".join(files[:3])}


def _build_feedback_state(profile, root, changes):
    """§10-a-C: 변경 대상 파일의 현재(디스크) 차단성 마커를 core 에 주입.

    core.decide 는 순수 함수라 IO 를 할 수 없으므로 어댑터가 읽는다. 저장소 전체가 아니라
    **지금 쓰려는 파일만** 읽는다 — 게이트는 모든 Write/Edit 마다 돌기 때문에 전체 스캔은
    비용이 크고, 판정에 필요한 것도 대상 파일뿐이다.
    """
    section = profile.get("feedback")
    if not isinstance(section, dict) or section.get("enabled") is not True:
        return None                      # 섹션 없음/꺼짐 = 하위호환 무동작
    try:
        import feedback_markers
    except Exception:
        return None                      # 구형 hook 코어(모듈 부재) → graceful skip
    # CLI 스캔과 동일하게 plan_docs 제외 — 계획·설계 문서는 마커 예시를 담는 오탐원이라,
    # 거기 적힌 예시가 그 문서 편집을 막으면 안 된다.
    plan_prefix = ""
    paths_cfg = profile.get("paths")
    if isinstance(paths_cfg, dict) and isinstance(paths_cfg.get("plan_docs"), str):
        value = paths_cfg["plan_docs"].strip().strip("/")
        plan_prefix = (value + "/") if value else ""

    targets = {}
    for change in changes or []:
        rel_path = (change or {}).get("path") or ""
        if not rel_path or rel_path in targets:
            continue
        if plan_prefix and rel_path.startswith(plan_prefix):
            continue
        absolute = os.path.join(root, rel_path)
        try:
            with open(absolute, "rb") as handle:
                raw = handle.read()
            text = "" if b"\0" in raw else raw.decode("utf-8")
        except (OSError, UnicodeDecodeError):
            text = ""                    # 신규 파일·바이너리 → 기존 마커 없음
        markers = feedback_markers.blocking_markers(text, rel_path)
        if markers:
            targets[rel_path] = {"markers": markers, "on_disk": text}
    return {"enabled": True, "targets": targets}


EOF_SENTINEL = "*** End of File"


def _apply_edits(image, edits):
    """치환쌍(Claude Edit/MultiEdit)을 순서대로 적용한 결과. 되짚을 수 없으면 None.

    `replace_all` 은 host 가 실제로 하는 동작이다 — 첫 하나만 바꾼 결과를 최종 문서라고 넘기면,
    모든 한국어를 걷어내는 편집이 "아직 한국어가 남아 있다" 로 읽혀 구조 이상을 놓친다.
    """
    for edit in edits:
        old = edit.get("old") or ""
        if not old or old not in image:
            return None
        image = image.replace(old, edit.get("new") or "", -1 if edit.get("all") else 1)
    return image



def _split_hunks(body):
    """apply_patch(v4a) update 본문 → [(anchors, before_lines, after_lines, at_eof)].

    `@@ <text>` 는 hunk 경계이자 **위치 지정자**다. 경계로만 쓰고 text 를 버리면 같은 문맥이
    여러 번 나오는 문서에서 패치가 엉뚱한 자리에 붙는다 — 그렇게 만든 post-image 는 실제 결과가
    아니므로 그걸로 내리는 판정은 전부 근거가 없다. `*** End of File` 도 같은 이유로 보존한다:
    "파일 끝" 이라는 위치 정보를 버리면 반복 문맥에서 앞쪽 일치를 골라 엉뚱한 문서를 만든다.

    접두사 없는 빈 줄은 빈 문맥줄로 읽는다(생성기가 후행 공백을 지우는 경우가 흔하다). 그 밖의
    접두사 없는 줄은 형식을 모르는 상태이므로 되짚기를 포기한다.
    """
    hunks, anchors, before, after, at_eof = [], [], [], [], False
    for line in body:
        if line == EOF_SENTINEL:
            at_eof = True
        elif line.startswith("@@"):
            if before or after:
                hunks.append((anchors, before, after, at_eof))
                anchors, before, after, at_eof = [], [], [], False
            text = line[2:].strip()
            if text:
                anchors.append(text)          # 연속 @@ 는 바깥→안쪽 순서로 좁혀 간다
        elif line.startswith("+"):
            after.append(line[1:])
        elif line.startswith("-"):
            before.append(line[1:])
        elif line.startswith(" ") or line == "":
            before.append(line[1:])
            after.append(line[1:])
        else:
            return None
    if before or after or at_eof:
        hunks.append((anchors, before, after, at_eof))
    return hunks


def _find_block(lines, block, start):
    """`start` 이후에서 block 이 통째로 일치하는 첫 위치(없으면 None)."""
    span = len(block)
    for index in range(max(start, 0), len(lines) - span + 1):
        if lines[index:index + span] == block:
            return index
    return None



def _find_anchor(lines, anchor, start):
    """`@@ <text>` 가 가리키는 줄의 위치. 정확히 일치하는 줄이 없으면 공백을 무시하고 찾는다."""
    for index in range(max(start, 0), len(lines)):
        if lines[index] == anchor or lines[index].strip() == anchor:
            return index
    return None


def _apply_hunks(image, body):
    """디스크 원본에 update hunk 를 적용한 결과. 위치를 확정할 수 없으면 None.

    위치를 확정한다는 것은 셋 중 하나다. anchor 가 있으면 그 줄 **뒤에서만** 문맥을 찾고,
    `*** End of File` 이 있으면 파일 끝에서만 찾으며, 둘 다 없으면 남은 구간에서 그 문맥이
    **유일해야** 한다. 어느 쪽도 아니면 "아마 여기일 것" 이 되는데, 그렇게 만든 문서로 내리는
    판정은 정상 편집을 막는 쪽으로도 틀린다(fence 복구 패치 오차단).

    문맥줄 없는 순수 추가는 anchor 나 EOF 가 자리를 잡아줄 때만 받는다 — 그때는 붙는 자리가
    확정되므로, 쓸 수 있는 패치를 거절할 이유가 없다.
    """
    hunks = _split_hunks(body)
    if not hunks:
        return None
    lines = image.split("\n")
    cursor = 0
    for anchors, before, after, at_eof in hunks:
        start = cursor
        for anchor in anchors:
            # anchor 는 존재·순서 확인과 탐색 하한으로만 쓴다. 최종 위치를 정하는 것은
            # EOF 표시가 있으면 EOF 고, 없으면 문맥이다.
            index = _find_anchor(lines, anchor, start)
            if index is None:
                return None                  # anchor 불일치 — 이 patch 는 이 원본에 맞지 않는다
            start = index + 1
        if at_eof:
            # EOF 패치는 파일 끝에서 **역산**해 자리를 정한다. 앞에서부터 첫 문맥을 찾으면
            # 반복 문맥에서 엉뚱한 자리를 잡거나, 끝이 아니라며 쓸 수 있는 패치를 거절한다.
            end = len(lines) - 1 if lines and lines[-1] == "" else len(lines)
            found = end - len(before)
            if found < start or lines[found:end] != before:
                return None
            lines[found:end] = after
            cursor = found + len(after)
            continue
        if not before:
            if not anchors:
                return None                  # 붙는 자리를 잡아주는 것이 아무것도 없다
            lines[start:start] = after
            cursor = start + len(after)
            continue
        found = _find_block(lines, before, start)
        if found is None:
            return None                      # 문맥 불일치
        if not anchors and _find_block(lines, before, found + 1) is not None:
            return None                      # 자리를 잡아줄 것 없이 반복되는 문맥
        lines[found:found + len(before)] = after
        cursor = found + len(after)
    return "\n".join(lines)


def _read_text(root, rel_path):
    """root 상대 경로의 텍스트. 없거나 텍스트가 아니면 None."""
    try:
        with open(os.path.join(root, rel_path), encoding="utf-8") as handle:
            return handle.read()
    except (OSError, UnicodeDecodeError):
        return None


def _image_targets(changes):
    """되짚기 비용을 낼 경로 집합 — 판정 대상인 `.md` 와 그 이동의 원본뿐.

    Markdown 여부를 보기 전에 모든 update 파일을 되짚으면, 이 게이트가 판정하지도 않는 큰
    소스·데이터 파일 편집이 hook 예산을 통째로 쓴다.
    """
    targets = set()
    for change in changes or []:
        if not isinstance(change, dict):
            continue
        path = change.get("path") or ""
        if not path.endswith(".md"):
            continue
        targets.add(path)
        if change.get("op") == "move" and change.get("source_path"):
            # 목적지가 판정 대상이면 원본도 읽어야 한다 — 원본 확장자가 무엇이든.
            targets.add(change["source_path"])
    return targets


def _attach_post_image(root, changes):
    """판정에 필요한 **변경 전·후 전체 본문**을 싣는다.

    `pre_image` 는 그 경로에 지금 있는 내용(없으면 빈 문자열)이고, `post_image` 는 이번 변경이
    끝난 뒤의 내용이다. 판정은 둘을 비교해 **이번 변경이 늘린 부채**만 본다 — 새로 추가된 줄만
    보면 Markdown 구문을 지워 기존 한국어가 prose 로 드러나는 경우를 통째로 놓치고, 반대로
    전체만 보면 legacy 문서 하나가 이후 모든 편집을 막는다.

    Write / apply_patch Add File 은 content 가 곧 post-image 다(`full_content`). 부분 diff 는
    디스크 원본에 적용해 되짚고, 성립하지 않으면 조용히 넘기지 않고 `post_image_error` 를 남긴다.
    """
    targets = _image_targets(changes)
    for change in changes or []:
        if not isinstance(change, dict):
            continue
        rel_path = change.get("path") or ""
        if rel_path not in targets:
            continue
        change["pre_image"] = _read_text(root, rel_path) or ""
        if change.get("full_content") or change.get("op") != "update":
            continue
        source = _read_text(root, rel_path)
        if source is None:
            change["post_image_error"] = "unreadable"
            continue
        edits, body = change.get("edits"), change.get("patch_body")
        if not edits and not body:
            # hunk 없는 `Update File` + `Move to` — 내용은 그대로 목적지로 간다.
            change["post_image"] = source
            continue
        result = _apply_edits(source, edits) if edits else _apply_hunks(source, body)
        if result is None:
            change["post_image_error"] = "unreconstructible"
        else:
            change["post_image"] = result
    _forward_post_image_to_move_targets(changes)


def _forward_post_image_to_move_targets(changes):
    """이동 목적지에 원본에서 되짚은 결과를 넘긴다.

    `Update File` + `Move to` 는 source(update)와 destination(move) 두 변경으로 갈라진다.
    되짚기를 update 에서만 하고 끝내면, 판정은 목적지 경로·stem 으로 하는데 그 변경에는
    post-image 가 없어 문서 검사를 건너뛴다 — 이동과 수정을 한 번에 하면 검사가 통째로 빠진다.
    `pre_image` 는 넘기지 않는다: 부채의 기준선은 **목적지에 지금 있는 것**이라, 사이클 안으로
    새로 들어오는 문서는 그 내용 전체가 새 부채다.
    """
    by_path = {change["path"]: change for change in changes or []
               if isinstance(change, dict) and change.get("path")}
    for change in changes or []:
        if not isinstance(change, dict) or change.get("op") != "move":
            continue
        source = by_path.get(change.get("source_path") or "")
        if source is None or change.get("full_content"):
            continue
        for key in ("post_image", "post_image_error"):
            if key in source:
                change[key] = source[key]


def run_pre_implementation_gate(io, root, core_dir, raw_text):
    """pre-implementation-gate 오케스트레이터. io = io_claude | io_codex (런타임별 IO만 위임)."""
    hid = "pre-implementation-gate"
    raw = parse_input_fail_open(hid, raw_text)
    if raw is None:
        return 0
    if io.should_skip(raw):                      # codex: tool_name!=apply_patch 면 skip / claude: 항상 처리
        return 0
    profile = load_profile_fail_open(hid)
    if profile is None:
        return 0

    rel = make_rel(root)
    changes = io.extract_changes(raw, rel)       # ← 런타임별 (file_path vs apply_patch)
    _attach_post_image(root, changes)
    declared = io.read_declared_level(raw, root)  # ← 런타임별 ($host/logs)
    # 선언은 env(SAGE_CYCLE_STEM) 와 `<root>/.sage/cycle.json` 두 통로다. 기원을 함께 실어야
    # 표시·감사가 "어느 통로로 읽었는지" 를 갈라 말할 수 있다 — env 가 이기는데 화면이
    # "파일 선언" 이라고 적으면 확정적으로 거짓이 된다.
    cycle_stem, cycle_origin, cycle_error = cycle_state.resolve_stem(root)
    # 문서 언어 미러는 **선언 파일이 이긴 stem 일 때만** 실린다. env 가 다른 stem 으로 이기면
    # 파일의 언어는 다른 사이클의 값이고, 그걸 실으면 게이트가 남의 사이클 선언과 대조한다.
    declaration = cycle_state.read_declaration_record(root)
    document_language = (declaration.document_language
                         if declaration.stem and declaration.stem == cycle_stem else None)
    event = {"hook_id": hid, "hook_event_name": "PreToolUse", "runtime": io.RUNTIME,
             "session_id": raw.get("session_id", "") or "", "branch": resolve_branch(root, ""),
             "cycle_stem": cycle_stem, "cycle_stem_origin": cycle_origin,
             "cycle_document_language": document_language,
             "declared_max": declared, "changes": changes}
    snapshot = build_snapshot(profile, root, rel)
    feedback_state = _build_feedback_state(profile, root, changes)
    if feedback_state is not None:
        snapshot["feedback"] = feedback_state
    strategy_result = run_strategy(hid, profile, core_dir, changes, event, snapshot)

    sys.path.insert(0, core_dir)
    import pre_implementation_gate_core as core
    decision = _decide_pre_implementation_fail_closed(
        hid, core, event, profile, snapshot, strategy_result)
    decision = _record_acceptance_waiver_uses(hid, root, decision)
    # override 우회보다 먼저 기록한다 — 우회로 통과하든 게이트가 통과시키든 선언 사실은 남아야 한다.
    decision = _record_declared_cycle_stem(hid, root, decision, event.get("session_id") or "")
    # 선언 파일이 있는데 못 읽은 상태는 선언 부재로 degrade 하되 반드시 보이게 한다. 부재·손상이
    # 똑같이 조용하면 파일을 1바이트만 잘라도 완결 사이클 차단이 사라지고 아무도 모른다.
    if cycle_error and isinstance(decision, dict):
        decision["cycle_declaration_error"] = cycle_error
    if _maybe_override(hid, root, decision, changes):   # P1-5: 활성 override 면 BLOCK 우회(감사 기록)
        return 0
    return io.render_gate(decision, profile, root)   # ← 런타임별 채널/포맷/exit


def _runtime_block(hook_id, code, detail):
    """런타임 직접 BLOCK 한 줄 — code 와 다음 행동을 함께 낸다.

    게이트 결정이 아니라 게이트를 **세우지 못한** 실패라 `gate_text` 를 지나지 않는다.
    그래서 여기서 직접 붙인다. 이 자리를 비워 두면 가장 깨진 상태의 사용자가 가장 적은
    정보를 받는다 — code 도 없어 검색조차 못 한다.
    """
    # 두 번째 import 도 실패할 수 있다 — 그때 예외가 그대로 올라가면 아래 fallback 에
    # 도달하지 못하고, 가장 손상된 설치에서 사용자가 아무 다음 행동도 받지 못한다.
    # 최소 보장은 표가 없어도 살아남아야 한다.
    _recovery = None
    for _load in (lambda: __import__("importlib").import_module(".recovery", __package__),
                  lambda: __import__("recovery")):
        try:
            _recovery = _load()
            break
        except Exception:
            continue
    if _recovery is None:
        return f"⛔ BLOCK [{code}] [{hook_id}] {detail}\nNext: sage status"
    try:
        from . import messages as _messages
    except Exception:
        try:
            import messages as _messages
        except Exception:
            return f"⛔ BLOCK [{code}] [{hook_id}] {detail}\nNext: sage status"
    try:
        # 설치본은 flat import 로도 로드된다. 상대 import 하나만 시도하면 그 경로에서
        # 번역이 통째로 꺼지고, 사용자 화면에 catalog 키가 그대로 나간다.
        try:
            from . import i18n as _i18n
        except ImportError:
            import i18n as _i18n
        language = _messages.display_language()
        translate = lambda key: _i18n.frag(language, key) or key   # noqa: E731
    except Exception:
        translate = lambda key: key                                # noqa: E731
    lines = [f"⛔ BLOCK [{code}] [{hook_id}] {detail}"]
    try:
        lines.extend(_recovery.render(code, translate, host="<host>"))
    except Exception:
        lines.append("Next: sage status")
    return "\n".join(lines)


def run_generated_artifact_write_guard(raw_text, core_dir, direct_path=None):
    """Run the Python write guard; unexpected failures block instead of disabling protection."""
    try:
        if core_dir not in sys.path:
            sys.path.insert(0, core_dir)
        core = importlib.import_module("generated_artifact_write_guard_core")
        decision = core.decide_input(raw_text or "", direct_path=direct_path)
        # 표시 언어는 렌더 직전에만 닿는다. 판정(`code`·`exit_code`)은 이미 언어 중립으로
        # 확정돼 있고 여기서 바꾸는 것은 사람이 읽는 문장뿐이다.
        code = decision.get("code")
        if code:
            # 이 모듈은 설치본에서 flat import 로도 로드된다. 상대 import 하나가 그 경로를
            # 끊으므로 두 형태를 모두 받는다 — `messages.py` 가 i18n 을 받는 방식과 같다.
            try:
                from . import messages as _messages
            except ImportError:
                import messages as _messages
            message = core.block_message(decision.get("path") or "",
                                         _messages.display_language())
        else:
            message = decision.get("message") or ""
        if message:
            print(message, file=sys.stderr)
        return int(decision.get("exit_code", 2))
    except Exception as exc:
        print(_runtime_block("generated-artifact-write-guard", "runtime.core_failure",
                             f"{type(exc).__name__}: {exc}"), file=sys.stderr)
        return 2


def run_capture_declared_risk(io, root, core_dir, raw_text):
    """capture-declared-risk 오케스트레이터(UserPromptSubmit). risk 포착은 비차단, parse 실패 silent.

    SessionStart 누락·지연에 대비해 같은 입력의 session_id 로 06 baseline 을 먼저 write-once 확보한다.
    단 first-opportunity claim I/O 실패는 늦은 baseline 방지를 증명할 수 없어 exit 2로 작업 시작을 막는다.
    cleanup(만료 state 삭제)·state write 는 런타임 무관 공유. 포착 메시지 렌더만 io 위임.
    """
    hid = "capture-declared-risk"
    snapshot_rc = _ensure_session_06_snapshot(io, root, core_dir, raw_text)
    if snapshot_rc:
        return snapshot_rc
    raw = parse_input_fail_open(hid, raw_text, surface=False)
    if raw is None:
        return 0
    log_dir = os.path.join(root, io.HOST_DIR, "logs")
    os.makedirs(log_dir, exist_ok=True)

    sys.path.insert(0, core_dir)
    import capture_declared_risk_core as core
    event = {"hook_id": hid, "hook_event_name": "UserPromptSubmit", "runtime": io.RUNTIME,
             "session_id": raw.get("session_id", "") or "", "prompt": raw.get("prompt", "") or "",
             "now_utc": os.environ.get("SAGE_NOW_UTC") or time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
    decision = core.decide(event)

    c = decision["cleanup"]
    now = time.time()
    for f in glob.glob(os.path.join(log_dir, c["pattern"])):
        try:
            if now - os.path.getmtime(f) > c["older_than_seconds"]:
                os.remove(f)
        except Exception:
            pass

    if decision["action"] == "capture":
        path = os.path.join(log_dir, decision["state_file"])
        try:
            with open(path, "w", encoding="utf-8") as fh:
                json.dump(decision["state"], fh, ensure_ascii=False)
            io.render_declared_capture(decision["level"])
        except Exception:
            pass
    elif decision.get("message_key") == "risk_declaration_ambiguous":
        # 기각을 알리는 것뿐이므로 상태 파일은 건드리지 않는다. UserPromptSubmit 은 exit 0 stdout 이
        # 컨텍스트로 올라가는 이벤트라 양 런타임에서 실제로 보인다.
        try:
            io.render_declared_ambiguous()
        except Exception:
            pass
    elif decision["action"] == "clear":
        # 잘못 잡힌 선언의 유일한 탈출구다. 파일이 없어도 성공으로 안내한다 — 사용자가 원한
        # 최종 상태(선언 없음)는 어느 쪽이든 같다.
        path = os.path.join(log_dir, decision["state_file"])
        try:
            existed = os.path.exists(path)
            if existed:
                os.remove(path)
            io.render_declared_clear(existed)
        except Exception as exc:
            print(f"[{hid}] 선언 해제 실패 — 수동으로 {path} 를 지우세요: "
                  f"{type(exc).__name__}: {exc}", file=sys.stderr)
    return decision["exit_code"]


def run_post_tool_logger(io, root, core_dir, raw_text):
    """post-tool-logger 오케스트레이터(PostToolUse). 변경 분류 JSONL append. 게이트 아님(parse silent)."""
    hid = "post-tool-logger"
    raw = parse_input_fail_open(hid, raw_text, surface=False)
    if raw is None:
        return 0
    if io.should_skip(raw):
        return 0
    profile = load_profile_fail_open(hid)
    if profile is None:                          # profile 외부주입 필수 — 없으면 noop
        return 0
    log_dir = os.path.join(root, io.HOST_DIR, "logs")
    os.makedirs(log_dir, exist_ok=True)

    rel = make_rel(root)
    event = {"hook_id": hid, "hook_event_name": "PostToolUse", "runtime": io.RUNTIME,
             "session_id": raw.get("session_id", "") or "", "tool": io.logger_tool_name(raw),
             "branch": resolve_branch(root, "unknown"),
             "now_utc": os.environ.get("SAGE_NOW_UTC") or time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
             "changes": io.extract_logged_changes(raw, rel)}

    sys.path.insert(0, core_dir)
    import post_tool_logger_core as core
    decision = core.decide(event, profile)
    if decision["action"] == "log":
        out = os.path.join(log_dir, decision["log_file"])
        with open(out, "a", encoding="utf-8") as fh:
            for e in decision["log_entries"]:
                fh.write(json.dumps(e, ensure_ascii=False) + "\n")
    return decision["exit_code"]


def build_checklist_snapshot(core, event, profile, root):
    """pre-phase4 fs_adapter: core.plan_reads 가 요구한 glob 을 읽어 snapshot 구성(런타임 무관)."""
    reads = core.plan_reads(event, profile)
    glob_results, files = {}, {}
    root_real = os.path.realpath(root)
    for g in reads["globs"]:
        absolute_matches = []
        for path in glob.glob(os.path.join(root, g), recursive=True):
            path_real = os.path.realpath(path)
            try:
                contained = os.path.commonpath((root_real, path_real)) == root_real
            except ValueError:
                contained = False
            if not contained:
                raise ProfileLoadError(
                    f"checklist_scan_targets glob이 root 밖 symlink를 매치함: {g}")
            absolute_matches.append(path)
        matches = sorted(os.path.relpath(p, root) for p in absolute_matches)
        glob_results[g] = matches
        for rp in matches:
            try:
                with open(os.path.join(root, rp), encoding="utf-8") as fh:
                    files[rp] = fh.read()
            except Exception:
                files[rp] = None
    return {"glob_results": glob_results, "files": files}


def run_pre_phase4_checklist_gate(io, root, core_dir, raw_text):
    """pre-phase4-checklist-gate 오케스트레이터(PreToolUse). 03→04 전환 시 체크리스트 완료 강제."""
    hid = "pre-phase4-checklist-gate"
    # 게이트 hook 이므로 malformed 입력을 surface(5-1): pre-implementation-gate 와 일관.
    # silent fail-open 은 게이트가 조용히 열린 걸 숨긴다(비게이트 logger 와 달리). fail-closed 전환은
    # transient 입력 전면차단 위험이라 별도 설계결정(보류) — 여기선 비silent 화까지만.
    raw = parse_input_fail_open(hid, raw_text, surface=True)
    if raw is None:
        return 0
    if io.should_skip(raw):
        return 0
    try:
        profile = load_profile_fail_closed(hid, root)
        if profile is None:
            return 0

        rel = make_rel(root)
        event = {"hook_id": hid, "hook_event_name": "PreToolUse", "runtime": io.RUNTIME,
                 "session_id": raw.get("session_id", "") or "", "changes": io.extract_phase4_changes(raw, rel)}
        sys.path.insert(0, core_dir)
        import pre_phase4_checklist_gate_core as core
        snapshot = build_checklist_snapshot(core, event, profile, root)
        decision = core.decide(event, profile, snapshot)
    except Exception as exc:
        # profile/snapshot 계약 오류는 저작자가 고칠 수 있다. core 내부 실패와 같은 code 로
        # 묶으면 SAGE 버그처럼 보여 고칠 곳을 못 찾는다.
        print(_runtime_block(hid, "runtime.profile_contract",
                             f"{type(exc).__name__}: {exc}"), file=sys.stderr)
        return 2

    if _maybe_override(hid, root, decision, event["changes"]):   # P1-5: 활성 override 면 BLOCK 우회(감사 기록)
        return 0
    return io.render_phase4(decision)


def _project_manifest_entry(root, hook_id):
    """Return None for genuine version skew; raise for a present but damaged registry."""
    path = os.path.join(root, "docs", "sage_harness", ".manifest.json")
    if not os.path.exists(path):
        return None
    try:
        with open(path, encoding="utf-8") as stream:
            manifest = json.load(stream)
    except Exception as exc:
        raise ProjectHookError(
            f"manifest unreadable: {type(exc).__name__}: {exc}") from exc
    if not isinstance(manifest, dict) or not isinstance(manifest.get("assets"), dict):
        raise ProjectHookError("manifest root/assets must be objects")
    key = f"hooks/{hook_id}"
    if key not in manifest["assets"]:
        return None
    entry = manifest["assets"][key]
    if not isinstance(entry, dict):
        raise ProjectHookError("manifest project hook entry must be an object")
    if entry.get("origin") != "project" or entry.get("form") != "core_adapter":
        raise ProjectHookError("manifest project hook entry origin/form is invalid")
    version = entry.get("adapter_contract_version")
    if not isinstance(version, str) or not version:
        raise ProjectHookError("manifest project hook contract version is missing")
    return entry


def _load_project_core(root, hook_id, expected_version):
    if re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", hook_id) is None:
        raise ProjectHookError("project hook id is not strict lowercase kebab-case")
    # 소비 프로젝트의 SAGE 트리. hook 트리는 엔진(`sage.asset_paths`)을 import 하지 않으므로
    # 이름을 여기서 안다 — 대신 **구 레이아웃도 읽는다.** 이행 전이거나 이행이 중간에 멈춘
    # 프로젝트에서 프로젝트 hook 이 조용히 사라지면, 그것은 게이트가 사라지는 것과 같다.
    project_hooks = os.path.join(root, "sage_harness", "hooks")
    if not os.path.isdir(project_hooks):
        legacy = os.path.join(root, "scripts", "sage_harness", "hooks")
        if os.path.isdir(legacy):
            project_hooks = legacy
    hooks_real = os.path.realpath(project_hooks)
    path = os.path.join(project_hooks, f"{hook_id.replace('-', '_')}_core.py")
    if not os.path.isfile(path) or os.path.islink(path):
        raise ProjectHookError(f"registered project canonical core missing: {path}")
    path_real = os.path.realpath(path)
    try:
        contained = os.path.commonpath((hooks_real, path_real)) == hooks_real
    except ValueError:
        contained = False
    if not contained:
        raise ProjectHookError("project canonical core escapes the hook root")
    try:
        spec = importlib.util.spec_from_file_location(
            f"sage_project_hook_{hook_id.replace('-', '_')}", path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
    except Exception as exc:
        raise ProjectHookError(
            f"project core import failed: {type(exc).__name__}: {exc}") from exc
    actual_version = getattr(module, "CONTRACT_VERSION", None)
    if not isinstance(actual_version, str) or not actual_version:
        raise ProjectHookError("project core CONTRACT_VERSION is missing")
    if actual_version != expected_version:
        raise ProjectHookError(
            f"project core contract drift: manifest={expected_version}, core={actual_version}")
    if not callable(getattr(module, "decide", None)):
        raise ProjectHookError("project core decide(event, profile, snapshot) is missing")
    return module


def _project_snapshot(core, event, profile, root):
    planner = getattr(core, "plan_reads", None)
    if planner is None:
        # plan_reads 는 선택이지만 snapshot 형태는 선택이 아니다. 여기서 {} 를 돌려주면
        # core 의 snapshot["files"] 가 KeyError 로 죽고 catch-all 이 그것을 내부 버그로 안내한다.
        return {"glob_results": {}, "files": {}}
    reads = planner(event, profile)
    if not isinstance(reads, dict) or set(reads) != {"globs"}:
        raise ProjectHookError("project plan_reads must return {'globs': [...]} only")
    globs = reads["globs"]
    if not isinstance(globs, list):
        raise ProjectHookError("project plan_reads.globs must be a list")
    root_real = os.path.realpath(root)
    results = {}
    files = {}
    for pattern in globs:
        issue = checklist_contract.unsafe_glob(pattern)
        if issue:
            raise ProjectHookError(f"unsafe project plan_reads glob {pattern!r}: {_overlay_say(root, issue)}")
        matches = []
        for path in glob.glob(os.path.join(root, pattern), recursive=True):
            path_real = os.path.realpath(path)
            try:
                contained = os.path.commonpath((root_real, path_real)) == root_real
            except ValueError:
                contained = False
            if not contained:
                raise ProjectHookError(
                    f"project plan_reads matched path outside project root: {path}")
            if os.path.islink(path):
                raise ProjectHookError(f"project plan_reads matched symlink: {path}")
            if os.path.isdir(path):
                continue
            if not os.path.isfile(path):
                raise ProjectHookError(
                    f"project plan_reads matched unsupported non-regular path: {path}")
            relative = os.path.relpath(path, root)
            matches.append(relative)
            try:
                with open(path, encoding="utf-8") as stream:
                    files[relative] = stream.read()
            except OSError as exc:
                raise ProjectHookError(f"project plan_reads read failed: {relative}: {exc}") from exc
        results[pattern] = sorted(matches)
    return {"glob_results": results, "files": files}


def _project_decision(value):
    if not isinstance(value, dict) or set(value) != {"status", "exit_code", "message"}:
        raise ProjectHookError("project decision must contain exactly status/exit_code/message")
    status = value.get("status")
    exit_code = value.get("exit_code")
    message = value.get("message")
    if status not in ("block", "ok", "warn", "skip"):
        raise ProjectHookError("project decision status is invalid")
    expected = 2 if status == "block" else 0
    if type(exit_code) is not int or exit_code != expected:
        raise ProjectHookError(
            f"project decision status/exit_code mismatch: {status}/{exit_code!r}")
    if not isinstance(message, str):
        raise ProjectHookError("project decision message must be a string")
    return value


def run_project_hook(io, root, core_dir, hook_id, raw_text):
    """Dispatch one registered project hook; preserve true unknown-id version skew."""
    try:
        entry = _project_manifest_entry(root, hook_id)
        if entry is None:
            return 0
        raw = json.loads(raw_text or "{}")
        if not isinstance(raw, dict):
            raise ProjectHookError("hook input must be a JSON object")
        if io.should_skip(raw):
            return 0
        profile = load_profile_fail_closed(hook_id, root)
        if profile is None:
            raise ProjectHookError("registered project hook compiled profile is missing")
        rel = make_rel(root)
        event = {"hook_id": hook_id, "hook_event_name": "PreToolUse", "runtime": io.RUNTIME,
                 "session_id": raw.get("session_id", "") or "",
                 "changes": io.extract_phase4_changes(raw, rel)}
        core = _load_project_core(root, hook_id, entry["adapter_contract_version"])
        snapshot = _project_snapshot(core, event, profile, root)
        decision = _project_decision(core.decide(event, profile, snapshot))
        if decision["message"]:
            print(decision["message"], file=sys.stderr)
        return decision["exit_code"]
    except ProfileLoadError as exc:
        # 저작자가 고칠 수 있는 profile 계약 오류다. internal dispatch failure 로 묶으면
        # SAGE 내부 버그처럼 보여 고칠 곳을 못 찾는다.
        print(_runtime_block(hook_id, "runtime.profile_contract",
                             f"profile contract failure: {exc}"), file=sys.stderr)
        return 2
    except ProjectHookError as exc:
        print(_runtime_block(hook_id, "runtime.project_hook_contract",
                             f"contract failure: {exc}"), file=sys.stderr)
        return 2
    except Exception as exc:
        print(_runtime_block(hook_id, "runtime.core_failure",
                             f"internal dispatch failure: {type(exc).__name__}: {exc}"),
              file=sys.stderr)
        return 2


def code_types_of(profile):
    """코드 타입 집합 — plan_gate_code_types 우선, 없으면 file_type_map 의 type 전체(도메인 하드코딩 금지)."""
    comp = profile.get("compliance", {}) or {}
    ct = set(comp.get("plan_gate_code_types") or [])
    if not ct:
        ct = {m.get("type") for m in (profile.get("file_type_map") or []) if m.get("type")}
    return ct


def _epoch_of_iso(s):
    try:
        return calendar.timegm(time.strptime(s, "%Y-%m-%dT%H:%M:%SZ"))
    except Exception:
        return None


def knowledge_capture_result(profile, entries):
    """knowledge_capture 정책 결과(양 런타임 공유 — F7). policies 는 호출 전 sys.path 에 있어야 함."""
    import knowledge_capture
    ct = code_types_of(profile)
    has_code = any(e.get("type") in ct for e in entries)
    vault = (profile.get("knowledge_capture", {}) or {}).get("vault_path", "") or ""
    wiki_log = os.path.join(vault, "wiki", "log.md") if vault else ""
    wiki_mtime = os.path.getmtime(wiki_log) if (wiki_log and os.path.exists(wiki_log)) else None
    code_ts = [t for t in (_epoch_of_iso(e.get("ts", "")) for e in entries if e.get("type") in ct) if t]
    earliest = min(code_ts) if code_ts else None
    return knowledge_capture.check(vault, has_code, wiki_mtime, earliest)


_LOOP_RUN_RE = re.compile(r"(?im)^\s*Loop-Run:\s*(\S+)\s*$")   # pre_implementation_gate_core.py 와 동일 어휘


def _pdca_phase_glob(profile, phase_id):
    for ph in ((profile.get("pdca") or {}).get("phases") or []):
        # 비-dict phase 항목(예: 들여쓰기 실수로 bare 문자열 "00")은 건너뛴다 — ph.get() 가 AttributeError 로
        # Stop 게이트를 조용히 죽여 enforce 를 무력화한다(validate 가 phases items 를 강제하지 않아 통과).
        if isinstance(ph, dict) and ph.get("id") == phase_id:
            glob = ph.get("glob")
            return glob if isinstance(glob, str) else ""
    return ""


def _safe_glob(root, pglob):
    """root 밖/절대경로/`..` 탈출 glob 은 거부(build_snapshot 과 동일 방어 — 독립성) 후 실제 매치 경로.
    `glob.glob(recursive=True)` 를 쓴다 — `fnmatch` 는 `foo/**/*.md` 가 `foo/x.md`(0 단계 디렉토리)에
    안 걸린다(codex 구현리뷰 P0: 표준 06 glob `plan_docs/06-report/**/*.md` 가 06 문서를 직속 자식으로
    두면 fnmatch 로는 영원히 매치 안 돼 게이트가 항상 조용히 skip). glob.glob 은 `**` 를 문서화된 대로
    "0개 이상의 디렉토리"로 올바르게 해석한다."""
    if not pglob or os.path.isabs(pglob) or ".." in pglob.split("/"):
        return []
    return glob.glob(os.path.join(root, pglob), recursive=True)


def _canon_relkey(s):
    """root-상대 경로를 대조용 정규 키로. 세션 로그의 `entries[].file`(logger 가 그대로 저장)과
    glob 결과가 표기만 달라 조용히 공집합이 되는 것을 막는다(codex 구현리뷰 2R P1): `\\`→`/`,
    선행 `./` 제거, 중복 슬래시 정리(posixpath.normpath). 양쪽에 동일 적용해야 의미가 있다.
    심링크로 06 을 두고 target 경로로 편집하는 경우는 표기정규화로도 안 잡혀 공집합→skip(fail-open,
    잘못된 block 은 아님) — v1 은 심링크 06 을 지원 대상으로 명시하지 않는다."""
    s = (s or "").replace("\\", "/")
    return posixpath.normpath(s) if s else ""


def _glob_relmap(root, pglob):
    """{정규 키: abspath} — glob 매치 파일을 세션 로그 키(정규화)로 인덱싱."""
    return {_canon_relkey(os.path.relpath(p, root)): p for p in _safe_glob(root, pglob)}


def _glob_relpaths(root, pglob):
    """세션 로그의 상대경로(`entries[].file`)와 직접 대조하기 위한 root-상대 정규 키 집합."""
    return set(_glob_relmap(root, pglob).keys())


_H2_PLUS_RE = re.compile(r"#{2,}(?:\s|$)")   # H2 이상 ATX 헤딩 = 본문 섹션 시작(H1 제목은 종료로 보지 않음)


def _header_loop_run_ids(content):
    """06 문서 **최상단 메타데이터 블록**의 Loop-Run run_id 집합. 첫 H2 이상 헤딩 전까지만 본다.

    문서 전체를 finditer 하면(codex W1 R2 P2) 본문 섹션의 예시 코드블록에 든 `Loop-Run: rl-example` 까지
    매치돼 실제 run 과 상충으로 잡혀 false ambiguous BLOCK 이 난다. Loop-Run/Source-05 는 템플릿상 H1 제목
    바로 아래(첫 H2 전)에 오는 고정 헤더이므로 그 구간만 파싱한다. 헤딩 판정은 선행 공백/탭을 제거하고
    H2 이상(`##`+)만 종료로 본다(codex W1 R2 재검 P2: `  ## `·`##\t` 변형이 새어 본문 예시를 재파싱). 선두
    BOM 은 제거해 BOM+Loop-Run 시작 문서에서 마커를 놓치지 않는다. H1(`# `)은 제목이라 종료로 보지 않는다."""
    ids = set()
    for line in content.lstrip("\ufeff").splitlines():
        if _H2_PLUS_RE.match(line.lstrip()):
            break
        m = _LOOP_RUN_RE.match(line)
        if m:
            ids.add(m.group(1))
    return ids


def _session_06_run_ids(root, profile, session_files):
    """이번 세션에 쓰인 06 문서별로 자기선언한 Loop-Run run_id 집합. {정규 키: {run_ids}}.

    06 이 자기 사이클을 명시 선언(Loop-Run:)하게 하고 게이트는 06 만 읽는다. 05 를 stem 으로 추측해
    디스크에서 찾지 않는다(codex W1 P1 2건): 전역 stem 스캔은 (1) 다중 06 을 하나의 run_id 집합으로 합쳐
    결속 불가 06 을 이미 확인된 06 에 가리고, (2) 과거/타 디렉토리의 동명 05 를 이번 06 에 오결속한다.
    run_id 는 sage-review 가 05 에 기록하고 06 작성 시 06 으로 복사된다(쓰기 시점에 06←05 review_loop
    게이트가 그 run 결속을 이미 검증). 06 이 이번 세션 로그 ∩ glob 실존파일일 때만 인정한다. 한 06 에
    마커가 여럿이어도 finditer 로 전부 보아 상충을 포착한다 — 0개/2개↑ 판정은 호출부(_reduce_06_bindings)."""
    out = {}
    for key, path in _glob_relmap(root, _pdca_phase_glob(profile, "06")).items():
        if key not in session_files:
            continue
        try:
            with open(path, encoding="utf-8", errors="ignore") as f:
                content = f.read()
        except Exception:
            content = ""
        out[key] = _header_loop_run_ids(content)
    return out


def _reduce_06_bindings(per06_ids, audit_summary):
    """이번 세션 06 들의 자기선언 결속을 retro_gate.check 용 판정 (run_id, binding, checked, missing)으로 축약.

    **06 마다** 결속을 본다(codex W1 P1: 집계 run_id 는 결속 불가 06 을 확인된 다른 06 에 가리고, 정상
    다중 사이클을 모호로 오판한다). 한 06 이라도 마커가 정확히 1개가 아니면 결속 불가다 — 2개↑=상충
    (ambiguous), 0개=미선언(no_candidate). 전부 유일 결속이면 각 run 을 감사에서 확인한다: 대표 run_id 와
    checked 는 게이트 표시용(정렬 첫 미확인, 없으면 첫 run).

    missing 은 **유일 결속(마커 1개) 06 의 미확인 run 전체**다 — **다른 06 의 결속 불가와 무관하게** 모은다
    (codex W1 R2 재검 P1: 결속 불가 06 을 먼저 만나 즉시 missing=[] 를 반환하면, 같은 세션에서 유효 선언·미확인
    된 run 의 doctor 가시성이 사라져 R2 #2 가 다시 깨진다). 게이트 판정만 worst-case binding failure 로 축약한다.
    어댑터가 missing 전부를 record_missing 해야 다중 미확인이 첫 run 하나로 잘리지 않는다(codex W1 R2 P1)."""
    def _satisfied(rid):
        # 게이트 통과 = 실제 --check 통과(checked) 또는 --no-vault 로 노트 생략(state=skipped). 그 외 미완료.
        e = audit_summary.get(rid) or {}
        return bool(e.get("checked")) or e.get("state") == "skipped"

    items = sorted(per06_ids.items())
    resolved = [next(iter(ids)) for _, ids in items if len(ids) == 1]
    missing = sorted({r for r in resolved if not _satisfied(r)})
    if any(len(ids) > 1 for _, ids in items):
        return None, "ambiguous", False, missing
    if any(len(ids) == 0 for _, ids in items):
        return None, "no_candidate", False, missing
    if missing:
        return missing[0], "resolved", False, missing
    return resolved[0], "resolved", True, missing


def _stop_hook_active(raw):
    """Stop 입력의 `stop_hook_active` 를 안전하게 판정. 플랫폼은 JSON boolean 을 보내지만, 어댑터
    직렬화/스키마 변형에 대비해 bool True 와 문자열 "true"(대소문자 무관)만 active 로 본다(codex
    구현리뷰 P1: `bool("false")` 는 True 라 문자열 "false" 를 재시도로 오인해 첫 block 이 사라진다).

    방향성: 재시도(무한루프 방지)는 플랫폼이 `true` 를 보낼 때만 성립하므로 `true`/`"true"` 를 놓치지
    않으면 루프-안전이 유지되고, 그 외(`false`/`"false"`/누락/malformed)를 not-active 로 봐 첫 시도의
    teeth 를 보존한다."""
    v = raw.get("stop_hook_active")
    if v is True:
        return True
    return isinstance(v, str) and v.strip().lower() == "true"


def _session_log_entries(log_dir, session_id):
    """이번 세션(session_id)의 로그 엔트리를 로그 디렉토리의 **모든** session-*.jsonl 에서 모은다.

    로거는 UTC 날짜(now_utc[:10] = gmtime)로 session-YYYY-MM-DD.jsonl 을 쓰는데 Stop 은 로컬 날짜
    (localtime)로 파일 하나만 열어(codex 7R P0), KST 등 양수 오프셋의 자정 경계·UTC 자정을 넘는 세션에서
    파일명이 어긋나 게이트가 조용히 무동작한다. 세션 감지를 날짜 파일 하나가 아니라 session_id 로 전
    파일에서 걸러야 타임존/날짜와 무관하게 이번 세션을 놓치지 않는다(리포트 본문 집계 범위는 그대로 둔다)."""
    if not session_id:
        return []
    out = []
    for fp in glob.glob(os.path.join(log_dir, "session-*.jsonl")):
        try:
            with open(fp, encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        e = json.loads(line)
                    except Exception:
                        continue
                    # object 가 아닌 유효 JSON 라인(숫자·배열·문자열: 손상/동시쓰기/손편집)은
                    # .get 이 터져 outer except 로 새면 이 파일의 뒤 라인이 통째로 유실된다.
                    # 그 라인만 건너뛰어 이후 이번-세션 엔트리를 놓치지 않는다.
                    if isinstance(e, dict) and e.get("session") == session_id:
                        out.append(e)
        except Exception:
            continue
    return out


def _retro_gate_config(profile, root):
    """(mode, notes_enabled) — retro 게이트 활성 판정 요소. retro_gate_result·SessionStart·early-return 공유.

    mode ∈ off|advisory|enforce. notes_enabled 는 retro CLI 와 동일 조건: retro_note is True + vault 가
    usable 디렉토리(isdir, 상대경로는 project root 기준). 노트가 안 써지면 --check 대상이 없어 게이트가
    통과 불가능한 걸 강제하게 되므로, 이 조합이 참일 때만 게이트가 실제로 동작한다."""
    pdca_retro = (profile.get("pdca") or {}).get("retro") or {}
    mode = pdca_retro.get("report_gate_enforce") or "off"
    kc = profile.get("knowledge_capture")
    kc = kc if isinstance(kc, dict) else {}
    vp = kc.get("vault_path")
    vault = vp.strip() if isinstance(vp, str) else ""
    vault_abs = vault if os.path.isabs(vault) else os.path.join(root, vault)
    notes_enabled = (kc.get("retro_note") is True) and bool(vault) and os.path.isdir(vault_abs)
    return mode, notes_enabled


def _retro_gate_active(profile, root):
    """게이트가 실제로 동작하는가(mode advisory/enforce + notes_enabled). 비활성 프로젝트에서 매 Start/Stop
    마다 06 전체를 해싱하는 낭비를 피하려고 스냅샷 IO 를 이 조건 뒤에 둔다."""
    mode, notes_enabled = _retro_gate_config(profile, root)
    return mode in ("advisory", "enforce") and notes_enabled


_DEPTH_REVIEW_RE = re.compile(r"\s*Depth-Self-Review:\s*(\S+)", re.I)   # 06 의 self-review 자기선언
# 제로폭/BOM 포맷 문자(유니코드 Cf) — `\s` 는 이들을 매치하지 못해, 라인 앞에 끼면 정규식이 선언을 놓친다
# (BOM'd `Risk Level: L3` 이 무시돼 낮은 tier 로 under-read, codex R7 P1). 스캔 전 전역 제거로 봉쇄한다.
_ZERO_WIDTH_STRIP = {c: None for c in (0xFEFF, 0x200B, 0x200C, 0x200D, 0x2060)}


def _header_fields_06(content):
    """06 최상단 메타블록에서 (Risk Level tier, depth self-review performed 여부)를 뽑는다.

    첫 H2 이상 헤딩 전까지만 본다 — _header_loop_run_ids 와 동일 규약(선두 BOM 제거). 추가로 헤더
    구간의 펜스 코드블록(``` / ~~~) 안은 건너뛴다: 헤더에 든 예시 블록의 `Depth-Self-Review:
    performed`·`Risk Level:` 예시 라인이 실제 선언으로 오인돼 게이트가 조용히 OK 되는 걸 막는다.
    tier ∈ {"L1","L2","L3"} 또는 None(미기재). declared 는 'performed' 선언이 있고 'skipped' 선언은
    없을 때만 True — performed/skipped 상충이나 skipped 우회는 미선언(fail-closed)으로 본다."""
    tier = _doc_risk_tier(content)   # tier 해석은 공용 파서 단일 정본
    performed = False
    skipped = False
    in_fence = None   # None 또는 연 펜스 마커("```"/"~~~") — 같은 종류로만 닫는다(혼합 펜스 우회 방지)
    for line in content.translate(_ZERO_WIDTH_STRIP).splitlines():
        stripped = line.lstrip()
        fence = "```" if stripped.startswith("```") else ("~~~" if stripped.startswith("~~~") else None)
        if in_fence is not None:
            if fence == in_fence:   # 다른 종류(``` 안의 ~~~)는 닫지 못한다
                in_fence = None
            continue
        if fence is not None:
            in_fence = fence
            continue
        if _H2_PLUS_RE.match(stripped):
            break
        m = _DEPTH_REVIEW_RE.match(line)
        if m:
            v = m.group(1).strip().casefold()
            if v == "performed":
                performed = True
            elif v == "skipped":
                skipped = True
    return tier, (performed and not skipped)


_TIER_RANK = {"L0": 0, "L1": 1, "L2": 2, "L3": 3}   # _RISK_LEVEL_RE 가 L0 도 매치 — 누락 시 KeyError


def _doc_risk_tier(content):
    """문서 **헤더 메타블록**(첫 H2 이상 헤딩 전)의 Risk Level 최대 tier(L0<L1<L2<L3). 없으면 None.

    무엇이 선언인지는 `risk_declaration` 이 소유한다 — 게이트와 각자 정규식을 들고 있던 탓에 같은
    문서에 다른 tier 가 나왔다. 본문 산문('escalation rejected — Risk Level: L3' 등)을 tier 로 오독해
    실제 L1 사이클을 하드 BLOCK 하던 false-positive 봉쇄는 그 모듈의 헤더 한정 규칙이 이어받는다.

    **여기서 최대를 취하는 것이 fail-closed 다.** 선언 하나를 놓치면 tier 가 낮게 읽혀 06 검증이
    얕아진다(BOM 이 낀 `Risk Level: L3` 를 놓쳐 under-read 되던 결함). 사이클 결속 쪽은 반대로
    "정확히 1개"를 요구하는데, 그 방향 차이 때문에 선택은 파서가 아니라 소비자가 한다.
    """
    found, error = risk_declaration.scan(content)
    if error is not None:
        # 문법을 벗어났지만 선언을 의도한 게 분명한 줄(`Risk Level [custom]: L3`)을 건너뛰고 옆의
        # 정상 선언을 채택하면, 작성자가 L3 로 쓰려던 사이클이 동거하는 L1 로 확정된다. 그러면
        # `_authoritative_cycle_tier` 가 None 대신 L1 을 돌려주고 06 심층 검증이 통째로 꺼진다 —
        # 조용한 하향이다. 게이트의 acceptance risk 추정(`unknown`)과 같은 방향으로 맞춘다.
        return None
    tiers = [tier for _line, tier in found]
    if not tiers:
        return None
    return max(tiers, key=_TIER_RANK.__getitem__)


def _authoritative_cycle_tier(root, profile, stem, exclude_keys=None):
    """cycle stem 에 결속된 00 base plan 의 authoritative Risk tier(다중 일치 시 최대). 결속 불가·부재 None.

    tier 의 정본은 00 이다 — 06 의 자기선언(Risk Level·Cycle-Stem 라인)은 전부 신뢰하지 않는다(위조로
    enforce 우회 가능, codex R1/R3/R5 P1). 00 문서는 **경로 basename(path_stem)** 이 이 stem 과 일치하는
    것만 본다: 자기선언이 아니라 파일이 실제 놓인 경로라 위조 불가. 다중 일치(동일 basename)는 보수적으로
    최대 tier. 부재/불일치는 None → _reduce_06_depth 가 보수적으로 L2 로 취급(fail-closed).

    **일치하는 00 중 하나라도 읽기 실패·Risk Level 부재/모호면 None 을 반환한다**(codex R6 P1): 그런
    00 에 숨은 상위 tier 가 있을 수 있는데, 그걸 건너뛰고 함께 놓인 낮은 tier 00 로 확정하면 실제 L3
    사이클이 malformed L3 + 동거 L1 조합으로 게이트를 우회한다. 확신 없으면 낮은 tier 를 신뢰하지 않고
    L2(applies)로 떨어뜨린다.

    exclude_keys = tier 정본으로 인정하지 않을 경로(정규 키). 06 phase 문서를 넘겨, 00/06 glob 이 겹치는
    misconfig 에서 06 이 자기 자신의 authoritative 00 이 돼(자기선언 Risk Level 이 정본으로 부활) 우회되는
    것을 막는다(자체 clean-context 리뷰 P2)."""
    if not stem:
        return None
    exclude_keys = exclude_keys or frozenset()
    best = None
    for key, path in _glob_relmap(root, _pdca_phase_glob(profile, "00")).items():
        if key in exclude_keys or cycle_binding.path_stem(key) != stem:
            continue
        try:
            with open(path, encoding="utf-8", errors="ignore") as f:
                content = f.read()
        except Exception:
            return None   # 결속 대상 00 을 못 읽음 → 숨은 상위 tier 가능 → fail-closed
        t = _doc_risk_tier(content)
        if t is None:
            return None   # 결속 대상 00 에 Risk Level 없음/모호 → fail-closed(낮은 동거 tier 로 확정 금지)
        if best is None or _TIER_RANK[t] > _TIER_RANK[best]:
            best = t
    return best


def _session_06_depth(root, profile, session_files):
    """이번 세션에 쓰인 06 문서별 (authoritative tier, declared). {정규 키: (tier, bool)}.

    tier 는 06 의 자기선언이 아니라 **결속된 00 의 authoritative Risk Level** 이다 — 06 이 낮은 tier 를
    자기선언(위조/stale)해도 enforcement 를 못 끄게 한다(codex R1/R3/R5 P1). declared 는 06 자신의
    Depth-Self-Review 선언(위조해도 게이트를 *통과*시키는 방향이라 06 자기선언으로 충분 — 낮추는 게 아니라
    스스로 완료를 주장하는 축). 06 glob·정규 키·세션 교집합 규약은 _session_06_run_ids 와 동일.

    결속 identity 는 06 의 **경로 basename**(path_stem) 이다 — Risk Level·Cycle-Stem 같은 자기선언 라인은
    모두 위조 가능하지만 파일이 실제 놓인 경로는 세션 로그가 기록한 사실이라 위조 불가. 06 이 무관 저-tier
    사이클을 가리키려면 파일명을 그 사이클로 바꿔야 하는데, 그러면 실제 사이클의 06 이 사라져
    retro/acceptance/review 게이트가 잡는다(codex R5: 자기선언 Cycle-Stem 우회 봉쇄)."""
    out = {}
    six = _glob_relmap(root, _pdca_phase_glob(profile, "06"))
    six_keys = frozenset(six)   # 00 tier 조회에서 제외 — 06 은 자기 자신의 authoritative 00 이 될 수 없다
    for key, path in six.items():
        if key not in session_files:
            continue
        try:
            with open(path, encoding="utf-8", errors="ignore") as f:
                content = f.read()
        except Exception:
            content = ""
        _self_tier, declared = _header_fields_06(content)   # self_tier 는 무시(교차검증용) — 정본은 00
        stem = cycle_binding.path_stem(key)
        out[key] = (_authoritative_cycle_tier(root, profile, stem, six_keys), declared)
    return out


def _reduce_06_depth(per06):
    """이번 세션 06 들을 writeback_depth_gate.check 용 (applies, declared)로 축약.

    심층 대상 = tier in {L2,L3} 또는 tier 미기재(보수적으로 L2 취급 — sage-team 기본값과 일치:
    00 에 Risk Level 이 없으면 심층 노트를 쓰라는 규약). L1 은 얕은 노트가 정상이라 제외한다.
    declared 는 대상 06 이 **전부** performed 선언일 때만 True(다중 L2/L3 06 중 하나라도 미선언이면
    미완료로 잡는다)."""
    applies_keys = [k for k, (tier, _) in per06.items() if tier in (None, "L2", "L3")]
    applies = bool(applies_keys)
    declared = applies and all(per06[k][1] for k in applies_keys)
    return applies, declared


def _writeback_gate_config(profile, root):
    """(mode, vault_enabled) — writeback_depth_gate 활성 판정 요소.

    mode ∈ off|advisory|enforce (pdca.writeback.depth_review_gate). vault_enabled 는 write-back 이
    실제로 노트를 쓰는 조건: knowledge_capture.update_after_dev is True + vault 가 usable 디렉토리
    (isdir, 상대경로는 project root 기준). write-back 이 꺼지면 강제할 심층 노트 자체가 없으므로 이
    조합이 참일 때만 게이트가 동작한다(retro_gate_config 와 동형)."""
    pdca = profile.get("pdca")
    pdca = pdca if isinstance(pdca, dict) else {}            # pdca 비-dict → off (crash 대신 안전 degrade)
    pdca_wb = pdca.get("writeback")
    pdca_wb = pdca_wb if isinstance(pdca_wb, dict) else {}   # writeback 비-dict → off (crash 대신 안전 degrade)
    mode = pdca_wb.get("depth_review_gate") or "off"
    kc = profile.get("knowledge_capture")
    kc = kc if isinstance(kc, dict) else {}
    vp = kc.get("vault_path")
    vault = vp.strip() if isinstance(vp, str) else ""
    vault_abs = vault if os.path.isabs(vault) else os.path.join(root, vault)
    vault_enabled = (kc.get("update_after_dev") is True) and bool(vault) and os.path.isdir(vault_abs)
    return mode, vault_enabled


def _writeback_gate_active(profile, root):
    """writeback_depth_gate 가 실제로 동작하는가(mode advisory/enforce + vault_enabled)."""
    mode, vault_enabled = _writeback_gate_config(profile, root)
    return mode in ("advisory", "enforce") and vault_enabled


def _any_stop_gate_active(profile, root):
    """Stop 계열 게이트(retro_gate·writeback_depth_gate) 중 하나라도 활성인가. 둘 다 SessionStart
    06 baseline(writer-독립 감지)을 공유하므로, baseline 기록/감지 활성 조건을 이 합집합으로 판정한다."""
    return _retro_gate_active(profile, root) or _writeback_gate_active(profile, root)


def _snapshot_path(log_dir, session_id):
    """이번 세션의 06 baseline 스냅샷 파일 경로. session_id 를 파일명 안전문자로 정규화(io_codex 와 동형)."""
    sid = re.sub(r"[^A-Za-z0-9_-]", "_", session_id or "nosession")[:64]
    return os.path.join(log_dir, f"session-snapshot-{sid}.json")


def _snapshot_claim_path(log_dir, session_id):
    """첫 baseline 기회를 원자적으로 선점하는 파일. snapshot과 같은 정규화된 session key를 쓴다."""
    return _snapshot_path(log_dir, session_id) + ".attempt"


_SNAPSHOT_TTL_SECONDS = 14 * 86400   # claim 없는 legacy baseline·중단된 temp 정리 상한
_SEV_ORDER = {"INFO": 0, "OK": 1, "WARN": 2, "BLOCK": 3}   # retro_gate._SEVERITIES 순위(degraded 승격 비교용)


def _cleanup_old_snapshots(log_dir, keep_path=None, keep_paths=()):
    """TTL 초과 claim 없는 legacy baseline·중단된 temp 삭제. 실패는 무시(best-effort).

    attempt claim은 재개 가능한 세션의 첫 baseline 기회를 영구 소비하므로 자동 삭제하지 않는다. claim과
    결속된 baseline도 함께 보존한다. 이를 지우면 장기 중단 세션이 resume될 때 이미 변경된 06 상태를 늦은
    baseline으로 굳혀 초기 변경을 잃는다."""
    now = time.time()
    keep = {os.path.abspath(p) for p in keep_paths if p}
    if keep_path:
        keep.add(os.path.abspath(keep_path))
    for f in glob.glob(os.path.join(log_dir, "session-snapshot-*")):
        try:
            if os.path.abspath(f) in keep:
                continue
            if f.endswith(".attempt"):
                continue
            if f.endswith(".json") and os.path.lexists(f + ".attempt"):
                continue
            if now - os.lstat(f).st_mtime > _SNAPSHOT_TTL_SECONDS:
                os.remove(f)
        except Exception:
            pass


def _claim_snapshot_opportunity(log_dir, session_id):
    """첫 baseline 시도 상태: True=선점, False=기존 claim, None=claim 기록 실패."""
    path = _snapshot_claim_path(log_dir, session_id)
    try:
        os.makedirs(log_dir, exist_ok=True)
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        return False
    except Exception as e:
        print("[session-start-snapshot] 06 baseline first-opportunity 선점 실패 → "
              f"안전하게 작업을 시작할 수 없음: {type(e).__name__}: {e}", file=sys.stderr)
        return None
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump({
                "session_id": session_id,
                "claimed_at": os.environ.get("SAGE_NOW_UTC")
                or time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            }, f, ensure_ascii=False)
    except Exception as e:
        # claim은 남겨 재시도를 막는다. 지우면 이미 agent 작업이 시작된 뒤 늦은 baseline을 만들 수 있다.
        print("[session-start-snapshot] 06 baseline first-opportunity 기록 실패 → "
              f"writer-독립 감지 이번 세션 skip: {type(e).__name__}: {e}", file=sys.stderr)
        return False
    return True


def _mark_snapshot_opportunity_resolved(claim_path, session_id, outcome):
    """claim 소유자만 호출: 첫 시도가 끝났음("noop"|"written")을 claim 파일에 덧쓴다.

    소유자 외 어떤 프로세스도 이 claim_path 에 쓰지 않으므로(패자는 읽기만) 동시쓰기 경합이 없다.
    best-effort — 못 쓰면 대기 중인 loser 는 미해결로 보고 fail-closed 를 유지한다(안전한 쪽으로 열화)."""
    if outcome not in ("noop", "written"):
        return
    try:
        tmp = claim_path + f".tmp-{os.getpid()}-{time.time_ns()}"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump({"session_id": session_id, "resolved": outcome}, f, ensure_ascii=False)
        os.replace(tmp, claim_path)
    except Exception:
        pass


def _snapshot_opportunity_resolved(claim_path, session_id):
    """claim 소유자의 첫 시도가 끝났다고 증명 가능한가. 못 읽거나(미기록/경합/손상) session_id 가
    다르면(파일명 정규화 충돌·잔여 claim 재사용) 미해결로 간주한다."""
    try:
        doc = _load_regular_snapshot(claim_path)
        return (isinstance(doc, dict)
                and doc.get("session_id") == session_id
                and doc.get("resolved") in ("noop", "written"))
    except Exception:
        return False


def _hash_06_glob(root, profile):
    """{정규 키: sha256} — 현재 존재하는 06 glob 파일 전체의 내용 해시. 읽기 실패 파일은 제외(부분 스냅샷).

    바이트 단위 해시라 편집기·Bash·apply_patch 등 **작성 도구와 무관하게** 내용 변화를 포착한다.
    _session_06_run_ids 와 같은 06 glob·정규 키를 써 스냅샷↔감지 키가 정확히 대응한다."""
    out = {}
    for key, path in _glob_relmap(root, _pdca_phase_glob(profile, "06")).items():
        try:
            with open(path, "rb") as f:
                out[key] = hashlib.sha256(f.read()).hexdigest()
        except Exception:
            continue
    return out


def _load_regular_snapshot(path):
    """심볼릭 링크와 경로 교체를 신뢰하지 않고 정규 snapshot JSON만 읽는다."""
    before = os.lstat(path)
    if not stat.S_ISREG(before.st_mode):
        raise ValueError("snapshot is not a regular file")
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(path, flags)
    try:
        after = os.fstat(fd)
        if (not stat.S_ISREG(after.st_mode)
                or before.st_dev != after.st_dev
                or before.st_ino != after.st_ino):
            raise ValueError("snapshot path changed while opening")
        with os.fdopen(fd, encoding="utf-8") as f:
            fd = -1
            return json.load(f)
    finally:
        if fd >= 0:
            os.close(fd)


def _load_session_snapshot(path, session_id):
    """Load a trusted baseline bound to exactly one session."""
    doc = _load_regular_snapshot(path)
    if not isinstance(doc, dict) or doc.get("session_id") != session_id:
        raise ValueError("snapshot session_id mismatch")
    if not isinstance(doc.get("sha256"), dict):
        raise ValueError("snapshot sha256 is not a mapping")
    return doc


def _trusted_session_snapshot_exists(path, session_id):
    if not os.path.lexists(path):
        return False
    try:
        _load_session_snapshot(path, session_id)
        return True
    except Exception:
        return False


def _publish_snapshot_create_once(path, record):
    """완결된 JSON을 기존 경로 교체 없이 원자적으로 게시한다. 이미 존재하면 False."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + f".tmp-{os.getpid()}-{time.time_ns()}"
    flags = (os.O_WRONLY | os.O_CREAT | os.O_EXCL
             | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0))
    fd = os.open(tmp, flags, 0o600)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            fd = -1
            json.dump(record, f, ensure_ascii=False)
        try:
            try:
                os.link(tmp, path, follow_symlinks=False)
            except TypeError:  # 일부 Python/플랫폼은 follow_symlinks 인자를 지원하지 않는다.
                os.link(tmp, path)
        except FileExistsError:
            return False
        except OSError as e:
            unsupported = {
                getattr(errno, "EACCES", -1),
                getattr(errno, "ENOSYS", -1),
                getattr(errno, "ENOTSUP", -1),
                getattr(errno, "EOPNOTSUPP", -1),
                getattr(errno, "EPERM", -1),
                getattr(errno, "EXDEV", -1),
            }
            if e.errno not in unsupported:
                raise
            # 일부 외장/네트워크 파일시스템은 hard link를 지원하지 않는다. O_EXCL 직접 쓰기는
            # 완성 전 Stop과 경합하면 corrupt로 fail-closed되지만 기존 baseline은 교체하지 않는다.
            try:
                direct_fd = os.open(path, flags, 0o600)
            except FileExistsError:
                return False
            try:
                with os.fdopen(direct_fd, "w", encoding="utf-8") as direct:
                    direct_fd = -1
                    json.dump(record, direct, ensure_ascii=False)
            finally:
                if direct_fd >= 0:
                    os.close(direct_fd)
        return True
    finally:
        if fd >= 0:
            os.close(fd)
        try:
            os.remove(tmp)
        except FileNotFoundError:
            pass


def _snapshot_changed_06(root, profile, log_dir, session_id):
    """(status, changed) — 세션 baseline 대비 이번 세션에 신규/변경된 06 정규 키 집합(writer-독립).

    post-tool-logger 는 Write/Edit(claude)·apply_patch(codex)만 로깅해 **Bash 로 쓴 06 을 놓친다**(P0-b:
    로그기반 감지만으로는 게이트가 조용히 무동작). 파일시스템 상태(세션 baseline ↔ Stop 현재)를
    직접 비교하면 작성 도구와 무관하게 이번 세션 작성 06 을 잡는다.

    status ∈ {"ok","no_session","absent","corrupt"}. baseline 이 없거나(no_session/absent) 손상(corrupt)이면
    **조용히 통과시키면 안 된다**(codex: 부재/불일치/손상 snapshot 이 Bash-06 을 무음 bypass). 호출부가
    게이트 활성 시 이 status 를 표면화하고, changed 는 로그기반 감지와 union 한다(부재여도 회귀 아님)."""
    if not session_id:
        return "no_session", set()   # 상관키 없음 → "nosession" 공유 파일 오염 대신 신뢰불가로 처리
    path = _snapshot_path(log_dir, session_id)
    if not os.path.lexists(path):
        return "absent", set()
    try:
        doc = _load_session_snapshot(path, session_id)
        base = doc["sha256"]
    except Exception:
        return "corrupt", set()   # 잘린/손상 JSON — 부분기록 방지(원자쓰기)와 별개로 읽기측도 안전 처리
    return "ok", {key for key, h in _hash_06_glob(root, profile).items() if base.get(key) != h}


def _overlay_say(root, diagnostic):
    """엔진 판정이 낸 진단을 **hook 도메인** 문장으로. 렌더가 실패해도 줄은 서야 한다.

    이 편의 레이어는 엔진이 import 될 때만 돈다. 그래도 문장은 hook catalog 가 소유한다 —
    같은 code 를 두 도메인이 각자 렌더하는 것이 이 사이클이 세운 계약이고, 여기서 엔진
    catalog 를 끌어오면 hook 이 엔진 문안에 묶인다.
    """
    try:
        from sage.diagnostics import render
        import i18n as hook_i18n
        language = hook_i18n.context.resolve(root)[0]

        def translate(key, **arguments):
            text = hook_i18n.frag(language, key)
            if not text:
                return f"[SAGE] message_key={key}"
            try:
                return text.format(**arguments)
            except (KeyError, IndexError, ValueError):
                return f"[SAGE] message_key={key}"

        return render(diagnostic, translate, "hook")
    except Exception:
        return str(diagnostic)


def _session_start_overlay_l1(io, root):
    """SessionStart — CORE 렌더의 오버레이 관리 블록을 재수렴한다.

    현재 오버레이 파일 기준으로 각 CORE 렌더 블록을 재합성해, sync 를 따로 안 돌려도 새 세션이 fresh
    오버레이를 본다. manifest 앵커는 손대지 않는다 — 권위(base 무결성·업그레이드 skew)는 install/sync 와
    validate(L2)가 소유하고, L1 이 앵커를 덮으면 advisory 재합성이 그 권위 영수증을 오염시킨다. skew 판정도
    안 한다(현재 오버레이→현재 base 로만 수렴). retro-gate 와 무관한 독립 스텝이라 게이트 비활성 프로젝트에서도
    돈다. 오버레이 로직을 import할 수 없는 환경은 L2 validate 권위를 남기고 skip한다. 하지만 명시적으로
    탐지한 blocked/malformed/gate-relaxation 오류는 L3 지침 경계이므로 stderr로 표면화하고 exit 2로 막는다."""
    try:
        from sage import overlay_materialize
    except Exception:
        return 0   # sage 패키지 미도달 → L2 권위 경로를 남기고 편의 레이어 skip
    try:
        skill_scope = None
        if io.RUNTIME == "codex":
            manifest_path = os.path.join(root, "docs", "sage_harness", ".manifest.json")
            manifest = None
            if os.path.exists(manifest_path):
                try:
                    with open(manifest_path, encoding="utf-8") as f:
                        manifest = json.load(f)
                    if not isinstance(manifest, dict):
                        manifest = {"core_skill_receipts": None}
                except Exception:
                    # 손상 manifest를 legacy로 오인해 로컬 CORE를 추론하지 않는다. L2 validate가 권위 오류를 낸다.
                    manifest = {"core_skill_receipts": None}
            skill_scope = overlay_materialize.resolve_codex_skill_scope(
                root, manifest=manifest)
        _anchors, changed, errors = overlay_materialize.materialize(
            root, io.RUNTIME, skill_scope)
    except Exception as e:
        print(f"[session-start-overlay] WARN: overlay convenience sync 실패: {type(e).__name__}: {e}",
              file=sys.stderr)
        return 0
    if errors:
        for path, message in errors:
            print(f"[session-start-overlay] BLOCK: {path}: {_overlay_say(root, message)}",
                  file=sys.stderr)
        if changed:
            print("[session-start-overlay] 안전하게 식별된 blocked managed block은 제거됐습니다. "
                  "남은 오류를 고치고 새 세션을 시작하세요.", file=sys.stderr)
        return 2
    return 0


def _ensure_session_06_snapshot(io, root, core_dir, raw_text):
    """SessionStart 또는 첫 UserPromptSubmit에서 세션별 06 baseline을 write-once 확보한다."""
    hid = "session-start-snapshot"
    raw = parse_input_fail_open(hid, raw_text, surface=False)
    if raw is None:
        return 0
    session_id = raw.get("session_id") or ""
    # session_id가 없으면 상관키가 없어 "nosession" 공유 파일이 세션 간 오염되므로 claim도 쓰지 않는다.
    if not session_id:
        return 0
    log_dir = os.path.join(root, io.HOST_DIR, "logs")
    path = _snapshot_path(log_dir, session_id)
    claim_path = _snapshot_claim_path(log_dir, session_id)
    _cleanup_old_snapshots(log_dir, keep_paths=(path, claim_path))
    if os.path.lexists(path):
        if _trusted_session_snapshot_exists(path, session_id):
            return 0
        print(f"[{hid}] 기존 06 baseline이 현재 session_id에 결속된 정규 snapshot이 아님 → "
              "안전하게 작업을 시작할 수 없음", file=sys.stderr)
        return 2
    # profile 로드/게이트 활성 판정보다 먼저 첫 기회를 소비한다. 첫 prompt 때 비활성·오류였는데 나중에
    # 활성화됐다고 이미 변경된 06을 늦은 baseline으로 승인하면 fail-open이므로, 실패 claim도 세션 끝까지 유지한다.
    claim_status = _claim_snapshot_opportunity(log_dir, session_id)
    if claim_status is None:
        # claim이 없으면 다음 hook이 이미 작업 후 늦은 baseline을 만들 수 있다. 그 상태로 진행하지 않는다.
        return 2
    if not claim_status:
        # 다른 프로세스(대개 SessionStart)가 먼저 claim했다. 그 시도가 아직 끝났다고 증명되지 않으면(진행
        # 중 또는 완료 표시 없이 중단) 지금 진행은 이번 세션 06 이 이미 바뀐 뒤에 baseline 이 늦게 게시돼
        # 그 변경을 흡수하는 상황을 배제할 수 없다 — 그 경우에만 첫 claim 과 동일하게 fail-closed 한다.
        if _trusted_session_snapshot_exists(path, session_id):
            return 0
        if _snapshot_opportunity_resolved(claim_path, session_id):
            return 0
        return 2
    resolved = None
    try:
        profile = load_profile_fail_open(hid)
        if profile is None or not _any_stop_gate_active(profile, root):
            resolved = "noop"
            return 0
        # 다른 버전의 writer가 claim을 모른 채 baseline을 만들었을 가능성까지 고려해 다시 확인한다.
        if os.path.lexists(path):
            if _trusted_session_snapshot_exists(path, session_id):
                resolved = "written"
                return 0
            print(f"[{hid}] 경합 중 게시된 06 baseline의 session 결속 검증 실패 → "
                  "안전하게 작업을 시작할 수 없음", file=sys.stderr)
            return 2
        sha = _hash_06_glob(root, profile)
        event = {"session_id": session_id,
                 "now_utc": os.environ.get("SAGE_NOW_UTC") or time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
        sys.path.insert(0, core_dir)
        import session_start_snapshot_core as core
        decision = core.decide(event, {"exists": False, "sha256": sha})
        if decision["action"] != "write":
            resolved = "noop"
            return 0
        try:
            # temp 정규 파일을 완결한 뒤 hard-link로 게시한다. destination이 일반 파일·symlink 어느 쪽으로든
            # 먼저 생기면 EEXIST로 유지해 baseline write-once를 보장하며, Stop 읽기측이 신뢰 여부를 판정한다.
            published = _publish_snapshot_create_once(path, decision["record"])
            if not published and not _trusted_session_snapshot_exists(path, session_id):
                print(f"[{hid}] baseline 게시 경합 승자의 session 결속 검증 실패 → "
                      "안전하게 작업을 시작할 수 없음", file=sys.stderr)
                return 2
            resolved = "written"
        except Exception as e:
            # baseline 기록 실패 = 이번 세션 writer-독립 감지 불가 → 로그기반 감지로 폴백(회귀 아님). silent 금지.
            print(f"[{hid}] 06 baseline 스냅샷 기록 실패 → writer-독립 감지 이번 세션 skip: {type(e).__name__}: {e}",
                  file=sys.stderr)
            resolved = "noop"
        return 0
    finally:
        # claim 을 쥔 이번 시도가 끝났음을 대기 중인 loser 에게 알린다(노력수준 — 못 쓰면 loser 는
        # 미해결로 보고 안전한 쪽으로 열화). winner 만 이 claim_path 에 쓴다(단일 writer, 경합 없음).
        if resolved is not None:
            _mark_snapshot_opportunity_resolved(claim_path, session_id, resolved)


def run_session_start_snapshot(io, root, core_dir, raw_text):
    """session-start-snapshot 오케스트레이터(SessionStart). 이번 세션의 06 baseline(존재+해시)을 기록한다.

    Stop 훅의 retro_gate 가 이 baseline 대비 변경분으로 **작성 도구와 무관하게** 이번 세션 작성 06 을 감지
    (W2/P0-b). 게이트 아님 → parse 실패 silent. 스냅샷은 세션당 **1회만** 쓴다: resume/재-SessionStart 가
    세션 도중 baseline 을 덮으면 그 전 변경이 baseline 에 흡수돼 감지에서 사라진다(write-once 로 방지).
    Codex lifecycle 이상으로 SessionStart가 누락돼도 UserPromptSubmit 경로가 같은 helper를 재호출한다."""
    # 오버레이 블록 재수렴(L1)은 retro-gate·profile·parse 와 독립인 편의 스텝이라 early-return 앞에서 먼저 돈다.
    overlay_rc = _session_start_overlay_l1(io, root)
    if overlay_rc:
        return overlay_rc
    return _ensure_session_06_snapshot(io, root, core_dir, raw_text)


def retro_gate_result(profile, root, raw, session_entries, snapshot_06=None, snapshot_status="ok"):
    """retro_gate 정책 결과(양 런타임 공유, knowledge_capture 와 동형 — 9-C v1).

    raw = Stop 이벤트 원본(파싱됨, session_id/stop_hook_active 추출용). session_entries = 이번 세션의
    로그 엔트리(모든 session-*.jsonl 에서 session_id 로 모은 것 — _session_log_entries). snapshot_06 =
    SessionStart baseline 대비 변경된 06 정규 키(writer-독립 감지 — Bash 작성 06 포착). snapshot_status =
    baseline 신뢰도("ok"|"no_session"|"absent"|"corrupt"): degraded 면 게이트 활성 시 표면화한다(무음 bypass
    금지). 06 작성 감지는 로그기반 ∪ 스냅샷기반 union(리포트 본문 집계 범위는 건드리지 않는다 — 기존 동작 보존)."""
    import retro_audit
    import retro_gate

    # 게이트 활성 조건은 retro CLI 와 동일: mode advisory/enforce + retro_note is True + usable vault(isdir,
    # 상대경로는 root 기준). 노트가 안 써지면 --check 대상이 없어 게이트가 통과 불가능한 걸 강제하게 된다.
    mode, notes_enabled = _retro_gate_config(profile, root)

    # 06 감지 = 로그기반(정규 키) ∪ 스냅샷기반(writer-독립). 로그는 Write/Edit·apply_patch 만 보므로
    # Bash 작성 06 을 스냅샷 diff 가 보완한다(W2/P0-b). glob.glob(recursive) 실존파일과 대조하는 건 동일 —
    # fnmatch 로 `entries[].file` 을 직접 매칭하면 `**` 제로디렉토리 케이스를 놓친다(구현리뷰 1R P0).
    session_files = {_canon_relkey(e.get("file", "")) for e in session_entries}
    session_files |= set(snapshot_06 or ())
    per06 = _session_06_run_ids(root, profile, session_files) if session_files else {}
    has_06 = bool(per06)

    run_id, binding, checked, missing = None, "resolved", False, []
    audit = retro_audit.audit_summary(root)
    if has_06:
        run_id, binding, checked, missing = _reduce_06_bindings(per06, audit)

    result = retro_gate.check(mode, has_06, run_id, checked, _stop_hook_active(raw), notes_enabled, binding)

    # --no-vault 로 노트를 생략한 run 은 checked=True 로 게이트를 통과하지만 "--check 통과"는 아니다 —
    # OK 문구를 실제 상태(노트 생략)로 바로잡는다(오해 방지). run 이 여럿이면 대표 run 기준.
    if result["severity"] == "OK" and (audit.get(run_id) or {}).get("state") == "skipped":
        result = dict(result)
        result["text"] = f"N/A — --no-vault 로 이번 run 노트 생략 (run_id={run_id})"

    # 미완료 종료(게이트 활성 + 미확인 = WARN/BLOCK)를 .sage/retro_audit.jsonl 에 영구 기록한다 —
    # host 로그의 컴플라이언스 리포트와 별개로, doctor/다음 사이클이 볼 수 있는 커밋 대상 증거(유저 스코프).
    # **미확인 run 전부** 기록한다(codex W1 R2 P1): 다중 06 이 각기 미확인이면 대표 하나만 기록하던 옛
    # 코드는 나머지를 재시도 dedup 뒤 doctor 가시성에서 잃었다. 상태변화 시에만 append(record_missing 이
    # dedup). 기록 실패는 세션을 막지 않되(fail-open) **조용히 삼키지 않는다** — 리포트에 명시(구현리뷰 3R P1).
    if missing and result["severity"] in ("WARN", "BLOCK"):
        try:
            for rid in missing:
                retro_audit.record_missing(root, rid, note_path=None)
        except Exception as e:
            result = dict(result)
            result["text"] += f" (⚠️ retro_audit 기록 실패: {type(e).__name__} — 미완료 영구기록·doctor 가시성 유실)"

    # writer-독립 감지 degraded 처리: 게이트 활성인데 baseline 이 없거나(no_session/absent) 손상(corrupt)이면
    # 로그에 안 남는 Bash-06 을 놓쳤을 수 있다(로그기반 06 이 일부 잡혀도 두 번째 숨은 06 가능성 — codex R2 P0).
    # enforce 는 이걸 통과로 두면 게이트가 무력화되고 W3(codex 실차단)이 막을 BLOCK 자체가 안 생기므로
    # **fail-closed(BLOCK)** 한다. advisory 는 WARN, 재시도(stop_hook_active=true)는 세션당 1회 차단 제약상
    # WARN 으로 완화(무한 Stop 재호출 방지 — 미확인 케이스와 동일 정책). off 는 애초에 gate_active=False.
    gate_active = mode in ("advisory", "enforce") and notes_enabled
    if gate_active and snapshot_status in ("no_session", "absent", "corrupt"):
        reason = {"no_session": "session_id 없음(상관 불가)",
                  "absent": "SessionStart/UserPromptSubmit baseline 없음(훅 미발화?)",
                  "corrupt": "baseline 손상"}[snapshot_status]
        deg_sev = retro_gate._unchecked_severity(mode, _stop_hook_active(raw))   # enforce 첫Stop=BLOCK / advisory·재시도=WARN
        result = dict(result)
        if _SEV_ORDER[deg_sev] > _SEV_ORDER[result["severity"]]:
            result["severity"] = deg_sev
        verb = "차단" if deg_sev == "BLOCK" else "경고"
        result["text"] += (f" (⚠️ writer-독립 06 감지 불가 — {reason}. Bash 로 작성한 06 을 놓쳤을 수 있어 "
                           f"{verb} — SessionStart/UserPromptSubmit 훅 동작을 확인하세요)")

    return result


def writeback_depth_gate_result(profile, root, raw, session_entries, snapshot_06=None, snapshot_status="ok"):
    """writeback_depth_gate 정책 결과(양 런타임 공유). retro_gate_result 와 동형으로 같은 세션 06
    감지(로그기반 ∪ 스냅샷기반 writer-독립)를 재사용한다 — Bash 로만 쓴 06 도 포착한다.

    retro_gate 와 달리 run_id 결속·감사 jsonl 은 없다: 이 게이트는 사이클을 run 으로 특정할 필요
    없이 '이번 세션 L2/L3 06 이 self-review 선언을 달았는가'만 본다. 미완료는 이 Stop 이 쓰는
    compliance-<날짜>.md 에 WARN/BLOCK 으로 남아 사후 확인 가능하다.

    snapshot_status = SessionStart baseline 신뢰도. degraded(no_session/absent/corrupt)면 로그·스냅샷
    둘 다로 못 본 Bash 작성 L2/L3 06 이 있을 수 있어, retro_gate 와 동일하게 게이트 활성 시 fail-closed
    로 승격(enforce 첫Stop=BLOCK / advisory·재시도=WARN) — 놓친 06 가능성을 조용히 통과시키지 않는다."""
    import writeback_depth_gate as gate

    mode, vault_enabled = _writeback_gate_config(profile, root)
    session_files = {_canon_relkey(e.get("file", "")) for e in session_entries}
    session_files |= set(snapshot_06 or ())
    per06 = _session_06_depth(root, profile, session_files) if session_files else {}
    applies, declared = _reduce_06_depth(per06) if per06 else (False, False)
    result = gate.check(mode, applies, declared, _stop_hook_active(raw), vault_enabled)

    gate_active = mode in ("advisory", "enforce") and vault_enabled
    if gate_active and snapshot_status in ("no_session", "absent", "corrupt"):
        reason = {"no_session": "session_id 없음(상관 불가)",
                  "absent": "SessionStart/UserPromptSubmit baseline 없음(훅 미발화?)",
                  "corrupt": "baseline 손상"}[snapshot_status]
        deg_sev = gate._unchecked_severity(mode, _stop_hook_active(raw))
        result = dict(result)
        if _SEV_ORDER[deg_sev] > _SEV_ORDER[result["severity"]]:
            result["severity"] = deg_sev
        # baseline degraded 사실은 severity 를 올리지 못해도(이미 동급 BLOCK/WARN) 항상 리포트에 남긴다 —
        # retro_gate 와 동일. 안 그러면 '이미 BLOCK 인' 케이스에서 writer-독립 감지 실패가 조용히 사라진다.
        verb = "차단" if result["severity"] == "BLOCK" else "경고"
        result["text"] += (f" (⚠️ writer-독립 06 감지 불가 — {reason}. Bash 로 작성한 L2/L3 06 을 놓쳤을 수 "
                           f"있어 {verb} — SessionStart/UserPromptSubmit 훅 동작을 확인하세요)")
    return result


def run_stop_compliance_report(io, root, core_dir, raw_text):
    """stop-compliance-report 오케스트레이터(Stop). session JSONL → report.md.

    knowledge_capture 는 양 런타임 공유(F7). output_contract 는 codex 전용 → io.attach_policy_results 가
    런타임별로 policy_results 순서까지 결정(codex: [output_contract, knowledge_capture] / claude: [knowledge_capture]).
    retro_gate(9-C v1)는 그 뒤에 공유로 붙는다(호스트 무관 문구라 순서 분기 불필요).

    retro_gate 가 BLOCK 을 내면 이 함수가 exit 2 를 반환한다 — 기존 PreToolUse 게이트(io.render_gate)와
    동일한 "exit code 2 = block" 관례를 그대로 쓴다(이 저장소에 Stop 전용 JSON decision 프로토콜 선례가
    없어, 검증된 기존 관례를 재사용). `stop_hook_active` 가 true 인 재시도에서는 retro_gate 가 스스로
    severity 를 WARN 으로 낮추므로, 여기서 별도 처리 없이 model["exit_code"]==0 이 자연히 나온다
    (플랫폼 제약: 세션당 block 은 최대 1회 — retro_gate.py 문서 참조).
    """
    hid = "stop-compliance-report"
    today = os.environ.get("SAGE_TODAY") or time.strftime("%Y-%m-%d", time.localtime())
    log_dir = os.path.join(root, io.HOST_DIR, "logs")
    log_file = os.path.join(log_dir, f"session-{today}.jsonl")
    profile = load_profile_fail_open(hid)
    if profile is None:
        return 0

    # 리포트 본문은 오늘자(로컬 날짜) 파일을 그대로 쓴다(기존 집계 범위 보존). 게이트 세션 감지는 이와
    # 별개로 모든 session-*.jsonl 에서 이번 session_id 로 모은다 — 로거는 UTC 날짜로, Stop 은 로컬 날짜로
    # 파일명을 잡아 자정 경계에서 오늘자 파일이 아예 없을 수 있고(codex 7R P0), 그때 여기서 早期 return
    # 하면 게이트가 통째로 무동작한다. 그래서 오늘자 파일 부재만으로는 바로 종료하지 않는다.
    raw = parse_input_fail_open(hid, raw_text, surface=False) or {}   # session_id/stop_hook_active 추출용
    session_id = raw.get("session_id") or ""
    session_entries = _session_log_entries(log_dir, session_id)
    # writer-독립 06 감지: SessionStart baseline 대비 변경분(status, changed). Bash 로만 06 을 쓴 세션은 로그
    # 엔트리가 아예 없어(로거 미매칭) 아래 早期 return 에 걸려 게이트가 무동작하므로, 이 감지 결과도 종료판정에
    # 넣는다. baseline 이 degraded(부재/손상/상관불가)여도 게이트 활성이면 早期 return 대신 리포트를 내
    # writer-독립 감지 불가를 표면화한다(무음 bypass 금지).
    gate_active = _any_stop_gate_active(profile, root)
    if gate_active:
        snapshot_status, snapshot_06 = _snapshot_changed_06(root, profile, log_dir, session_id)
    else:
        snapshot_status, snapshot_06 = "ok", set()   # 게이트 비활성 → 감지 불필요, 06 해싱 IO skip(off 기본)
    gate_degraded = gate_active and snapshot_status != "ok"

    entries = []
    if os.path.exists(log_file):
        with open(log_file, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    try:
                        entries.append(json.loads(line))
                    except Exception:
                        pass
    elif not session_entries and not snapshot_06 and not gate_degraded:
        return 0   # 오늘자 로그·이번 세션 로그·writer-독립 06 변경 전무 + baseline 정상 → 리포트/게이트 생략(기존 동작)

    snapshot = {"entries": entries, "today": today, "branch": resolve_branch(root, ""), "runtime": io.RUNTIME}
    event = {"hook_id": hid, "hook_event_name": "Stop", "runtime": io.RUNTIME}
    sys.path.insert(0, core_dir)
    sys.path.insert(0, os.path.join(core_dir, "policies"))
    import stop_compliance_report_core as core
    model = core.decide(event, profile, snapshot)

    kc_result = knowledge_capture_result(profile, entries)   # 공유(F7)
    io.attach_policy_results(model, profile, entries, raw_text, kc_result)  # 런타임별 정책+순서

    try:
        rg_result = retro_gate_result(profile, root, raw, session_entries, snapshot_06, snapshot_status)
    except Exception as e:
        # Stop 훅은 내부 오류로 세션을 막으면 안 된다(fail-open) — 게이트 판정 불가 시 skip 으로 낮춘다.
        rg_result = {"name": "retro_gate", "severity": "INFO",
                     "text": f"N/A — 게이트 판정 중 오류로 skip ({type(e).__name__})"}
    model["sections"]["policy_results"].append(rg_result)

    try:
        wb_result = writeback_depth_gate_result(profile, root, raw, session_entries, snapshot_06, snapshot_status)
    except Exception as e:
        wb_result = {"name": "writeback_depth_gate", "severity": "INFO",
                     "text": f"N/A — 게이트 판정 중 오류로 skip ({type(e).__name__})"}
    model["sections"]["policy_results"].append(wb_result)

    md = core.render_markdown(model)
    report = os.path.join(log_dir, f"compliance-{today}.md")
    with open(report, "a", encoding="utf-8") as f:
        f.write(md)
    exit_code = model["exit_code"]
    # Stop 계열 게이트(retro_gate·writeback_depth_gate)는 플랫폼 제약상 세션당 최대 1회만 block 가능하다.
    # 둘 다 BLOCK 이면 한 번의 block 에 두 문구를 합쳐 싣는다 — 하나로 묶지 않으면 두 번째 block 이
    # 무시돼 한 미완료가 사용자에게 안 보인다. 각 게이트는 stop_hook_active 재시도에서 스스로 WARN 으로
    # 낮추므로 재호출은 자연히 exit 0 으로 수렴한다.
    blocking = [r for r in (rg_result, wb_result) if r["severity"] == "BLOCK"]
    if blocking:
        # 정책 의미는 양 host 동일하고 wire만 IO 모듈이 소유한다. Claude는 exit 2, Codex는
        # stdout decision:block + exit 0으로 같은 turn을 한 번 더 실행한다.
        return io.render_stop_result(today, "\n\n".join(r["text"] for r in blocking))
    io.render_stop_result(today)
    return exit_code
