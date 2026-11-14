# Profile — Keporkak

> The bindings of the universal rulebook for Keporkak itself (effort `KP`, D-47 finding 7). Source: the KP plan,
> `contracts.md`, `architecture.md`, `pool/offices.yaml` and the decisions D-27 n, D-44, D-46. Where this file and
> those disagree, fix this file. The toy Keporkak is proven on has its own profile: `toy.md`.

- **Project repo:** `~/Dev/keporkak/project` → `Collite/keporkak-project` — the plan, STATUS, the stage lists, reviews,
  and from P1 on the log under `mail/` (contracts.md §3). The toy's plan is under `toy/` in the same checkout.
  No register yet: S3.3 brings it. `PL-1, ST-4`
- **Repos (folder → GitHub):** `keporkak` → `Collite/keporkak` (the platform: one Gradle build, the rules, robots,
  personas, skills, scripts, `pool/`, `office/`) · `kp-toy` → `Collite/kp-toy` (see `toy.md`). All checkouts are
  under `~/Dev/keporkak/` on Panda; a stage's worktree is `~/Dev/keporkak/_wt/<repo>-<stage>`.
- **Streams and lanes (PL-7):** `stream: dev | project`; lanes `coding` · `junior` · `bora-unblock` (dev) and
  `bora-legal` · `bora-support` · `claude-support` (project). KP's efforts are `dev` / `coding`.
- **Live target (PL-9 `live:`; only QA touches it, EX-16):** Keporkak's own stages have `live: none` until P4. From
  S4.1 the live target is **`bp-dsk`** (kube-context `bp-dsk` on Panda, delivered for P4 — plan.md §4.3; always pass
  `--context`), and only the QA seat touches it, through `kp-qa` on temp pods in `qa-<stage>` namespaces (EN-11).
  Computer use is `off` on Panda; QA's browser work is headless Playwright (D-46). `R7`
- **Floors (PL-14):** none yet. Keporkak parses no model text and has no wire readers of its own.
- **Pins live in** `gradle/libs.versions.toml` (Kotlin, the ACP and MCP SDKs, their libraries; each with where it was
  checked) · `gradle/wrapper/gradle-wrapper.properties` (Gradle, with its checksum) · `requirements.txt` (the Python
  tools behind `just`) · `.github/workflows/*.yml` (actions by commit) · `pool/roster.yaml`
  `vendors.claude.adapter_version` (the Claude adapter, from S2.1; its `sdk` repeats the catalog's `acp` pin and
  `check-rules.py` fails when they differ) (EX-4).
- **Releases:** none in v1. The daemon is upgraded on Panda only at a phase exit, by Bora (`just install`), never by a
  merge (plan.md §4.2).
- **Library references (PL-12):** context7 — the ACP Kotlin SDK `/websites/deepwiki_agentclientprotocol_kotlin-sdk`,
  the protocol `/websites/agentclientprotocol`, the MCP Kotlin SDK `/modelcontextprotocol/kotlin-sdk`. The spike in
  `~/Dev/_scratch/ba-spike-acp/` may be read and cited; nothing is copied from it without a task that says so.
- **Scratch:** `~/Dev/_scratch/<repo>/<stage>/` (EX-15).
- **DCO:** agents add Bora's `Signed-off-by` with his configured identity (`git commit -s`; authorized, EX-14).
