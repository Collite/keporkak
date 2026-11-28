---
effort: TOY — the toy project Keporkak v1 is proven on (a small Kotlin duration library)
repo_home: project (~/Dev/keporkak/project/toy)
code_home: [kp-toy (~/Dev/keporkak/kp-toy)]
state: "S0.1 exited: the skeleton builds and tests in CI on master"
phase: "P0 · S0.1 exited; S0.2 is cut and waits for KP S3.5's run"
next: "nobody by hand: the daemon carries S0.2 in KP S3.5's one run with real seats (KP D-121)"
blocked_on: []
gates: []
updated: 2026-11-24
stream: dev
lane: coding
---

# TOY — status

A fixture with real CI: three small stages that make the platform walk H-1′ and a small H-3. S0.1 (the skeleton)
runs by hand now; S0.2 is the first stage the daemon carries (KP S3.5); P1 runs when KP/P4 has exited.

- **2026-11-14 — S0.1 exited.** Collite/kp-toy#1 merged at `25d4f09`; CI green on master (5 specs, 3 build
  regressions; the run id is in the PR's Verified section). review-001: FIX FIRST (B1, B2) → PASS — follow-ups filed
  after one fix round; follow-up kp-toy#2. Five Found lines triaged (`_closed/`). The agent: nothing. Bora: nothing.
- **2026-11-13 — planned.** `plan.md`, `architecture.md`, `contracts.md` and the list of S0.1 written. The agent:
  nothing. Bora: starts S0.1 when he wants it.
