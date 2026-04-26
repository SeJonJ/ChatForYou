# CLAUDE.md

이 문서는 Claude 전용 실행 차이만 정의하는 thin wrapper다.
공통 규칙, workflow, output contract는 반드시 `AGENT_GUIDE.md`를 단일 기준으로 따른다.

## Start Order
1. `AGENT_GUIDE.md`
2. 관련 `plan_docs/00-base_plan/YYYY/MM/[기능]_plan.md`
3. 관련 컴포넌트 기준 문서
   - `docs/springboot_backend.md`
   - `docs/nodejs_frontend.md`
   - `docs/chatforyou_desktop.md`
4. Claude runtime assets
   - `.claude/agents/*`
   - `.claude/skills/*`

## Claude-Specific Rules
- Claude는 이 프로젝트의 Claude runtime asset 생태계를 사용해 설계, 구현, 검증 흐름을 조율한다.
- 설계 또는 분석 작업에서는 기존 `plan_docs`와의 정합성 점검을 우선한다.
- 구현 전 관련 컴포넌트 기준 문서와 Claude skill/agent 지침을 함께 확인한다.
- `.claude/*` 경로는 Claude runtime 자산이므로, 사용자가 명시적으로 요청한 경우를 제외하고 수정하지 않는다.

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
- Ship, deploy, create a PR, "send it" → invoke /ship
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
