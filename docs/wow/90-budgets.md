# 90 — Budgets

> The shape is ruled (BU-4); the numbers are Bora's to set in planning.

- **BU-1 The mandate.** The Pool Manager monitors subscriptions, limits and consumption; directs work to seats with
  the budgets in mind; informs Bora and warns early when budget may become a constraint; enforces the constraints Bora
  sets, per subscription, per machine, or both. `coding-squad brief`
- **BU-2 Where the numbers come from.** Per-turn token usage arrives from the driver for every vendor; subscription
  limits come from the vendors' own endpoints. `KP 06`
- **BU-3 A budget jump is an anomaly** that pauses provisioning while Bora is absent (`70` EN-9). `D-28`

- **BU-4 The shape is slot limits per dimension** — in total, per subscription, per office, per effort — in
  `pool/budgets.yaml`, reloaded when changed, enforced as guards on `session-request` and `assign`. `D-39`

## Open — to be ruled
The numbers of the slot limits · warning thresholds in
numbers · what "warn early" means in days · whether a cap pauses or only warns.
