# TOY — task management

> Law order: KP's D-n › toy/contracts.md › toy/architecture.md › toy/plan.md › these lists. One PR per stage. Read
> `30-execution` (`keporkak/docs/wow/30-execution.md`) first, then this file, then the one stage list you were given.
> Until Keporkak carries stages itself, the "By hand" table of `../tasks-overview.md` applies.

## Progress
- [ ] **P0 · Skeleton and smoke**
  - [x] S0.1 The skeleton — [tasks-p0-s0.1-skeleton.md](./tasks-p0-s0.1-skeleton.md)
    done 25d4f09 #1; review-001 PASS — follow-ups filed (one fix round).
  - [ ] S0.2 Smoke — [tasks-p0-s0.2-smoke.md](./tasks-p0-s0.2-smoke.md) · cut 2026-11-24 (KP D-121), recut at six tasks 2026-11-27 (KP D-128); the first
    stage the daemon carries, in KP S3.5's one run with real seats — nobody starts it by hand
  - [ ] S0.3 The docs standard — by hand, before KP P4 (KP D-102, D-104); the list is cut when Bora wants it run
- [ ] **P1 · The library** — depends on KP/P4's exit; lists are cut then
  - [ ] S1.1 Parse
  - [ ] S1.2 Format, and a consumer (H-1′, with two planted triggers)
  - [ ] S1.3 The demo (`[QA]` on bp-dsk)

## Standing references
- `contracts.md` §1 the library · §2 the demo. `architecture.md` — modules, stack, deployment.
- The repo: `Collite/kp-toy`. The live target and who may touch it: Keporkak's rulebook `70` EN-11.

## Known gaps
- The toy has no profile until KP S0.3 writes `keporkak/docs/profiles/toy.md`.
- The spec "the version catalog has no dynamic version" understands single-line literal pins and `version.ref`
  only; any other way of declaring a version turns it red on purpose. Extend the spec in the stage that needs it.
- Open follow-up: Collite/kp-toy#2 (CI actions on a deprecated Node; `setup-java` v4 deprecated).
