---
name: sage-plan
description: "Plan the first half of a SAGE PDCA cycle (Phases 00–02) — verifies gate conditions, invokes the leader to author plan docs, and distributes file ownership before any implementation begins. Hands back an ownership map that /sage-team (03–06) picks up. Invoke when the user says /sage-plan (Claude) or $sage-plan (Codex), 기획, 설계 시작, 계획 세워, plan a feature, or wants to scope a change before implementation."
---

# sage-plan — SAGE PDCA Planning (Phases 00–02)

Invoke as `/sage-plan` (Claude) or `$sage-plan` (Codex).

Do not edit this CORE render directly (the write-guard blocks it and `sage install --force` overwrites it).
- overlay: optional `sage/asset_overrides/skills/sage-plan.md` has project-local priority over this CORE render and is not shipped by `sage install`; it must not relax AGENT_GUIDE, phase, review, or verification gates (they stay floored by independent oracles). Put broad project rules in profile/conventions and create genuinely new project assets with `/sage-asset`.

This skill owns the **planning half** of a PDCA cycle: it produces the plan doc
(Phases 00–02) and the file-ownership map, then hands back. Implementation through
completion (03–06) is `/sage-team`'s job; `/sage-cycle` is the umbrella that runs
both in sequence.

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

## Read these first (mandatory, in order)

1. `docs/sage_harness/skills/sage-plan.md` — authoritative spec: intent, procedure, drift_checks
2. `AGENT_GUIDE.md` — PDCA phases, risk gate, phase-first rule
3. `sage/project-profile.yaml` — project.name, components, paths.plan_docs

## Gate check (do this before anything else)

Confirm `sage/project-profile.yaml` is bootstrapped:
- `project.name` is non-empty
- `risk` section has L0/L1/L2 globs set
- `components` list is non-empty

If any check fails: stop and say "Profile is not bootstrapped. Run `/sage-init` to
set up the project profile before starting a PDCA cycle."

On a resumed session with a user-supplied context packet, run
`sage context restore --snapshot <path>` and read the generated briefing before
resolving the planning stage. A stale or invalid packet is a hard stop; never claim
that hidden Claude conversation state was restored.

## Step 1 — Scope + planning interview

Get a one-sentence task description if the user hasn't given one
("What is the feature or change we are implementing in this PDCA cycle?"), then **run the
planning interview** per `docs/agent/plan-interview.md`: ask the core questions
(platform / core features / data·API / constraints / done-criteria) plus adaptive
follow-ups, anchored to what Phase 00/01 need. **Do not write a shallow plan from the
one-liner.** Record the Q&A verbatim to `.sage/plan_interview.md` — this is the input the
leader authors 00/01 from. Skip/shorten only if the user already gave rich detail or says
"enough" (record what you have). The interview elicits *requirements*; it does NOT re-ask
profile config (`components`/`risk`/`cross_model`) that `sage-init` already settled.

Ask which language this cycle's Phase 00–06 documents should be written in (`ko` or `en`)
unless an existing Phase 00 for this stem already carries a `Document-Language:` line — that
line wins and is not re-asked. This is the document language, not the language you talk in;
see `docs/agent/language-policy.md`.

Do not proceed to leader handoff until scope + interview are confirmed.

## Step 2 — Invoke the leader

Before leader handoff, run the configured knowledge scan when it is enabled:

1. If `knowledge_capture.scan_before_dev: true` and `knowledge_capture.vault_path`
   is set, create `.sage/knowledge_query.txt` containing the task scope.
2. Run:
   ```bash
   python -m sage knowledge scan --query-file .sage/knowledge_query.txt
   ```
3. Read `.sage/knowledge_scan.md`. It is refreshed on every run and starts with
   `status: ran`, `status: n/a`, or `status: error`.
   - `ran`: pass the matched context to the leader.
   - `n/a` or `error`: tell the leader no usable vault context was available and
     continue; do not read a previous cycle's scan as current context.

Hand off to the `leader` agent with this briefing:
- Task scope + interview: the one-sentence description **plus `.sage/plan_interview.md`** —
  the leader authors 00/01 FROM the interview record, structuring it into 00 CONTEXT /
  01 CONTENT (do not transcribe; mark unresolved items TBD, do not hide gaps)
- Profile location: `sage/project-profile.yaml`
- Plan docs directory: the `paths.plan_docs` value from the profile
- Knowledge scan: `.sage/knowledge_scan.md` status and matches, if `status: ran`
- Required: author a plan doc covering the task scope, distribute file ownership
  to `implementer-a` and `implementer-b` by component, and state the integration
  point
