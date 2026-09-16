---
id: sage-team-fast
kind: skill
---
## intent
Implement and verify an open Fast run, bind exact lens review receipts, write 05 and 06, perform standard completion work, and close before clearing.
## when_to_use
- When a composite Fast Plan has a clean active Fast audit run and is ready for implementation.
## procedure
0. Resolve the conversation language once: an explicit `--lang ko|en` on this skill's
   invocation, then `interface.language` in `sage/project-profile.local.yaml`, then `ko`.
   Every question, proposal, progress note, warning and summary uses it; machine values
   and Phase 00–06 `Document-Language:` prose do not. Document prose includes section headings
   and list labels, except `## 5. Done Criteria` and `## 6. Done Criteria Revision Log`, which a
   parser reads by their exact string. See `docs/agent/language-policy.md`.
1. Verify the exact stem, composite plan, audit state, ownership, actual risk, Fast level, rounds, and lenses.
2. Implement and verify while updating composite Done Criteria. Replanning increments its
   revision and records a reason plus affected virtual phases, which are rerun. Require all
   criteria resolved, then run independent review with exact per-round lens receipts and bind APPROVED Phase 05.
3. Write bound Phase 06, complete write-back, retro, and snapshot, then close the Fast audit before clearing the cycle.
## advisory_scope
- self_overlay: unsupported; Fast state transitions are gate-bearing and have no independent overlay oracle.
## runtime_bindings
- claude: .claude/skills/sage-team-fast/SKILL.md
- codex: $CODEX_HOME/skills/sage-team-fast/SKILL.md or .codex/skills/sage-team-fast/SKILL.md
