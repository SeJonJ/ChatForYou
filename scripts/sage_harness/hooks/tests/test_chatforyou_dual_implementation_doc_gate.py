"""ChatForYou component implementation-doc gate — deterministic regression.

같은 event/snapshot 이면 항상 같은 decision 이어야 하므로 filesystem 을 쓰지 않고
snapshot 을 직접 조립한다. 실제 allowlist 파일은 면제가 실제로 성립하는지 볼 때만 읽는다 —
면제는 배포된 스냅샷 하나에만 걸리므로 손으로 만든 목록으로는 그 경로를 재현할 수 없다.
"""

import hashlib
import importlib.util
import os
import unittest
import unicodedata

_HERE = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.abspath(os.path.join(_HERE, "..", "..", "..", ".."))
_CORE = os.path.join(_HERE, "..", "chatforyou_dual_implementation_doc_gate_core.py")

_spec = importlib.util.spec_from_file_location("cfy_doc_gate_core", _CORE)
core = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(core)

TRIGGER = "plan_docs/04-analyze/newcyc.md"
BASE = "plan_docs/00-base_plan/newcyc.md"
BASE_GLOB = "plan_docs/00-base_plan/**/newcyc.md"
MONTH_BASE = "plan_docs/00-base_plan/2026/09/newcyc.md"
BACKEND_DOC = "springboot-backend/plan_docs/newcyc.md"
FRONTEND_DOC = "nodejs-frontend/plan_docs/newcyc.md"
ALLOWLIST = core.LEGACY_CYCLES_FILE

# 도메인 어휘를 피한 중립 stem — 테스트 소스가 자기 위험도를 올리지 않게 한다(03 문서 D5).
LEGACY_STEM = "legacy_alpha"


def shipped_allowlist():
    """배포된 rollout 스냅샷 원문과 그 안의 stem 하나.

    소스에 실제 stem 을 적지 않고 파일에서 읽는 이유도 D5 와 같다.
    """
    with open(os.path.join(_ROOT, ALLOWLIST), encoding="utf-8") as handle:
        text = handle.read()
    stems = sorted(line.strip() for line in text.splitlines()
                   if line.strip() and not line.strip().startswith("#"))
    return text, stems[0]


def event(path=TRIGGER):
    return {"changes": [{"path": path, "op": "write"}]}


def snapshot(allowlist=LEGACY_STEM, **files):
    merged = {}
    if allowlist is not None:
        merged[ALLOWLIST] = "# comment\n\n" + allowlist + "\n"
    merged.update(files)
    return {"files": merged}


def base_plan(marker="ChatForYou-Component-Doc-Gate: v1",
              backend="Component-Backend: REQUIRED",
              frontend="Component-Frontend: N/A: 브라우저 계약 변경 없음",
              extra=()):
    lines = ["# [Base Plan] sample", ""]
    lines += [line for line in (marker, backend, frontend) if line is not None]
    lines += list(extra)
    return "\n".join(lines) + "\n"


def decide(ev, snap):
    return core.decide(ev, {}, snap)


class TestTrigger(unittest.TestCase):
    def test_non_phase4_change_is_skipped(self):
        d = decide(event("springboot-backend/src/main/java/webChat/Sample.java"), snapshot())
        self.assertEqual((d["status"], d["exit_code"]), ("skip", 0))

    def test_two_phase4_targets_have_no_single_cycle(self):
        ev = {"changes": [{"path": "plan_docs/04-analyze/a.md", "op": "write"},
                          {"path": "plan_docs/04-analyze/b.md", "op": "write"}]}
        self.assertEqual(decide(ev, snapshot())["status"], "skip")

    def test_non_markdown_phase4_target_is_ignored(self):
        self.assertEqual(decide(event("plan_docs/04-analyze/notes.txt"), snapshot())["status"], "skip")


