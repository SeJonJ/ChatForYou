"""Dependency-free contract for ``checklist_scan_targets``."""

import re


_DRIVE_PREFIX = re.compile(r"^[A-Za-z]:")
_CONTROL = re.compile(r"[\x00-\x1f\x7f\x85  ]")
_TARGET_KEYS = {"label", "glob", "is_impl"}


def _diagnostic(code, **arguments):
    """언어 중립 진단(code+arguments). 이 모듈은 sage.diagnostics 를 import 할 수 없어
    (엔진 없이 소비 프로젝트에서 단독 실행되어야 하므로) 같은 모양의 plain dict 로 올린다 —
    hook_runtime.py 는 자체 _i18n(hook_runtime._overlay_say)으로, CLI(sage/checklist_contract.py
    경유 profile_validate.py)는 sage.i18n 으로 각자 렌더한다."""
    return {"code": code, "arguments": arguments, "evidence": ""}


def unsafe_glob(value):
    if not isinstance(value, str) or not value.strip():
        return _diagnostic("checklist_contract.glob_empty")
    if len(value) > 512:
        return _diagnostic("checklist_contract.glob_too_long")
    if _CONTROL.search(value):
        return _diagnostic("checklist_contract.glob_control_chars")
    if value.startswith(("/", "\\")) or _DRIVE_PREFIX.match(value):
        return _diagnostic("checklist_contract.glob_absolute_path")
    if any(part == ".." for part in value.replace("\\", "/").split("/")):
        return _diagnostic("checklist_contract.glob_dotdot")
    return None


def checklist_target_issues(profile):
    if "checklist_scan_targets" not in profile:
        return []
    targets = profile.get("checklist_scan_targets")
    if not isinstance(targets, list):
        return [_diagnostic("checklist_contract.targets_not_list")]

    issues = []
    for index, target in enumerate(targets):
        where = f"checklist_scan_targets[{index}]"
        if not isinstance(target, dict):
            issues.append(_diagnostic("checklist_contract.target_not_mapping", where=where))
            continue
        non_string_keys = [key for key in target if not isinstance(key, str)]
        if non_string_keys:
            issues.append(_diagnostic("checklist_contract.target_keys_not_string", where=where))
        unknown = sorted((str(key) for key in target if key not in _TARGET_KEYS))
        if unknown:
            issues.append(_diagnostic("checklist_contract.target_unknown_keys", where=where,
                                      unknown=unknown))
        label = target.get("label")
        if (not isinstance(label, str) or not label.strip() or len(label) > 80
                or _CONTROL.search(label)):
            issues.append(_diagnostic("checklist_contract.target_label_invalid", where=where))
        reason = unsafe_glob(target.get("glob"))
        if reason:
            issues.append(_diagnostic("checklist_contract.target_glob_invalid", where=where,
                                      reason=reason))
        if "is_impl" in target and not isinstance(target.get("is_impl"), bool):
            issues.append(_diagnostic("checklist_contract.target_is_impl_not_bool", where=where))
    return issues
