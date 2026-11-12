#!/usr/bin/env python3
"""Keporkak rules: validate the three YAML files against each other and render the catalogue as markdown.

Checks (each a line of output; exit 1 if any FAIL):
  - every transition in stage-lifecycle.yaml names a kind that exists in message-kinds.yaml
  - every kind whose `fires` names a stage transition has that transition in the lifecycle (by from→to)
  - every kind's `from` roles exist in engagement.yaml:roles (or are 'mailroom'/'the asker' forms)
  - every state referenced by a transition exists; every non-terminal state has an exit; every active state is reachable
  - cycles in the stage chart: each cycle must touch a counter-capped transition (we check the known cycles list)
  - graduation.never kinds exist; presence.pause kinds exist
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
# simple cycle detection on the explicit graph
g = {}
for t in trans:
    if t["from"].startswith("*") or t["to"].startswith("*"): continue
    g.setdefault(t["from"], set()).add(t["to"])
def cycles():
    found = []
    def dfs(n, path, seen):
        for m in g.get(n, ()):
            if m in path: found.append(path[path.index(m):] + [m])
            elif m not in seen: seen.add(m); dfs(m, path + [m], seen)
    dfs("planned", ["planned"], {"planned"}); return found
cyc = cycles()
for c in cyc:
    edges = list(zip(c, c[1:]))
    if not any(e in capped for e in edges) and not any(L["states"][n].get("bora_gate") for n in c):
        fail(f"uncapped cycle without a Bora gate: {' → '.join(c)}")
ok(f"{len(cyc)} cycles found; each is capped by a counter or passes a Bora gate")

# 6. engagement references
for kname in E["graduation"]["never"]:
    if kname not in K: fail(f"graduation.never names unknown kind '{kname}'")
for kname in E["presence"]["while_absent"]["pause"]:
    if kname not in K: fail(f"presence.pause names unknown kind '{kname}'")
ok("engagement.yaml references resolve")

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
