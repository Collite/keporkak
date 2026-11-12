---
name: retro
description: Run a retrospective on a finished stage or phase and propose changes to the environment the seats work in. Use when a retro is started for a stage or a phase, or when asked "how do we make this go better next time".
---

# Retro

You improve the **environment**, not the work: rules, checks, skills, pointers, tools, the shape of plans.

## Read
The stage list(s) with their Found lines, triage outcomes and **Retro notes** · the review file(s) and the PR · STATUS
· the mail thread · seat transcripts where you can read them · `docs/wow/CHANGELOG.md` and `docs/wow/_rejected/`
(what was already changed, and what was already declined).

## Look for
- **Navigation** — the seat took long to find something. → a pointer.
- **Missing or unwired checks** — a mistake a lint rule, a type, a test or a hook would have caught; a check that
  exists but does not run. A repository with no guardrail at all is itself a finding.
- **Standards** — the reviewer missed, or had to argue, something. Classify first: a *mechanical* violation (a fixed
  pattern, a banned call, a file location) gets a check, never a paragraph; prose is for judgement calls only.
- **Bloated steering** — rules in `CLAUDE.md`/`AGENTS.md` or a persona that belong in a check, or that change nothing.
- **Tool economy** — an expensive or repeated tool call; a job that deserves a `just` recipe.
- **Information access** — a log, a read access or a fixture the seat did not have.
- **Plan shape** — an over-specified task, a wrong size, a stop condition that should have existed, a fix round the
  list made likely.

## Write
`retro-<phase|stage>.md` next to the list, from [references/retro.md](references/retro.md), at most 100 lines. Per
candidate: one line of what happened with a pointer, and **where the fix lives** — a rule (a flag for Bora) · a check
or hook · a skill's or persona's text · a navigation pointer · a tool or an access · the plan's shape. Most severe
first, at most ten; three to five for a single stage. Name what worked well enough to keep. Send the `retro-report`.

## Never
Re-propose what `_rejected/` holds. Propose process where a check would do. Judge a seat. Edit anything but your report.

Adapted from mattpocock/skills `retro` (MIT) — see `skills/_credits`.
