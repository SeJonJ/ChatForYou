# CLAUDE.md

Thin Claude-specific execution override. All common rules, workflow, and the
output contract are governed solely by `AGENT_GUIDE.md`.

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

## Mandatory read (session start)

1. `AGENT_GUIDE.md` — single source of truth
2. `sage/project-profile.yaml` — project values
3. `sage/project-profile.local.yaml` — machine values, when present
4. Relevant plan doc + convention docs (per profile)

## Claude-specific

- Use the Claude runtime asset ecosystem (`.claude/agents`, `.claude/skills`,
  `.claude/hooks`) which are generated from `docs/sage_harness/` specs.
- Do not modify generated artifacts directly — edit the spec and run
  `sage generate`. (Exception: hand-shipped CORE bootstrap renders under
  `.claude/skills/{sage-init,sage-init-local,sage-cycle,sage-plan,sage-team,sage-review,sage-asset,sage-asset-override,sage-profile-modify,sage-feedback}` and `.claude/agents/`
  CORE roster are not generated and are write-guard exempt — edit directly.)
