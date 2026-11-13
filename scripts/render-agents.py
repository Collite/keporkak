#!/usr/bin/env python3
"""Keporkak personas: validate agents/<role>/AGENT.md against the rules and render renders/<role>.md.

A persona never restates its borders or its message kinds; this script generates those sections from
rules/engagement.yaml and rules/message-kinds.yaml, so they cannot drift.
Checks (FAIL exits 1; WARN does not): every seat role has a persona and vice versa · frontmatter complete · skills exist
· every mode has a section · every message kind the persona text names is one the role may send or receive ·
files named in reads_at_start exist (WARN: some are created in planning).
Usage: python3 scripts/render-agents.py
"""
import re, sys, yaml, pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
E = yaml.safe_load((ROOT / "rules/engagement.yaml").read_text())
K = yaml.safe_load((ROOT / "rules/message-kinds.yaml").read_text())["kinds"]
NON_SEATS = {"bora", "daemon", "mailroom"}
WILD = {"the asker", "the sender", "a seat"}
fails, warns = [], []
def fail(m): fails.append(m); print("FAIL", m)
def warn(m): warns.append(m); print("warn", m)

def parties(v):
    if isinstance(v,dict) and v.get("re")=="from":return {"the asker"}
    return {x.strip() for x in str(v).split("|")}

def describe(value):
    if isinstance(value,list):return ", ".join(describe(item) for item in value)
    if not isinstance(value,dict):return str(value)
    if "any" in value:return "("+" or ".join(describe(item) for item in value["any"])+")"
    if "not" in value:return "not ("+describe(value["not"])+")"
    operators={"eq":"=","ne":"≠","in":"in","gt":">","gte":"≥","lt":"<","lte":"≤"}
    form=next((name for name in ("fact","arg","counter","cap","roster","office","budget","engagement","robot","trigger","msg","re","signed","attest") if name in value),None)
    if form:
        label=str(value[form]) if form in ("fact","counter") else form+"."+str(value[form])
        params={key:item for key,item in value.items() if key not in set(operators)|{form}}
        if params:label+=" ("+", ".join(key+"="+describe(item) for key,item in params.items())+")"
        comparison=next((name for name in operators if name in value),None)
        return label+(" "+operators[comparison]+" "+describe(value[comparison]) if comparison else "")
    return ", ".join(key+": "+describe(item) for key,item in value.items())

def recipient(value):
    return "reply to requester" if isinstance(value,dict) and value.get("re")=="from" else describe(value)

def classification(value):
    if not isinstance(value,dict):return str(value)
    return "; ".join([value["default"]]+[case["class"]+" if "+describe(case["if"]) for case in value.get("cases",[])])

def cell(value):return str(value).replace("|", "\\|").replace("\n","<br>")

def actions(values):
    return "; ".join("wake your "+value["wake"]+" mode" if "wake" in value else "send "+value["send"]+" to "+recipient(value["to"]) for value in values)

def sends(role): return {n: k for n, k in K.items() if role in parties(k["from"])}
def receives(role): return {n: k for n, k in K.items() if role in parties(k["to"]) or role in (k.get("cc") or []) or parties(k["to"]) & WILD}

seat_roles = set(E["roles"]) - NON_SEATS
have = {p.parent.name for p in (ROOT / "agents").glob("*/AGENT.md")}
for r in sorted(seat_roles - have): fail(f"role '{r}' has no agents/{r}/AGENT.md")
for r in sorted(have - seat_roles): fail(f"agents/{r} is not a seat role in engagement.yaml")

