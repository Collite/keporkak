# Profile — the Tatrman ecosystem

> The bindings of the universal rulebook for the Tatrman repos. Source: `collite-gh/CLAUDE.md`, `collite-gh/AGENTS.md`,
> `planning-skill-rules-260714.md` R1, R2, R7, R8. Where this file and those disagree, fix this file.

- **Project repo:** `~/Dev/collite-gh/project` — areas `tatrman/ server/ kantheon/ platform/ designer/ common/`;
  `REGISTER.md` by `scripts/build-register.py`; the board by `scripts/build-dashboard.py`. `PL-1, ST-4`
- **Repos (folder → GitHub):** `tatrman` → `Collite/ttr-core` · `tatrman-server` → `Collite/ttr-server` · `hartland` →
  `Collite/ttr-demo` · `tatrman-platform` → `Collite/ttr-platform` · `kantheon` · `olymp` (GitOps) · `modeler` (frozen).
- **Streams and lanes (PL-7):** `stream: dev | project`; lanes `coding` · `junior` · `bora-unblock` (dev) and
  `bora-legal` · `bora-support` · `claude-support` (project). Historical `senior1`/`senior2` values stay until touched.
- **Live target (PL-9 `live:`):** every certification stage, live golden, smoke or drill runs on **`bp-dsk`**
  (kube-context `bp-dsk`, always pass `--context`; olymp `clusters/bp-dsk/`). **`hartland` is the demo cluster**; its
  olymp tree changes only for a demo release Bora calls. "hartland" the content (repo, world, bundles, data) keeps its
  name; a plan written before 2026-10-31 that says "on hartland" reads "on bp-dsk". `R7`
- **Floors (PL-14):** the reader floor is hartland's `toolchain.lock [ttr-core].ref`. Every service that parses model
  text pins ≥ that grammar minor; wire readers follow the plan-proto/translator pin. Checked at every phase exit and
  whenever the floor moves; a reader below the floor is a pin stage before its next live step. The planner lists the readers' pins against the floor in the phase-exit STATUS entry, one
  line per repo. Readers move to the latest patch of the floor's minor, not patch by patch. The cluster rolls together, readers before the model. `R8`
- **Pins live in** `gradle/libs.versions.toml` or `toolchain.lock` (EX-4).
- **Releases:** on 0.x.x, internal releases suffice; no public registry release unless Bora asks. Grammar bundle
  versions follow the `.g4` grammar's major.minor. `AGENTS`
- **Olymp** has no PR CI: its evidence is the `just preflight` run. `R6.1`
- **Library references (PL-12):** context7; cloned sources with graphify output at `~/Dev/view-only/{calcite,koog,
  kotlin-mcp-sdk}`; examples in `~/Dev/ai-platform/EXAMPLES.md` (Ktor, serialization, Kotlin MCP server, Koog,
  Calcite, Vue, OTel).
- **Scratch:** `~/Dev/_scratch/<repo>/<stage>/` (EX-15).
- **DCO:** agents add Bora's `Signed-off-by` with his configured identity (authorized 2026-10-24).
