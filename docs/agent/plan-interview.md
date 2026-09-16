# Plan Interview — requirements and design interview for Phase 00/01

Single source of truth for the interview a host runs **before** `sage-plan` (or `sage-cycle`)
writes the first documents of a new PDCA cycle (Phase 00 CONTEXT / 01 CONTENT), to draw the
user's intent out in detail. The questions are not a fixed script: they are driven by **the
sections 00/01 have to fill** (section rules: `docs/agent/pdca-templates.md` §"00 vs 01").

## When to run it, when to skip

- **Run it** when entering a new cycle and the user has stated the task in a single line. Do not
  write a shallow 00/01 from a one-sentence scope; secure the requirements with the core
  questions below first.
- **Skip or shorten** when the user has already supplied a detailed plan, or says they have said
  enough (`"충분"`, `"그만"`). Stop immediately and proceed with what you have. Do not interrogate.
- **Scope**: this is a planning interview, not a profile-configuration interview. Never re-ask
  values `sage-init` has already filled in (components, risk globs, cross_model, and so on).

## Output

1. **Always**: record the raw interview Q/A to `.sage/plan_interview.md` — the authoritative
   record of the intent the user gave.
2. The leader then **structures that record into the 00/01 documents** — not a transcription, but
   placed according to the split rule where 00 holds context and 01 holds detail
   (`pdca-templates.md`).
3. **On completion**, when Obsidian is in use and `.sage/plan_interview.md` exists, `sage-team`
   writes this record to the vault as a separate `기획 인터뷰` note via
   `sage knowledge write-back`, honouring the single write path. Without Obsidian,
   `.sage/plan_interview.md` is the final artifact.

## Core questions (always asked; adapt only the wording to the task)

Each answer anchors to the section on the right.

| # | Question | Section it fills |
|:--:|---|---|
| 1 | **Platform and environment** — where does this run? (web / mobile Android, iOS or both / desktop / server / CLI …) | 00 scope · 01 requirements |
| 2 | **Core features** — what must be included, and in what priority? | 01 requirements |
| 3 | **Data and integrations** — are APIs, databases or external services involved, and which? | 01 data and API |
| 4 | **Constraints and cautions** — security, performance, regulatory or existing-code constraints, and what must not be done | 00 risk · 01 constraints |
| 5 | **Definition of done** — what determines that this is finished? (the scenario the user will check) | seed for the 01 acceptance matrix |

## Adaptive follow-ups (derived from the answers)

Dig only as far as the core answers warrant. For example:

- API said yes → which API, what authentication, rate limits, fallback on failure?
- Mobile → minimum supported OS version, offline requirements?
- Security or secrets mentioned → is this an L3 candidate (check against the risk globs), and
  what are the storage and masking requirements?
- Several features called mandatory → which are in this cycle's scope and which follow later?

Map every follow-up answer to 00 (context, risk) or 01 (requirements, data, acceptance) as well.

## How to run it

- **Ask in batches** — a few related questions at a time. The form differs per host: claude asks
  structured questions, codex asks them sequentially. Only the form differs, never the content.
- Finish once the five core answers plus whatever the user considered worth expanding are in.
  If the user says they have had enough, stop immediately.
- The interview must complete before the leader handoff; the existing "confirm scope first" gate
  still applies.

## Handoff

The leader authors 00/01 with `.sage/plan_interview.md` as input. When the interview is empty or
the user ended it at once, write 00/01 from the minimum information secured and mark every
undecided item in the document as TBD or an explicit assumption. Never paper over a gap.
