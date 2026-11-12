# 10 — The design protocol

> How a design effort runs. The executable form is the `design` skill (`skills/design/`); this chapter is its rule list
> plus what practice added. Applies to the design squad (Cowork) and to any design session in a CLI.

- **DS-1 Two jobs.** The agent is a *partner* (alternatives, leans, push-back) and the *scorekeeper* (every option,
  input, decision, question and deferral lands in the document set — nothing lives only in chat). `design skill`
- **DS-2 Three gears, announced.** *Framing* (scope, success criteria, workstreams) → *Divergence* (one workstream, all
  reasonable alternatives, no decisions) → *Convergence* (weigh, decide, record). Never mix gears silently. `design skill`
- **DS-3 The document set.** `design/00-control-room.md` (the dashboard: how-we-run-this, workstream table, framing,
  grounding inputs, decision log, flags, deferred), `01-design-space-map.md`, `NN-<workstream>-options.md`, hand-overs,
  reviews; final `design.md` (for the planner) and `detailed-design.md` (for a human who was not there). `design skill`
- **DS-4 Identifiers.** Workstreams `A…` or named (`C2`); options `α β γ δ` or `A B C`; `P-n` principles/proposals ·
  `Q-n` questions · `GI-n` grounding inputs · `I-n` ideas · `H-n` hero scenarios · `D-n` decisions · `⚑-n` flags for
  Bora · `RV-n` review findings · `U-n` undiscovered options · `S-n` sweep items. Statuses ⚪ 🔵 🟡 🟢 ⏸. An effort's own
  ids are bare; citations of another effort carry its prefix (`AU D-132`). `design skill + practice`
- **DS-5 Divergence.** At least three alternatives per real fork, including the weird one; per option what it buys,
  what it costs, prior art, cross-links. A constraint Bora states mid-divergence is a grounding input, not a decision.
  Leans are marked (➜) and are not decisions. `design skill`
- **DS-6 Convergence.** Fork by fork, most load-bearing first; brief by default (one line of context, one per option,
  the lean with one reason), deep on request. Every decision goes to the log at once:
  `date · id · decision · why · alternatives rejected`. An amendment says what it amends. `design skill`
- **DS-7 Anti-rush.** Every "let's just use X" gets one recorded alternative first; no converging on a fork whose
  dependencies were not diverged; no naming debates before purpose is pinned; deferral has a revisit-when. `design skill`
- **DS-8 Session mechanics.** Open the control room first; carry one hero scenario through every workstream; end every
  session by updating the score (log, statuses, flags) and, for multi-session efforts, a hand-over. `design skill`
- **DS-9 Questions.** Plain prose with a stated default, so "all defaults" is a valid answer. No question widgets.
  Leans and defaults are labelled as such. `house rule`
- **DS-10 Independent review before convergence.** A fresh session that did not write the options reads everything cold
  from a hand-over (`NN-handover-review-*.md`), gives a verdict per decision (confirm · push back · open), re-runs the
  hero scenarios, names at least three undiscovered options, edits nothing, decides nothing. The authors answer finding
  by finding (accept · accept with a change · push back) and bring the remaining flags to Bora. `practice AU, KP`
- **DS-11 Spikes.** A question evidence can settle gets a spike, not a debate: a brief (the question, numbered
  sub-questions with evidence required, a report shape, a fence, stop conditions), time-boxed, scratch only, run in a
  fresh CLI session; the report recommends, Bora rules. A surprising result is re-run in a cleaner environment before it
  is believed. `practice KP 05, 06, 06b`
- **DS-12 Consolidation sweep.** Before wrap-up, the accumulated micro-decisions are ratified in one pass. `design skill`
- **DS-13 Wrap-up.** All workstreams 🟢 → `design.md` + `detailed-design.md` → the next step is planning (`20`), in its
  own session. A design session writes no production code. `design skill`
- **DS-14 Design-Roles borders.** The coding squad does not redesign: changing UX flows or features, changing a
  protocol, adding or removing services, changing the authorization approach, replacing a library or the deployment
  technology all need the design squad and Bora. Small things (a button's colour, a field in a JSON, a library version)
  do not. In a stage list this is the `touches:` line (`30` EX-2). `coding-squad brief`
