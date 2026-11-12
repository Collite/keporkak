---
name: review
description: Review one stage's PR against its stage list's DONE line and the frozen contract, and write the verdict as a GitHub PR review plus a review-NNN.md record. Use when asked to "review", "/review", "review PR #n", "re-review", or when a review-request arrives. Never use from the session that wrote the code.
---

# Review — one stage, one DONE line

You are the independent reviewer. If this session implemented any of the diff, stop and say so.

## Inputs
The stage list (its DONE line and fence) · the PR diff at the **exact head sha** · `contracts.md` and the D-n the list
cites. Not earlier stages, not the whole branch. For a certification stage: check the recorded numbers and fixtures
against the DONE line — no code review beyond the fixture spec.

## Steps
1. Pin the range (`base..head`) and confirm the head on GitHub. Read CI on that head: run ids and counts.
2. Read the **acceptance specs first** — the ones the PR lists against the DONE line. For each DONE item: is there a
   spec that reads as that behaviour, and would it go red if the behaviour were removed? (Break it locally if you
   doubt it.) A DONE item with no such spec, or a spec that would stay green, is a blocker. Then read the diff: does
   each DONE item hold, within the contract? If the repo documents coding
   standards, read them as a second axis; skip what tooling already enforces.
3. Classify every finding. A **blocker** violates the contract, a D-n, the DONE line or a security invariant, or is a
   test that does not test what it claims. Everything else is a **follow-up** → a GitHub issue (group them).
4. Settle doubts yourself: run the test, or a throwaway probe (say so; dummy credentials only). Do not ask the coder
   for logs, proof packages or mutation runs. A claim without a test is a blocker whose fix is a test.
5. Write each blocker so it can be fixed without a conversation: what · where (permalink at the reviewed sha) · what
   it violates · the regression test that is missing.
6. Verdict: `PASS` · `PASS — follow-ups filed` · `FIX FIRST`. Post it as a GitHub PR review on the head; write
   `review-NNN.md` next to the stage list from [references/review.md](references/review.md) (≤ ~200 lines; NNN = the
   next free number in the project repo at the moment of writing); send the verdict message with the links.

## Re-review
Per open blocker only: closed or open. A new finding is allowed only if the fix introduced it — mark it
`introduced_by_fix`. Two fix rounds run on your verdict; a third needs the Principal's grant and a fourth Bora's
ruling — when blockers remain after round two, say so and stop; do not open a round yourself.

## Never
Steer (no next-work suggestions; follow-ups go to issues) · plan (a finding that changes a contract or adds behaviour
is a contract flag to the Principal, not work in this stage) · change the implementation · add "coder response"
sections · re-review earlier stages · ask Bora anything but a classification (blocker or follow-up).

Rules behind this skill: rulebook chapters 40 (review) and 50 (PR and evidence).
