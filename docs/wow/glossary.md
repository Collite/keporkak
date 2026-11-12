# Glossary

| term | meaning | avoid |
|---|---|---|
| **effort** | a thread of work with its own folder, STATUS and control room | project, epic |
| **phase** | a set of stages after which something deployable ships | milestone |
| **stage** | 6–8 tasks, one PR, one review, one DONE line | sprint, ticket |
| **task** | ½–1 day, one observable result | sub-task (there are none) |
| **stage list** | the markdown file of one stage; the executor's only plan | task log |
| **fence** | a stage's in-scope, out-of-scope and stop conditions | guardrails |
| **stop condition** (`SC-n`) | a named reason the executor stops instead of solving | blocker |
| **Found line** | one line of discovery under "Found while doing" | TODO |
| **acceptance spec** | one of the few tests of a stage written to be read: it names a listed behaviour and fails without it | unit test, test suite |
| **DONE line** | the observable result that ends a stage | acceptance criteria |
| **pin** | a producer version a repo builds on; changes only in a pin stage | dependency bump |
| **certification stage** | a stage whose DONE line is live | integration phase |
| **blocker / follow-up** | a review finding that violates contract, D-n, DONE or security / anything else | major, minor |
| **the ladder** | 2 fix rounds auto · 3rd by the Principal · 4th by Bora · then re-cut | retries |
| **re-cut** | the stage goes back to the planner | reopen |
| **control room** | `design/00-control-room.md`, the dashboard and decision log of an effort | notes |
| **D-n / ⚑ / GI-n** | a decision / a flag for Bora / a grounding input | — |
| **seat** | a named role instance (`coder-1`); the identity messages address | session, agent |
| **office** | a machine that runs seats | host, runner |
| **the daemon** | the Keporkak process of an office: mailroom, drivers, requested operations; it holds no rule (P-10) | server |
| **persona** | an entity that decides by judgement — an LLM seat (`agents/`) | agent |
| **robot** | an entity, or the half of one, that decides by its written rules (`robots/`) | automation, the daemon |
| **mechanism** | what decides nothing: facts, declared guards, declared effects, requested operations | — |
| **mailroom** | the daemon's mechanism that checks, stores, counts and refuses messages | mailmaster, a persona |
| **kind** | a message type from the catalogue | message type |
| **class A / C / B** | runs alone / runs with Bora cc'd / Bora must act | — |
| **presence** | whether Bora acted recently (`present` / `absent`) | online |
| **the Deputy** | what takes over while Bora is absent: the daemon's policy (hold, pause, queue, anomaly stops) and a seat that only writes shadow rulings | auto-Bora |
| **the queue** | the stages Bora pre-approved before leaving | — |
| **shadow ledger** | the log of what a judging Deputy would have ruled | — |
| **QA** | the one seat per office that touches the live target and opens a browser | tester, deployer |
| **live target** | the cluster where live DONE lines are observed (profile) | prod, the cluster |
| **the tail** | merge → release → deploy after PASS | pipeline |
| **second key** | reviewer vendor ≠ coder vendor, required for a merge without Bora | — |
| **design squad / coding squad** | Cowork roles that design and plan / CLI seats that build | Cat Paws, Blazing Arrows |
| **profile** | an ecosystem's bindings of the universal rules | config |
