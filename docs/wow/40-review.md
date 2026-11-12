# 40 — Review

> Executable form: the `review` skill (`skills/review/`). Template: `templates/review.md`.

- **RW-1 One review = one stage = one DONE line.** Inputs: the stage list, its PR diff, the frozen contract. Not
  earlier stages, not the whole branch. A certification stage's review checks the recorded numbers and fixtures against
  the DONE line — no code review beyond the fixture spec. `R5.1`
- **RW-2 Three verdicts.** `PASS` · `PASS — follow-ups filed` · `FIX FIRST`. `R5.2`
- **RW-3 A blocker** violates `contracts.md`, a D-n, the DONE line or a security invariant, or is a test that does not
  test what it claims. Everything else — minors, cleanups, style, wishes — is a **follow-up** and becomes a GitHub
  issue (one issue may collect several), never a task in the stage. `R5.2`
- **RW-4 The ladder.** Two fix rounds run on the reviewer's verdict alone; a third needs the Principal's grant; a
  fourth Bora's ruling; then the stage goes back to the planner. `R5.3 as amended by D-23`
- **RW-5 A re-review rules per open blocker only.** A new finding is admissible only if the fix introduced it, and is
  marked `introduced_by_fix`. No spot-check rounds. `R5.3`
- **RW-6 Rulings classify, they do not plan.** The reviewer may ask Bora to classify a finding (blocker or follow-up).
  A finding that would change a contract or add behaviour is a contract flag to the Principal → a D-n → a stage in the
  plan; it is never executed inside the stage under review. `R5.4`
- **RW-7 Evidence is read, not demanded.** The reviewer trusts CI on the exact head plus the test counts. A doubt is
  settled by the reviewer running the test (a throwaway probe is fine — say so, use dummy credentials only), never by
  asking the coder for proof packages, logs or mutation runs. "Claim without a test" is a blocker whose fix is a test. `R5.5`
- **RW-8 The reviewer does not steer.** No next-work suggestions; follow-ups go to issues, not to the coder's queue.
  The reviewer changes no implementation. `R5.6`
- **RW-9 Independence.** The reviewer is never the coder's seat; its model ranks at least as high as the coder's; a
  different vendor is preferred, and is required for a merge without Bora (`70` EN-7). `R5.8, D-27`
- **RW-10 Two axes, when the repo has standards.** *Spec*: does the diff deliver the DONE line within the contract.
  *Standards*: does it follow the repo's documented standards (`CODING_STANDARDS.md`), skipping what tooling enforces.
  Standards findings are follow-ups unless they are a blocker by RW-3. `D-12`
- **RW-11 The record.** The verdict is a **GitHub PR review** on the exact head sha; `review-NNN.md` next to the stage
  list is the durable record, ≤ ~200 lines, numbered across the whole project repo (take the next free number at the
  moment of writing). No "coder response" sections: the coder's response is the fix commit and the `done` line. `R5.7, R6.2`
- **RW-12 A blocker is written so it can be fixed without a conversation:** what · where (a permalink at the reviewed
  sha) · which contract clause, D-n or DONE item it violates · the regression test that is missing. `practice review-234`
- **RW-13 Verification section.** The exact head, the CI run ids and counts read, and any independent command the
  reviewer ran with its result. `practice review-234`
