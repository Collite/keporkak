# review-NNN — <repo>#<PR> · <stage id · title> @ <head sha>

**Current verdict: <PASS | PASS — follow-ups filed | FIX FIRST> @ `<sha>`**

**Date:** … · **Reviewer:** <seat, vendor> — independent of the implementation seat.
**Range:** `<base sha>..<head sha>` · **Stage list:** <path in the project repo> · **Contract:** contracts.md §… ; D-…
**GitHub review:** <link>, on the exact head above.

## Scope
<the stage's DONE line; what was read; what is outside this review — earlier stages, follow-ups, other stages by name>

## Verdict — <…>
<two or three lines: what holds, what does not>

## Blockers (FIX FIRST only)
### B1 — <what, in one line>
**Where:** <permalink at the reviewed sha> · **Violated:** <contract §, D-n, DONE item or security invariant> ·
**Missing test:** <the regression that must exist after the fix>

## Follow-ups
- <one line each> — <issue link>

## Verification
- Head confirmed; CI <run ids> — <counts>. Not re-run: <what was trusted>.
- Independent command (if any): `<command>` → <result>; dummy credentials only.

## ⚑ for Bora — classification questions only (contract questions go to the Principal as a contract flag)

## Re-review @ <sha> — round <n of the ladder>
| blocker | verdict | note |
|---|---|---|
| B1 | closed / open | |
<a new finding only with `introduced_by_fix`> → PASS | next round | back to the planner
