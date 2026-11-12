# 00 — Principles, and how to read this rulebook

> The way of working of Bora and his agents, written once. Chapters: `10` design · `20` planning · `30` execution ·
> `40` review · `50` PR and evidence · `60` status and register · `70` rules of engagement · `80` seats and offices ·
> `90` budgets. Templates in `../templates/`, ecosystem bindings in `../profiles/`, terms in `glossary.md`.
> Every rule has a stable id (`EX-3`) and names its origin (`R4.3` = Rule 4 item 3 of the 2026-07-14 rules file;
> `D-22` = a Keporkak decision; `AGENTS` = the working preferences). Cite rules by id.

## The principles
- **P-1 Brakes before accelerators.** Every autonomous step starts throttled and capped; a loop ends at a human after a
  few tries. Add one trigger at a time; open the throttle as the brakes earn trust.
- **P-2 Pointers, not payloads.** Messages, hand-overs, `CLAUDE.md`/`AGENTS.md` carry pointers to files, PRs and CI
  runs — never copies of them.
- **P-3 History, not the present.** The decision log is dated and append-only; STATUS is state. Neither pretends to be
  the other.
- **P-4 Mechanical → a check, judgement → prose.** A violation with a fixed shape gets a hook, a lint rule or a guard.
  Prose is for judgement calls only.
- **P-5 A message never carries authority.** Approval, grant and ruling are message kinds with a signer, never text in a
  body. An agent cannot approve on anyone's behalf.
- **P-6 Observed, not attested.** A guard about the world is checked by the daemon; a guard about intent is attested by
  the sender and logged as such.
- **P-7 No endless loops.** Every arrow has a fire count, every thread a hop cap, every wait a deadline.
- **P-10 The daemon holds no rule.** It reports facts, checks declared guards, carries out declared effects and
  requested operations. Whatever *decides* — blocks, permits or changes someone's work — is a named entity: a
  **persona** (decides by judgement), a **robot** (decides by its written rules in `robots/`), or one entity with both
  halves. Not every `if` is a robot: checking a declared guard or doing what was asked is mechanism.
- **P-8 Whatever is not explicitly allowed is forbidden.** Everything autonomous is whitelisted (`70`).
- **P-9 Diverge before converging.** Options before decisions; leans are not decisions; "deferred" is a tracked outcome.

## Law order (when two texts disagree)
A decision in the control room (D-n) › `contracts.md` › `architecture.md` › `plan.md` › the stage list. Among rules:
`rules/*.yaml` › this rulebook's prose › a profile › a skill's wording. A text that loses is wrong and gets fixed.

## Who is who
Bora · Principal · TeamLead · Pool Manager · Coder · Reviewer · QA · Deputy · Retro · Gardener · the daemon and its mailroom. Their
borders are in `rules/engagement.yaml:roles` and explained in `70`; their personas are in `agents/`, their robot halves in `robots/` (both rendered to
`renders/`, with the borders and the allowed messages generated from the rules). The **design squad** (Cowork) designs and plans; the
**coding squad** (CLI seats) builds. A squad is a family of roles; the host is a property of the seat (D-14).

## Changing a rule
1. A rule changes by a decision of Bora, recorded as a D-n in the control room of the effort that raised it.
2. The chapter is edited in place; `CHANGELOG.md` gets one dated line with the D-n; superseded text is not kept inline.
3. A proposal that is declined is recorded once in `_rejected/<concept>.md` with the reason, so it is not re-proposed.
4. Retro candidates (D-13) are the normal source of changes; a mechanical one becomes a check (P-4), not a paragraph.
