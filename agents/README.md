# Personas

One folder per seat role. `AGENT.md` is the **neutral** definition: frontmatter (machine-read) + the persona text
(second person). It never restates borders or message kinds — `scripts/render-agents.py` generates those sections from
`rules/engagement.yaml` and `rules/message-kinds.yaml` and writes the composed persona to `renders/<role>.md`.
`_common.md` is prepended to every seat. Vendor packaging (config-home files, permission settings) is derived from the
render and the `deny:` list during planning.

Frontmatter:
- `role` — a key of `rules/engagement.yaml:roles` · `squad` · `host` (`cli-seat` | `cli-scheduled` | `robot+seat` | `robot+cli-scheduled`)
- `engine` — `llm` (decides by judgement) or `hybrid` (plus a robot half: its written rules in `robots/<role>.yaml`,
  rendered into the persona). The daemon holds no rule (P-10); whatever decides is a persona or a robot.
- `instances` — `many` | `one-per-office` | `one-per-effort` | `few`
- `level` — default `{skill: senior|junior, thinking: S|M|L|XL}`; the roster binds it to a vendor model (SE-8)
- `reads_at_start` — what the seat loads before its first action (rulebook chapters, files)
- `tools` — what the daemon injects into the seat: `shell` commands and `mcp` servers. Nothing else is available
  to it (least tools per seat; never the vendor's global config).
- `skills` — skills the seat has; `modes` — name → when it applies and which skill it uses
- `deny` — mechanical borders in neutral words (`side-channels`, `push-master`, `pin-files`, `outside-worktree`,
  `live-target`, `browser`, `write-code`, `write-project`), translated per vendor
- `subagents` — helpers on a cheaper model, with their job

The design squad has no seats (D-8): see `design-squad/`.
