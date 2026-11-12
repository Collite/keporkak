# 70 — Rules of engagement

> Who may say what to whom, when, and what happens otherwise. The executable form is `rules/stage-lifecycle.yaml`
> (the statechart), `rules/message-kinds.yaml` (the catalogue) and `rules/engagement.yaml` (roles, caps, presence);
> `scripts/check-rules.py` keeps the three consistent. This prose explains them; where they differ, the YAML wins and
> this file is wrong. Converged 2026-11-12 (KP D-9, D-20…D-28); amendments go through a D-n and `CHANGELOG.md`.

## EN-1 The rules are a statechart; the mailroom is its guard
Every stage has a lifecycle. A message is an attempt to fire one of its transitions. The mailroom — a mechanism, never a
decider (D-21) — delivers a message only if (a) the kind exists in the catalogue, (b) the sender's role owns that kind,
(c) the stage is in the transition's source state, (d) the guard holds, and (e) no cap is exhausted. Everything else is
**refused**, and a refusal is itself a message: a `nudge` back to the sender with the rule id and the failed guard.
Whatever is not explicitly allowed is forbidden; there is no other switch.

## EN-2 Three classes of autonomy
Every arrow is class **A** (runs without Bora), **C** (runs; Bora is cc'd on the board and in the digest) or **B** (Bora
must act). A kind moves from B to C only by a decision (D-n), proposed by the shadow ledger after N recorded agreements
(EN-9); `assignment-proposal`, `approval`, `ruling`, `reverse`, `queue-approval` and `phase-exit-request` never move.
Autonomy *profiles* (`supervised` · `assisted` · `autonomous`) are presets over the classes, chosen per effort; v1 runs
`supervised`.

## EN-3 Bora's gates
Bora approves a stage **at its start** — the assignment (rewrites R4.10). PASS merges; release and deploy follow where
the stage's plan names them (`rolls:` and `live:` lines). Bora also rules: the fourth fix round, a re-cut, a failed tail,
a frozen stage, a classification question, a contract change, a phase exit. He answers from a **non-agent client**
(`kp approve | decline | rule | reverse | queue | away`) signed by his OS user — never through a model's tool (RV-22).
While he is absent (EN-9), class-B requests are held, never refused.

## EN-4 The ladder: no endless loops
Two fix rounds run on the reviewer's verdict alone; a third needs the Principal's grant; a fourth Bora's ruling; then the
stage goes back to the planner (amends R5.3). Every re-review rules per open blocker only; a new finding is admissible
only if the fix introduced it (`introduced_by_fix`, attested and logged). The same ladder shape — *auto × N → Principal
→ Bora → stop* — caps every other loop: stop/resume (2, then Bora), proposals (2, then back to planned), seat re-seats
(3 events per stage, then pause), tail retries (1 each, then `tail_failed`), refusals (3 per seat per day, then pause).
Every reply-expecting kind has a deadline; expiry does what the catalogue says and never auto-resolves a ⚑ thread.

## EN-5 The fence and the stop
A stage list carries its fence with **stop-condition ids** (`SC-1 another repo must change`, …) and a `touches:` line
(`contracts · architecture · UX · infra · none`) that Bora saw at assignment. A coder does not ask questions; it takes
the default, writes a Found line, and continues — or, when the answer is a stop condition, it sends `stop` with the
SC id, pushes what compiles and ends its turn. Found lines travel batched in the PR hand-off and the `review-request`;
the mailroom fans them out to the Principal; a stage does not exit while a Found line has no triage outcome (adds to R4.1).

## EN-6 Observed, not attested
A guard about the world — CI green on the head sha, every task ticked with `done <sha>`, the GitHub PR review present,
no stray change in the main checkouts, the pin files untouched — is **checked by the daemon**. A guard about intent —
"DONE attempted", "blockers addressed" beyond the response lines — is **attested** by the sender and logged as such.
Since the TeamLead is both actor and auditor at the exit (D-25), the merge audit is the daemon's, not the TeamLead's.

## EN-7 The tail
`passed → merged → released → deployed → exited`, through the daemon's guarded operations (merge, release, deploy),
with skip edges for stages that roll nothing. Autonomous merge needs the **second key**: the reviewer's vendor differs
from the coder's; otherwise the merge is class B. Certification stages roll the **candidate before review** (U-1), so a
live DONE line is attemptable when the reviewer reads it. A failed merge, build or roll after one retry is Bora's
(`tail_failed`); a stray change at the audit freezes that stage only and pauses its seat. Seats push branches; master is
unreachable except through the daemon's identity (GitHub branch protection) — in the code repos and in `project`.

## EN-8 Seats, identity, offices
The seat is the identity (`coder-1`), the process is replaceable; a re-seat replays the list, STATUS and the unread
inbox. The Pool Manager observes seats (a dead process is seen at once; a silent long command is *busy*, not gone) and
its driver actions — pause, resume, compact, kill, retire — are rows of the matrix with caps, not side effects. Each
office keeps its own roster; `project` is the channel between offices: any Keporkak office may commit, push and merge it.

## EN-9 Presence, and the Deputy
The daemon tracks Bora's **presence**: `present` → `absent` after N idle hours or on `kp away`; back on any action.
*The Deputy is the policy that applies while he is absent* (D-28): B requests are **held**; the autonomous tail
**pauses** (the deadman switch); the **anomaly rules** may stop or pause (two FIX FIRST in a row, a flapping seat, a
budget jump, a stray change) but never approve; the only "yes" is for stages in the **pre-approved queue** Bora set
before leaving (U-2). On return, one **digest**: held items, stops, and the **shadow ledger** — what a judging Deputy
*would* have ruled, graded against what Bora then rules. Kinds graduate from recorded agreement, never from silence.
A judging Deputy that acts is a later decision, gated on that ledger.

## EN-10 What no guard holds
Say it rather than assume it: R5.6 (no steering) inside a verdict body; the Design-Roles borders inside a triage body;
read-exfiltration by a seat (every vendor mode reads everything); the retro's and the scout's judgment. These are held
by personas, reviews and retros — the rulebook's other chapters — not by the mailroom.

## Amendments this chapter makes to earlier rules
- **R4.2** stop list: "the 2nd review round" → *a fix round the ladder has not granted*.
- **R4.10**: *Bora approves a stage at its start — the assignment. The executor does not pause between tasks;
  "continue" means the next task. PASS merges; release and deploy follow where the stage's plan names them.*
- **R4.12** skeleton: stop-condition ids; a `touches:` line; `rolls:` and `live:` lines; `## Retro notes` (D-15).
- **R5.3**: the ladder (EN-4) and the per-blocker re-review.
- **R4.1** addition: a stage does not exit while a Found line has no triage outcome.
- **R6.2**: the `verdict` kind is guarded by the GitHub PR review on the same head sha; it links, it does not copy.
- **AGENTS.md** "do not commit or push `project`": superseded for the daemon (D-27 m); still true for human sessions.
