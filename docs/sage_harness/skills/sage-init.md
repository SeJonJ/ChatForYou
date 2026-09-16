---
id: sage-init
kind: skill
# CORE skill (neutral). Project specifics come from profile, not this spec.
# CORE framework bootstrap asset: hand-shipped by `sage install`, NOT manifest-tracked.
# The manifest/claims/validate loop is reserved for project-authored skills
# (spec + claims + render hash) created via the generate/extract flow.
---
## intent
Bootstrap a SAGE project through conversation: interview the user, then author both
ownership layers — shared project policy in `sage/project-profile.yaml` and this
machine's capabilities in `sage/project-profile.local.yaml`. The profile is
hand-authored SSOT, not a generated artifact, so it is edited directly and never
through `sage generate`. Runs once per project; `/sage-init-local` handles a developer
joining later and `/sage-profile-modify` handles every change after bootstrap.

## when_to_use
- At the start of a SAGE project, when the shared profile exists but is unbootstrapped
- After `sage install` places the empty shared template
- When the user says "/sage-init" (Claude) or "$sage-init" (Codex)

## procedure
1. Read context in order: `sage/project-profile.yaml` (confirm the bootstrap predicate is
   false), `sage/project-profile.local.yaml` (only if a partial prior attempt exists),
   `AGENT_GUIDE.md`, `docs/agent/bootstrap-authoring.md`, `docs/agent/language-policy.md`,
   and the repository itself — list top-level directories and detect stack and build files
   so values can be proposed rather than asked blind.
2. Apply the state gate. Bootstrap is complete only when `project.name` is non-empty **and**
   at least one of these holds: `components` is non-empty, or `risk` carries a non-empty
   L0–L3 classification glob. This is the same deterministic predicate `sage generate`
   enforces. File existence alone is never completion, because `sage install` deliberately
   ships an empty template. If the predicate is already true, stop with **BLOCKED** and route
   to `/sage-init-local`; shared-policy changes belong to `/sage-profile-modify`.
3. Resolve the conversation language once: an explicit `--lang` on the invocation, then
   `interface.language` in the local profile, then `ko`. Only the conversation takes that
   language. Machine values written into the profile stay language-neutral — paths, globs,
   command strings, component ids, strategy enums and the fixed schema keys are never
   translated. Human-facing message values such as `desktop_block_hint` are prose, not
   machine tokens, and follow the conversation language.
4. Interview one topic per turn, following the topic order and the shared interview sets in
   `bootstrap-authoring.md` — a single source, so `/sage-profile-modify` never drifts from
   this flow. Propose concrete values inferred from the Step 1 scan rather than asking blind,
   and name the evidence in one short clause so the user can judge the inference.
5. Author both layers. Shared project policy goes to `sage/project-profile.yaml`; machine
   capabilities and private paths go to `sage/project-profile.local.yaml`. Never compile a
   local value into JSON, a manifest, a plan document or a committed generated asset.
6. Fill values; never add or remove schema keys. The schema is fixed and only values change.
   A new top-level key, or any key added under `risk`, `pdca` or `output_contract`, is a
   schema violation `sage validate` rejects.
7. This skill and `/sage-init-local` are the only flows permitted to persist an interface
   language preference, and then only to the local profile and only after explicit user
   approval. It is never written to the shared profile.
8. Present the complete YAML for both layers and get explicit approval before writing.
9. Validate: `sage validate --check --schema --kind all` and `sage doctor`. Never bypass a
   FAIL — it is the guardrail working. Then hand off, asking whether asset generation should
   run automatically or manually.

## advisory_scope
- role_boundary: authors the profile (SSOT) directly, NOT via generate (that is for assets);
  does not add/remove schema keys; does not bypass a validate FAIL; does not overwrite an
  established shared profile (routes to /sage-init-local when already bootstrapped); does not
  edit generated artifacts
- persists: interface language preference to the local profile only, after user approval
- uses: sage validate / sage doctor / sage generate (asset handoff),
  bootstrap-authoring.md (shared interview sets), language-policy.md (language resolution)
- convention_doc: AGENT_GUIDE.md
- self_overlay: unsupported; this gate-bearing CORE skill is not in `COMPOSE_ALLOWED`

## runtime_bindings
- claude: .claude/skills/sage-init/SKILL.md (repo — Claude Code auto-discovers)
- codex:  $CODEX_HOME/skills/sage-init/SKILL.md or .codex/skills/sage-init/SKILL.md (explicit global or project-local install scope)

## drift_checks
- conformance: the bootstrap predicate in procedure step 2, the language resolution order in
  step 3, and the validate re-run in step 9 must be present
