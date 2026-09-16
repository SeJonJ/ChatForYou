"""전환 시점 phase 문서의 **실제** provenance — 디스크를 읽는 계층.

## 왜 계약과 갈라놓는가

`fast_cycle_contract` 는 순수하다. 형식만 본다 — phase 집합, 경로 문법, digest 형식, size 형.
그 계층은 **과거 기록**을 읽을 때도 쓰인다. 감사와 게이트는 이미 기록된 값을 판정하고, 그때
디스크에는 그 파일이 더 이상 같은 내용으로 없을 수 있다(전환 뒤 정상 개발을 허용하는 설계다).

그러나 형식만 보는 검사는 `{"path": ".", "sha256": "sha256:<64 hex>", "size": 0}` 을 막지
못한다. `.` 은 저장소 상대 문자열이고 digest 는 정규 형식이며 size 는 정수다 — 그런데 phase
문서가 아니다. 임의 digest·size 도 같다. **구조는 "저장소 안의 그 파일" 을 결코 보증할 수
없다.**

그래서 실제 파일 확인은 여기 따로 둔다. 그리고 이 검증이 도는 자리는 **writer 시점 한 번**이다.
감사와 게이트가 나중에 디스크를 재검증하면 전환 뒤의 정상 개발이 손상으로 오판된다.

## 이 감사가 보증하는 것과 보증하지 않는 것

보증: 기록이 append 될 때, 그 경로에 그 내용의 regular file 이 저장소 안에 실제로 있었다.

보증하지 않음: 그 기록이 그 뒤로 손대지 않았다는 것. 감사 JSONL 은 로컬 파일이고 소유자는 그것을
고치고 해시 체인을 다시 만들 수 있다. 막으려면 외부 신뢰 기반(서명 키·원격 receipt)이 필요하고,
같은 기계에 둔 키는 아무것도 사지 못한다. 그래서 이 감사는 **self-attested local provenance**
다 — `convert_fast` 가 레코드에 `attestation="self_asserted_local"` 을 싣는 것이 그 선언이다.

게이트가 겨냥하는 것은 지름길을 타는 에이전트이고, 소유자 자신의 우회가 아니다. 소유자는 언제든
`.sage/` 를 지울 수 있다. 이 계층이 닫는 것은 **정상 API 경로로 가짜 snapshot 을 쓰는 것**이다.
"""
from __future__ import annotations

import glob as globlib
import hashlib
import os

from fast_cycle_contract import (
    converted_provenance_issue,
    expected_source_phases,
)


class SourceProvenanceError(Exception):
    """전환 시점 실제 문서를 확인할 수 없거나, 준 값이 실제와 다르다."""


def phase_glob(profile, phase_id):
    """이 phase 의 glob. 정확히 하나여야 한다 — 둘이면 어느 문서가 근거인지 정해지지 않는다."""
    pdca = profile.get("pdca") if isinstance(profile, dict) else None
    phases = pdca.get("phases") if isinstance(pdca, dict) else None
    matches = [item.get("glob") for item in (phases or [])
               if isinstance(item, dict) and item.get("id") == phase_id
               and isinstance(item.get("glob"), str)]
    if len(matches) != 1:
        raise SourceProvenanceError(f"profile needs exactly one phase {phase_id} glob")
    return matches[0]


def phase_document(root, profile, phase_id, stem):
    """이 stem 의 phase 문서 실경로. regular file·저장소 내부·symlink 아님을 확인한다.

    symlink 를 거부하는 이유는 감사가 가리키는 대상이 그 파일이라는 보장이 사라지기 때문이다.
    """
    pattern = phase_glob(profile, phase_id)
    candidates = [path for path in globlib.glob(os.path.join(root, pattern), recursive=True)
                  if os.path.basename(path) == f"{stem}.md"]
    links = [path for path in candidates if os.path.islink(path)]
    if links:
        raise SourceProvenanceError(f"phase {phase_id} document for {stem!r} is a symlink")
    files = [path for path in candidates
             if os.path.isfile(path) and not os.path.islink(path)]
    if len(files) != 1:
        raise SourceProvenanceError(
            f"phase {phase_id} document for {stem!r} must exist exactly once; "
            f"found={len(files)}")
    real_root = os.path.realpath(root)
    real = os.path.realpath(files[0])
    if os.path.commonpath([real_root, real]) != real_root:
        raise SourceProvenanceError(f"phase {phase_id} document escapes project root")
    return real


def source_phase_snapshot(root, profile, stem, current_phase):
    """전환 시점 00~current_phase 의 경로·raw-byte 해시·크기.

    동결 기준이 아니라 provenance 다 — 전환 뒤 문서가 정상 개발로 바뀌는 것은 허용하고, 어디까지
    어떤 문서로 Standard 를 진행했는지만 남긴다.
    """
    phases, issue = expected_source_phases(current_phase)
    if issue:
        raise SourceProvenanceError(issue)
    real_root = os.path.realpath(root)
    snapshot = {}
    for phase in phases:
        path = phase_document(root, profile, phase, stem)
        with open(path, "rb") as handle:
            payload = handle.read()
        snapshot[phase] = {
            "path": os.path.relpath(path, real_root).replace(os.sep, "/"),
            "sha256": "sha256:" + hashlib.sha256(payload).hexdigest(),
            "size": len(payload),
        }
    return snapshot


def verify_source_phases(root, profile, stem, current_phase, source_phases):
    """호출자가 준 값이 **지금 실재하는** 문서에서 만든 snapshot 과 정확히 같은가.

    형식 검증을 먼저 돌린다 — 형식이 깨진 값에 대해 "실제와 다르다" 라고만 말하면 무엇이 틀렸는지
    알 수 없다. 그다음 실물과 대조한다.

    같은지만 보고 호출자의 값을 조용히 실물로 갈아치우지 않는다. 조용히 고치면 호출자가 무엇을
    기록했다고 믿는지와 실제로 기록된 것이 갈리고, 그 어긋남은 다음에 누가 봐도 알 수 없다.
    """
    issue = converted_provenance_issue(current_phase, source_phases)
    if issue:
        raise SourceProvenanceError(issue)
    live = source_phase_snapshot(root, profile, stem, current_phase)
    if source_phases == live:
        return live
    differing = sorted(phase for phase in live
                       if source_phases.get(phase) != live.get(phase))
    detail = "; ".join(
        f"{phase}: given={source_phases.get(phase)!r} actual={live.get(phase)!r}"
        for phase in differing[:2])
    raise SourceProvenanceError(
        "source phase provenance does not match the documents on disk: " + detail)
