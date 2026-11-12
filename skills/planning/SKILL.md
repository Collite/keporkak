---
name: planning
description: Turn a converged design into architecture, contracts, a phased plan and per-stage task lists in the mandatory stage-list skeleton. Use when the user asks to "prepare a plan", "plan this", "prepare the tasks / task lists", "cut a stage", "re-cut", or when a design.md exists and implementation is next. Not for exploring options — that is the design skill.
---

# Planning

Output lives in the effort's folder of the **project repo**, never in a code repo. Read the effort's `design.md` and
control-room decisions first; cite them (`Realizes: D-…`).

## 1. Three artefacts before any task list
1. `architecture.md` — components, module dependencies, deployment, tech stack, build/test/deploy flow, diagrams.
2. `contracts.md` — every API, DTO, schema, event and persisted shape in full. The source of truth across boundaries.
3. `plan.md` — phases and stages ([references/plan.md](references/plan.md)): per phase the deliverable, the pins it
   builds on, and the exact DONE evidence — attemptable at that phase's end. Never plan a later phase's component as a
   "preview"; stub with a fixture or move the stage. A Mermaid dependency chart of the phases (solid = needs the exit,
   dashed = the frozen contract suffices); waves are gates. No person-lanes.
Ask before writing lists: open questions go in a section with a stated default each.

## 2. Stage lists — one file per stage, in the skeleton
Use [references/stage-list.md](references/stage-list.md) exactly. The executor that reads it may add nothing: it
ticks boxes, appends `done <sha>`, writes Found lines and Retro notes, and stops at the fence.
- **Size.** 6–8 tasks; a task is ½–1 day with one observable result; a stage is one PR and ≤ 8 working days.
- **Specific about the result and its test — not the implementation.** Name interfaces, behaviours and acceptance;
  do not pre-decide detail the executor can read from the code; avoid file paths and line numbers that go stale. A
  specs task names ≤ 5 behaviours.
- **Tests first.** T1 is specs. Build stages verify with mocked unit and component tests. Anything that needs a real
  dependency is a **certification stage** with a live DONE line, placed where the plan needs it.
- **The three lines.** `rolls:` what the stage releases · `live:` where its DONE is observed · `touches:` which design
  border it crosses by plan (normally `none`). They decide what happens after PASS.
- **The fence.** In scope (one sentence) · out of scope (neighbouring stages by name) · stop conditions with ids
  `SC-1…SC-9` plus stage-specific ones. A need in another repo is a stage in that repo's plan; a pin moves only in a
  pin stage (bump · build · publish · roll).
- **Library help.** Where a task touches a complex library, point at current documentation (context7), a cloned
  source, or a named example — see the ecosystem profile.
- `tasks-overview.md` ([references/tasks-overview.md](references/tasks-overview.md)) indexes the lists and is the
  executor's entry point.

## 3. STATUS
Create or update `STATUS.md` (frontmatter incl. `stream` and `lane`; ≤ 10 lines of position). Planning is not complete
until both fields are set. Rebuild the register.

## 4. Keeping a plan alive (Planner in design sessions; Principal at runtime)
- Triage each Found line: resume · issue · a new stage · drop. Fold the outcome into the list text, move the triaged
  lines to `_closed/<list>-found-<yymmdd>.md`; Found returns to empty. Keep the list under 150 lines.
- At runtime you may add, delete, split and merge tasks within the size rules, and cut pin stages. You may not change
  a contract, the architecture, the UX or a library choice — that is a flag for Bora and the design squad.
- At every phase exit: refresh the chart, check the profile's floors, roll STATUS over.

Rules behind this skill: rulebook chapters 20 (planning) and 30 (execution).