class TestLegacyAllowlist(unittest.TestCase):
    def test_listed_stem_is_not_checked_retroactively(self):
        text, stem = shipped_allowlist()
        d = decide(event("plan_docs/04-analyze/" + stem + ".md"), {"files": {ALLOWLIST: text}})
        self.assertEqual((d["status"], d["exit_code"]), ("skip", 0))

    def test_missing_allowlist_does_not_waive_anything(self):
        """파일 삭제가 곧 게이트 해제가 되면 안 된다."""
        d = decide(event("plan_docs/04-analyze/" + LEGACY_STEM + ".md"), snapshot(allowlist=None))
        self.assertEqual(d["status"], "block")
        self.assertIn("missing_base_plan", d["message"])

    def test_comments_and_blank_lines_are_not_stems(self):
        snap = {"files": {ALLOWLIST: "# " + LEGACY_STEM + "\n\n"}}
        d = decide(event("plan_docs/04-analyze/" + LEGACY_STEM + ".md"), snap)
        self.assertEqual(d["status"], "block")

    def test_shipped_allowlist_file_exists_at_contract_path(self):
        self.assertTrue(os.path.isfile(os.path.join(_ROOT, ALLOWLIST)),
                        ALLOWLIST + " 가 계약 경로에 없습니다")


class TestBasePlanContract(unittest.TestCase):
    def test_new_cycle_requires_base_plan(self):
        d = decide(event(), snapshot())
        self.assertEqual(d["exit_code"], 2)
        self.assertIn("missing_base_plan", d["message"])

    def test_missing_marker_blocks(self):
        d = decide(event(), snapshot(**{BASE: base_plan(marker=None)}))
        self.assertIn("missing_gate_marker", d["message"])

    def test_duplicate_marker_blocks(self):
        body = base_plan(extra=("ChatForYou-Component-Doc-Gate: v1",))
        self.assertIn("duplicate_gate_marker", decide(event(), snapshot(**{BASE: body}))["message"])

    def test_wrong_marker_version_blocks(self):
        body = base_plan(marker="ChatForYou-Component-Doc-Gate: v2")
        self.assertIn("invalid_component_status", decide(event(), snapshot(**{BASE: body}))["message"])

    def test_declaration_inside_code_fence_does_not_count(self):
        body = "# [Base Plan] sample\n\n```\nChatForYou-Component-Doc-Gate: v1\n```\n"
        self.assertIn("missing_gate_marker", decide(event(), snapshot(**{BASE: body}))["message"])


class TestComponentDeclaration(unittest.TestCase):
    def test_missing_declaration_blocks(self):
        d = decide(event(), snapshot(**{BASE: base_plan(backend=None)}))
        self.assertIn("missing_component_declaration", d["message"])

    def test_duplicate_declaration_blocks(self):
        body = base_plan(extra=("Component-Backend: REQUIRED",))
        self.assertIn("duplicate_component_declaration", decide(event(), snapshot(**{BASE: body}))["message"])

    def test_unknown_status_blocks(self):
        body = base_plan(backend="Component-Backend: MAYBE")
        self.assertIn("invalid_component_status", decide(event(), snapshot(**{BASE: body}))["message"])

    def test_required_with_trailing_reason_is_rejected(self):
        body = base_plan(backend="Component-Backend: REQUIRED: 사유")
        self.assertIn("invalid_component_status", decide(event(), snapshot(**{BASE: body}))["message"])

    def test_na_without_reason_blocks(self):
        for value in ("Component-Backend: N/A", "Component-Backend: N/A:", "Component-Backend: N/A:   "):
            body = base_plan(backend=value)
            with self.subTest(value=value):
                self.assertIn("empty_na_reason", decide(event(), snapshot(**{BASE: body}))["message"])


