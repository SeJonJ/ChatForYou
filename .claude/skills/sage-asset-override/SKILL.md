---
name: sage-asset-override
description: "Author a project-local overlay for an eligible non-gate CORE worker without editing the CORE render. Gate-relaxation hits and framework targets are hard stops."
---

# sage-asset-override — Conversational CORE Overlay Authoring

This skill authors a **project-local overlay** for an **eligible existing CORE worker**. It is
the fourth conversational entry point:
- `/sage-init` — first authoring (0→1).
- `/sage-profile-modify` — edit existing profile / governance values.
- `/sage-asset` — add/modify governance assets (hook/agent/skill) via spec→generate.
- **`/sage-asset-override` — overlay an existing CORE agent/skill (this skill).**

Authoritative protocol: `docs/agent/bootstrap-authoring.md`. Rules: `AGENT_GUIDE.md`.

Do not edit this CORE render directly (the write-guard blocks it and `sage install --force` overwrites it). Self-overlay is unsupported: `skills/sage-asset-override` is not in `COMPOSE_ALLOWED`. This skill may author overlays only for the explicitly eligible worker agents listed below.

> This skill is a **CORE framework bootstrap asset** — hand-shipped by `sage install`,
> NOT manifest-tracked. Its reference spec lives at
> `docs/sage_harness/skills/sage-asset-override.md`. Claude reads this render from the
> repo (`.claude/skills/sage-asset-override/`); Codex reads it from the user-global
> skills dir (`$CODEX_HOME/skills/sage-asset-override/`).

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

## Why overlays exist

CORE agent/skill renders (leader, reviewer, sage-review, …) are **hand-shipped** and
`sage install --force` overwrites them wholesale. Editing them directly means the next
SAGE upgrade silently discards your change — and the write-guard now blocks that edit.
An **overlay** at `sage/asset_overrides/{agents,skills}/<id>.md` is the supported place
for project-local customization: `sage install` never ships it, so `--force` preserves it,
and SAGE materializes eligible overlays into the installed CORE render.

## What this skill is NOT

- **Not for new assets.** Creating a *new* hook/agent/skill is `/sage-asset` (spec→generate,
  the write-guard forces that path). This skill only overlays an **existing CORE roster id**.
- **Not `sage generate`.** An overlay is hand-authored (like the profile), written directly
  and not generated. There is no manifest/claims stamp for it.
- **Not a gate loophole.** An overlay *adds* project guidance on top of CORE; it must never
  relax AGENT_GUIDE, phase, review, or verification gates.

## Hard rules

- **Executable eligibility only.** The id must be listed by `sage.overlay_classify.COMPOSE_ALLOWED`.
  Currently that is `agents/implementer-a` and `agents/implementer-b`. Framework and gate-bearing
  assets are blocked until an executable independent oracle is registered.
- **Gate-relaxation is a stop condition.** If the drafted overlay reads like it skips/bypasses
  a phase, review, verification, or the gate — or tells the agent to ignore AGENT_GUIDE —
  stop and revise it. Explicit confirmation is not a bypass.
- **The user owns intent.** Show the CORE base, propose the overlay, get approval before writing.

## Step 0 — Read context first

Read, in order, before asking anything:
1. `sage/project-profile.yaml` — if **not bootstrapped** (`project.name` empty OR risk globs
   and components both unset), stop and route to `/sage-init`. Overlays presuppose a set-up project.
2. `AGENT_GUIDE.md` — the gates an overlay must not relax.
3. The **target CORE base**: `docs/sage_harness/agents/<id>.md` or its installed render.

## Step 1 — Identify the target (one focused turn)

Pin **kind** and **id** from the user's intent (or a retro/absorb proposal's `asset_id` hint).
Confirm it is currently compose-eligible (see Hard rules). A new asset routes to `/sage-asset`;
a framework or gate-bearing target stops until an independent oracle exists.

## Step 2 — Show the CORE base

Show the current CORE base content relevant to the requested change, so the user sees what
the overlay layers on top of (overlays add; they do not replace the whole render).

## Step 3 — Interview the deviation

Ask what should change for **this project only**, and **why** (e.g. a recurring retro
pattern the CORE render can't encode because it must stay project-neutral).

## Step 4 — Draft + gate-relaxation check (mandatory)

1. Draft the overlay text.
2. Check the draft with the deterministic `sage.overlay_lint.scan_text` rules before writing;
   `sage validate --strict` runs the same scanner after materialization and fails with
   `overlay-gate-relaxation` for suspect text.
3. **If it looks like it relaxes a gate, do not write it.** Revise or remove the text.
   Explicit user confirmation cannot downgrade this hard stop.

## Step 5 — Write the overlay

Write `sage/asset_overrides/agents/<id>.md` **directly** (create the dir if needed).
No `sage generate`. Keep it additive — project guidance on top of CORE, not a rewrite.

## Step 6 — Sync, then re-validate

```
sage sync-overlays
sage validate --schema --kind all --strict
```
Confirm the file exists and no `overlay-gate-relaxation` failure remains. Note the overlay survives
`sage install --force`. SAGE materializes the eligible overlay into the CORE render during sync,
then `sage validate` gates it. Gate-bearing assets without an independent oracle are
not yet overlay-eligible (validate reports them).

## Done

The overlay is complete when it captures the project-local intent, the gate-relaxation check
ran with no hit, the file is at `sage/asset_overrides/agents/<id>.md`, and
`sage validate --strict` passes the overlay checks. The next run of that agent applies CORE first,
then the overlay.
