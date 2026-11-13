# 80 — Seats and offices

> What is ruled so far (D-17…D-19, D-27, the spikes `05`, `06`, `06b`). Open items are listed at the end and are **not
> rules yet**.

- **SE-1 The seat is the identity** (`coder-1`); the process is replaceable. Messages address seats. `D-20`
- **SE-2 One driver.** V1 runs Claude Code through the pinned Claude ACP adapter behind a vendor-neutral driver
  interface. A handshake contract test runs on every upgrade. Codex and OpenCode adapters and their configuration
  paths are deferred until a second vendor is planned. `D-18, D-44`
- **SE-3 A seat never inherits the vendor's global configuration.** Each seat has its own config home
  (`CLAUDE_CONFIG_DIR` in v1), Claude mode `default`, and the assigned model/effort. Persona tools, denies, skills and
  hooks are compiled per session; MCP servers are injected. Unmatched permissions allow inside the working directory
  and deny outside, always as a one-time answer. Other vendors’ homes and permission joins are deferred. `D-18, D-19, D-44`
- **SE-4 Seats reach Keporkak only through the injected messaging MCP.** No other bus, no vendor cross-session
  messaging (denied in every seat). `D-17, KP I-3`
- **SE-5 Credentials.** Long-lived vendor tokens live in the seat's config home, never in a shell profile. Seats hold
  no credential that can move master; merge, tag, roll and issue-filing run through the daemon. `D-19, D-27 n`
- **SE-6 The daemon reports, the Pool Manager decides.** The driver reports a closed protocol or a dead process at
  once; the Pool Manager's robot half (`robots/pool.yaml`) turns the fact into `seat-gone` and a re-seat; a long silent
  command with an open tool call is *busy*. Re-seat replays the list, STATUS and the unread inbox; at most three
  seat-gone events per stage. Pause, resume, compact, kill and retire are rows of the matrix. `D-20, D-27 k`
- **SE-7 Watching.** A seat's tmux pane tails the daemon's transcript of that seat; the Pool Manager owns the windows. `D-18`
- **SE-8 Ranks.** `pool/roster.yaml` explicitly orders (model, effort) pairs, model before effort, and maps skill level
  × thinking size to a pair. V1 uses Claude only; a reviewer is a different seat in a fresh session, ranks at or above
  the coder’s pair, and meets the model review floor. The roster holds the actual CLI model names. `KP C3.3, D-44, D-45`
- **SE-9 An office** has git, the Claude CLI, the daemon and roots for worktrees, scratch, seat homes and secrets.
  V1’s one office is Panda: Linux, terminal only, reached by Bora over ssh. The Mac runs no daemon or seat; its watcher
  carries class-B notices and an ssh port forward exposes the board. The live target remains bp-dsk. Each office
  keeps its roster; the project repo is the channel between offices. `D-27 m, D-46`
- **SE-10 Conformance before a seat.** A new vendor or version runs the sandbox probes (`06b`) and the handshake test
  before it gets a seat. V1 is the Claude-only Panda office, with Bora present for the tail. The office check verifies
  root-peer command authentication; the OS-user split remains v1.1 work. `D-19, D-43, D-44, D-46`

- **SE-11 One QA seat per office,** with the live target's context, the stored credentials and a browser profile of its
  own. The office sets the vendor and the computer-use class (`A | B | C | off`; Bora's Mac: **B**). QA runs one job at
  a time and cleans up after each. `D-30, D-31`

- **SE-21 Working seats start fresh.** Coders, Reviewers, QA jobs, Retro, Deputy and Gardener get a new session for
  every stage or job — the seat name stays; what they need is in `reads_at_start`. The Principal, the TeamLead and the
  Pool Manager's seat live as long as their effort and are compacted. `D-39`
- **SE-22 The daemon owns worktrees.** It creates a stage's worktree at assignment (one per stage and repository,
  under the office's root) and removes it, with its scratch, at exit or close. A Reviewer gets a throwaway worktree of
  the PR head. Nobody creates a worktree by hand. `D-39`
- **SE-23 A stage never spans offices.** Its coder, reviewer and QA are the office's own. Only managers' traffic
  crosses offices, and it travels through the project repo. Version 1 is one office. `D-39`

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
  fallback and has a class per office. Panda is terminal only: computer use is `off` and QA uses headless Playwright.
  The class-B Mac policy belongs to a later Mac office. `D-36 c, D-46`
- **SE-20 Credentials are injected, never handled.** A seat obtains no token by itself: no copied headers, no
  clipboard, no "sign in for me". It names an identity; `kp-cred` puts a short-lived token into the command's
  environment. `D-36 d` (the mechanism: KP `16`)

- **SE-24 Root peers distinguish Bora from a seat.** State-changing commands use `sudo kp`. The office check must
  verify no `NOPASSWD` for Bora, no cached sudo ticket for `kp`, a root-owned `kp`, and `kp`/`sudo` on every seat’s
  deny list. The root helper only sends; the office-user daemon writes the log. Unsigned reads never change presence.
  Office bootstrap files are S0.3’s; a coder never installs them or runs sudo. `D-43`
- **SE-25 The office files and thresholds have owners.** `pool/` holds roster, office and budget configuration;
  `robots/pool.yaml` holds the ratified watchdog, open-call watch and compaction thresholds. A running seat is never
  stopped for a slot cap; only idle seats may be retired when the cap is exceeded. The office user’s readable App key
  files are an accepted v1 risk; user separation is v1.1 work. `D-46, D-47, D-50`

## Implementation boundaries
S0.3 supplies the Panda configuration and bootstrap templates. The roster uses actual CLI model strings. S2.2
packages Claude seat homes and defines the stop-gate requirements against a real hook; S0.1 declares its empty block
and cap only. The engine evaluates rules data while the daemon supplies observations and operations. `D-41, D-49, D-50`