class TestPhase05Findings(unittest.TestCase):
    """Phase-05 cross-model 리뷰(codex)가 재현한 결함의 회귀 고정."""

    def test_info_string_line_does_not_close_a_fence(self):
        """```text 안의 ```python 은 닫는 fence 가 아니다 — 코드 예시가 본문 선언이 되면 안 된다."""
        body = ("# sample\n```text\n```python\nChatForYou-Component-Doc-Gate: v1\n"
                "Component-Backend: N/A: 예시\nComponent-Frontend: N/A: 예시\n```\n")
        d = decide(event(), snapshot(**{BASE: body}))
        self.assertEqual(d["status"], "block")
        self.assertIn("missing_gate_marker", d["message"])

    def test_plain_closing_fence_still_closes(self):
        body = ("# sample\n```\nComponent-Backend: REQUIRED\n```\n"
                "ChatForYou-Component-Doc-Gate: v1\n"
                "Component-Backend: N/A: 무관\nComponent-Frontend: N/A: 무관\n")
        self.assertEqual(decide(event(), snapshot(**{BASE: body}))["status"], "ok")

    def test_unknown_component_declaration_blocks(self):
        """모르는 컴포넌트 선언을 무시하면 작성자는 선언했다고 믿는데 아무것도 검사되지 않는다."""
        body = base_plan(backend="Component-Backend: N/A: 무관",
                         extra=("Component-Desktop: REQUIRED",))
        d = decide(event(), snapshot(**{BASE: body}))
        self.assertIn("unknown_component_declaration", d["message"])
        self.assertIn("Component-Desktop", d["message"])

    def test_changing_governed_doc_in_the_same_event_blocks(self):
        """snapshot 은 쓰기 전 상태다 — 같은 patch 로 00 을 무효화하며 04 를 쓰면 판정할 수 없다."""
        ev = {"changes": [{"path": TRIGGER, "op": "write"}, {"path": BASE, "op": "write"}]}
        d = decide(ev, snapshot(**{BASE: base_plan()}))
        self.assertIn("mixed_change_scope", d["message"])
        self.assertIn(BASE, d["message"])

    def test_changing_component_doc_in_the_same_event_blocks(self):
        ev = {"changes": [{"path": TRIGGER, "op": "write"}, {"path": BACKEND_DOC, "op": "write"}]}
        d = decide(ev, snapshot(**{BASE: base_plan(), BACKEND_DOC: "- [x] ok\n"}))
        self.assertIn("mixed_change_scope", d["message"])

    def test_unrelated_co_change_is_still_allowed(self):
        """게이트 판정 대상이 아닌 파일이 함께 바뀌는 것은 막지 않는다."""
        ev = {"changes": [{"path": TRIGGER, "op": "write"},
                          {"path": "docs/unrelated.md", "op": "write"}]}
        snap = snapshot(**{BASE: base_plan(), BACKEND_DOC: "- [x] ok\n"})
        self.assertEqual(decide(ev, snap)["status"], "ok")


class TestPhase05SecondRoundFindings(unittest.TestCase):
    """Phase-05 2차 cross-model 리뷰가 재현한 결함의 회귀 고정."""

    def test_unreadable_phase4_stem_blocks_instead_of_skipping(self):
        """'Phase 04 를 안 건드림' 과 '이름을 못 읽음' 을 묶어 skip 하면 파일명만으로 우회된다."""
        d = decide(event("plan_docs/04-analyze/_new.md"), snapshot())
        self.assertEqual(d["exit_code"], 2)
        self.assertIn("invalid_phase4_stem", d["message"])

    def test_component_name_with_dot_is_not_silently_ignored(self):
        body = base_plan(extra=("Component-Desktop.UI: REQUIRED",))
        d = decide(event(), snapshot(**{BASE: body}))
        self.assertIn("unknown_component_declaration", d["message"])

    def test_component_name_with_non_ascii_is_not_silently_ignored(self):
        body = base_plan(extra=("Component-데스크탑: REQUIRED",))
        self.assertIn("unknown_component_declaration",
                      decide(event(), snapshot(**{BASE: body}))["message"])

    def test_changing_the_allowlist_in_the_same_event_blocks(self):
        """면제 판정 입력을 같은 patch 로 바꾸면 옛 snapshot 의 면제로 영구 통과한다."""
        ev = {"changes": [{"path": TRIGGER, "op": "write"},
                          {"path": ALLOWLIST, "op": "write"}]}
        d = decide(ev, snapshot(allowlist="newcyc"))
        self.assertIn("mixed_change_scope", d["message"])
        self.assertIn(ALLOWLIST, d["message"])

    def test_conflict_check_precedes_legacy_waiver(self):
        ev = {"changes": [{"path": "plan_docs/04-analyze/" + LEGACY_STEM + ".md", "op": "write"},
                          {"path": ALLOWLIST, "op": "write"}]}
        self.assertEqual(decide(ev, snapshot())["status"], "block")

    def test_backtick_in_info_string_does_not_open_a_fence(self):
        """```bad` 는 CommonMark 상 fence 가 아니다 — fence 로 보면 본문 선언이 통째로 숨는다."""
        body = "```bad`\n" + base_plan(backend="Component-Backend: N/A: 무관")
        self.assertEqual(decide(event(), snapshot(**{BASE: body}))["status"], "ok")

    def test_plain_backtick_fence_still_opens(self):
        body = base_plan() + "```python\nComponent-Backend: REQUIRED\n```\n"
        d = decide(event(), snapshot(**{BASE: body, BACKEND_DOC: "- [x] ok\n"}))
        self.assertEqual(d["status"], "ok")


