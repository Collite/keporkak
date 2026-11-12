#!/usr/bin/env python3
"""Keporkak rules: validate the three YAML files against each other and render the catalogue as markdown.

Checks (each a line of output; exit 1 if any FAIL):
  - every transition in stage-lifecycle.yaml names a kind that exists in message-kinds.yaml
  - every kind whose `fires` names a stage transition has that transition in the lifecycle (by from→to)
  - every kind's `from` roles exist in engagement.yaml:roles (or are 'mailroom'/'the asker' forms)
  - every state referenced by a transition exists; every non-terminal state has an exit; every active state is reachable
  - cycles in the stage chart: with the capped edges and the Bora-gate states removed, the graph must be acyclic
  - graduation.never kinds exist
  - P-10: the mailroom sends only declared effects, the daemon sends nothing; every robot rule sends a kind its role may send
Usage: python3 scripts/check-rules.py [--render out.md]
"""
import sys, re, yaml, pathlib, itertools

ROOT = pathlib.Path(__file__).resolve().parents[1]
L = yaml.safe_load((ROOT / "rules/stage-lifecycle.yaml").read_text())
K = yaml.safe_load((ROOT / "rules/message-kinds.yaml").read_text())["kinds"]
E = yaml.safe_load((ROOT / "rules/engagement.yaml").read_text())
fails = []
def fail(m): fails.append(m); print("FAIL", m)
def ok(m): print("ok  ", m)

states = set(L["states"])
trans = L["transitions"]
roles = set(E["roles"])

# 1. transitions → kinds
for t in trans:
    if t["kind"] not in K: fail(f"transition {t['from']}→{t['to']} fires unknown kind '{t['kind']}'")
    for s in (t["from"], t["to"]):
        if not s.startswith("*") and s not in states: fail(f"transition {t['from']}→{t['to']} references unknown state '{s}'")
ok(f"{len(trans)} stage transitions checked against {len(K)} kinds")

# 2. kinds → transitions (only those that name a stage transition 'a→b')
pairs = {(t["from"], t["to"]) for t in trans}
for name, k in K.items():
    f = str(k.get("fires", "-")); src = None
    for tok in [t.strip() for t in f.split("|")]:
        if "→" in tok:
            src, dst = [x.strip() for x in tok.split("→", 1)]
        else:
            dst = tok
        if not src or not dst or ":" in tok or " " in dst: continue   # companion-machine notes, prose
        if src.startswith("*") or dst.startswith("*"): continue
        if src not in states or dst not in states: continue  # companion machines use other state names
        if (src, dst) not in pairs: fail(f"kind '{name}' fires {src}→{dst} which the lifecycle does not have")
ok("kinds' `fires` cross-checked")

# 3. roles
for name, k in K.items():
    for r in re.split(r"\s*\|\s*", str(k["from"])):
        if r not in roles and r not in ("mailroom",): fail(f"kind '{name}' from unknown role '{r}'")
ok("sender roles exist")

# 4. states: exits and reachability
non_terminal = [s for s, d in L["states"].items() if not d.get("terminal") and not d.get("overlay")]
for s in non_terminal:
    if not any(t["from"] == s for t in trans): fail(f"state '{s}' has no exit")
reach = {"planned"}; frontier = ["planned"]
while frontier:
    s = frontier.pop()
    for t in trans:
        if t["from"] == s and not t["to"].startswith("*") and t["to"] not in reach: reach.add(t["to"]); frontier.append(t["to"])
for s in L["active_states"]:
    if s not in reach: fail(f"active state '{s}' unreachable from planned")
ok(f"{len(reach)} states reachable; all non-terminal states have an exit")

# 5. cycles must be capped (the known cycles and the counter that caps each)
capped = {("building","stopped"):"stops/resumes", ("stopped","building"):"resumes", ("proposed","planned"):"proposals",
          ("fix_round","re_review"):"fix_rounds", ("re_review","fix_round"):"fix_rounds", ("*active","paused"):"seat_gone_events",
          ("paused","*previous"):"seat_gone_events", ("tail_failed","merged"):"ruling (B)", ("frozen","passed"):"ruling (B)",
          ("re_review","grant_pending"):"fix_rounds (once: at round 2)", ("re_review","ruling_pending"):"fix_rounds (once: at round 3)"}
