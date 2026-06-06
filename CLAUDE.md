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
3. Relevant `plan_docs/N월_[기능]_plan.md` or `plan_docs/00-base_plan/YYYY/MM/[feature]_plan.md`
4. Relevant component convention docs
   - `docs/springboot_backend.md`
   - `docs/nodejs_frontend.md`
   - `docs/chatforyou_desktop.md`
   - `docs/git_commit_convention.md`
5. Claude runtime assets
   - `.claude/agents/*`
   - `.claude/skills/*`

## Claude-Specific Rules
- Use the Claude runtime asset ecosystem to coordinate design, implementation, and verification flows.
- For design or analysis tasks, verify consistency with existing `plan_docs` first.
- Before implementation, review relevant component convention docs and Claude skill/agent guidelines.
- Do not modify `.claude/*` paths unless the user explicitly requests it — they are Claude runtime assets.

## Skill routing
<!-- Claude/gstack specific tool routing only — not project policy. Project rules are in AGENT_GUIDE.md -->

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
