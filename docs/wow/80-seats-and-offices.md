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
- **SE-6 The Pool Manager observes seats.** A closed protocol or a dead process is `seat-gone` at once; a long silent
  command with an open tool call is *busy*. Re-seat replays the list, STATUS and the unread inbox; at most three
  seat-gone events per stage. Pause, resume, compact, kill and retire are rows of the matrix. `D-20, D-27 k`
- **SE-7 Watching.** A seat's tmux pane tails the daemon's transcript of that seat; the Pool Manager owns the windows. `D-18`
- **SE-8 Ranks.** `pool/roster.yaml` orders models across vendors (for RW-9) and maps skill level × thinking size to a
  model and effort per vendor. `KP C3.3`
- **SE-9 An office** is a machine with git, the vendor CLIs, the daemon, a worktree root and a scratch root. Each
  office keeps its own roster; the project repo is the channel between offices. `D-27 m`
- **SE-10 Conformance before a seat.** A new vendor or version runs the sandbox probes (`06b`) and the handshake test
  before it gets a seat. Until the daemon owns the file-system fence (v1.1), v1 runs Bora-present on his Mac. `D-19`

## Open — to be ruled (C3 / planning)
What a seat loads at start · context-health thresholds and when to compact · the roster's actual model table · seat
naming across offices · when a seat is retired · the office bootstrap checklist.
