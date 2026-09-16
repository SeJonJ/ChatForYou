---
name: sage-init-local
description: Create or update only sage/project-profile.local.yaml for a developer joining an already bootstrapped SAGE project. Invoke as /sage-init-local or $sage-init-local.
---

# sage-init-local - Machine-Local SAGE Setup

Invoke as `/sage-init-local` (Claude) or `$sage-init-local` (Codex).

This skill is a **CORE framework bootstrap asset**. Self-overlay is unsupported.
It owns only `sage/project-profile.local.yaml`; it must never alter shared project
policy in `sage/project-profile.yaml`.

## Conversation language (mandatory)

Resolve it once, before the first turn, in this order:

1. an explicit `--lang ko|en` on this skill's invocation,
2. `interface.language` in `sage/project-profile.local.yaml`,
3. `ko`.

Conduct **every** question, proposal, progress note, warning and summary in that language.

Only the conversation takes it. Machine values are never translated — paths, globs, command
strings, component ids, strategy enums, statuses and the fixed schema keys. Phase 00–06 document
prose follows the cycle's `Document-Language:` marker, which is a **separate** decision and may
differ from the conversation. That document prose includes the **human-facing structure** — section
headings, list labels, table headers and checklist text — not just paragraphs; a Korean document
under English headings is the mixed state the marker exists to prevent. The two headings a parser
reads by their exact string, `## 5. Done Criteria` and `## 6. Done Criteria Revision Log`, stay
English in every language. Only `/sage-init` and `/sage-init-local` may persist a language
preference. Full rules: `docs/agent/language-policy.md`.

## State gate

Read `sage/project-profile.yaml` first and classify the repository before asking questions:

1. Shared profile missing: **BLOCKED**. Run `sage install`, then `/sage-init` or `$sage-init`.
2. Shared profile present but unbootstrapped: **BLOCKED**. Run `/sage-init` or `$sage-init`.
3. Shared profile malformed or semantically invalid: **BLOCKED**. Repair the shared profile first.
4. Valid bootstrapped shared profile: continue, whether the local profile is missing or already exists.

`bootstrapped shared` uses the same deterministic predicate as `sage generate`:

- `project.name` is non-empty.
- At least one of these is true: `components` is non-empty, or `risk` contains a
  non-empty L0-L3 classification glob.

Never treat file existence alone as bootstrap completion. `sage install` intentionally
places an empty shared template.

## Ownership boundary

- Read shared policy but do not change it.
- Create or update only `sage/project-profile.local.yaml`.
- `project-profile.yaml을 수정하지` 마십시오. Even a convenient policy correction belongs
  in `/sage-profile-modify`, not this flow.
- Allowed local sections are exactly `runtime.installed_hosts`,
  `capabilities.{claude,codex}`, `cross_model.enabled`,
  `knowledge_capture.{enabled,vault_path}`, and
  `models.available.{claude,codex}`.
- Never copy local values into `sage/project-profile.json`, manifests, plan documents,
  or committed generated assets.

## Interview

Conduct the interview in Korean, one topic per turn. Read the current local file first
and propose retained values when it already exists.

1. Detect installed host CLIs with `command -v claude` and `command -v codex`; propose
   `runtime.installed_hosts` and matching capability booleans. The user confirms.
2. Run `sage models --host <host>` for each installed host and let the user select the
   entries stored in `models.available.<host>`. Report each candidate's verification label.
3. Read shared `cross_model.policy`:
   - `required`: `cross_model.enabled` is forced to `true`. Do not offer `false`.
     Any explicit `false` request is **BLOCKED** because local state cannot weaken shared policy.
   - `recommended`: propose `true`; the user may choose `false`.
   - `off`: write `false` and do not offer enablement.
   - absent legacy policy: preserve existing shared `options.cross_model` behavior and allow a local override.
4. Ask whether Obsidian knowledge capture is available on this machine. When enabled,
   record this machine's `vault_path`; when disabled, set `enabled: false` and omit the path.

Show the complete local YAML and get explicit approval before writing it.

## Validation

After writing, run:

```text
sage validate --check --schema --kind all
sage doctor
```

If validation reports that a `required` policy was set to `false`, stop with **BLOCKED**
and correct the local file to `true`. Never bypass or downgrade that failure.

The local profile must remain ignored by Git. If `sage doctor` or `sage validate`
reports it as tracked or not ignored, remove it from the index or repair `.gitignore`
before handoff.
