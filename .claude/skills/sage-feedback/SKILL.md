---
name: sage-feedback
description: "Resolve developer feedback markers (`sage-feedback ::`) left in completed-cycle code. Judges each marker against that cycle's own 00~06 plan documents — feedback is not accepted unconditionally — then fixes, dismisses, or asks."
---

# sage-feedback — Developer Feedback on Completed Cycle Code

The developer marks a spot they doubt; this skill checks whether the doubt is founded.

Unlike the phase skills, this one runs **outside the cycle**. It never re-runs a cycle and
never edits completed `06` reports or plan documents. Use it when a cycle is done and
something in the resulting code looks off.

> This skill is a **CORE framework bootstrap asset** — hand-shipped by `sage install`,
> NOT manifest-tracked. Its reference spec lives at
> `docs/sage_harness/skills/sage-feedback.md`. Claude reads this render from the repo
> (`.claude/skills/sage-feedback/`); Codex reads it from the user-global skills dir
> (`$CODEX_HOME/skills/sage-feedback/`).

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

## The marker

The developer leaves the token `sage-feedback ::` in a comment. **The comment sigil is
whatever that language uses** — the scanner looks for the token, not for `//`, so it works
in every language without knowing any of them.

```java
// sage-feedback :: 이 캐시 무효화, 원래 계획은 TTL 기반 아니었나?
```
```python
# !sage-feedback :: 결제 재시도 로직이 설계와 다름
```
```sql
-- sage-feedback :: 이 인덱스, 계획에 있었나?
```

| Marker | Force |
|---|---|
| `sage-feedback ::` | advisory — surfaced, blocks nothing |
| `!sage-feedback ::` | blocking — `03` implementation entry on that file is refused |

The developer chooses the force. Just as this skill does not accept feedback
unconditionally, the developer does not demand blocking force for every doubt.

## Procedure

**1. Check the profile.** `feedback.enabled` must be true. If absent or false, stop and route
to `/sage-profile-modify` — the scan is a no-op otherwise.

**2. Get the inventory deterministically.**

```
sage feedback --output json
```

Never hand-grep. The CLI owns scan scope (git-tracked files, `paths.plan_docs` excluded), so
this skill and the gate always see exactly the same markers.

**3. Find the evidence.** For each marker, identify the cycle that produced that code:
`git log` the file → the commits that touched it → the `paths.plan_docs` documents of that
period, matched by `Cycle-Stem`. Read that cycle's `00` design and `01` requirements. If a
vault is configured, read the related notes too.

This step is what separates this skill from guessing. Without the plan documents you do not
know what "the original design" was either.

**4. Judge — three branches, no fourth.**

| Verdict | Code | Marker | Output |
|---|---|---|---|
| **Real divergence** | fix to match the plan | remove | one line: what changed and why |
| **Not a divergence** (intentional/justified) | untouched | remove | cite which part of the plan justifies it |
| **Undecidable** (no basis in the plan, or the intent itself is ambiguous) | **untouched** | **keep** | ask the user what is unclear |

The third branch is the important one. When the plan gives no basis, **do not guess-fix** —
that is the failure mode that quietly damages working code. Leave the marker and ask.

**5. Report** per marker: `path:line`, verdict, action.

**6. Record the outcome** — once per marker, after the code edit and marker removal:

```
sage feedback --record --path <path> --line <line> \
  --verdict fixed|intentional|undetermined \
  --note "<one line: what changed, or which part of the plan justifies it>" \
  --cycle-stem <stem from step 3>
```

Call it the same way every time. The profile decides what happens: `feedback.record: false`
(the default) makes it a no-op that says so. When recording is on, `.sage/feedback.jsonl` —
the append-only machine-readable audit, committed like `.sage/override.jsonl` — is **always**
written; `record_target` only decides whether a human-readable per-cycle vault note is written
too (`auto` = when `vault_path` is set, `sage` = never, `vault` = required, warns and exits
non-zero if the vault is off). The verdict determines `resolved`, so it cannot be asserted
separately; recording `fixed` while the marker is still in the file gets a warning.

## What this skill is NOT

- **Not a cycle.** No phase documents are produced or advanced. Completed `06` reports and
  plan documents are read-only here.
- **Not `/sage-review`.** Phase 05's reviewer is read-only by governance design (it returns
  findings to the implementer). This skill is not a gate, so it may edit code directly — the
  three-branch rule is what prevents unfounded edits, not read-only-ness.
- **Not a way to silence the gate.** See below.

## When the gate blocks you

`pre-implementation-gate` refuses a write that leaves a blocking marker in place:

```
⛔ [GATE BLOCK] 미해결 개발자 피드백 마커(!sage-feedback) 위에 새 구현 금지.
```

The rule is **not** "files with markers are frozen" — it is "does the marker survive your
write". A write that removes the marker passes, which is precisely why this skill can do its
job. Editing *around* the marker is what gets refused.

If work genuinely cannot wait, the recorded escape hatch is an override with a reason and an
expiry (24h cap, audited in `.sage/override.jsonl`):

```
sage override grant --gate pre-implementation-gate --reason "<why>" --ttl 2h
```

Deleting the marker by hand also passes the gate — nothing can prevent that, since it is a
comment in your own file. But it leaves no record, and its disappearance without a resolution
note is visible in `git diff`. The override exists so there is an honest path.
