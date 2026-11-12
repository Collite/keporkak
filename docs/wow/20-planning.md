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