class TestPhase05ThirdRoundFindings(unittest.TestCase):
    """Phase-05 3차 cross-model 리뷰가 재현한 결함의 회귀 고정.

    지적된 입력 한 개씩이 아니라 그 입력이 속한 부류를 고정한다 — case 변형은 관할 판정과
    충돌 판정 양쪽에서, 불가시 문자는 zero-width 하나가 아니라 제어·format·구분자 전체에서.
    """

    def test_case_variant_of_phase4_directory_is_still_governed(self):
        """case 를 구분하지 않는 볼륨에서는 같은 디렉터리다 — 문자열 비교로는 그냥 통과한다."""
        for path in ("PLAN_DOCS/04-ANALYZE/newcyc.md",
                     "Plan_Docs/04-Analyze/newcyc.md",
                     "plan_docs/04-Analyze/newcyc.md"):
            with self.subTest(path=path):
                d = decide(event(path), snapshot())
                self.assertEqual(d["exit_code"], 2)
                self.assertIn("missing_base_plan", d["message"])

    def test_case_variant_of_governed_document_still_conflicts(self):
        """관할 판정만 고치고 충돌 판정을 두면 같은 우회가 한 칸 옆에서 그대로 성립한다."""
        for governed in (BASE, BACKEND_DOC, FRONTEND_DOC, ALLOWLIST):
            with self.subTest(governed=governed):
                ev = {"changes": [{"path": TRIGGER, "op": "write"},
                                  {"path": governed.upper(), "op": "write"}]}
                d = decide(ev, snapshot(**{BASE: base_plan()}))
                self.assertIn("mixed_change_scope", d["message"])
                self.assertIn(governed.upper(), d["message"])

    def test_neighbouring_directories_are_not_pulled_into_scope(self):
        """case 를 무시하는 대가로 관할이 번지면 안 된다."""
        for path in ("plan_docs/04-analyzed/newcyc.md",
                     "plan_docs/04-analyze/sub/newcyc.md",
                     "other/plan_docs/04-analyze/newcyc.md"):
            with self.subTest(path=path):
                self.assertEqual(decide(event(path), snapshot())["status"], "skip")

    def test_stem_added_to_allowlist_does_not_waive(self):
        """면제 목록을 선행 변경으로 키우면 그 변경 자체는 이 게이트를 지나간다."""
        text, _ = shipped_allowlist()
        d = decide(event("plan_docs/04-analyze/newcyc.md"),
                   {"files": {ALLOWLIST: text + "\nnewcyc\n"}})
        self.assertEqual(d["exit_code"], 2)
        self.assertIn("legacy_allowlist_changed", d["message"])

    def test_shrinking_the_allowlist_also_invalidates_the_waiver(self):
        """집합이 줄어드는 방향도 똑같이 스냅샷이 아니다 — 한쪽만 막으면 부류가 안 닫힌다."""
        text, stem = shipped_allowlist()
        kept = [line for line in text.splitlines()
                if line.strip().startswith("#") or line.strip() in ("", stem)]
        d = decide(event("plan_docs/04-analyze/" + stem + ".md"),
                   {"files": {ALLOWLIST: "\n".join(kept) + "\n"}})
        self.assertIn("legacy_allowlist_changed", d["message"])

    def test_reformatting_the_allowlist_preserves_the_waiver(self):
        """digest 대상은 원문이 아니라 파싱된 stem 집합이다 — 주석·순서·공백은 계약이 아니다."""
        text, stem = shipped_allowlist()
        stems = [line.strip() for line in text.splitlines()
                 if line.strip() and not line.strip().startswith("#")]
        reformatted = "# 새 주석\n\n" + "\n".join("  " + s + "  " for s in reversed(stems)) + "\n\n"
        d = decide(event("plan_docs/04-analyze/" + stem + ".md"),
                   {"files": {ALLOWLIST: reformatted}})
        self.assertEqual((d["status"], d["exit_code"]), ("skip", 0))

    def test_missing_allowlist_does_not_become_a_total_block(self):
        """무결성 검사를 목록 읽는 시점에 두면 '파일 없음 = 전면 검사' 가 '전면 차단' 으로 뒤집힌다."""
        _, stem = shipped_allowlist()
        d = decide(event("plan_docs/04-analyze/" + stem + ".md"), snapshot(allowlist=None))
        self.assertIn("missing_base_plan", d["message"])
        self.assertNotIn("legacy_allowlist_changed", d["message"])

    def test_pinned_digest_matches_the_shipped_allowlist(self):
        """목록을 정당하게 고치고 상수를 잊으면, 나중에 이유 모를 차단이 아니라 여기서 먼저 깨진다."""
        text, _ = shipped_allowlist()
        stems = {line.strip() for line in text.splitlines()
                 if line.strip() and not line.strip().startswith("#")}
        digest = hashlib.sha256("\n".join(sorted(stems)).encode("utf-8")).hexdigest()
        self.assertEqual(digest, core.LEGACY_CYCLES_DIGEST,
                         ALLOWLIST + " 를 고쳤다면 LEGACY_CYCLES_DIGEST 도 같이 갱신하세요")

    def test_invisible_only_na_reason_blocks(self):
        """사람이 읽을 근거를 요구하는 자리라 가시성이 곧 조건이다."""
        # \ubb38\uc790\ub97c \uc18c\uc2a4\uc5d0 \uadf8\ub300\ub85c \uc801\uc73c\uba74 \uc774 \ud14c\uc2a4\ud2b8 \uc790\uccb4\uac00 \uc548 \ubcf4\uc774\ub294 \ucf54\ub4dc\uac00 \ub41c\ub2e4 \u2014 escape \ub85c\ub9cc \uc4f4\ub2e4.
        for name, ch in (("zero-width space", "\u200b"),
                         ("left-to-right mark", "\u200e"),
                         ("BOM", "\ufeff"),
                         ("word joiner", "\u2060"),
                         ("no-break space", "\u00a0"),
                         ("ideographic space", "\u3000")):
            with self.subTest(char=name):
                body = base_plan(backend="Component-Backend: N/A: 무관",
                                 frontend="Component-Frontend: N/A: " + ch)
                d = decide(event(), snapshot(**{BASE: body}))
                self.assertEqual(d["exit_code"], 2)
                self.assertIn("empty_na_reason", d["message"])

    def test_visible_reason_survives_invisible_padding(self):
        body = base_plan(backend="Component-Backend: N/A: 무관",
                         frontend="Component-Frontend: N/A: \u200b브라우저 계약 변경 없음\u200b")
        self.assertEqual(decide(event(), snapshot(**{BASE: body}))["status"], "ok")


