# The office — what Bora installs on the office machine

Keporkak's office is **dsk** since 2026-11-24 (it was Panda, D-46; the move is the homelab's host swap D8a, KP D-112):
Linux, terminal only, reached over ssh. Its description is in `pool/offices.yaml`.
The repository installs nothing on it. This folder holds the templates Bora installs from, and the steps below.

`just office-check` (from `~/Dev/keporkak/keporkak`, as `bora`) prints one line per item:

- `ok` — in place;
- `to do` — Bora's, with the exact command;
- `later` — a later stage delivers it, and the line names that stage.

It reads and reports only, and exits 1 while anything is `to do`. P0 exited when nothing was `to do`; since S1.5, `kp`,
its `sudo` rule and the service are checked too (§2, §3).

## 1. Now — for P0's exit

The office's roots (`pool/offices.yaml`; plan.md §4.1). The secrets directory is private to the office user:

```sh
mkdir -p ~/Dev/keporkak/_wt ~/Dev/_scratch ~/.local/share/keporkak/seats
mkdir -p -m 0700 ~/.config/keporkak/secrets
```

Lingering, so the office user's services run without a login session (architecture.md §4):

```sh
sudo loginctl enable-linger bora
```

Then `just office-check` ends with `RESULT: READY`.

The tools come from mise on the office (`~/.zshrc`: `mise activate`), and git, gh, jq and tmux from apt. If the check
reports one missing, its line gives the command.

The build's own Python is made by the first recipe that needs it: `just` makes a virtual environment in
`build/python` from the first of `python3.14` … `python3.11` on the path, and installs `requirements.txt` into it.
Where that Python has no `venv` module — Ubuntu's `python3.14` without its `python3.14-venv` package — the recipe
stops with one line that says so. Then either name a Python that has it for that first run, for example mise's:

```sh
KP_PYTHON=~/.local/share/mise/installs/python/3.13/bin/python3 just office-check
```

or install the system's package (`sudo apt install python3.14-venv`) and run the recipe again. `build/python` is kept
from then on; a fresh clone, or `build/` removed, makes it again.

## 2. The `sudo` rule for `kp` (contracts.md §6, D-43)

A state-changing `kp` command is signed by running it under `sudo`; the daemon accepts it only from a root peer.
That means something only if `sudo` asks Bora for his password every time. Install the drop-in:

```sh
sudo visudo -cf office/sudoers.d/zz-keporkak-kp
sudo install -o root -g root -m 0440 office/sudoers.d/zz-keporkak-kp /etc/sudoers.d/zz-keporkak-kp
sudo -l
```

`sudo -l` must show no `NOPASSWD` entry for `bora` at all (D-43). Panda had one, `/etc/sudoers.d/90-bora-nopasswd`;
where the office has one, look at it, and remove it:

```sh
sudo cat /etc/sudoers.d/90-bora-nopasswd
sudo rm /etc/sudoers.d/90-bora-nopasswd
sudo -l
```

A `NOPASSWD` entry anywhere else — another file of `/etc/sudoers.d`, or `/etc/sudoers` itself (`sudo visudo`) — goes
the same way. The drop-in makes `sudo` ask for `/usr/local/bin/kp` and for nothing else: a `NOPASSWD` rule beside it
would still let anything running as `bora`, a seat among them, run `sudo sh -c 'kp approve …'` without a password,
and the daemon would take that as your signature. `timestamp_timeout=0` means a ticket from an earlier `sudo` is not
reused for `kp`.

The office check reads `/etc/sudoers.d`'s listing and the drop-in's owner, mode and size. Their content needs `sudo` to
read, so every rule there but the drop-in and sudo's own `README` is "to do" until you have looked at it and removed
it. `/etc/sudoers` it cannot read at all: `sudo -l` is yours to look at.

The seats' compiled permissions deny `kp` and `sudo` (S2.2: the `side-channels` border; see §5).

## 3. The daemon and `kp` — S1.5's steps, in order

Run them on the office, as `bora`, from the checkout the daemon is to run: the office's, `~/Dev/keporkak/keporkak`
(`pool/offices.yaml`: `repos.keporkak`) — or, to try a stage before it merges, that stage's worktree, then again from
the office's checkout once it has merged. The daemon reads the rules of the checkout it was installed from.

