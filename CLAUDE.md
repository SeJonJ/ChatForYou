# CLAUDE.md

This document is a thin wrapper that defines Claude-specific execution overrides only.
All common rules, workflow, and output contracts are governed solely by `AGENT_GUIDE.md`.

## Mandatory Read (required at session start)

Before responding to any request, read the following files in order. Do not skip.

1. `AGENT_GUIDE.md` — single source of truth for common rules, workflow, and output contract
2. `.local/local_agent_guide.md` — local-only configuration (read if it exists)

## Start Order
1. `AGENT_GUIDE.md`
2. `.local/local_agent_guide.md` (if it exists)
3. Relevant `plan_docs/00-base_plan/YYYY/MM/[feature]_plan.md`
4. Relevant component convention docs
   - `docs/springboot_backend.md`
   - `docs/nodejs_frontend.md`
   - `docs/chatforyou_desktop.md`
   - `docs/git_commit_convention.md`
5. Claude runtime assets
   - `.claude/agents/*`
   - `.claude/skills/*`

## AGENT_GUIDE Compliance Gate (applies to all direct requests)

AGENT_GUIDE rules apply equally when implementation is requested directly,
without going through `/chatforyou-dev-team`.

### Before writing any implementation code (required declaration)
Explicitly state the following before writing any code:
- **Risk Level**: L0 / L1 / L2 / L3 (AGENT_GUIDE §5)
- **Applicable phase range**: Phase XX–XX (AGENT_GUIDE §6)

For L2 or higher, confirm that a `plan_docs/00-base_plan/` file exists before proceeding.

### Before reporting any implementation task as complete (required check)
Verify all applicable items in AGENT_GUIDE §11 Definition of Done.
Explicitly state the reason for any item that was skipped.
If `.local/local_agent_guide.md` exists, confirm that vault knowledge capture was performed.

---

## Claude-Specific Rules
- Use the Claude runtime asset ecosystem to coordinate design, implementation, and verification flows.
- For design or analysis tasks, verify consistency with existing `plan_docs` first.
- Before implementation, review relevant component convention docs and Claude skill/agent guidelines.
- Do not modify `.claude/*` paths unless the user explicitly requests it — they are Claude runtime assets.

## Skill routing

When the user's request matches an available skill, invoke it via the Skill tool. The
skill has multi-step workflows, checklists, and quality gates that produce better
results than an ad-hoc answer. When in doubt, invoke the skill. A false positive is
cheaper than a false negative.

Key routing rules:
- Product ideas, "is this worth building", brainstorming → invoke /office-hours
- Strategy, scope, "think bigger", "what should we build" → invoke /plan-ceo-review
- Architecture, "does this design make sense" → invoke /plan-eng-review
- Design system, brand, "how should this look" → invoke /design-consultation
- Design review of a plan → invoke /plan-design-review
- Developer experience of a plan → invoke /plan-devex-review
- "Review everything", full review pipeline → invoke /autoplan
- Bugs, errors, "why is this broken", "wtf", "this doesn't work" → invoke /investigate
- Test the site, find bugs, "does this work" → invoke /qa (or /qa-only for report only)
- Code review, check the diff, "look at my changes" → invoke /review
- Visual polish, design audit, "this looks off" → invoke /design-review
- Developer experience audit, try onboarding → invoke /devex-review
- Ship, deploy, create a PR, "send it" → invoke /chatforyou-ship
- Merge + deploy + verify → invoke /land-and-deploy
- Configure deployment → invoke /setup-deploy
- Post-deploy monitoring → invoke /canary
- Update docs after shipping → invoke /document-release
- Weekly retro, "how'd we do" → invoke /retro
- Second opinion, codex review → invoke /codex
- Safety mode, careful mode, lock it down → invoke /careful or /guard
- Restrict edits to a directory → invoke /freeze or /unfreeze
- Upgrade gstack → invoke /gstack-upgrade
- Save progress, "save my work" → invoke /context-save
- Resume, restore, "where was I" → invoke /context-restore
- Security audit, OWASP, "is this secure" → invoke /cso
- Make a PDF, document, publication → invoke /make-pdf
- Launch real browser for QA → invoke /open-gstack-browser
- Import cookies for authenticated testing → invoke /setup-browser-cookies
- Performance regression, page speed, benchmarks → invoke /benchmark
- Review what gstack has learned → invoke /learn
- Tune question sensitivity → invoke /plan-tune
- Code quality dashboard → invoke /health
