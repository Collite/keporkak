# 70 — Rules of engagement

> Who may say what to whom, when, and what happens otherwise. The executable form is `rules/stage-lifecycle.yaml`
> (the statechart), `rules/message-kinds.yaml` (the catalogue) and `rules/engagement.yaml` (roles, caps, presence);
> `scripts/check-rules.py` keeps the three consistent. This prose explains them; where they differ, the YAML wins and
> this file is wrong. Converged 2026-11-12 (KP D-9, D-20…D-28; QA D-30, D-31); amendments go through a D-n and `CHANGELOG.md`.

## EN-1 The rules are a statechart; the mailroom is its guard
Every stage has a lifecycle. A message is an attempt to fire one of its transitions. The mailroom — a mechanism that holds no rule of
its own (D-21, P-10) — delivers a message only if (a) the kind exists in the catalogue, (b) the sender's role owns that kind,
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
`passed → merged → released → deployed → exited` — merge and release through the daemon's guarded operations, the deploy by QA (EN-11) —
with skip edges for stages that roll nothing. Autonomous merge needs the **second key**: the reviewer's vendor differs
from the coder's; otherwise the merge is class B. Certification stages roll the **candidate before review** (U-1), so a
live DONE line is attemptable when the reviewer reads it. A failed merge or build after one retry, or a roll QA had to roll back, is Bora's
(`tail_failed`); a stray change at the audit freezes that stage only and pauses its seat. Seats push branches; master is
unreachable except through the daemon's identity (GitHub branch protection) — in the code repos and in `project`.

## EN-8 Seats, identity, offices
The seat is the identity (`coder-1`), the process is replaceable; a re-seat replays the list, STATUS and the unread
inbox. The Pool Manager observes seats (a dead process is seen at once; a silent long command is *busy*, not gone) and
its driver actions — pause, resume, compact, kill, retire — are rows of the matrix with caps, not side effects. Each
office keeps its own roster; `project` is the channel between offices: any Keporkak office may commit, push and merge it.

## EN-9 Presence, and the Deputy
The daemon tracks Bora's **presence**: `present` → `absent` after N idle hours or on `kp away`; back on any action.
*The Deputy takes over while he is absent* (D-28, D-33) — one entity with two halves: a **robot** that acts by its written rules (`robots/deputy.yaml`) and a seat that only shadows.
The daemon merely reports that he is absent; everything that follows is the Deputy's: B requests are **held**; the autonomous tail
**pauses** (the deadman switch); the **anomaly rules** may stop or pause (two FIX FIRST in a row, a flapping seat, a
budget jump, a stray change) but never approve; the only "yes" is a `queued-approval` for stages in the **queue** Bora signed
before leaving (U-2). On return, one **digest**: held items, stops, and the **shadow ledger** — for every held item the
Deputy seat wrote what it *would* have ruled, graded against what Bora then rules. Kinds graduate from recorded agreement, never from silence.
A judging Deputy that acts is a later decision, gated on that ledger.

## EN-11 QA and the live target
**QA is the only seat that touches the live target and the only seat that opens a browser or uses computer use** — one
QA per office, vendor per office. Other seats ask: a `qa-request` names the stage, the task and the DONE item to verify,
never a command; QA decides how, runs one job at a time (tail jobs first), and answers `qa-report pass|fail` with
pointers. Candidates run as temp pods in a `qa-<stage>` namespace outside GitOps and are dropped at the end of the job.
After a merge the TeamLead sends `deploy-request`; QA has the daemon make the GitOps change, smoke-tests it, and
reports `deploy-done` — or rolls back to the previous pin by itself and reports `deploy-failed`, after which the stage
is Bora's. QA's browser work is scripted (Playwright, its own profile) and needs no approval. Only **computer use** has a class per
office (`A | B | C | off`); on Bora's own machine it is **B**: every `computer-use-session` waits for him. QA never edits code, plans or lists, and never echoes a credential. This exclusivity is
a rule of the way of working, not a fence (D-31): it is held by the role borders, by deny lists where a vendor makes
them cheap, and by the retro.

## EN-10 What no guard holds
Say it rather than assume it: R5.6 (no steering) inside a verdict body; the Design-Roles borders inside a triage body;
read-exfiltration by a seat (every vendor mode reads everything); the retro's and the gardener's judgment. These are held
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