class TestPhase05SelfReviewFindings(unittest.TestCase):
    """3차 수정분에 대한 자체 리뷰가 재현한 결함의 회귀 고정."""

    def test_blank_rendering_glyphs_do_not_count_as_a_reason(self):
        """카테고리로는 문자·기호인데 화면에는 아무것도 안 그려지는 것들."""
        for name, ch in (("braille blank", "\u2800"),
                         ("hangul filler", "\u3164"),
                         ("hangul choseong filler", "\u115f"),
                         ("hangul jungseong filler", "\u1160"),
                         ("halfwidth hangul filler", "\uffa0")):
            with self.subTest(glyph=name):
                body = base_plan(backend="Component-Backend: N/A: 무관",
                                 frontend="Component-Frontend: N/A: " + ch)
                d = decide(event(), snapshot(**{BASE: body}))
                self.assertEqual(d["exit_code"], 2)
                self.assertIn("empty_na_reason", d["message"])

    def test_visible_reason_containing_a_blank_glyph_still_passes(self):
        body = base_plan(backend="Component-Backend: N/A: 무관",
                         frontend="Component-Frontend: N/A: 무관\u2800함")
        self.assertEqual(decide(event(), snapshot(**{BASE: body}))["status"], "ok")

    def test_control_sequences_never_reach_the_message(self):
        """판정 결과를 사람이 읽는 채널이라, 문서 텍스트의 제어열이 차단을 통과처럼 덮어쓸 수 있다."""
        payload = "\x1b[2K\x1b[A[GATE OK] all clear"
        cases = {
            "N/A 사유(ok 경로)": base_plan(backend="Component-Backend: N/A: " + payload,
                                        frontend="Component-Frontend: N/A: 무관"),
            "미지 컴포넌트 이름": base_plan(backend="Component-Backend: N/A: 무관",
                                     extra=("Component-" + payload + ": REQUIRED",)),
        }
        for label, body in cases.items():
            with self.subTest(case=label):
                message = decide(event(), snapshot(**{BASE: body}))["message"]
                self.assertNotIn("\x1b", message)
                self.assertFalse(any(unicodedata.category(ch)[0] == "C"
                                     for ch in message.replace("\n", "")))

    def test_control_sequences_in_evidence_lines_are_stripped(self):
        body = base_plan(frontend="Component-Frontend: N/A: 무관")
        doc = "- [ ] \x1b[2K할 일\n"
        message = decide(event(), snapshot(**{BASE: body, BACKEND_DOC: doc}))["message"]
        self.assertIn("unchecked_component_doc", message)
        self.assertNotIn("\x1b", message)


