---
id: sage-init-local
kind: skill
# CORE skill (neutral). Project specifics come from profile, not this spec.
# CORE framework bootstrap asset: hand-shipped by `sage install`, NOT manifest-tracked.
# The manifest/claims/validate loop is reserved for project-authored skills
# (spec + claims + render hash) created via the generate/extract flow.
---
## intent
Create or update only `sage/project-profile.local.yaml` for a developer joining an already
bootstrapped SAGE project. It reads shared policy but never changes it: this machine's
capabilities, private paths and personal preferences are the whole of its ownership. The
machine-local counterpart to /sage-init (first authoring) and /sage-profile-modify (shared
policy changes).

## when_to_use
- When a developer joins a project whose shared profile is already bootstrapped
- When this machine's installed hosts, available models, or vault availability change
- When the user says "/sage-init-local" (Claude) or "$sage-init-local" (Codex)

## procedure
1. Read `sage/project-profile.yaml` first and classify the repository before asking anything:
   - Shared profile missing → **BLOCKED**. Run `sage install`, then `/sage-init`.
   - Shared profile present but unbootstrapped → **BLOCKED**. Run `/sage-init`.
   - Shared profile malformed or semantically invalid → **BLOCKED**. Repair it first.
   - Valid bootstrapped shared profile → continue, whether or not a local profile exists.
   `bootstrapped` uses the same deterministic predicate as `sage generate`: `project.name` is
   non-empty, and at least one of `components` non-empty or a non-empty L0–L3 `risk`
   classification glob. File existence alone is never bootstrap completion.
2. Resolve the conversation language once: an explicit `--lang` on the invocation, then
   `interface.language` in the existing local profile, then `ko`.
3. Interview one topic per turn. Read the current local file first and propose retained
   values when it already exists.
   1. Detect installed host CLIs with `command -v claude` and `command -v codex`; propose
      `runtime.installed_hosts` and the matching capability booleans for confirmation.
   2. Run `sage models --host <host>` for each installed host and let the user select the
      entries stored in `models.available.<host>`, reporting each candidate's verification label.
   3. Read shared `cross_model.policy`: `required` forces `cross_model.enabled` to `true` and
      any explicit `false` is **BLOCKED**, because local state cannot weaken shared policy;
      `recommended` proposes `true` and allows `false`; `off` writes `false` and does not offer
      enablement; an absent legacy policy preserves existing shared `options.cross_model`
      behavior and allows a local override.
   4. Ask whether Obsidian knowledge capture is available on this machine. When enabled,
      record this machine's `vault_path`; when disabled, set `enabled: false` and omit the path.
   5. Ask for the interface language preference. Store `interface.language` only on explicit
      user approval; leaving it unset means `ko`.
4. Respect the ownership boundary. Read shared policy but never change it. Even a convenient
   policy correction belongs to `/sage-profile-modify`, not this flow. The allowed local
   sections are exactly `runtime.installed_hosts`, `capabilities.{claude,codex}`,
   `cross_model.enabled`, `knowledge_capture.{enabled,vault_path}`,
   `models.available.{claude,codex}` and `interface.language`. Never copy a local value into
   `sage/project-profile.json`, a manifest, a plan document or a committed generated asset.
5. Show the complete local YAML and get explicit approval before writing it.
6. Validate: `sage validate --check --schema --kind all` and `sage doctor`. If validation
   reports that a `required` policy was set to `false`, stop with **BLOCKED** and correct the
   local file to `true`; never bypass or downgrade that failure.
7. The local profile must stay ignored by Git. If `sage doctor` or `sage validate` reports it
   as tracked or not ignored, remove it from the index or repair `.gitignore` before handoff.

## advisory_scope
- role_boundary: owns `sage/project-profile.local.yaml` only; never alters shared project
  policy; does not bootstrap (routes to /sage-init when unbootstrapped); does not bypass a
  validate FAIL; does not weaken a shared `required` policy
- persists: interface language preference to the local profile only, after user approval
- uses: sage models / sage validate / sage doctor, language-policy.md (language resolution)
- convention_doc: AGENT_GUIDE.md
- self_overlay: unsupported; this gate-bearing CORE skill is not in `COMPOSE_ALLOWED`

## runtime_bindings
- claude: .claude/skills/sage-init-local/SKILL.md (repo — Claude Code auto-discovers)
- codex:  $CODEX_HOME/skills/sage-init-local/SKILL.md or .codex/skills/sage-init-local/SKILL.md (explicit global or project-local install scope)

## drift_checks
- conformance: the four-way state gate in procedure step 1, the allowed local section list in
  step 4, and the validate re-run in step 6 must be present
