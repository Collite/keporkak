# <effort>-<repo>-P<n> · S<n.m> — <title>            (PR: feat/<effort>-<repo>-p<n>-s<n.m>-<short>)
Realizes: D-… · Contract: contracts.md §… · Pins: <producer> <version> (master's at branch time)
rolls: none | <image or artefact>  ·  live: none | <target>  ·  touches: none | contracts | architecture | UX | infra

## Scope fence
- In scope: <one sentence — the deliverable of this stage>
- Out of scope: <neighbouring stages' deliverables, by name>; <anything tempting>
- Stop conditions — send `stop SC-n`, write the need under "Found while doing", push what compiles, end the turn:
  SC-1 another repo must change · SC-2 contracts.md must change · SC-3 a pin must move · SC-4 a table/endpoint not in
  contracts is needed · SC-5 a fix round the ladder has not granted · SC-6 the list exceeds 150 lines · SC-7 day <2× the
  plan, max 16> · SC-8 a border beyond `touches:` · SC-9 the live target is unavailable · SC-10 <stage-specific>

## Pre-flight
- [ ] <previous stage merged / release available / branch from master>

## Tasks (tick right after landing; append one line `done <sha>` per task; edit nothing else)
- [ ] T1 — Specs: <≤ 5 behaviours, one line each>
- [ ] T2 — …                                     (6–8 tasks)

## DONE (observable, all required)
- <behaviour visible from outside> · <test command + expected green> · <fixture saved at …> (if live)

## Exit
- [ ] PR opened as ready from the stage branch; CI green on head; the PR description is the hand-off
- [ ] review-NNN: PASS (fix rounds per the ladder: two on the verdict · a third on the Principal's grant · a fourth on Bora's ruling)
- [ ] merged · released (if rolls) · deployed (if live) — by the TeamLead through the daemon
- [ ] STATUS entry (≤ 5 lines: PR, merge sha, CI run, counts); register rebuilt; every Found line triaged

## Found while doing (one line each; the Principal triages — the executor never acts on these here)
-

## Retro notes (one line each: looked for and not found · a check that did not catch · an expensive tool call)
-
