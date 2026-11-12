# Rulebook changelog

One line per change: date · rule ids · what · the decision. Newest first.

- 2026-11-12 · PL-11, RW-14 · **Acceptance specs are written to be read**: enough tests to see the stage was really done (the removal test, the reading test), not every test. · KP D-38
- 2026-11-12 · SE-12…SE-20 · **Tools per seat**: least tools, injected by the daemon, `just` first, CLI before MCP, no polling, scripted browser vs computer use, credentials injected never handled. · KP D-36
- 2026-11-12 · P-10, EN-1, EN-9, SE-6 · **The daemon holds no rule.** Deciding belongs to personas and robots; `robots/` holds the rule-based halves of the Deputy, the Pool Manager, the Retro and QA; the mailroom sends only four declared effects. · KP D-35
- 2026-11-12 · — · The issue-tending seat is named **Gardener** (was "Scout"); the **Deputy** is a shadow seat. · KP D-33, D-34
- 2026-11-12 · EX-16, EN-11, SE-11, PL-9 · **The QA seat**: the only seat on the live target and in a browser; `qa-request` / `qa-report`; QA deploys on the TeamLead's request and rolls back its own failed roll; computer use per office. · KP D-30, D-31
- 2026-11-12 · — · First edition ratified. · KP D-29
- 2026-11-12 · all · **First edition.** Extracted from `planning-skill-rules-260714.md` (R1–R8), `collite-gh/AGENTS.md`,
  `planning-conventions.md`, the `design` and `planning` skills, the coding-squad brief, and Keporkak D-1…D-28. · KP C1
- 2026-11-12 · EX-5 · R4.10 rewritten: approval at the stage start, not the exit. · KP D-22
- 2026-11-12 · RW-4, RW-5, EX-2 `SC-5` · R5.3 and R4.2 amended: the fix-round ladder; re-review per open blocker. · KP D-23
- 2026-11-12 · PL-8, PL-9, EX-2, EX-8 · R4.12 skeleton: `rolls:` · `live:` · `touches:`, stop-condition ids, Retro notes. · KP D-15, D-27 l
- 2026-11-12 · EX-7 · R4.1 addition: no exit while a Found line is untriaged. · KP D-27 (RV-29)
- 2026-11-12 · EX-6 · the executor takes the default; a question is a `stop`. · KP D-27 i
- 2026-11-12 · EX-13, EX-14 · `AGENTS` "do not commit or push project" narrowed to human-attended sessions; the daemon
  commits, pushes and merges; master only through the daemon. · KP D-27 m, n
- 2026-11-12 · RW-9 · reviewer vendor ≠ coder vendor preferred; required for a merge without Bora. · KP D-27 (U-6)
- 2026-11-12 · PL-8 · the skeleton's "Pins: … (frozen for this phase)" reads "master's at branch time", following the
  2026-11-01 rewrite of R4.7. · R4.7
