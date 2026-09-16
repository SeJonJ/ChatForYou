"""cycle_state — 사이클 선언 파일(`<root>/.sage/cycle.json`)의 단일소스.

`sage cycle` CLI 와 게이트가 같은 코드를 공유한다. env(`SAGE_CYCLE_STEM`)로만 선언하던 통로는
셸이 사이클보다 오래 살고, 자식 프로세스를 전부 따라가고, 조회할 방법이 없었다. 파일로 옮기면
그 셋이 한꺼번에 해소되는 대신 새 성질이 셋 생긴다 — 세션을 넘겨 살아남고, 편집 도구로 심을 수
있고, 손상될 수 있다. 각각 완결 사이클 차단(gate core)·write guard·아래 degrade 로 받는다.

**저장 위치를 탐색하지 않는다.** root 가 정본이고 선언은 항상 그 바로 아래다. 상위로 올라가며
찾으면 파일이 `install` 이 `.gitignore` 를 쓴 앵커 밖에 놓여 커밋 대상이 된다.

root 해석은 CLI 전용이다. 게이트는 자기 root 를 이미 알고 들어오므로 `read_declaration` 만 쓴다.
둘이 어긋날 수 있다는 사실은 설계의 알려진 한계이고, `sage cycle set` 이 절대경로를 찍어 보이게 한다.
"""
import json
import os
import sys
import tempfile
import time

HOOKS_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if HOOKS_DIR not in sys.path:
    sys.path.insert(0, HOOKS_DIR)
import cycle_binding

# "여기가 SAGE 프로젝트다" 의 정의 — sage install 이 만든다. git 과 무관하다.
MARKER_REL = os.path.join("docs", "sage_harness", ".manifest.json")
DECLARATION_REL = os.path.join(".sage", "cycle.json")
SCHEMA_VERSION = 2

# 문서 언어는 Phase 00 의 `Document-Language` 마커가 정본이고 이 파일은 재개·cross-host 를 위한
# 미러다. 둘이 다르면 어느 쪽이 이기는 게 아니라 hard conflict — 자동으로 어느 쪽도 고치지 않는다.
DOCUMENT_LANGUAGES = ("ko", "en")
LEGACY_DOCUMENT_LANGUAGE = "ko"


class DeclarationRootError(RuntimeError):
    """SAGE 표식이 없는 곳에서 선언을 요구했다 — 조용히 다른 자리에 쓰지 않는다.

    CLI(sage/commands/cycle.py)에서만 생성된다. `sage.diagnostics.Diagnostic` 을 담아 두면
    호출부가 exception_text() 로 원하는 언어로 렌더할 수 있다 — 이 클래스 자체는 그 타입을
    몰라도(hook runtime 은 sage.diagnostics 를 import 할 수 없음) 문제없다."""

    def __init__(self, diagnostic):
        self.diagnostic = diagnostic
        super().__init__(str(diagnostic))


def _diagnostic(code, **arguments):
    """언어 중립 진단(code+arguments). hook runtime 은 sage.diagnostics 를 import 할 수 없어
    (엔진 없이 소비 프로젝트에서 단독 실행되어야 하므로) 같은 모양의 plain dict 로 올린다 —
    CLI 는 sage.diagnostics.render() 로, hook 은 자체 i18n.tr() 로 각자 렌더한다."""
    return {"code": code, "arguments": arguments, "evidence": ""}


def find_project_root(start=None):
    """가장 가까운 SAGE 표식을 가진 조상 디렉터리, 없으면 None.

    시작 경로를 `realpath` 로 정규화한다. `abspath` 만 쓰면 symlink 를 경유한 cwd 에서 상위
    탐색이 실제 트리가 아니라 링크 경로를 거슬러 올라가 프로젝트 밖으로 나간다. macOS 의
    `/tmp`→`/private/tmp` 가 일상적 사례고, 실측에서 8갈래 중 4갈래가 틀렸다(그중 하나는 정상
    프로젝트를 `None` 으로 거부한다).
    """
    cur = os.path.realpath(start or os.getcwd())
    while True:
        if os.path.exists(os.path.join(cur, MARKER_REL)):
            return cur
        parent = os.path.dirname(cur)
        if parent == cur:
            return None
        cur = parent


def declaration_path(root):
    return os.path.join(root, DECLARATION_REL)


def read_declaration(root):
    """선언 stem 을 읽어 (stem, diagnostic) 로 돌려준다.

    - 부재: `("", None)` — 선언한 적이 없는 정상 상태다
    - 손상·스키마 위반: `("", <진단>)` — **부재와 갈라서 돌려준다** (code+arguments, 언어 중립)

    호출부는 진단을 degrade 로 처리하되(파일 하나 깨진 것으로 모든 편집을 멈추지 않는다)
    반드시 표면화해야 한다. 부재·손상·스키마 위반이 전부 `None` 으로 뭉개지면 파일을 1바이트만
    잘라도 선언이 조용히 사라지고, 그게 곧 우회 레버가 된다.
    """
    path = declaration_path(root)
    try:
        with open(path, encoding="utf-8") as handle:
            raw = handle.read()
    except FileNotFoundError:
        return "", None
    except (OSError, UnicodeDecodeError) as exc:
        return "", _diagnostic("cycle_state.read_failed", path=path, error_type=type(exc).__name__)
    try:
        data = json.loads(raw)
    except ValueError:
        return "", _diagnostic("cycle_state.json_invalid", path=path)
    if not isinstance(data, dict):
        return "", _diagnostic("cycle_state.not_object", path=path)
    stem = cycle_binding.normalize_stem(data.get("cycle_stem"))
    if stem is None:
        return "", _diagnostic("cycle_state.stem_invalid", path=path)
    return stem, None


