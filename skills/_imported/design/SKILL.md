---
name: design
description: Run a structured design + brainstorming effort using the diverge-then-converge method with a control-room document set. Use whenever the user wants to design a feature, language, component, or system; says "design session", "brainstorm", "let's explore options", "open a workstream", "diverge"/"converge"; or references a control room, options docs, or a design folder. Also use when resuming an existing design effort (a `design/` folder with a `00-control-room.md` exists). This is the phase BEFORE `/planning` — if the user wants to design or think through a solution space, use this skill, not planning.
---
 
# Design — diverge, then converge
 
Claude has two jobs in a design effort:
 
1. **Partner.** Think alongside the user: enumerate alternatives (including the crazy ones), surface branches not yet explored, offer opinions and leans, push back when the effort narrows too early.
2. **Scorekeeper.** Hold the structure and keep the score: every option, grounding input, decision, open question, and deferral lands in the document set — nothing lives only in chat.

The reference implementation of this process are the examples in this skill folder: control room `00-control-room.md`, design-space map `01`, option docs `02`–`04`, review doc). When in doubt about tone, depth, or format, read those docs and imitate them.
 
## The core rule
 
**Diverge before converging.** Enumerate alternatives broadly and resist committing to a design until the option space has been walked. Divergence and convergence are distinct modes — never mix them silently; announce which gear the session is in.
 
| Gear | What happens | Output |
|---|---|---|
| **Framing** | Set scope, personas, success criteria, the load-bearing forks. Split the effort into **workstreams** (A, B, C, …). | Control room + design-space map populated. |
| **Divergence** | Pick one workstream. Enumerate *all* reasonable alternatives with trade-offs, examples, prior art. **No decisions.** | An `NN-<workstream>-options.md` option catalogue. |
| **Convergence** | Only once a workstream's options are well understood: weigh, decide, record. | Decisions in the log; workstream goes 🟢. |
 
## The document set (the score)
 
Lives in the repo, typically `docs/<feature>/design/`. Follow the repo's existing convention if one exists.
 
- `00-control-room.md` — the single dashboard. Open this first, every session. Contains: how-we-run-this, workstream dashboard, framing inputs, asset inventory, load-bearing forks, design principles, **append-only decision log**, parking lot, rolling open questions, session index. Skeleton in [`references/templates.md`](references/templates.md).
- `01-design-space-map.md` — the option tree. One section per workstream: **Question → Branches → Cross-links → Open**. Deliberately divergent: options are added, never removed without recording why.
- `NN-<workstream>-options.md` — one option catalogue per deep-dived workstream.
- `next-steps-<yymmdd>.md` — optional session handoff (where to resume).
- `review-<yymmdd>.md` — optional independent progress review (see Session mechanics).
**Final deliverables**, written when all workstreams are 🟢:
 
- `design.md` — the technical result. Compact; references the option docs and the decision log rather than restating them. **Its audience is Claude in the next `/planning` session** — it must contain everything planning needs (decisions, contracts-shaped constraints, scope boundaries, deferred items).
- `detailed-design.md` — the exhaustive write-up, a future manual: full prose, examples, rationale. Audience is a human reader who wasn't in the sessions.
## Identifiers and status vocabulary
 
Everything referenceable gets a stable ID, because later decisions cite earlier ones:
 
- Workstreams: letters (`A`, `B`, … `Z`); threads within one: `B-T5`; options: Greek (`α`, `β`, `γ`) or roman letters.
- `P-n` design principles · `Q-n` open questions · `GI-n` grounding inputs · `FI-n` framing inputs · `S-n` consolidation-sweep items.
- Workstream status: ⚪ not started · 🔵 diverging · 🟡 options captured · 🟢 converged · ⏸ parked.
- Decision log format (append-only, ground truth over everything else): `YYYY-MM-DD · [id] · Decision · Why · Alternatives rejected`.
## Divergence rules
 
- Aim for **≥3 alternatives per fork, including the "weird" one**. Implementability does not matter yet — a crazy option that gets rejected still maps the space and sharpens the why of the winner.
- For each option record: what it **buys**, what it **costs**, prior art / examples that show it, which other forks it constrains (cross-links).
- **No decisions in divergence.** When the user states a constraint or preference mid-divergence, record it as a **grounding input (GI-n)** — decision-shaped, narrows the forks, but not a converged decision.
- Leans are allowed and useful: mark them explicitly (*"Lean: β, because…"*) — a lean is not a decision.
- Capture open questions (Q-n) and cross-links as they surface.
- End each divergence session with: are we done diverging here? What would we need in order to converge?
## Convergence rules
 
- Walk the workstream **fork by fork**. Claude guides the order (most load-bearing first) and proposes; the user decides.
- **Be brief by default.** Present a fork as: one line of context, one line per option, then Claude's lean with a one-sentence reason. Chat stays terse — the exhaustive version lives in the options doc.
- **Dive deep on request.** When the user asks, expand fully: explain the fork, walk each option with concrete examples (render the hero scenario in each candidate, sketch the syntax, hand-run the algorithm), and give detailed pros/cons.
- Record **every** decision in the log immediately — including *why* and *which alternatives were rejected and why*. Rejected-with-reasons is half the value of the log.
- A decision that amends an earlier one says so explicitly (`amends B-T9`).
- After decisions: update the dashboard status, strike resolved Q-n entries (keep them visible, struck through), move deferred items to the parking lot with a revisit condition.
## Anti-rush guardrails (Claude enforces these)
 
- Every "let's just use X" gets at least one recorded alternative before it becomes a decision.
- Push back when converging on a fork whose dependencies haven't been diverged yet.
- No detail bikeshedding (naming, syntax) before the thing's purpose and audience are pinned.
- **"Deferred" is a valid, tracked outcome** — parking lot, with a revisit-when condition. Deferral ≠ cancellation.
- A useful pattern: adopt 1–3 named **design principles** (`P-n`) early (e.g. "small core, rich edges", "no miracles — everything explicit or deterministically derived") and cite them by ID in decisions.
## Session mechanics
 
- **Open the control room first, every session.** State where the effort stands and propose what this session should tackle; the user picks.
- Keep a **hero scenario**: one concrete end-to-end example chosen in framing and carried through every workstream — render candidate options against it.
- **End every session by updating the score**: decision log, dashboard statuses, session-index row, and (for multi-session efforts) a `next-steps` handoff so the next session starts cold-start-free.
- Mid-effort, offer an **independent review session**: re-read everything fresh, confirm or push back decision-by-decision, and flag undiscovered option space (see `review-260702.md` in ttr-p).
- When nearly done, run a **consolidation sweep**: batch-ratify the accumulated micro-decisions (S-n items) in one pass so no small fork is left decided-by-drift.
## Wrap-up and handoff
 
When all workstreams are 🟢 and the sweep is done:
 
1. Write `design.md` and `detailed-design.md` (see Final deliverables above).
2. Tell the user the design effort is complete and the next step is a `/planning` session, which consumes `design.md` and produces architecture, contracts, the phased plan, and task lists. Do not start planning inside the design session.
