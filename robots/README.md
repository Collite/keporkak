# Robots

A **robot** is the rule-based engine of an entity: it decides by the rules written here, and by nothing else. The
daemon holds no rule of its own (P-10) — it reports facts, checks declared guards, carries out declared effects and
requested operations. Whatever *decides* is a persona (judgement), a robot (these rules), or both halves of one entity.

One file per entity that has a robot half: `role`, `active_when`, `intercepts` (kinds the mailroom routes to it — a
declared effect), and `rules`: `id · when (a fact or an arriving message) · then (the kind it sends, to whom)`.
A robot is in the matrix like any seat: it may send only its role's kinds, under their caps. It can run inside the
daemon's process; it is still a separate entity with its own name in the log.
