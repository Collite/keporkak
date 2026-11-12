# 60 — Status, register, reports

- **ST-1 The project repo is the home** of every effort's status, plan and tasks — the single place to look for
  "what is open, and where". `R1`
- **ST-2 `STATUS.md` frontmatter:** `effort · repo_home · code_home · state · phase · next · blocked_on · gates ·
  updated · stream · lane` (`templates/STATUS.md`). `R2 + practice`
- **ST-3 STATUS is state, not history:** frontmatter + ≤ 10 lines of position + the last 5 log entries (each ≤ 5
  lines); older entries roll into `STATUS-log-<yymmdd>.md` at each phase exit. Cap 8 KB. `R4.9`
- **ST-4 The register is generated.** After any STATUS edit `scripts/build-register.py` rebuilds `REGISTER.md`; the
  board is a derived view of the project repo, the mail store and the roster — it holds no state of its own. `R1, KP I-6`
- **ST-5 A status report has three parts:** (a) what the agent is doing now, (b) what Bora needs to do now — say
  "nothing" when that is the case, (c) what the agent proposes to do after approval. Agent-owned tasks are never
  presented as instructions for Bora. `AGENTS`
- **ST-6 Bora's attention budget.** When something is needed from him: list every waiting item in the suggested order
  of handling; give the important details of the first one inline; for the rest give the file path and approximate
  lines. Only the three managers report to him (`70`). `coding-squad brief, D-27 h`
- **ST-7 Phase exit bookkeeping:** the dependency chart refreshed (PL-6), STATUS rolled over (ST-3), floors checked
  (PL-14), the retro delivered (D-13) — then Bora's gate (`70`, the phase machine). `R3, R4.9, R8, D-27`
