---
id: sage-cycle-fast
kind: skill
---
## intent
Delegate a complete explicitly enabled Fast Cycle to sage-plan-fast and sage-team-fast without duplicating their state transitions.
## when_to_use
- When the user explicitly requests the profile-enabled Fast Cycle for an L2/L3 change.
## procedure
0. Resolve the conversation language once: an explicit `--lang ko|en` on this skill's
   invocation, then `interface.language` in `sage/project-profile.local.yaml`, then `ko`.
   Every question, proposal, progress note, warning and summary uses it; machine values
   and Phase 00–06 `Document-Language:` prose do not. Document prose includes section headings
   and list labels, except `## 5. Done Criteria` and `## 6. Done Criteria Revision Log`, which a
   parser reads by their exact string. See `docs/agent/language-policy.md`.
1. Confirm shared policy enables Fast Cycle and resolve the current cycle state.
2. Delegate composite planning and audit open to `sage-plan-fast` — a fresh Fast run from a
   composite Phase 00, or, for a cycle already past Phase 00 and only when
   `pdca.fast_cycle.standard_transition.enabled` is true, an explicitly confirmed conversion.
3. Delegate implementation, review, completion evidence, audit close, and cycle clear to `sage-team-fast`.
## advisory_scope
- self_overlay: unsupported; Fast state transitions are gate-bearing and have no independent overlay oracle.
## runtime_bindings
- claude: .claude/skills/sage-cycle-fast/SKILL.md
- codex: $CODEX_HOME/skills/sage-cycle-fast/SKILL.md or .codex/skills/sage-cycle-fast/SKILL.md
