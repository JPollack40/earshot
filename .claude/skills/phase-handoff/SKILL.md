---
name: phase-handoff
description: At the end of an Earshot phase (or PR batch), write a short handoff note to docs/handoffs/<phase>.md (what shipped, open issues, next steps, files touched) so the next thread starts from it instead of this thread's history, then wrap up the thread.
---

# Phase handoff

Run this when a phase's work is merged or parked. The goal: a new thread can pick up from one short
file plus PROJECT_BRIEF.md, without reading this thread.

## 1. Gather (cheaply)

- `git log --oneline main..HEAD` (or the phase's merge commits) and `git diff --stat <base>...HEAD`.
- The phase's test-steps file and test results Serena reported.
- Don't re-read the whole codebase or the thread: use what you already know.

## 2. Write `docs/handoffs/<phase>.md`

Name it after the phase, e.g. `docs/handoffs/phase-17.md` or `phase-16-pr-b.md`. Under ~60 lines:

```markdown
# Phase <N>: <name> (handoff, <YYYY-MM-DD>)

## Shipped
- <feature / fix>, PR #<n> merged <sha> (or branch <name>, not merged)

## State
- Room schema version <n>; SQL migrations applied: <list>; functions deployed: <list>
- Installed on Serena's phone: <build / sha> or "not installed"
- Tests: <unit count> passing; phone / DHU tests <passed | pending: which>

## Open issues
- <issue> — <where it lives, file:line if useful>

## Next steps
1. <the very next thing to do>

## Files touched
- <path>: <one line on what changed>
```

Rules:
- Facts only, each one checked (commit SHAs, test counts). Say "unverified" where you didn't check.
- **The repo is public**: no secrets, keys, IPs, emails or personal details. Serena's name is fine.
- Link to longer docs (test steps, research reports) instead of copying them.

## 3. Commit and finish

- Commit the note on the phase's branch (or a small docs branch) so it lands with the work.
- Serena's standing rule: at the end of each phase also deliver the complete updated PROJECT_BRIEF.md
  (status, open issues, decisions). Keep the handoff and the brief consistent.
- Tell Serena the phase is wrapped with links to the handoff and PR, then **finish the thread**
  instead of starting the next phase in it. The next phase starts in a new thread that reads
  CLAUDE.md, the handoff and the brief section it needs.
