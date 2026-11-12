---
name: diagnosing-bugs
description: A disciplined loop for a bug, a failing check or a regression met while executing a stage or repairing a blocker. Use when something is broken, throwing, flaky or slow and the cause is not obvious within a few minutes.
---

# Diagnosing a bug

Skip a phase only with a reason you write down.

## 1. Build a feedback loop — this is the skill
One command that goes **red on this bug** and will go green when it is fixed: a failing test at the seam that reaches
it; a CLI call with a fixture diffed against a known-good output; a replayed captured request; a throwaway harness
around one code path; a bisection or old-versus-new differential. Make it fast (seconds), deterministic (pin time, seed,
filesystem, network) and sharp (it asserts the exact symptom, not "did not crash"). For a flaky bug raise the
reproduction rate until it is workable. **No red-capable command, no hypothesis.**

If the loop needs the live target or a browser, that is QA's: send a `qa-request` for the item and work on what you
can. If you cannot build a loop at all, stop with the stage's stop condition and say what you tried.

## 2. Reproduce and minimise
Watch it go red with the symptom you were given — not a neighbouring failure. Then cut inputs, config and steps one at
a time until every remaining element is load-bearing.

## 3. Hypothesise
Three to five ranked hypotheses before testing any, each with its prediction: "if X is the cause, changing Y makes it
disappear". No prediction, no hypothesis. You do not wait for anyone to re-rank them.

## 4. Instrument
One variable at a time, each probe tied to a prediction. A debugger before logs; targeted logs at the boundary that
separates two hypotheses; never "log everything". Tag every debug line with one prefix so cleanup is a single search.
For a slowdown: measure a baseline first, then bisect.

## 5. Fix, with the regression test first
Turn the minimised repro into a failing test at a seam that exercises the real bug pattern; watch it fail; fix; watch
it pass; re-run the original loop. If no such seam exists, that is the finding — one Found line.

## 6. Clean up
The original repro is green · the regression test passes · every tagged debug line is gone · throwaway harnesses are
deleted or in scratch · the confirmed cause is stated in the commit message.

## Secrets
Loops read credentials from the environment (`kp-cred`, where you have it); nothing you quote contains one.

Adapted from mattpocock/skills `diagnosing-bugs` (MIT) — see `skills/_credits`.
