# 80 — Seats and offices

> What is ruled so far (D-17…D-19, D-27, the spikes `05`, `06`, `06b`). Open items are listed at the end and are **not
> rules yet**.

- **SE-1 The seat is the identity** (`coder-1`); the process is replaceable. Messages address seats. `D-20`
- **SE-2 One driver.** Seats are driven over ACP: Claude Code and Codex through their adapters, OpenCode natively.
  Adapter versions are pinned; a handshake contract test runs on every upgrade. `D-18`
- **SE-3 A seat never inherits the vendor's global configuration.** Each seat has its own config home
  (`CLAUDE_CONFIG_DIR` / `CODEX_HOME` / `OPENCODE_CONFIG_DIR`) and a per-session mode: Claude `default` (asks),
  Codex `approval_policy=never` with no auto-reviewer, OpenCode with its permission config injected per launch. `D-18, D-19`
- **SE-4 Seats reach Keporkak only through the injected messaging MCP.** No other bus, no vendor cross-session
  messaging (denied in every seat). `D-17, KP I-3`
- **SE-5 Credentials.** Long-lived vendor tokens live in the seat's config home, never in a shell profile. Seats hold
  no credential that can move master; merge, tag, roll and issue-filing run through the daemon. `D-19, D-27 n`
- **SE-6 The daemon reports, the Pool Manager decides.** The driver reports a closed protocol or a dead process at
  once; the Pool Manager's robot half (`robots/pool.yaml`) turns the fact into `seat-gone` and a re-seat; a long silent
  command with an open tool call is *busy*. Re-seat replays the list, STATUS and the unread inbox; at most three
  seat-gone events per stage. Pause, resume, compact, kill and retire are rows of the matrix. `D-20, D-27 k`
- **SE-7 Watching.** A seat's tmux pane tails the daemon's transcript of that seat; the Pool Manager owns the windows. `D-18`
- **SE-8 Ranks.** `pool/roster.yaml` orders models across vendors (for RW-9) and maps skill level × thinking size to a
  model and effort per vendor. `KP C3.3`
- **SE-9 An office** is a machine with git, the vendor CLIs, the daemon, a worktree root and a scratch root. Each
  office keeps its own roster; the project repo is the channel between offices. `D-27 m`
- **SE-10 Conformance before a seat.** A new vendor or version runs the sandbox probes (`06b`) and the handshake test
  before it gets a seat. Until the daemon owns the file-system fence (v1.1), v1 runs Bora-present on his Mac. `D-19`

- **SE-11 One QA seat per office,** with the live target's context, the stored credentials and a browser profile of its
  own. The office sets the vendor and the computer-use class (`A | B | C | off`; Bora's Mac: **B**). QA runs one job at
  a time and cleans up after each. `D-30, D-31`

## Tools
- **SE-12 Least tools per seat.** A seat gets the tools its modes need and no others; each is listed in its persona. `D-36`
- **SE-13 The daemon injects a seat's tools** at session start from the persona — never from a vendor's global config. `D-36, D-18`
- **SE-14 `just` first.** Where a repo has a `just` recipe for a job, a seat calls the recipe, not the commands under
  it. A job seats repeat gets a recipe. `D-36 a`
- **SE-15 A command line before an MCP server** where one exists and the seat has a shell (`gh`, `kubectl`, `git`). An
  MCP server where a sandbox blocks the network, where results must be structured, or where the tool is ours. `D-36`
- **SE-16 A tool that changes the world belongs to exactly one role** — or is a requested operation of the daemon. `D-36`
- **SE-17 Do not poll.** `gh run watch`, `gh pr checks --watch`; never a loop of status calls. `D-36`
- **SE-18 A skill is a seat's procedure;** a skill no mode names is not installed. `D-36`
- **SE-19 Browser work is scripted** (Playwright, QA's own profile) and needs no approval; **computer use** is the
  fallback and has a class per office — B on Bora's Mac. `D-36 c`
- **SE-20 Credentials are injected, never handled.** A seat obtains no token by itself: no copied headers, no
  clipboard, no "sign in for me". It names an identity; `kp-cred` puts a short-lived token into the command's
  environment. `D-36 d` (the mechanism: KP `16`)

## Open — to be ruled (C3 / planning)
QA's tooling (C5, first item) · what a seat loads at start · context-health thresholds and when to compact · the roster's actual model table · seat
naming across offices · when a seat is retired · the office bootstrap checklist.
