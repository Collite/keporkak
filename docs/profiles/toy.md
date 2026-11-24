---
# The profile's machine part (contracts.md §10): what agents/packaging/claude.yaml's placeholders need. The prose below
# says where each value comes from; where they disagree, fix this frontmatter.
# The effort whose seats are built with this profile (contracts.md §10, Amendment 22).
effort: TOY
default_branch: master
# Relative to a repo's root; globs as the packaging data reads them.
pin_files: [gradle/libs.versions.toml, gradle/wrapper/gradle-wrapper.properties]
cluster_tools: [kubectl, kustomize, helm]
# No demo cluster: bp-dsk is the live target, and only QA touches it.
demo_context: null
---
# Profile — the toy

> The bindings of the universal rulebook for the toy Keporkak v1 is proven on (effort `TOY`, D-40, D-48 Q-6): a small
> Kotlin duration library. Source: `project/toy/` (plan, contracts, architecture) and `pool/offices.yaml`. Where this
> file and those disagree, fix this file.

- **Project repo:** `~/Dev/keporkak/project` → `Collite/keporkak-project`, under `toy/` (plan, STATUS, the stage
  lists, reviews); its log is `mail/TOY/` in the same checkout (contracts.md §3). `PL-1, ST-4`
- **Repos (folder → GitHub):** `kp-toy` → `Collite/kp-toy` — one Gradle build: `lib` (the library) and, from S1.2,
  `consumer` (uses the library as a published release). Checkout `~/Dev/keporkak/kp-toy` on the office; a stage's
  worktree is `~/Dev/keporkak/_wt/kp-toy-<stage>`.
- **Streams and lanes (PL-7):** the values of `keporkak.md`. TOY's efforts are `dev` / `coding`.
- **Live target (PL-9 `live:`; only QA touches it, EX-16):** **`bp-dsk`**, and only for the demo of S1.3: QA's temp
  pod in a `qa-<stage>` namespace, outside GitOps, dropped when the job ends (EN-11). Always pass
  `--context bp-dsk`. Nothing is deployed durably; the toy has no realm client, no secret, no ingress. `R7`
- **Floors (PL-14):** none.
- **Pins live in** `gradle/libs.versions.toml` and `gradle/wrapper/gradle-wrapper.properties` of `kp-toy`. The toy
  pins its own versions; the only pin it shares with `keporkak` is JVM 21. `consumer`'s pin of the library's release
  is the planted pin of H-1′ (EX-4).
- **Releases:** nothing is published before the pin stage that S1.2 provokes — the release `consumer` needs is
  deliberately not published (H-1′). The coordinates and registry are decided when S1.2 is cut (default: GitHub
  Packages of `Collite/kp-toy`); the demo's image goes to the repository's container registry (`toy/plan.md` §4).
- **Library references (PL-12):** the Kotlin standard library only (`lib` has no other dependency); JUnit 5 for tests.
- **Scratch:** `~/Dev/_scratch/kp-toy/<stage>/` (EX-15).
- **DCO:** agents add Bora's `Signed-off-by` with his configured identity (`git commit -s`; authorized, EX-14).
