---
id: sage-plan-fast
kind: skill
---
## intent
Collect all mandatory Fast inputs, author the composite 00 through 04 plan, and open a bound audited Fast run before source edits.
## when_to_use
- When an enabled Fast Cycle needs its mandatory inputs and composite Phase 00 before implementation.
## procedure
0. Resolve the conversation language once: an explicit `--lang ko|en` on this skill's
   invocation, then `interface.language` in `sage/project-profile.local.yaml`, then `ko`.
   Every question, proposal, progress note, warning and summary uses it; machine values
   and Phase 00–06 `Document-Language:` prose do not. Document prose includes section headings
   and list labels, except `## 5. Done Criteria` and `## 6. Done Criteria Revision Log`, which a
   parser reads by their exact string. See `docs/agent/language-policy.md`.
1. Collect Fast level, lens count, and one-line reason before any write; classify actual L2/L3 risk separately.
2. Author one composite Phase 00 with `Done-Criteria-Revision: 1`, a concrete
   `### Done Criteria` list of `[ ]` outcomes, exact embedded Phase 00 through 04
   sections, the separate Document Mapping checklist, and acceptance trace.
   Settle the cycle's document language in the same pass: Phase 00's `Document-Language:`
   line if that document exists, else `document_language` in `.sage/cycle.json`, else ask
   the user and record it with `sage cycle set <stem> --create --document-language <ko|en>`.
   The composite document carries that one line outside any code fence and is written in
   that language, fixed for the whole Fast run — see `docs/agent/language-policy.md`.
3. Declare the verified stem and run `sage fast-cycle open`; hand off only after its audit run is bound.
4. A cycle already past Phase 00 converts instead of re-authoring: `sage fast-cycle convert`,
   available only when `pdca.fast_cycle.standard_transition.enabled` is true. The confirmation
   token, reason and approver come from the user in that turn and are never inferred. The
   conversion writes no document; the audit record is the only evidence of the transition, and
   the run waives only the pre-implementation phases its snapshot can show.
## advisory_scope
- self_overlay: unsupported; Fast state transitions are gate-bearing and have no independent overlay oracle.
## runtime_bindings
- claude: .claude/skills/sage-plan-fast/SKILL.md
- codex: $CODEX_HOME/skills/sage-plan-fast/SKILL.md or .codex/skills/sage-plan-fast/SKILL.md