- Required: choose one markdown basename as the cycle identity and declare the
  exact same `Cycle-Stem: <basename>` once near the top of every 00–02 document.
- Required: declare the agreed document language once per document as a standalone
  `Document-Language: <ko|en>` line outside any code fence, and write the prose in that
  language. It is fixed for the whole cycle — the pre-implementation gate blocks when
  documents of one cycle disagree. After the stem is chosen, mirror it for resumed sessions
  with `sage cycle set <stem> --document-language <ko|en>`.
- Required: the 00 base plan must record a `Risk Level: Lx` line — L1/L2/L3, the
  higher of the user-declared level and the risk the change globs imply. This is the
  durable per-cycle tier used by later gates and knowledge write-back. Fill exactly one
  declaration with a real `L1`/`L2`/`L3`; never leave the `<L1|L2|L3>` placeholder.
- Required: record `Done-Criteria-Revision: 1` and exactly one `## 5. Done Criteria`.
  Translate confirmed outcomes into concrete `[ ]` items. Planning does not prove
  implementation completion, so do not pre-check them and do not leave TODO.

## Step 3 — Verify plan doc exists

After the leader completes, confirm the plan doc file exists and is non-empty.
If the leader did not create it, block and ask the leader to retry.

Confirm every 00–02 markdown basename equals its single `Cycle-Stem` declaration.
Missing, duplicate, mismatched, or multiple candidate stems are a hard stop; do
not select a recent document as a fallback.

Also confirm the 00 base plan carries exactly one filled `Risk Level: L1`/`L2`/`L3`
line outside code fences (not the `<L1|L2|L3>` placeholder). If it is missing,
unfilled, malformed, or duplicated, block and ask the leader to repair Phase 00 before
handoff. Later-phase and governed source writes fail closed without this declaration.
Also reject a missing/malformed/duplicate Done Criteria section, a missing revision 1,
an empty list, or placeholder-only criteria before handoff.

After all stem and risk checks pass, declare the verified identity:

```bash
sage cycle set <stem>
```

Do not declare before these checks. If the command reports that `SAGE_CYCLE_STEM`
still wins, surface that warning and resolve the environment declaration before handoff.

## Step 4 — Report ownership map

Present the ownership map to the user:
```
Planning complete for: [task scope]
Plan doc: [path]
implementer-a owns: [component id / paths]
implementer-b owns: [component id / paths]
Integration point: [where the two connect]
```

Confirm the user is ready to proceed to implementation. The next step is
`/sage-team` (drives 03–06); or, if the user started here via `/sage-cycle`,
that umbrella continues into `/sage-team` automatically.

## Step 5 — State the phase flow (so the user knows what comes next)

Before handing back, tell the user the phase order and what each phase is *for*,
because 03/04 are easy to misorder. This skill produces 00–02; the rest follow:

- **00–02** (now, by leader) — base plan / requirements / design. Must exist
  before any L2/L3 code edit (`pre-implementation-gate` blocks otherwise).
- **03 Implementation** — open/update the 03 document **before source edits** with
  file ownership, implementation checklist, verification plan, and Phase-01
  acceptance IDs. Then write the code **and the unit tests**, and complete 03 with
  changed files, acceptance trace, and build/test results.
- **04 Analyze** — leader + qa review the result: design↔implementation gap +
  **test coverage** (qa) + acceptance evidence (`PASS`/`FAIL`/`NOT TESTED`/`N/A`).
  No verdict here. (Writing tests is 03's job; 04 judges their sufficiency.)
- **05 Expert Review** — independent reviewer (cross-model when enabled) issues
  the verdict (APPROVED/FAIL/BLOCKED). Required `FAIL` blocks APPROVED. `NOT TESTED`
  also blocks unless an exact active L3 waiver preserves it as residual evidence;
  never convert it to PASS. Run `/sage-review` here.
- **06 Report** — only after 05 records APPROVED.

When `context_management.compaction.enabled: true`, run
`sage context snapshot --cycle-stem <stem> --phase <id>` after each completed
00, 01, and 02 boundary and report every packet path. Do not infer a boundary from
file presence alone and do not launch or switch hosts.

---

> This skill is a **CORE framework bootstrap asset**: hand-shipped by `sage install`,
> NOT a manifest-tracked skill (no claims file, no render hash, not gated by
> `sage validate`/`sage asset-check`). Its reference spec lives at
> `docs/sage_harness/skills/sage-plan.md`. To change it, edit the framework
> template, not via `sage generate`. Deploy location is runtime-specific: Claude
> reads it from the repo (`.claude/skills/sage-plan/`); Codex reads it from the
> user-global skills dir (`$CODEX_HOME/skills/sage-plan/`).