common = (ROOT / "agents/_common.md").read_text().strip()
out = ROOT / "renders"; out.mkdir(exist_ok=True)
REQUIRED = ["role", "squad", "engine", "host", "instances", "level", "reads_at_start", "skills", "tools", "deny", "modes"]
for r in sorted(have & seat_roles):
    text = (ROOT / f"agents/{r}/AGENT.md").read_text()
    m = re.match(r"---\n(.*?)\n---\n(.*)", text, re.S)
    if not m: fail(f"{r}: no frontmatter"); continue
    fm, body = yaml.safe_load(m.group(1)), m.group(2).strip()
    for k in REQUIRED:
        if k not in fm: fail(f"{r}: frontmatter lacks '{k}'")
    if fm.get("role") != r: fail(f"{r}: frontmatter role is '{fm.get('role')}'")
    for sk in fm.get("skills") or []:
        if not ((ROOT / "skills" / sk / "SKILL.md").exists() or (ROOT / "skills/_imported" / sk / "SKILL.md").exists()):
            warn(f"{r}: skill '{sk}' is not written yet")
    for mode, d in (fm.get("modes") or {}).items():
        if not re.search(rf"^## .*\b{re.escape(mode)}\b", body, re.M): fail(f"{r}: mode '{mode}' has no section")
        if d.get("skill") and d["skill"] not in (fm.get("skills") or []): fail(f"{r}: mode '{mode}' uses skill '{d['skill']}' not in skills")
    for f in fm.get("reads_at_start") or []:
        if re.match(r"^(docs|rules|pool)/", f) and not (ROOT / f).exists(): warn(f"{r}: reads_at_start '{f}' does not exist yet")
    robot = None; rf = ROOT / "robots" / f"{r}.yaml"
    if fm.get("engine") not in ("llm", "hybrid"): fail(f"{r}: engine must be llm or hybrid for a persona (a pure robot has no AGENT.md)")
    if fm.get("engine") == "hybrid":
        if rf.exists(): robot = yaml.safe_load(rf.read_text())
        else: fail(f"{r}: engine is hybrid but robots/{r}.yaml is missing")
    elif rf.exists(): fail(f"{r}: robots/{r}.yaml exists but engine is '{fm.get('engine')}'")
    S, R = sends(r), receives(r)
    if not S: fail(f"{r}: may send no kind at all")
    for tok in set(re.findall(r"`([a-z][a-z-]+)`", body)):
        if tok in K and tok not in S and tok not in R: fail(f"{r}: text names kind '{tok}' which the role neither sends nor receives")
    role = E["roles"][r]
    lines = [f"<!-- GENERATED by scripts/render-agents.py from agents/{r}/AGENT.md + rules/*.yaml — do not edit -->", "", common, "", body, "",
             "## Your borders (generated from rules/engagement.yaml)", f"- **You may:** {role['may']}", f"- **You never:** {role['never']}", "",
             "## Messages you may send (generated from rules/message-kinds.yaml)", "Anything else is refused.", "",
             "| kind | to | allowed when | class | cap / window | you then get |", "|---|---|---|---|---|---|"]
    for n, k in S.items():
        limit="—" if k.get("max_fires") is None else str(k["max_fires"])+" / "+k.get("window","stage")
        values=["`"+n+"`",recipient(k["to"]),k.get("guard","-"),classification(k.get("class","-")),limit,k.get("reply","-")]
        lines.append("| "+" | ".join(cell(value) for value in values)+" |")
    t = fm.get("tools") or {}
    if "messaging" not in (t.get("mcp") or []): fail(f"{r}: every seat needs the messaging MCP")
    lines += ["", "## Your tools (generated from your persona; nothing else is available to you)", f"- **Shell:** {', '.join(t.get('shell') or []) or 'none'}", f"- **MCP servers:** {', '.join(t.get('mcp') or [])}", f"- **Skills:** {', '.join(fm.get('skills') or []) or 'none'}"]
    if robot:
        lines += ["", f"## Your robot half (generated from robots/{r}.yaml)", "Executable rules run under your name. Rules marked persona are your instructions.", ""]
        if robot.get("thresholds"):
            lines.append("Named thresholds: "+" · ".join(name+" = "+str(value) for name,value in robot["thresholds"].items()))
            lines.append("")
        if "queue" in robot:
            queue=robot["queue"]
            lines += ["Queue: one job at a time; "+" before ".join(queue["order"])+"; FIFO within a kind. Next job requires "+describe(queue["next_when"])+".", ""]
            lines += [f"- **{item['id']}** when {item['when']} → {item['note']}" for item in queue.get("prose",[])]
        for item in robot["rules"]:
            if item.get("for")=="persona":
                lines.append(f"- **{item['id']} (persona)** when {item['when']} → {item.get('note','')}")
            else:
                conditions="; if "+" and ".join(describe(value) for value in item["if"]) if item["if"] else ""
                lines.append(f"- **{item['id']}** when {item['when']}{conditions} → {actions(item['then'])}")
    lines += ["", "## Messages that reach you", ", ".join(f"`{n}`" for n in R) or "-", ""]
    (out / f"{r}.md").write_text("\n".join(lines))
    print(f"ok   {r} [{fm.get('engine')}]: {len(fm['modes'])} modes · sends {len(S)} kinds · receives {len(R)} · level {fm['level']}")

print("RESULT:", "FAIL" if fails else "PASS", f"({len(fails)} failures, {len(warns)} warnings)")
sys.exit(1 if fails else 0)
