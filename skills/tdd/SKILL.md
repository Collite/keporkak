---
name: tdd
description: Build a stage's tasks test-first — specs at the public seam, then one red-green slice at a time. Use when executing a stage list that lands code, when a task says "Specs", or when fixing a blocker that names a missing regression test.
---

# Test-first, inside a stage

The stage list already says *what* must hold: its specs task names at most five behaviours, its DONE line the result.
This skill is how you get there with tests worth keeping.

## Where tests go
Tests verify behaviour through a **public seam** — the interface where you observe the behaviour without reaching
inside. Take the seams from the list and `contracts.md`; you do not ask anyone. If the list names no seam, choose the
outermost one the behaviour is visible at and write one Found line saying which.

## The specs task (T1) — the tests Bora reads
Bora reads these. They are how a person sees that the stage was really done, so they are written **for a reader**, and
there are **enough of them — not all of them**.

- **One spec per behaviour the list names** (at most five), in one file or one clearly named group for the stage. The
  spec's name is the list's line, as a sentence in the domain's words: `a stale bundle refuses the emit`.
- **Readable top to bottom in under a minute:** given · when · then, a dozen lines or so; the input and the expected
  value are literals you can see, not built by helpers three files away; no loops, no conditionals.
- **Enough** means two tests pass:
  - *the removal test* — for each DONE item, if that behaviour were deleted or broken, one of these specs would go red.
    If none would, a spec is missing.
  - *the reading test* — someone who has only the stage list could tick the DONE line from reading these specs and
    seeing them green. If they would have to read the implementation to believe it, a spec is missing or too clever.
- **Not everything:** edge cases, error paths and internals get tests in the slices below. They are for the next
  developer; they do not belong in the file Bora reads. When in doubt whether a case belongs here, ask: is it a
  behaviour the list names? If not, it goes below.
- They fail first, for the right reason (the behaviour is missing, not a typo).
- A `[QA]` item has no spec of yours: its readable evidence is QA's fixture and the command that produced it.

## The other tasks — one slice at a time (the tests the next developer reads)
Red before green: a failing test, then only enough code to pass it. One seam, one test, one minimal change per cycle.
Do not write a batch of unit tests ahead of the code — tests written against imagined structure go blind to real change.

## What a good test is
- It survives a refactor: it does not mock your own internal collaborators, test private methods, or look through a
  side channel (the database instead of the interface).
- Its expected value comes from an independent source — a literal, a worked example, the contract — never recomputed
  the way the code computes it.
- External collaborators (HTTP, gRPC, stores, the model gateway) are mocked or faked; behaviour that needs the real
  thing is a `[QA]` task, not yours.

## Not in the loop
Refactoring beyond what the task needs, speculative features, a later stage's component. If the code wants a refactor,
that is one Found line.

Adapted from mattpocock/skills `tdd` (MIT) — see `skills/_credits`.