# sound and deterministic: remove every capped edge and every Bora-gate state; what remains must be acyclic
gate = {n for n, d in L["states"].items() if d.get("bora_gate")}
g = {}
for t in trans:
    a, b = t["from"], t["to"]
    if a.startswith("*") or b.startswith("*") or (a, b) in capped or a in gate or b in gate: continue
    g.setdefault(a, set()).add(b)
WHITE, GREY, BLACK = 0, 1, 2
colour = {}; loops = []
def visit(n, path):
    colour[n] = GREY
    for m in sorted(g.get(n, ())):
        if colour.get(m, WHITE) == GREY: loops.append(path[path.index(m):] + [m])
        elif colour.get(m, WHITE) == WHITE: visit(m, path + [m])
    colour[n] = BLACK
for n in sorted(states):
    if colour.get(n, WHITE) == WHITE: visit(n, [n])
for c in loops: fail(f"uncapped cycle without a Bora gate: {' → '.join(c)}")
ok(f"no cycle survives once the {len(capped)} capped edges and {len(gate)} Bora-gate states are removed")

# 6. engagement references
for kname in E["graduation"]["never"]:
    if kname not in K: fail(f"graduation.never names unknown kind '{kname}'")
ok("engagement.yaml references resolve")

# 7. P-10: the mailroom sends only declared effects; robots are entities in the matrix
declared = set(E["mailroom_declared_effects"])
for name, k in K.items():
    senders = {x.strip() for x in str(k["from"]).split("|")}
    if "mailroom" in senders and name not in declared: fail(f"kind '{name}' is sent by the mailroom but is not a declared effect (P-10)")
    if "daemon" in senders: fail(f"kind '{name}' is sent by the daemon — the daemon decides nothing (P-10)")
for d in declared:
    if d not in K: fail(f"declared effect '{d}' is not a kind")
robots = sorted((ROOT / "robots").glob("*.yaml"))
for f in robots:
    R = yaml.safe_load(f.read_text()); role = R["role"]
    if role not in roles: fail(f"{f.name}: role '{role}' not in engagement.yaml"); continue
    may = {n for n, k in K.items() if role in {x.strip() for x in str(k["from"]).split("|")}}
    for i in R.get("intercepts") or []:
        if " " not in i and i not in K: fail(f"{f.name}: intercepts unknown kind '{i}'")
    for rule in R["rules"]:
        for kind in re.findall(r"\bsend ([a-z][a-z-]+)", rule["then"]):
            if kind not in K: fail(f"{f.name} {rule['id']}: sends unknown kind '{kind}'")
            elif kind not in may: fail(f"{f.name} {rule['id']}: role '{role}' may not send '{kind}'")
ok(f"mailroom sends only its {len(declared)} declared effects; {len(robots)} robots checked against the matrix")

# render
if "--render" in sys.argv:
    out = pathlib.Path(sys.argv[sys.argv.index("--render") + 1])
    rows = ["| kind | from → to (cc) | fires | guard | class | max | deadline | on expiry | reply |", "|---|---|---|---|---|---|---|---|---|"]
    for name, k in K.items():
        cc = f" (cc {', '.join(k['cc'])})" if k.get("cc") else ""
        rows.append(f"| `{name}` | {k['from']} → {k['to']}{cc} | {k.get('fires','-')} | {k.get('guard','-')} | {k.get('class','-')} | {k.get('max_fires','-')} | {k.get('deadline','-')} | {k.get('on_expiry','-')} | {k.get('reply','-')} |")
    out.write_text("\n".join(rows) + "\n")
    print("rendered", out, len(K), "rows")

print("RESULT:", "FAIL" if fails else "PASS", f"({len(fails)} failures)")
sys.exit(1 if fails else 0)
