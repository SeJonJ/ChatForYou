"""경로만 보는 위험도 판정 — gate 와 `sage explain` 의 단일 정본.

## 왜 따로 떼는가

`pre_implementation_gate_core._classify_one(path, content, profile)` 은 경로와 내용을 함께 본다.
`sage explain --path` 는 내용을 갖지 못한다 — 사용자가 **앞으로 쓸** 내용은 파일에 아직 없고,
현재 파일 내용을 읽어 대신 쓰면 실제 write 와 다른 답을 자신 있게 내놓게 된다.

그래서 경로만 보는 부분을 여기로 옮긴다. **복사가 아니라 이동이다.** gate 가 이 함수를 부르고
`explain` 도 이 함수를 부른다. 두 벌을 두면 언젠가 갈리고, 갈린 뒤에는 어느 쪽이 진짜 게이트
판정인지 알 수 없다 — 직전 사이클이 Risk 선언 정규식 두 벌에서 겪은 것과 같은 일이다.

## 왜 matched_rule 을 함께 돌려주는가

"L2 입니다" 로 끝나는 설명은 사용자를 아무 데도 데려가지 않는다. 어느 glob 이 걸렸는지 보여야
프로필을 고칠지 경로를 옮길지 판단할 수 있다.
"""
import fnmatch

# 경로 규칙이 위험도를 정하는 순서. 위에서 아래로 처음 걸린 것이 이긴다.
_PATH_RULES = (("l3_filename_globs", "L3", "L3 filename 패턴", "filename_l3"),
               ("l2_path_globs", "L2", "L2 소스/설정", "path_l2"),
               ("l1_path_globs", "L1", "L1 저위험", "path_l1"))


class PathRisk:
    """경로 기준 판정 하나.

    `risk` 는 **하한**이다. 실제 write 는 내용 키워드·세션 선언·다중 변경에 따라 더 높아질 수
    있고, 절대 더 낮아지지 않는다. 그 비대칭이 이 값을 설명에 쓸 수 있게 만든다.
    """

    __slots__ = ("risk", "reason", "trigger_sources", "matched_rule", "l0_excluded")

    def __init__(self, risk, reason="", trigger_sources=(), matched_rule=None,
                 l0_excluded=False):
        self.risk = risk
        self.reason = reason
        self.trigger_sources = list(trigger_sources)
        self.matched_rule = matched_rule
        self.l0_excluded = l0_excluded

    def __repr__(self):
        return (f"PathRisk(risk={self.risk!r}, matched_rule={self.matched_rule!r}, "
                f"l0_excluded={self.l0_excluded!r})")

    def as_tuple(self):
        """gate 가 쓰던 `(risk, reason, trigger_sources)` 모양."""
        return (self.risk, self.reason, self.trigger_sources)


def imatch(path, glob):
    return fnmatch.fnmatch((path or "").lower(), (glob or "").lower())


def _first_match(path, globs):
    for index, glob in enumerate(globs or []):
        if imatch(path, glob):
            return index, glob
    return None, None


def path_risk_floor(path, profile):
    """경로와 profile 만으로 정해지는 위험도 하한.

    판정 순서는 gate 가 쓰던 것 그대로다.

    1. l0_exclude 에 걸리지 않은 채 l0_pass 에 걸리면 즉시 L0.
    2. l3_filename → l2_path → l1_path 순으로 처음 걸린 규칙이 이긴다.
    3. 아무 경로 규칙에도 안 걸렸는데 l0_exclude 에만 걸렸다면 L3 다 — exclusion 만 있고
       상위 결속이 없는 profile 은 손상이며, 손상을 L0 으로 내리지 않는다.
    4. 그 외에는 `none`.

    3 이 `none` 이 아니라 `L3` 인 것이 이 함수의 유일한 비직관적 지점이다. 그러나 그게
    맞다 — "제외한다" 고만 적힌 규칙을 통과로 읽으면, profile 오타 하나가 게이트를 연다.
    """
    rules = (profile or {}).get("risk", {}) or {}
    l0_excluded = any(imatch(path, g) for g in rules.get("l0_exclude_globs", []) or [])

    if not l0_excluded:
        index, glob = _first_match(path, rules.get("l0_pass_globs"))
        if glob is not None:
            return PathRisk("L0", "문서/plan", ["l0_path"],
                            matched_rule=("risk.l0_pass_globs", index, glob))

    for key, risk, reason, source in _PATH_RULES:
        index, glob = _first_match(path, rules.get(key))
        if glob is None:
            continue
        sources = [source] + (["l0_excluded"] if l0_excluded else [])
        return PathRisk(risk, reason, sources,
                        matched_rule=(f"risk.{key}", index, glob), l0_excluded=l0_excluded)

    if l0_excluded:
        return PathRisk("L3", "L0 exclusion 상위 위험도 결속 누락",
                        ["l0_excluded", "invalid_profile"], l0_excluded=True)
    return PathRisk("none")
