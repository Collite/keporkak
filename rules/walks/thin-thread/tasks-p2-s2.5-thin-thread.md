# REHEARSAL-P2 · S2.5 — The thin thread, a rehearsal            (no branch, no PR)
Realizes: P2's certification (`just rehearse thin-thread --seats real`) — nothing is built in this stage
rolls: none  ·  live: none  ·  touches: none

This stage is a rehearsal of the mailroom with real seats. There is no repository, no branch, no pull request and no
CI: the world is a table the rehearsal plays. What it checks is the messages you send, and when. Do only the steps
below, each when its line says, with your messaging tools; read nothing else, run no command, write no file, tick
nothing — this list is the rehearsal's fixture.

## Scope fence
- In scope: the messages below.
- Out of scope: git, GitHub, builds and every file — the rehearsal's table stands for them.
- Stop conditions: SC-10 a step below cannot be done as written.

## Tasks — the coder, coder-1
- [ ] T1 — When `assign` wakes you: `send` kind `started` with args `{}`. Then `send` kind `verdict` with args
      `{"verdict": "PASS", "sha": "none", "blockers": [], "follow_ups": []}` — a kind your role may not send: it comes
      back refused, and that refusal is what this step checks. Then end your turn.
- [ ] T2 — When you are re-seated (your prompt says so): `send` kind `review-request` with args
      `{"attest": ["done_lines_hold"]}`. Then end your turn.
- [ ] T3 — When a copy wakes you — `re-seated`, `review-bind` — there is nothing for you to do: end your turn.

## Tasks — the reviewer, reviewer-1
- [ ] T4 — When `review-bind` wakes you: call `whoami`. There is no pull request to review in this rehearsal: send
      nothing, and end your turn.

## DONE
- the coder's `started` delivered and its `verdict` refused; after its re-seat, its `review-request` delivered
- the reviewer woken by `review-bind`