class TestComponentDocuments(unittest.TestCase):
    def test_required_backend_document_must_exist(self):
        d = decide(event(), snapshot(**{BASE: base_plan()}))
        self.assertIn("missing_component_doc", d["message"])
        self.assertIn(BACKEND_DOC, d["message"])

    def test_required_frontend_document_must_exist(self):
        body = base_plan(backend="Component-Backend: N/A: 서버 변경 없음",
                         frontend="Component-Frontend: REQUIRED")
        d = decide(event(), snapshot(**{BASE: body}))
        self.assertIn(FRONTEND_DOC, d["message"])

    def test_both_required_blocks_when_either_is_missing(self):
        body = base_plan(frontend="Component-Frontend: REQUIRED")
        d = decide(event(), snapshot(**{BASE: body, BACKEND_DOC: "- [x] done\n"}))
        self.assertIn("missing_component_doc", d["message"])
        self.assertIn(FRONTEND_DOC, d["message"])

    def test_unchecked_box_blocks_with_line_evidence(self):
        doc = "# impl\n- [x] done\n- [ ] 남은 작업\n"
        d = decide(event(), snapshot(**{BASE: base_plan(), BACKEND_DOC: doc}))
        self.assertIn("unchecked_component_doc", d["message"])
        self.assertIn("L3", d["message"])
        self.assertIn("남은 작업", d["message"])

    def test_unchecked_box_inside_fence_is_ignored(self):
        doc = "# impl\n```\n- [ ] 예시\n```\n- [x] done\n"
        self.assertEqual(decide(event(), snapshot(**{BASE: base_plan(), BACKEND_DOC: doc}))["status"], "ok")

    def test_evidence_is_capped(self):
        doc = "# impl\n" + "- [ ] item\n" * 25
        message = decide(event(), snapshot(**{BASE: base_plan(), BACKEND_DOC: doc}))["message"]
        self.assertIn("25건", message)
        self.assertIn("외 15건", message)

    def test_na_component_document_is_not_required(self):
        body = base_plan(backend="Component-Backend: N/A: governance 전용 변경")
        d = decide(event(), snapshot(**{BASE: body}))
        self.assertEqual((d["status"], d["exit_code"]), ("ok", 0))
        self.assertIn("gate_ok", d["message"])

    def test_na_component_stale_document_is_ignored(self):
        body = base_plan(backend="Component-Backend: N/A: governance 전용 변경")
        snap = snapshot(**{BASE: body, BACKEND_DOC: "- [ ] 남은 작업\n"})
        self.assertEqual(decide(event(), snap)["status"], "ok")

    def test_complete_backend_cycle_passes(self):
        snap = snapshot(**{BASE: base_plan(), BACKEND_DOC: "# impl\n- [x] done\n"})
        d = decide(event(), snap)
        self.assertEqual((d["status"], d["exit_code"]), ("ok", 0))
        self.assertIn("Backend=REQUIRED", d["message"])


