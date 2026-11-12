# 50 — The PR and the evidence

- **EV-1 The hand-off is the PR description,** nothing else (`templates/pr-description.md`): stage · what landed by
  task id · the DONE line item by item with where to see it · verified (command → counts; CI run on the head sha; for
  live, the fixture path and the command) · not in this stage (the fence's out-of-scope line, verbatim) · Found while
  doing (the list's lines, verbatim). No `handoff.md`, no `review/<stage>/` folder, anywhere. `R6.1`
- **EV-2 The review is a GitHub PR review** carrying the verdict; `review-NNN.md` links to it. Follow-ups are GitHub
  issues, filed through the daemon. Nothing else from a review enters the project repo. `R6.2`
- **EV-3 Evidence is proportionate:** PR link · merge sha · CI run id · test command + counts; for a live DONE line,
  the saved fixture it names + the command. No XML, logs, mutation runs or proof packages. `R4.5`
- **EV-4 The audit trail is pointers.** The STATUS entry (≤ 5 lines) names the PR, merge sha and CI run; the
  `done <sha>` lines name the commits. The rest is on GitHub. `R6.6`
- **EV-5 The project repo refuses proof shapes** (`*.log`, `*.xml`, `*.jsonl`, `**/review/**`, `**/proofs/`) in its
  `.gitignore`. It is a guard, not the rule: an agent that needs such a file there is already off the list. `R6.4`
- **EV-6 What the daemon observes** before a merge (`70` EN-6): the PR review on the head sha, CI green on that sha,
  every task ticked with `done <sha>`, the evidence lines present, every Found line triaged, no proof files, no stray
  change in the main checkouts and sibling repos. `D-19 c, D-27`
