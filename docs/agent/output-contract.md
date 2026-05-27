# Output Contract

Standard report formats for all AI agents working on ChatForYou v2.

---

## Implementation Task Report (L1–L3)

```markdown
## Task Result Report

### 1. Task Summary
[One sentence describing what was done]

### 2. Risk Level
**Level**: L[0–3]
**Reason**: [Why this level was selected]

### 3. Scope & Assumptions
[Scope and assumptions made]

### 4. Impact Analysis
- **Backend**: [Impact or N/A]
- **Frontend**: [Impact or N/A]
- **Desktop**: [Impact or N/A]

### 5. Phases
- Completed: Phase [XX, XX, ...]
- Skipped: Phase [XX] — [reason]

### 6. Modified Files
- `file/path` — [one-line description of change]

### 7. Validation
| Check | Command | Result |
|-------|---------|--------|
| Backend build | `./gradlew clean build` | PASS / FAIL / SKIPPED |
| Frontend syntax | `node --check <file>` | PASS / FAIL / SKIPPED |
| Convention check | backend/frontend-convention-checker | PASS / FAIL / SKIPPED |

### 8. Remaining Risks
- [P0/P1/P2] [Risk description]

### 9. Next Action
[Recommended next step]
```

---

## L0 Report (Documentation Only)

```markdown
## Documentation Change Report

### 1. Summary
[What was changed]

### 2. Modified Files
- `file/path` — [description]

### 3. Reason for Change
[Why this change was made]

### 4. Rules Affected
[List of rules affected, or "None"]

### 5. Validation Skipped
[Reason: "Documentation-only change — build/test validation not applicable"]
```

---

## Design / Analysis Report

```markdown
## Design / Analysis Result

### 1. Files Analyzed
- [file list]

### 2. Design Validity Assessment
[Overall judgment on whether the design aligns with project goals and structure]

### 3. Changes Applied or "No Changes"
[Modifications made, or "No changes — design is consistent with project structure"]

### 4. Industry Reference Comparison
[Strengths and cautions relative to industry standards]

### 5. Improvement Proposals & Trade-offs
[Optional quality improvement suggestions]

### 6. Risks & Caveats
[Identified risks]
```

---

## Related Rules
- `AGENT_GUIDE.md` — Output Contract
- `AGENT_GUIDE.md` — Pre-Implementation Compliance Gate
- `AGENT_GUIDE.md` — Definition of Done
