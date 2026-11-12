# The agent brief

The brief is the contract a future stage is cut from; the issue's body and discussion are only context. It may wait
for weeks, so it must survive the code changing under it.

- **Durable, not precise:** name interfaces, types and behaviours — never file paths or line numbers.
- **Behavioural, not procedural:** what the system should do, not how to change it.
- **Testable acceptance:** each criterion can be checked on its own.
- **An explicit border:** what is out of scope.

```markdown
## Agent Brief
**Category:** bug | enhancement
**Summary:** one line
**Current behaviour:** what happens now (for a pull request: the state of the diff)
**Desired behaviour:** what should happen, with edge cases and errors
**Key interfaces:** the types, functions or config shapes involved, and what changes about each
**Acceptance criteria:**
- [ ] …
**Out of scope:** …
```

Adapted from mattpocock/skills `triage/AGENT-BRIEF.md` (MIT).
