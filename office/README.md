# The office — what Bora installs on Panda

Keporkak's office is Panda (D-46): Linux, terminal only, reached over ssh. Its description is in `pool/offices.yaml`.
The repository installs nothing on it. This folder holds the templates Bora installs from, and the steps below.

`just office-check` (from `~/Dev/keporkak/keporkak`, as `bora`) prints one line per item:

- `ok` — in place;
- `to do` — Bora's, with the exact command;
- `later` — a later stage delivers it, and the line names that stage.

It reads and reports only, and exits 1 while anything is `to do`. P0 exits when nothing is `to do`.

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

The tools come from mise on Panda (`~/.zshrc`: `mise activate`), and git, gh, jq and tmux from apt. If the check
reports one missing, its line gives the command.

## 2. Before S1.5 — the `sudo` conditions for `kp` (contracts.md §6, D-43)

A state-changing `kp` command is signed by running it under `sudo`; the daemon accepts it only from a root peer.
That means something only if `sudo` asks Bora for his password every time. Install the drop-in:

```sh
sudo visudo -cf office/sudoers.d/zz-keporkak-kp
sudo install -o root -g root -m 0440 office/sudoers.d/zz-keporkak-kp /etc/sudoers.d/zz-keporkak-kp
sudo -l
```

`sudo -l` must show no `NOPASSWD` entry for `bora`. If one is there, remove it from the file that holds it
(`sudo visudo` or `sudo visudo -f /etc/sudoers.d/<file>`). The drop-in makes `kp` ask anyway: it sorts last, so its
`PASSWD` rule decides for `kp`, and `timestamp_timeout=0` means a ticket from an earlier `sudo` is not reused.

S1.5 installs `kp` at `/usr/local/bin/kp`, owned by root and not writable by `bora`. The seats' compiled permissions
deny `kp` and `sudo` (S2.2).

## 3. At S1.5 — the systemd user service

`systemd/keporkak.service` is a template: S1.5 fills in the daemon's launcher and working directory. Once it has:

```sh
mkdir -p ~/.config/systemd/user
cp office/systemd/keporkak.service ~/.config/systemd/user/keporkak.service
systemctl --user daemon-reload
```

S1.5's `just run`, `just stop` and `just logs` drive it from then on.

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

**Before P3 — the Apps' keys.** For each of the four Apps, generate its private key on GitHub and copy it to Panda
(Panda has no browser; for example `scp` it from the Mac), then:

```sh
mkdir -p -m 0700 ~/.config/keporkak/secrets/apps
install -m 0600 <the copied key>.pem ~/.config/keporkak/secrets/apps/kp-merger.pem
```

and the same for `kp-coder`, `kp-reviewer` and `kp-gardener`. Delete the copies on both machines afterwards.