1. **Install the daemon and its service.** Nothing here runs `sudo`:

   ```sh
   just install
   ```

   It builds the daemon, copies it to `~/.local/share/keporkak/daemon/`, writes the systemd user unit
   (`office/systemd/keporkak.service`, its launcher and working folder filled in) to
   `~/.config/systemd/user/keporkak.service`, checks that the unit's `PATH` — mise's shims first — finds a JDK 21,
   runs `systemctl --user daemon-reload`, and prints the one step that is yours, with `sudo`.

2. **Install `kp`, root-owned**, where the `sudo` rule names it — the line step 1 printed:

   ```sh
   sudo install -o root -g root -m 0755 ~/Dev/keporkak/keporkak/scripts/kp /usr/local/bin/kp
   ```

   Again after a pull that changes `scripts/kp`; the office check says when the installed one differs.

3. **The `sudo` rule**, if it is not in place yet: §2.

4. **Check:**

   ```sh
   just office-check
   ```

   `kp`, the `sudo` rule and the service are `ok`; it names the command for anything still to do.

5. **Rehearse** (architecture.md §7). In one ssh session:

   ```sh
   just rehearse h1-prime
   ```

   The daemon starts on a throwaway project under `build/rehearsal/`, plays H-1′ with scripted seats, and stops at
   the line `waiting for Bora's approval: kp inbox, then sudo kp approve <ticket>`. It listens on the office's
   socket, so with the service running it refuses to start: `just stop` first. In a second session:

   ```sh
   kp inbox                     # the proposal, its ticket, class B, and the list's three lines
   sudo kp approve <ticket>     # asks for your password; delivered — the walk goes on
   sudo kp approve <ticket>     # asks again; refused: the item has had its approval
   kp approve <ticket>          # no sudo: refused, unsigned — and in the log
   ```

   The walk then plays on to its next wait — `sudo kp rule <ticket> stage` answers it — and to `exited`. `kp status`
   shows where it is; `kp check build/rehearsal/<its folder>/mail` re-judges what it wrote. Ctrl-C ends it.

   `just rehearse h1-prime --seats fake` plays the same walk with the walk's seats as processes — S2.1's fake agent —
   that the daemon starts, wakes with their mail, re-seats when one dies, and watches, as it will real seats (S2.5).
   Their homes, transcripts and worktrees are under the throwaway project's `office/`.

6. **The service.** `just run` starts the office's daemon, `just logs` follows its journal, `just stop` stops it. It
   works on the project checkout (`~/Dev/keporkak/project`) and writes its log there, under `mail/`: start it when a
   stage says to. It drives the seats (S2.4): it starts and re-seats them through the adapter `just adapter` installed,
   wakes an idle one with its mail, serves their five tools and the stop hook on `listen.mcp` of `pool/offices.yaml`,
   and reports their facts every `watch.every`. Until the observers and performers of P3 exist it observes nothing of
   the world but your signed commands, and every operation that is not a seat's but `notify.bora` ends `failed`.

7. **Watching the seats.** Each live seat has a window in the tmux session `kp`, named after it, tailing its transcript
   (`<roots.seats>/_transcripts/<seat>.log`); the window opens when the seat starts and closes when it is retired or
   gone. From an ssh session:

   ```sh
   tmux attach -t kp            # Ctrl-b w lists the seats' windows; Ctrl-b d leaves them running
   ```

   An office without tmux says so once in the journal; the seats run all the same.

