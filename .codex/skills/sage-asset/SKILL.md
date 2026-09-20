---
name: sage-asset
description: "Add or modify a SAGE governance asset (hook / agent / skill) through conversation — interview intent, author the spec and renders (BOTH claude and codex), then run sage generate to extract+register. Invoke when the user says /sage-asset, add an agent/skill/hook, modify an asset, 자산 추가, 에이전트/스킬/훅 추가·수정, or after bootstrap to extend the team/roster."
---

# sage-asset — Conversational SAGE Asset Authoring

This skill turns user intent into a SAGE-conformant **asset** (hook / agent / skill)
**through conversation**, then hands off to the deterministic backend
(`sage generate` / `sage validate`). It is the post-bootstrap counterpart to
`/sage-init`: sage-init fills the profile; sage-asset adds/modifies assets.

Authoritative protocol: `docs/agent/bootstrap-authoring.md` (§5 Asset additions).
Rules: `AGENT_GUIDE.md`. The user supplies intent and approves; you author to the
spec/render and run the backend. This is not a turnkey generator.

To **customize an existing CORE agent/skill** (leader/reviewer/sage-review/…) for this
project without creating a new asset, use `/sage-asset-override` instead — it writes a
project-local overlay (`sage/asset_overrides/**`, `--force`-safe), not a new spec.

Do not edit this CORE render directly (the write-guard blocks it and `sage install --force` overwrites it). Self-overlay is unsupported: `skills/sage-asset` is not in `COMPOSE_ALLOWED`. Put project rules in profile/conventions and use this skill to create genuinely new project assets.

> This skill is a **CORE framework bootstrap asset** — hand-shipped by `sage install`,
> NOT manifest-tracked. Its reference spec lives at
> `docs/sage_harness/skills/sage-asset.md`. Claude reads this render from the repo
> (`.claude/skills/sage-asset/`); Codex reads it from the user-global skills dir
> (`$CODEX_HOME/skills/sage-asset/`).

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

## Hard rules

- **Never edit generated artifacts to "change" an asset.** The write-guard blocks
  direct edits of `.claude/.codex/{agents,hooks,skills}` (except CORE bootstrap
  renders). To change an asset: edit its spec (hook) or render (agent/skill) source,
  then re-run `sage generate`.
- **codex 함께 — author BOTH runtimes.** Every agent/skill gets a claude render AND
  a codex render. `sage generate --kind agent/skill` requires both (reverse-extract
  derives the required-claims from their intersection); a single render fails closed.
- **Never bypass a `sage validate` FAIL.** Fix the spec/render it points to and re-run.
- **The user owns intent.** Author per the rules, present consequential choices, get
  approval before handoff.

## Step 0 — Read context first

Read, in order, before asking anything:
1. `sage/project-profile.yaml` — confirm bootstrapped (`project.name` set). If not,
   stop and route to `/sage-init` first.
2. `AGENT_GUIDE.md` — asset model, write-guard, safety boundaries.
3. `docs/agent/bootstrap-authoring.md` §5 — the asset-addition loop.
4. The existing assets under `docs/sage_harness/{hooks,agents,skills}/` so you don't
   duplicate or collide with an existing id.

## Step 1 — Identify the operation (one focused turn)

Ask which operation, and for which kind:
- **Add** a new hook / agent / skill, or **Modify** an existing one.
- If modify: which asset id (list the existing ones from Step 0).

## Step 2 — Interview for intent (conversation, not a form)

Drive the conversation one topic per turn; propose from context, don't ask blind.
Gather, for the asset:
- **id** (kebab-case, unique). For a global codex skill, remember it deploys under
  the project `prefix` namespace (`$prefix-$id`) to avoid global collisions.
- **intent** — one sentence: what it governs / does.
- **advisory_scope** — owns (paths/role), role_boundary (what it does NOT do), the
  convention doc it follows.
- **runtime_bindings** — model (agent), trigger/when-to-use (skill), event+matcher (hook).
- For **hook** only: the deterministic check logic (it is pure-function, generate
  produces the artifact).

## Step 3 — Author the source

### hook (deterministic)
1. Author the spec `docs/sage_harness/hooks/<id>.md` + the canonical
   `sage_harness/hooks/<id>_core.py` (pure check).
2. For a new hook, hand off: `sage generate --kind hook --id <id> --write --target both`.
   This validates both bindings and atomically registers settings, adapters, shims, and manifest.

### agent / skill (interpretive — author BOTH renders)
1. Author the **claude render**:
   - agent → `.claude/agents/<id>.md` (frontmatter `name`+`description` + body)
   - skill → `.claude/skills/<id>/SKILL.md`
2. Author the **codex render** (codex 함께 — same intent, codex idioms):
   - agent → `.codex/agents/<id>.md`
   - skill → `.codex/skills/<id>/SKILL.md`
3. Keep the two renders semantically equivalent — their **intersection** becomes the
   required-claims; per-runtime wording differences become allowed variation.

For **modify**: edit the existing render(s) (agent/skill) or spec+core (hook). Do not
touch the generated `docs/sage_harness/.../<id>.md` spec for agent/skill — it is
re-derived from the renders by the next step.

## Step 4 — Handoff to the deterministic backend

### agent / skill
```
sage generate --kind <agent|skill> --id <id> --write
```
This reverse-extracts the spec + claims from the two renders and registers the asset
in the manifest (render_hash for both claude and codex). It **fails closed** if either
render is missing — author both first.

For a **skill** that codex must discover, also deploy it to the codex global dir:
```
sage generate --kind skill --id <id> --write --deploy-codex
```
(Codex does not auto-discover repo-scoped skills; `--deploy-codex` copies the repo
canonical `.codex/skills/<id>/SKILL.md` to `$CODEX_HOME/skills/<prefix>-<id>/`. The
manifest still tracks only the repo canonical — the global copy is a discovery cache.)

### hook
```
sage generate --kind hook --id <id> --write --target both
```

## Step 5 — Verify

```
sage validate --check --schema --kind all
```
This checks spec/claims hash staleness, manifest schema, and render conformance lint
(required-claims present, no forbidden-policy contradictions). A FAIL points at the
spec/render/claims to fix — fix and re-run, never bypass. Then `sage doctor` to confirm
option dependencies and (on a codex host) codex skill global-deployment freshness.

## Done

The asset is added/modified when its render(s)/spec reflect the user's intent, the
generate handoff registered it (manifest stamped), and `sage validate` passes (or only
WARNs the user accepted). The asset now participates in the governance closed loop.
