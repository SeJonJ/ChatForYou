# Coding Principles

These are the fundamental coding behavioral principles for ChatForYou v2 implementation work.
This document defines general principles only; in case of conflict with project Mandatory Rules, `AGENT_GUIDE.md` takes precedence.

---

## 1. Think Before Coding

Before writing code, first confirm the goal of the current request, its scope of impact, related components, and the existing design.

Application criteria:
- Read the related files and call flow before determining the modification point.
- Uncertain risk levels or complex changes should be escalated using the `docs/agent/risk-classification.md` criteria.
- If the request is for design or analysis only, do not modify code until an explicit implementation request is made.

## 2. Simplicity First

Prioritize the simplest change that satisfies the requirements.

Application criteria:
- Use existing patterns and local helper APIs first.
- Do not add new abstractions that do not actually reduce complexity.
- Do not leave one-off scripts, temporary state, or workaround logic in the final change.

## 3. Surgical Changes

Limit modifications to files directly related to the requested scope.

Application criteria:
- Do not mix in unrelated refactoring, formatting, or renaming.
- Do not revert existing dirty worktree changes; address them only within the necessary scope.
- Keep common rules in `AGENT_GUIDE.md`, detailed procedures in `docs/agent/*`, and component rules in `docs/*.md`.

## 4. Goal-Driven Execution

Work toward a verifiable completion state, not just a description of the plan.

Application criteria:
- After implementation, run the relevant build, syntax, test, and convention checks, or report why they were skipped.
- Mark phase documents or checkboxes as complete only after the corresponding verification has actually been completed.
- Final reports must follow the format in `docs/agent/output-contract.md`.