class CycleDeclarationRead:
    """선언 파일의 전체 record. 언어 중립이고 읽기 과정에서 파일을 고치지 않는다.

    `legacy` 는 version 1 이거나 문서 언어가 없는 상태다. 그 경우 `document_language` 는 None
    이며, 해석은 호출부가 한다 — 여기서 `ko` 로 채우면 "선언한 적 없음"과 "한국어로 선언함"이
    같은 값이 되어 마이그레이션이 언제 끝났는지 셀 수 없다.
    """

    __slots__ = ("stem", "document_language", "schema_version", "legacy", "error")

    def __init__(self, stem="", document_language=None, schema_version=None,
                 legacy=False, error=None):
        self.stem = stem
        self.document_language = document_language
        self.schema_version = schema_version
        self.legacy = legacy
        self.error = error

    def __repr__(self):
        return (f"CycleDeclarationRead(stem={self.stem!r}, "
                f"document_language={self.document_language!r}, "
                f"schema_version={self.schema_version!r}, legacy={self.legacy!r}, "
                f"error={self.error!r})")

    def __eq__(self, other):
        return isinstance(other, CycleDeclarationRead) and all(
            getattr(self, name) == getattr(other, name) for name in self.__slots__)


def read_declaration_record(root):
    """부재·version 1 legacy·version 2·손상을 구분해 돌려준다."""
    stem, error = read_declaration(root)
    if error:
        return CycleDeclarationRead(error=error)
    if not stem:
        return CycleDeclarationRead()
    path = declaration_path(root)
    try:
        with open(path, encoding="utf-8") as handle:
            data = json.loads(handle.read())
    except (OSError, UnicodeDecodeError, ValueError) as exc:
        return CycleDeclarationRead(
            error=_diagnostic("cycle_state.reread_failed", path=path, error_type=type(exc).__name__))
    version = data.get("version")
    language = data.get("document_language")
    if version != SCHEMA_VERSION or language is None:
        return CycleDeclarationRead(stem=stem, schema_version=version, legacy=True)
    if language not in DOCUMENT_LANGUAGES:
        return CycleDeclarationRead(
            stem=stem, schema_version=version,
            error=_diagnostic("cycle_state.language_invalid", path=path, language=language))
    return CycleDeclarationRead(stem=stem, document_language=language,
                                schema_version=version)


def write_declaration(root, stem, now=None, *, document_language):
    """선언을 원자적으로 기록하고 파일 경로를 돌려준다. 형식이 틀린 stem 은 ValueError.

    `mkstemp` + `os.replace`: 같은 디렉터리에 온전한 파일을 만든 뒤 한 번에 갈아끼운다.
    직접 `open(w)` 로 쓰면 쓰는 도중 중단된 순간 게이트가 읽는 자리에 잘린 파일이 남는다.
    """
    # 두 ValueError 는 방어적 재검증이다 — CLI 호출부(sage cycle set/create)는 stem 을
    # cycle_binding.normalize_stem 으로, document_language 를 DOCUMENT_LANGUAGES 로 이미
    # 검증한 뒤에만 여기 도달한다. 화면 문구가 아니라 잘못된 프로그램 호출을 잡는 내부 계약
    # 위반이라 영어로 직접 고정한다(언어 배선 불필요, hook shim 주석과 같은 처리).
    normalized = cycle_binding.normalize_stem(stem)
    if normalized is None:
        raise ValueError(f"invalid cycle stem format: {stem!r}")
    target = declaration_path(root)
    os.makedirs(os.path.dirname(target), exist_ok=True)
    if document_language not in DOCUMENT_LANGUAGES:
        raise ValueError(f"invalid document_language format: {document_language!r}")
    payload = json.dumps({"version": SCHEMA_VERSION, "cycle_stem": normalized,
                          "document_language": document_language,
                          "declared_at": time.strftime(
                              "%Y-%m-%dT%H:%M:%SZ",
                              time.gmtime(time.time() if now is None else now))},
                         ensure_ascii=False, indent=2) + "\n"
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(target), prefix=".cycle-", suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(payload)
        os.replace(tmp, target)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise
    return target


def clear_declaration(root):
    """선언을 지우고 실제로 있었는지 돌려준다."""
    try:
        os.unlink(declaration_path(root))
        return True
    except FileNotFoundError:
        return False


def resolve_stem(root, environ=None):
    """게이트가 쓸 (stem, origin, error). env > 파일 > 없음.

    env 를 위에 두는 이유는 범위가 더 좁아서다 — 프로세스 1회용이라 CI 에서 정당하고, 파일보다
    의도가 명확하다. **origin 을 함께 돌려주는 것이 핵심이다**: 둘이 같이 있으면 env 가 이기는데
    화면이 "파일 선언" 이라고 적으면 확정적으로 거짓이 된다.

    env 가 이겨도 파일 손상은 그대로 보고한다 — 지금 판정에 안 쓰였을 뿐 깨진 파일은 남아 있고,
    env 가 사라지는 순간 조용히 발화한다.
    """
    environ = os.environ if environ is None else environ
    stem, error = read_declaration(root)
    env_stem = (environ.get("SAGE_CYCLE_STEM") or "").strip()
    if env_stem:
        return env_stem, "env", error
    if stem:
        return stem, "cli", error
    return "", "", error
