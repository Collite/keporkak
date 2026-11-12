---
name: handoff-pr
description: Write the pull request description that hands a finished stage to its reviewer, and send the review request. Use when a stage's DONE line holds and CI is green on the head.
---

# The hand-off is the PR description

Before writing: every task is ticked with `done <sha>`; CI is green on the head (watch it — `gh pr checks --watch` —
do not poll); the DONE line holds item by item. If one does not, you are not done.

Fill [references/pr-description.md](references/pr-description.md) — six sections, nothing else:
1. **Stage** — the id and title, and the path of the list in the project repo.
2. **What landed** — three to six lines, by task id. One small sketch (a call tree, a file tree, a before/after diff of
   a shape) is welcome when it makes the change visible faster than prose.
3. **DONE line** — each item of the list's DONE, ticked, with **the spec that shows it**: the spec's name (it reads as
   the behaviour) and its file. This is the reading list for Bora and the reviewer — keep it to the acceptance specs;
   for a live item, QA's fixture path and command.
4. **Verified** — the test command and its counts; the CI run on the head sha; for a live item, QA's report and the
   fixture path.
5. **Not in this stage** — the list's out-of-scope line, verbatim.
6. **Found while doing** — the list's lines, verbatim. Nothing here is done; the Principal triages.

Open the PR as *ready* from the stage branch. Send `review-request`. End your turn.

Never: a `handoff.md`, a review folder, logs or reports committed anywhere; a description that argues with a future
review; a secret in any quoted output.
