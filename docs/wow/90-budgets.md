# 90 — Budgets

> The shape is ruled (BU-4); the numbers are Bora's to set in planning.

- **BU-1 The mandate.** The Pool Manager monitors subscriptions, limits and consumption; directs work to seats with
  the budgets in mind; informs Bora and warns early when budget may become a constraint; enforces the constraints Bora
  sets, per subscription, per machine, or both. `coding-squad brief`
- **BU-2 Where the numbers come from, and how a turn is counted.** A turn's tokens arrive from the driver in four
  parts — input, output, cache write, cache read — and a turn counts their sum weighted by `usage.weights` of
  `pool/budgets.yaml`, each part against an input token (a cache read a tenth of one, an output token five), rounded to
  a whole number; a part the agent did not report counts zero. That count is what `seat.usage` returns, what a walk's
  `tokens` bounds and what the daily median sums; each turn's end leaves one record with the count and its four parts.
  How full a subscription is comes from the vendor's own words, as the agent passes them (`budget.consumption`): the
  daemon asks no endpoint of the vendor's and derives no fraction of its own. `KP 06` · `D-92, D-116`
- **BU-3 A budget jump is an anomaly** that pauses provisioning while Bora is absent (`70` EN-9). `D-28`

- **BU-4 The shape is slot limits per dimension** — in total, per subscription, per office, per effort — in
  `pool/budgets.yaml`, reloaded when changed, enforced as guards on `session-request` and `assign`. `D-39`

## Open — to be ruled
The numbers of the slot limits · warning thresholds in
numbers · what "warn early" means in days · whether a cap pauses or only warns.