8. **The thin thread, with real seats** (S2.5; contracts.md §12, Amendment 27). It spends tokens: run it when a stage
   says to, and once.

   ```sh
   just stop                                  # the rehearsal listens on the office's socket
   just rehearse thin-thread --seats real     # in one ssh session
   ```

   It starts `coder-1` and `reviewer-1` as Claude seats on the adapter `just adapter` installed, with the token in
   `claude-token` — the coder at `senior S`, the reviewer at the coder's line and the review floor: both on Opus 5.5 at
   low effort — their homes, worktree and transcripts under the throwaway project's `office/`, the list and STATUS they
   read beside the walk (`rules/walks/thin-thread/`). It stops at `waiting for Bora's approval`: in a second session
   `kp inbox`, then `sudo kp approve <ticket>`; `tmux attach -t kp` shows the two transcripts as the seats work. From
   then on the seats send their own steps: the coder its `started`, a `verdict` it may not send — refused — and, once
   the rehearsal has ended its process and the Pool Manager has re-seated it, its `review-request`; the reviewer is
   woken by `review-bind`. Each of a seat's steps is waited for at most 10 minutes, and the seats may use 1.5 million
   tokens together. It ends when the turns the last message woke have ended — or at the first step that does not
   come as the walk says, and names it — stops the seats, prints where its evidence is
   (`~/Dev/_scratch/rehearsals/thin-thread-<time>.md`: the log, each seat's sessions, transcript, model, effort and
   tokens, P2's DONE line by its log lines) and exits, 0 when the thread passed. Beside the evidence, in
   `thin-thread-<time>/`, are the log (`mail/`) and the seats' transcripts (`transcripts/`), copied however the run ended;
   the evidence points there, not into the checkout's `build/` (S3.6). `just run` starts the service again.

   What it costs: S2.5's run (2026-11-19) used 388 861 tokens — the coder 232 471 in two turns, the reviewer 156 390
   in one — and took 28 seconds after the approval; `just handshake` before it, 140 576 (its evidence). The log and the
   seats' transcripts of a run on tokens are kept until its phase's retro is triaged: they are what that retro reads.

   `just handshake --personas coder,reviewer,teamlead,principal` (S3.6, D-96; 2026-11-21) used 298 818 tokens: S2.1's
   steps, then two turns that ask for nothing for a seat of each of the four personas, on the roster's cheapest pair.
   Each such turn counts about 20 thousand: the first reads 11 384 from the cache and writes 7 619–8 388 (the persona's
   own context), the second reads 19 003–19 772 and writes 186–323. Its evidence, each turn by its parts:
   `~/Dev/_scratch/handshakes/handshake-0.106.0-2026-11-21T111526Z.md`.

   Counted as the daemon counts a turn since S3.13 — its four parts weighted by `usage.weights` of `pool/budgets.yaml`
   (D-116) — the same eight turns count 58 397, not 158 196 at par: a persona's first turn 11 334–12 959, its second
   2 155–2 401. So `just handshake --personas coder` spends about 15 thousand for the coder's two turns weighted, 40
   thousand at par, beside S2.1's steps. The evidence shows each turn both ways — by its parts and weighted — and what
   the vendor said of the subscription through the seats, or that it said nothing.

`kp` without `sudo` reads (`kp inbox`, `kp status`, `kp check`); with `sudo` it answers (`approve`, `decline`,
`rule`, `reverse`, `queue`, `away`) — and any command run with `sudo` counts as you acting. `kp help` lists them.

**A seat that died a fourth time in its stage** (D-118). A stage's seats may die three times; each death pauses the
stage and the Pool Manager re-seats the seat. A fourth leaves the stage `paused` and the seat `gone`, and the Pool
Manager asks you: `kp inbox` shows a `ruling-request` from `pool`, `default: reseat`. Answer it one of two ways:

    sudo kp rule <ticket> reseat seat=<seat>   # one more re-seat: the seat comes back, and so does the stage
    sudo kp rule <ticket> recut                # the stage is recut; its seats are then retired

Each later death asks you again. While you are away the request is held like every class-B item.

**`tools/kp-notify` — your inbox on the Mac** (contracts.md §12, Amendment 45). On your Mac, in a terminal you leave
open, from a checkout of this repository there:

    tools/kp-notify dsk        # dsk: the office, as your ~/.ssh/config names it

It holds `ssh dsk kp inbox --follow` and raises one notification for every class-B item as it comes — one `kp inbox`
marks `B`, or `B, held by …` when the Deputy kept it for you: the ticket and kind as its title, the stage under it,
and the first of the list's three lines (`rolls: …`). Nothing else of the message is shown; `kp inbox` on the office
has the rest. What waited when it started is raised once, as a count. When the connection ends it says so once and
tries again, waiting longer each time up to a minute. It reads no secret, writes no file and needs nothing macOS does
not ship; Ctrl-C ends it. The first notification may ask you to allow notifications from Script Editor (System
Settings → Notifications). `notify.bora` on the office stays `ok` at once: the inbox is the notification.

## 4. The secrets directory

Outside every repository; the directory is `0700` and every file `0600`, owned by `bora` (architecture.md §4).
A seat can read these files — accepted for v1 under D-19; the OS-user split is v1.1's first item (D-46).

```
~/.config/keporkak/secrets/        0700
├── claude-token                   0600  the seats' Claude token — before P2 (S2.1 reads it)
└── apps/                          0700
    ├── kp-merger.pem              0600  the GitHub Apps' private keys — before P3 (S3.2 reads them)
    ├── kp-coder.pem               0600
    ├── kp-reviewer.pem            0600
    └── kp-gardener.pem            0600
```

**Before P2 — the seats' token.** Create a long-lived token with `claude setup-token`, then write it alone on one
line (paste it, then Ctrl-D):

```sh
install -m 0600 /dev/stdin ~/.config/keporkak/secrets/claude-token
```

**Before P3 — the Apps' keys.** For each of the four Apps, generate its private key on GitHub and copy it to the office
(for example `scp` it from the Mac), then:

```sh
mkdir -p -m 0700 ~/.config/keporkak/secrets/apps
install -m 0600 <the copied key>.pem ~/.config/keporkak/secrets/apps/kp-merger.pem
```

and the same for `kp-coder`, `kp-reviewer` and `kp-gardener`. Delete the copies on both machines afterwards.

**`just apps merge` — the live proof of the daemon's operations on GitHub (S3.9; contracts.md §12, Amendment 37).**
Run it on the office, in the keporkak checkout, when you want to see a merge, a close and an issue work as `kp-merger`; it
needs no daemon and spends none of the seats' tokens. It acts on `Collite/kp-toy` only:

- it reads which rules hold the toy's default branch and whom — and stops, having made nothing, if no rule holds the
  coder's App to `update`;
- it makes three branches `kp-apps-merge-<UTC time>-base`, `-head` and `-main` off the default branch, a few
  one-line commits under `kp-apps-merge/`, and two pull requests: `-main` into the default branch, `-head` into `-base`;
- on the pull request into the default branch it shows the coder's merge refused and a merge as `kp-merger` on a head
  other than its own refused — and, only where a second ruleset holds `kp-merger` to green checks, a merge before the
  checks are green refused — then closes that pull request unmerged;
- on the pull request between its own branches it shows a conflict, makes the head agree with the base, and merges
  it with a merge commit — into `-base`, never into the default branch;
- it files one issue and closes it; it deletes its three branches; it reads the default branch's sha again and says
  whether it is where it was.

It prints one line per step — `yes`, or `NO` and why — and never a token, a key or a claim; it exits 0 when every step
is as §12 gives it. What it leaves on GitHub: the two pull requests, closed (one merged into its own base, one
unmerged), and the one issue, closed. If GitHub ever merges into the default branch where it must refuse, the run says
`SC-10`, stops there and still deletes its branches; tell the Planner.

## 5. A seat's home — nothing to install, nothing to edit

Each seat has a Claude config home, `~/.local/share/keporkak/seats/<seat>/` (`roots.seats`), and its Claude Code
reads that home and nothing of yours: not `~/.claude/`, not `~/.claude.json` (S2.2's probe; contracts.md §10). The
driver builds it at every start of the seat, from the keporkak checkout:

```
~/.local/share/keporkak/seats/<seat>/     0700
├── CLAUDE.md          0600  renders/<role>.md, byte for byte
├── skills/<name>/           the skills the persona lists, and no other
├── settings.json      0600  the three hooks of hooks/, and nothing else — no permission rules
├── permissions.json   0600  the persona's deny rules, compiled with each border's name; the hooks and the driver answer from it
└── bin/               0700  seat/'s two programs, 0700: git-credential-keporkak and gh (§6)
```

Those five are replaced at every start; what Claude Code keeps beside them — its sessions, its own `.claude.json`
— stays, because loading a session needs it. **Nothing in a home is edited by hand**: a change there is gone at the
next start. A border changes in the persona (`agents/<role>/AGENT.md`), the packaging data
(`agents/packaging/claude.yaml`) or the profile's frontmatter (`docs/profiles/<name>.md`), through a stage. Never
run `claude mcp add`, `claude config` or `/permissions` with a home as `CLAUDE_CONFIG_DIR`: `just office-check`
reports a home whose `.claude.json` names an MCP server.

The seats' transcripts are beside the homes, in `~/.local/share/keporkak/seats/_transcripts/<seat>.log`.

## 6. A seat's GitHub identity — and `just apps prove`

A seat holds no GitHub key and no GitHub token (contracts.md §4.4, Amendment 35). Its home's `bin/` is first on its
path: when its `git` or `gh` needs GitHub, the program asks the daemon (`POST /hook/github-token` on the seat API,
with the seat's session token), which mints a token then — for the App `pool/offices.yaml`'s `github.seats` gives the
seat's role, narrowed to its stage's repository — and hides it in the seat's transcript, where a token handed before
the daemon was started again is hidden too, by its prefix `ghs_`, when the seat's session is loaded and replayed.
`git` is told, through its environment, to use that helper and no other, to reach GitHub over https where a remote
says `git@github.com:`, and never to prompt; `gh` runs the office's own `gh` with the token in that one process's
environment. Nothing is installed for it, and no `gh auth login` is needed for a seat: your own `gh` login and
`~/.gitconfig` stay yours, and a seat's `git` uses no helper they name.

**Your `~/.netrc` names no `github.com`** (contracts.md §4.4, Amendment 47). A seat runs as you, with your `HOME`, and
`git` reads `$HOME/.netrc` for every https request before it asks the seat's helper — nothing in a seat's environment
turns that off. An entry for `github.com` there, or a `default` entry, which `git` takes for any machine, would reach
GitHub as you, not as the seat's App. `just office-check` says "to do" when `~/.netrc` names either, and nothing when it
names neither or there is none; it reads the machines' names only and prints no login and no password. On dsk there is
none. Keep a token you need there for another machine; take a `github.com` or `default` entry out.

**`just apps prove`** (from `~/Dev/keporkak/keporkak`, on the office, where the Apps' keys are) is the live proof of it, on
`Collite/kp-toy`, with no agent and none of the seats' Claude tokens. It builds a home for the coder's persona and one
for the reviewer's in a throwaway folder (`build/apps-prove/`), and a listener of its own answers their asks. Then, on
GitHub:

1. the coder clones the toy over `git@github.com:…` — rewritten to https — commits one file on a branch
   `kp-apps-prove-<UTC time>`, and pushes that branch;
2. the coder pushes to `master`, and the ruleset refuses it;
3. the coder opens a pull request from the branch, under the name GitHub shows for `kp-coder` (`collite-kp-coder`);
4. the reviewer leaves a comment review on it, as `kp-reviewer`;
5. the coder approves its own pull request, and GitHub refuses it;
6. the coder closes the pull request, and
7. deletes the branch.

Steps 6 and 7 run whatever happened before. **It leaves nothing on GitHub**: no open pull request, no branch — the
closed pull request stays in the toy's history, as every closed one does. It prints each step's outcome and never a
token, removes its throwaway folder, and exits 0 when every step went as above. A step that GitHub lets through
where it must refuse (2 or 5) is printed as `SC-10`: the ruleset and the Apps' settings are yours.

## 7. Carrying a stage — the first carried stage (S3.5)

The toy's `TOY/P0/S0.2` is carried by the daemon on the office (contracts.md §12, Amendment 46; D-48, D-122): the walk
`rules/walks/first-carried-stage.yaml` sends what the TeamLead and the Pool Manager would send — they have no seat before
P4 — and waits for your steps and the two Claude seats'. It runs where the service runs: the office's project checkout
and its log, its main checkouts, GitHub. Every fact is read and every operation performed as the service does; nothing
is supplied. It spends tokens: it runs once, on your word (D-122: one coder and one reviewer, real, once), and a run that
stops is not started a second time without the Principal's word.

**Before.** From `~/Dev/keporkak/keporkak` on master, once S3.5 has merged and CI is green there:

```sh
just install && just office-check                         # READY
just stop                                                 # while it runs, the rehearsal is the office's daemon
just rehearse first-carried-stage --world office --check
```

`--check` reads and changes nothing. It prints the five conditions of a start — the service stopped, the stage's list
in the project, the project checkout and both main checkouts clean, you present in the office's log — how far the log
already went with the walk, the stage's facts its first step reads (six tasks, the fence, the plan's row, the slots),
the Apps as GitHub answers for them, and the rules GitHub holds `kp-toy`'s `master` to: the coder's App to `update`,
`kp-merger` to green checks (D-123). It ends `ready`, or lists what is missing and exits 1. Absent in the log? `just
run`, any `sudo kp status`, `just stop`.

**The run.** In one ssh session:

```sh
just rehearse first-carried-stage --seats real --world office
```

The TeamLead's `assignment-proposal` comes first, class B: the run prints `waiting for Bora's approval: kp inbox, then
sudo kp approve <ticket>`. In a second session, `kp inbox`, then `sudo kp approve <ticket>`. Nothing more is yours. The
daemon makes the stage's worktree in `kp-toy`; the walk asks the Pool Manager for the two seats and the daemon starts
them, `coder-1` and `reviewer-1`, both `senior S` (Opus 5.5, low); the TeamLead assigns the coder; the coder sends
`started`, and while that turn is in flight the run ends its process — the stage pauses, POOL-1 re-seats it with its
session, the stage goes on. The coder pushes its branch, opens the pull request as `collite-kp-coder[bot]` and sends
`review-request`; the TeamLead binds the reviewer, who reviews on GitHub as `kp-reviewer[bot]` and sends its `verdict`;
the TeamLead's `merge` is class C — you are copied — and the daemon merges as `kp-merger` on green checks. The
TeamLead's STATUS entry is appended to `toy/STATUS.md`, then its `stage-exit`: the daemon removes the worktree, rebuilds
`REGISTER.md`, commits and pushes the project. `tmux attach -t kp` shows the two transcripts. Each step of a seat is
waited for at most 60 minutes; the two seats may use 2 000 000 tokens, weighted as `pool/budgets.yaml` counts them.

It ends when the turns the `stage-exit` woke both seats for have ended, prints where its evidence is —
`~/Dev/_scratch/rehearsals/first-carried-stage-<time>.md` — and exits, 0 when the stage is `exited`. Then `just run`
starts the service again, and `kp check ~/Dev/keporkak/project/mail` re-judges the office's log, the stage's among it,
under the rules in force: it should print no difference.

**What the evidence holds.** The walk's steps with their log lines; each seat's sessions, model and effort, and its turns
weighted and by their parts beside the walk's bound; what the seats sent beyond the walk; each operation with its
result; the pull request and the merge commit; and every record of the run on the office's log, named by its `seq` —
the log is the office's and is not copied. Beside it, `first-carried-stage-<time>/transcripts/` holds both transcripts.
No token of any kind is in either. A turn that its seat's death ended reported no usage: the evidence says so.

**When it stops.** A step that does not come as the walk says ends the run and names the step, the seat and its
transcript: a seat that sends another kind, a seat silent for 60 minutes, the seats past their tokens, a step refused.
Nothing it wrote is taken back: the log keeps every line, the stage stays where the log has it (`kp status`), and the
evidence is written however it ended. The seats stop with it. Take the evidence and what it names to the Principal.

**Going on after a stop** — on the Principal's word only. The same command goes on: started again for a stage the log
already holds, it checks the steps the log holds against the walk — none is sent again — and plays on from the first it
does not hold. The seats the log held live died with the last run: the start reports them gone, POOL-1 re-seats them,
and the walk goes on once they are back. Each of those deaths counts against the stage's three
(`seat_gone_events_per_stage`), the walk's own among them: with both seats live, one start again is all the stage takes;
a second leaves it `paused` and asks you for a ruling, as a fourth death always does (above). A log that went another way
than the walk — a message of yours or the TeamLead's that is not the walk's next — is refused, naming the step; the log
and the stage are then the Principal's. A stop while an operation was in flight leaves that operation `failed:
interrupted`, as at any start, and the walk may then wait for what never comes: that is a stop to take to the
Principal too. Going on, the project checkout may hold what the run itself changed before its push — the log under
`mail/`, the stage's list as the coder ticked it, `toy/STATUS.md`, `REGISTER.md` — and nothing else.

**In CI.** `just check` plays the same walk with S2.1's fake agent as both seats on a fixture office (`kp-daemon`'s specs
in `kp.daemon.carried`): three bare remotes the spec builds, a local server for GitHub that keeps the toy's pull
requests, reviews and merges, and the fixture's own copies of the toy's plan, overview, list and STATUS. It reaches no
network but that server, starts no real agent and spends no token.
