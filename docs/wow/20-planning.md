# 20 — Planning

> From `design.md` to stage lists an executor can follow without adding anything. Executable form: the `planning`
> skill (`skills/planning/`). Templates: `templates/plan.md`, `tasks-overview.md`, `stage-list.md`.

- **PL-1 Where planning lives.** In the effort's folder of the **project repo** — never in a code repo:
  `brief.md`, `design/`, `architecture.md`, `contracts.md`, `plan.md`, `tasks-overview.md`,
  `tasks-p<n>-s<n.m>-<slug>.md`, `STATUS.md`, `review-NNN.md`, `retro-*.md`, `_closed/`. Code repos get a pointer. `R1`
- **PL-2 Three artefacts before any list.** `architecture.md` (components, dependencies, deployment, tech stack, flows),
  `contracts.md` (every wire contract in full — the source of truth across boundaries), `plan.md`. `planning skill`
- **PL-3 Hierarchy.** *Task*: ½–1 day, one observable result. *Stage*: 6–8 tasks, something testable ships, one PR,
  ≤ 8 working days. *Phase*: a set of stages, something deployable ships. A specs task names ≤ 5 behaviours. `R4.11`
- **PL-4 Names.** `P<n>`, `S<n.m>`, `T<n>`; branch `feat/<effort>-<repo>-p<n>-s<n.m>-<short>`; one file per stage. `conv §2`
- **PL-5 `plan.md` names, per phase,** the pins it builds on and the exact DONE evidence. Every DONE line is attemptable
  at that phase's end. Never plan a later phase's component as a "preview" inside an earlier one — stub with a fixture
  or move the stage. `R4 paste-in, R4.11`
- **PL-6 Dependencies and the chart.** Each phase marks what it needs, and the plan carries a Mermaid dependency chart
  (`flowchart LR`; solid edge = needs the exit, dashed = the frozen contract suffices; styles `done` / `ready` / `ext`),
  refreshed at every phase exit. No person-lanes in planning. **Waves are gates:** a phase starts when its solid
  predecessors exited; nothing from wave n+2 starts while a wave n exit is open. `R3, R4.8`
- **PL-7 Stream and lane.** Every STATUS carries `stream` and `lane` before planning is complete; the values are the
  profile's. Dev dependencies reference dev items only; a cross-stream link is a watch-list entry, and an effort that
  mixes streams is split. An effort that spans lanes takes the lane of its immediate next action. `R2`
- **PL-8 The skeleton is mandatory.** Every stage list has the same shape (`templates/stage-list.md`): header with
  `Realizes · Contract · Pins`, the three lines `rolls: · live: · touches:`, scope fence with stop-condition ids,
  pre-flight, tasks, DONE, exit, Found while doing, Retro notes. Any executor finds the fences in the same place. `R4.12, D-15, D-27 l`
- **PL-9 The three lines.** `rolls:` what the stage releases (`none` or the image/artefact); `live:` where its DONE is
  observed (`none` or the target — its live tasks are marked `[QA]`, EX-16); `touches:` which Design-Roles border it crosses by plan (`none` · `contracts` ·
  `architecture` · `UX` · `infra`). Bora sees them when he approves the assignment; they select the tail (`70` EN-7). `D-27`
- **PL-10 Specific about the result, not the implementation.** Be specific about the result and its test; do not
  pre-decide detail the executor can read from the code. Describe interfaces and behaviours rather than file paths and
  line numbers, which go stale. Acceptance is testable; out-of-scope is explicit. `R4 paste-in, D-11`
- **PL-11 Tests first, and which tests.** A stage that lands code starts with a specs task: at most five behaviours,
  each a sentence that becomes a spec's name. **These acceptance specs are written to be read** — by Bora and the
  reviewer — and there are enough of them when (a) breaking any DONE item turns one red and (b) the DONE line can be
  ticked from reading them. Finer tests are the executor's, one slice at a time; not every test must be written. Build stages are verified
  by mocked unit and component tests (all external collaborators mocked). Behaviour that needs a real dependency is a
  **certification stage** with a live DONE line — a stage like any other, planned where the plan needs it. `conv §4, R4.6, D-38`
- **PL-12 Library references.** Where a task touches a complex library, the list points at current documentation
  (context7), a locally cloned source, or a named example — the profile lists them. `planning skill`
- **PL-13 Cross-repo needs are stages in the owning plan.** The consumer waits for the pin. A pin changes only in a
  **pin stage** (one PR: bump · build · publish · roll). `R4.2, R4.7`
- **PL-14 Floors are checked at every phase exit** and whenever a floor moves (the profile says which floors exist). `R8`
- **PL-15 The list is the planner's to keep short.** When a triage is applied the planner folds its outcome into the
  list and moves the triaged lines to `_closed/<list>-found-<yymmdd>.md`; Found returns to empty. A list that hits the
  cap because of planner notes is the planner's to condense. `R4.1`
- **PL-16 Planner and Principal.** The Planner (design squad) writes plans and lists. The Principal (coding squad) owns
  them at runtime: triage, add · delete · split · merge tasks within PL-3, cut pin stages — never a redesign (DS-14). `D-7`
- **PL-17 Plans stay markdown.** Stage lists are the human-facing plan; machine indexes are generated from them. `D-10`
- **PL-18 Walk the contract before cutting.** A list cites every contract section its tasks touch, and the planner
  walks each task against those sections before cutting the list. A task that needs something no contract names means
  the list is not cut yet: the gap goes to the contract first. The walk reaches amendments too: before Bora rules on an
  amendment that changes how a message is judged, the planner walks it against every rule on master it reaches — each
  declared emit, each kind's addressee and cap, each expiry — and the proposal carries `check-rules.py`'s output on a
  copy of the rules with the edit applied. `D-54, D-78`
- **PL-19 The specs task names its seam** — the interface its specs observe the behaviours at (an entry point, a
  command, a file another program reads). The executor writes the specs there and nowhere further in. `D-54`
- **PL-20 DONE is checkable at the hand-off.** Every DONE item can be seen on the PR's head before the merge; what
  only the merge can show (CI on master, a released artefact, a deploy) is an Exit line. `D-54`
- **PL-21 A list cites what it names from master.** Every file, recipe and rule id a list names is cited as it is on
  master when the list is cut, or the list says "to be confirmed in T1". It extends D-54 and D-78. `D-83`
- **PL-22 A sentence about a class names the class and its edges.** A DONE item or a T1 sentence about a class names
  the class and the edges its spec must reach: two or more members, the last one, each order, a non-empty inbox. A
  certification walk runs each plural of its phase's DONE line with two or more members. `D-90`
- **PL-23 Who decides what — four tiers, from the coder to Bora.** **Tier 0 — the coder decides inside its fence:** a
  choice the list leaves, which stays inside the stage and can be undone within its pull request (EX-6, a `choice:`
  Found line); the Principal ratifies it at triage or reverses it. **Tier A — the Principal decides** and logs it as a
  D-n; Bora hears of it in the status report: an as-built clarification of the contract, a triage outcome, placing a
  follow-up in a stage already cut. **Tier B — the Principal applies it, and it stands unless Bora objects:** a
  contract line that changes behaviour, a new repair stage, the order of stages. **Tier C — Bora's yes before any
  work:** an edit to `rules/`, `robots/` or `pool/`, anything that spends his tokens or needs `sudo`, a phase's exit, a
  retro's triage, a change to the plan. Each D-n names its tier. A D-n that changes a seat's instructions says
  "→ rulebook"; `just owed <decision log>` lists each one no line of `CHANGELOG.md` names, and from P3's exit a
  phase does not exit while it lists one. `D-83, D-90`
