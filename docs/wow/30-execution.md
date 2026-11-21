# 30 — Execution

> What a coder (any executor — Claude Code, Codex, OpenCode, a person) does with one stage list. The rules of 2026-10-30
> came out of a stage that grew from 6 tasks to 246 checkboxes, 8 PRs and 26 reviews without attempting its DONE line.

- **EX-1 The list is a plan, not a log.** The executor ticks a box and appends one line `done <sha>` to the task —
  nothing else: no new tasks, no sub-tasks, no renumbering. A box is ticked only when the whole task is done and
  verified; a partly done task stays unticked. Tick right after landing, not at the end of the stage. `R4.1, AGENTS`
- **EX-2 The fence.** Every stage has *in scope*, *out of scope* (neighbouring stages by name) and **stop conditions
  with ids**: `SC-1` another repo must change · `SC-2` the contract must change what is built — where it is only
  silent, EX-6 applies · `SC-3` a pin must move · `SC-4` a
  table or endpoint not in contracts is needed · `SC-5` a fix round the ladder has not granted · `SC-6` the list
  exceeds 150 lines / ~10 KB · `SC-7` twice the planned days, at most day 16 · `SC-8` a border beyond the `touches:` line
  (DS-14) · `SC-9` the live target is unavailable · `SC-10+` stage-specific. On a stop: send `stop SC-n`, write the
  need under Found while doing, push what compiles, end the turn. Do not solve it in the stage. `R4.2, D-23, D-27 l, D-83`
- **EX-3 One PR per stage,** from a branch off master, opened as *ready* when the DONE line holds, merged at PASS. No
  phase PRs; no "continue on the same PR" into the next stage. `R4.3`
- **EX-4 A stage builds on what master pins when it branches and never edits a pin.** Master having moved because
  another effort merged is not a stop. Needing a newer producer release is (`SC-3`). Never bump, backport or downgrade a
  pin inside a stage. Using a producer feature the contract does not name is the planner's call, not the executor's. `R4.7`
- **EX-5 No pauses.** Bora approved the stage at its start; the executor does not pause between tasks for approval — it
  finishes the list or hits a stop condition. "Continue" means the next task of the list. `R4.10 as rewritten by D-22`
- **EX-6 Take the default.** The executor does not ask questions. Where the list leaves a choice that stays inside the
  stage's fence and can be undone within its pull request — and where the contract is only silent — the choice is the
  executor's (tier 0, `20` PL-23): it takes the conservative option, writes one Found line that begins `choice:`, and
  continues; the Principal ratifies or reverses it at triage. A choice that would put a decision into the daemon,
  touch `rules/`, `robots/` or `pool/`, or change what the contract promises is a stop. If the answer would be a stop
  condition, it stops. `D-27 i, D-83`
- **EX-7 Found while doing.** One line per discovery, under the list's section. The executor never acts on a Found line
  in that stage. The Principal triages each (resume · issue · stage · drop); the stage does not exit while a line has
  no outcome. `R4.1, D-20, RV-29`
- **EX-8 Retro notes.** One line each, as they happen: what was looked for and not found, what a check did not catch,
  which tool call was expensive. The Retro seat reads them. `D-15`
- **EX-9 Live steps happen when the list says so.** A certification stage is a stage. Never defer a live check to "the
  end of implementation"; a deferral needs a D-n. `R4.6`
- **EX-16 Only QA touches the live target and opens a browser.** A live task is marked `[QA]` in the list; the executor
  sends a `qa-request` naming the task and the DONE item and continues or ends its turn; QA's report carries the fixture
  and the command. No other seat runs commands against the live target, launches a headed browser or uses computer use.
  Headless browsers inside a test suite are fine. `D-30, D-31`
- **EX-10 A phase exits when its DONE line is attempted,** where and when the plan says. If the live step fails for an
  external reason, the phase exits *as built* with the gate named in STATUS, and the gate becomes a stage. `R4.6`
- **EX-11 Read in this order:** `30` (this chapter), the plan's `tasks-overview.md`, then the one stage list. Nothing
  under `_closed/` or `_to_delete/` is read or reused. `AGENTS`
- **EX-12 Shared lists are fresh-read before each update,** and other sessions' edits are preserved. `AGENTS`
- **EX-13 The project repo.** Until the daemon is the one writer of `project` on its office, every human-attended
  session — coder, reviewer, retro — works it like the code: a worktree and a branch of its own (`_wt/project-<stage>`,
  `proj/<stage>`), where it edits only its own file; at each hand-point it merges a pull request to `master` itself,
  carrying that file and nothing else, and after a triage the coder merges `master` back before it goes on. The main
  checkout is Bora's alone: `master`, the hand log and the Planner's edits. Once the daemon carries the stages, it
  commits, pushes and merges `project` at stage and phase exits (`70` EN-8), and seats create no `project` branches. `AGENTS, D-27 m, D-59`
- **EX-14 Code repos.** One worktree and one branch per stage; DCO `Signed-off-by` with Bora's identity is authorized;
  seats push the stage branch only; master moves only through the daemon. `AGENTS, D-27 n`
- **EX-15 Working artefacts** — logs, reports, probes, byte comparisons — live in the worktree's `build/` or under
  `~/Dev/_scratch/<repo>/<stage>/` and are deleted at merge. None enters any repo. `R6.3`
