# <effort> — plan

> Realizes design.md (D-…). Architecture: architecture.md · Contracts: contracts.md. Law order: D-n › contracts ›
> architecture › this plan › the stage lists.

## 1. Phases
| phase | deliverable (deployable) | builds on (pins) | DONE evidence (attemptable at this phase's end) | depends on |
|---|---|---|---|---|

## 2. Stages per phase
### P<n> — <title>
| stage | after | goal (testable) | ~task titles | rolls · live · touches | pre-flight | DONE |
|---|---|---|---|---|---|---|

`after`: the stages and phases of this plan the stage waits for — ids only (`S<n.m>`, `P<n>`), separated by commas, or
`—` for none; the daemon reads a stage's wave from it (contracts.md §4.4, Amendment 32), so prose belongs in pre-flight.

## 3. Dependency chart
```mermaid
flowchart LR
  P0[P0 …]:::done --> P1[P1 …]:::ready
  P1 -.-> P2[P2 …]
  classDef done fill:#cfe8cf,stroke:#2e7d32
  classDef ready fill:#fff4ce,stroke:#b58900
  classDef ext fill:#e0e0e0,stroke:#757575
```
Solid = needs the exit; dashed = the frozen contract suffices. Refreshed at every phase exit. Waves are gates.

## 4. Open questions and assumptions (each with a default)