class TestPlanReads(unittest.TestCase):
    def test_exact_paths_only(self):
        self.assertEqual(core.plan_reads(event()),
                         {"globs": [ALLOWLIST, BASE_GLOB, BACKEND_DOC, FRONTEND_DOC]})

    def test_no_globs_without_trigger(self):
        self.assertEqual(core.plan_reads(event("src/Sample.java")), {"globs": []})

    def test_only_base_plan_glob_has_wildcard(self):
        """00 문서만 월 폴더 배치를 잡으려고 재귀 glob 을 쓴다 — 나머지는 리터럴 경로."""
        for pattern in core.plan_reads(event())["globs"]:
            self.assertNotIn("..", pattern)
            self.assertFalse(pattern.startswith("/"))
            if pattern == BASE_GLOB:
                self.assertIn("*", pattern)
            else:
                self.assertNotIn("*", pattern)

    def test_prefix_similar_stem_is_not_matched(self):
        """정확 경로만 읽으므로 유사 prefix 파일은 판정에 쓰이지 않는다."""
        snap = snapshot(**{BASE: base_plan(),
                           "springboot-backend/plan_docs/newcyc_extra.md": "- [x] done\n"})
        self.assertIn("missing_component_doc", decide(event(), snap)["message"])


class TestBasePlanMonthFolder(unittest.TestCase):
    """00 base-plan 이 `plan_docs/00-base_plan/<stem>.md` flat 대신 월 폴더에 있어도 동일하게 동작한다."""

    def test_month_folder_base_plan_is_found(self):
        snap = snapshot(**{MONTH_BASE: base_plan(), BACKEND_DOC: "- [x] done\n"})
        d = decide(event(), snap)
        self.assertEqual((d["status"], d["exit_code"]), ("ok", 0))

    def test_month_folder_base_plan_enforces_same_rules_as_flat(self):
        d = decide(event(), snapshot(**{MONTH_BASE: base_plan(marker=None)}))
        self.assertIn("missing_gate_marker", d["message"])

    def test_flat_and_month_folder_together_is_ambiguous(self):
        snap = snapshot(**{BASE: base_plan(), MONTH_BASE: base_plan()})
        d = decide(event(), snap)
        self.assertEqual(d["exit_code"], 2)
        self.assertIn("ambiguous_base_plan", d["message"])

    def test_unrelated_month_folder_file_is_not_matched(self):
        other_stem_doc = "plan_docs/00-base_plan/2026/09/other.md"
        d = decide(event(), snapshot(**{other_stem_doc: base_plan()}))
        self.assertIn("missing_base_plan", d["message"])

    def test_conflicting_paths_detects_month_folder_base_plan(self):
        ev = {"changes": [{"path": TRIGGER, "op": "write"},
                          {"path": MONTH_BASE, "op": "write"}]}
        d = decide(ev, snapshot(**{MONTH_BASE: base_plan()}))
        self.assertIn("mixed_change_scope", d["message"])
        self.assertIn(MONTH_BASE, d["message"])


class TestDecisionContract(unittest.TestCase):
    def test_decision_shape_matches_runtime_contract(self):
        for snap in (snapshot(), snapshot(**{BASE: base_plan()}),
                     snapshot(**{BASE: base_plan(), BACKEND_DOC: "- [x] ok\n"})):
            d = decide(event(), snap)
            with self.subTest(status=d["status"]):
                self.assertEqual(set(d), {"status", "exit_code", "message"})
                self.assertIn(d["status"], ("block", "ok", "warn", "skip"))
                self.assertEqual(d["exit_code"], 2 if d["status"] == "block" else 0)
                self.assertIsInstance(d["message"], str)

    def test_contract_version_is_declared(self):
        self.assertTrue(isinstance(core.CONTRACT_VERSION, str) and core.CONTRACT_VERSION)

    def test_same_input_is_deterministic(self):
        snap = snapshot(**{BASE: base_plan()})
        self.assertEqual(decide(event(), snap), decide(event(), snap))


class TestHostParity(unittest.TestCase):
    """양 host 는 입력 추출만 다르다 — 같은 changes 면 같은 decision 이어야 한다."""

    def test_claude_and_codex_changes_agree(self):
        claude_changes = [{"path": TRIGGER, "op": "write"}]
        codex_changes = [{"path": TRIGGER, "op": "update"}]
        snap = snapshot(**{BASE: base_plan()})
        self.assertEqual(decide({"changes": claude_changes}, snap),
                         decide({"changes": codex_changes}, snap))


if __name__ == "__main__":
    unittest.main(verbosity=2)
