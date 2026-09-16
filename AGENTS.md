# AGENTS.md

Codex-native session entrypoint for this SAGE project. Codex auto-reads this file
at session start; it is a thin router, not a rules duplicate.

## Resolve the conversation language before the first message

Read `sage/project-profile.local.yaml` **silently** before you emit anything a person
reads. Its `interface.language` decides this session's conversation language; absent,
unreadable or invalid means `ko`. An explicit `--lang ko|en` on a skill invocation wins
for that invocation.

Print nothing before that read completes — no greeting, no "reading the profile", no
progress note. A first line in the wrong language is the failure this rule exists to
prevent, and switching later does not take it back. After the read, every question,
progress note, warning, error and closing summary stays in the resolved language for the
whole session, including turns after a tool failure, a resume or a compaction.

Machine values are never translated: paths, commands, ids, enum values, statuses and
schema keys. Phase 00–06 document prose follows that cycle's `Document-Language:` marker,
which is a **separate** decision and may differ from the conversation language. Full
rules: `docs/agent/language-policy.md`.

## Read in order

1. `AGENT_GUIDE.md` — the single source of truth (rules, risk gate, PDCA, safety).
2. `CODEX.md` — Codex-specific execution wrapper notes.
3. `sage/project-profile.yaml` — project values.
4. `sage/project-profile.local.yaml` — machine values, when present.

## Bootstrap first (if not done)

If `sage/project-profile.yaml` is unbootstrapped — `project.name` empty, or
`risk`/`components` unset — run the **conversational bootstrap FIRST, before any
other work**. The fastest path is the **`$sage-init` skill** (installed to the explicit
global `$CODEX_HOME/skills/sage-init/` or project-local `.codex/skills/sage-init/`
scope by `sage install --host codex --skill-scope <scope>`); invoke it with
`$sage-init`. It interviews the user → fills the profile values → hands off to
`sage generate` / `sage validate`. Underlying protocol:
`docs/agent/bootstrap-authoring.md`.

`sage generate` is BLOCKED until the profile is bootstrapped (by design — an empty
profile would silently disable the governance gate). So bootstrap is the required
first step, not optional.

(If `$sage-init` is not listed in `/skills`, inspect `sage doctor`, remove duplicate
scope copies after confirming intent, and re-run
`sage install --host codex --skill-scope <scope> --force`; or follow
`docs/agent/bootstrap-authoring.md` manually. Claude
runtime users invoke the same flow via the repo-scoped `/sage-init` skill.)

If the shared profile is already bootstrapped, do not run `$sage-init` again.
Run `$sage-init-local` to create or update only the Git-ignored local capability
profile. Shared policy changes use `$sage-profile-modify`.
