#!/usr/bin/env python3
"""Keporkak personas: validate agents/<role>/AGENT.md against the rules and render renders/<role>.md.

A persona never restates its borders or its message kinds; this script generates those sections from
rules/engagement.yaml and rules/message-kinds.yaml, so they cannot drift.
Checks (FAIL exits 1; WARN does not): every seat role has a persona and vice versa · frontmatter complete · skills exist
· every mode has a section · every message kind the persona text names is one the role may send or receive ·
files named in reads_at_start exist (WARN: some are created in planning) · the packaging data maps every `deny:` word
and MCP server a persona uses, and something fills every placeholder it names (contracts.md §10; kp-driver's reader
reports the same, in the same words, on the shared fixture agents/packaging/fixture/).
Usage: python3 scripts/render-agents.py
       python3 scripts/render-agents.py --packaging-fixture DIR   # only the packaging check, on DIR's claude.yaml,
                                                                  # personas/*.md and profiles/*.md; prints each problem
"""
import re, sys, yaml, pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]

# The packaging check (S2.2 T2). The session's placeholders the driver fills from the caller's places; every other
# placeholder must be a key of each profile's frontmatter.
SESSION_PLACEHOLDERS = ("worktree", "scratch", "project", "own_file", "code_repos", "mcp_listen")
HOOKS = ("inbox-first", "deny", "stop-gate")
PLACEHOLDER = re.compile(r"\{([a-z_]+)\}")
FRONTMATTER = re.compile(r"\A---\n(.*?)\n---", re.S)

def frontmatter(text):
    m = FRONTMATTER.match(text)
    if not m: return None
    try: return yaml.safe_load(m.group(1))
    except yaml.YAMLError: return None

def packaging_read(text):
    """The packaging data's words, servers and their placeholders, or the reason it cannot be read — as kp-driver reads it."""
    try: data = yaml.safe_load(text)
    except yaml.YAMLError as e: return None, f"the packaging data is not readable YAML: {e}"
    if not isinstance(data, dict): return None, "the packaging data is not a mapping"
    problems, owners = [], []
    for name, word in (data.get("deny") or {}).items():
        rules = (word or {}).get("rules") or []
        texts = []
        for rule in rules:
            rule = rule or {}
            if not rule.get("tools"): problems.append(f"deny word '{name}': a rule names no tool")
            if rule.get("outside") is not None and rule["outside"] != "writable": problems.append(f"deny word '{name}': outside names '{rule['outside']}', not writable")
            texts += ([str(rule["command"])] if rule.get("command") is not None else []) + [str(t) for key in ("path", "except", "early") for t in (rule.get(key) or [])]
        if not rules: problems.append(f"deny word '{name}' has no rule")
        owners += [(f"deny word '{name}'", p) for p in dict.fromkeys(p for t in texts for p in PLACEHOLDER.findall(t))]
    servers = data.get("mcp") or {}
    for name, server in servers.items():
        url = (server or {}).get("url")
        if url is None: problems.append(f"MCP server '{name}' has no url")
        owners += [(f"MCP server '{name}'", p) for p in dict.fromkeys(PLACEHOLDER.findall(str(url or "")))]
    owners += [("the writable set", p) for p in dict.fromkeys(p for glob in (data.get("writable") or []) for p in PLACEHOLDER.findall(str(glob)))]
    hooks = data.get("hooks") or {}
    for name, hook in hooks.items():
        if (hook or {}).get("event") is None: problems.append(f"hook '{name}' names no event")
    problems += [f"hook '{h}' is not in the packaging data" for h in HOOKS if h not in hooks]
    if problems: return None, "; ".join(problems)
    return {"words": set(data.get("deny") or {}), "servers": set(servers), "placeholders": owners}, None

def packaging_problems(personas, data, profiles):
    """Every problem of the packaging data with these personas ({role: text}) and profiles ({name: text}), sorted."""
    packaging, reason = packaging_read(data)
    if packaging is None: return [f"packaging: {reason}"]
    problems = []
    for role, text in personas.items():
        fm = frontmatter(text)
        if not isinstance(fm, dict): problems.append(f"persona {role}: no frontmatter"); continue
        problems += [f"persona {role}: deny word '{w}' is not mapped by the packaging data" for w in fm.get("deny") or [] if w not in packaging["words"]]
        problems += [f"persona {role}: MCP server '{s}' is not mapped by the packaging data" for s in ((fm.get("tools") or {}).get("mcp") or []) if s not in packaging["servers"]]
    for name, text in profiles.items():
        fm = frontmatter(text)
        if not isinstance(fm, dict): problems.append(f"profile {name}: no frontmatter"); continue
        problems += [f"packaging: placeholder '{{{p}}}' of {owner} is filled by nothing in profile {name}"
                     for owner, p in packaging["placeholders"] if p not in SESSION_PLACEHOLDERS and p not in fm]
    return sorted(set(problems))

if len(sys.argv) == 3 and sys.argv[1] == "--packaging-fixture":
    fixture = pathlib.Path(sys.argv[2])
    found = packaging_problems({p.stem: p.read_text() for p in sorted((fixture / "personas").glob("*.md"))}, (fixture / "claude.yaml").read_text(),
                               {p.stem: p.read_text() for p in sorted((fixture / "profiles").glob("*.md"))})
    print("\n".join(found))
    sys.exit(1 if found else 0)
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
    if "holds" in value:return "engagement."+str(value["holds"])+" holds"
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

def trigger(value):
    """What a robot rule waits on (contracts.md §8) — split rules differ only here, so the line must say it (issue #6)."""
    if "fact" in value:return value["fact"]+" changes"
    if "op" in value:return value["op"]+" is "+str(value.get("eq"))
    for form,verb in (("delivered","is delivered"),("arrives","arrives"),("expired","expires")):
        if form in value:return value[form]+" "+verb
    if "presence" in value:return "presence turns "+value["presence"]
    return describe(value)

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
        rendered = {}
        for item in robot["rules"]:
            if item.get("for")=="persona":
                text=f"when {item['when']} → {item.get('note','')}"
                lines.append(f"- **{item['id']} (persona)** {text}")
            else:
                conditions="; if "+" and ".join(describe(value) for value in item["if"]) if item["if"] else ""
                text=f"on {trigger(item['on'])}: when {item['when']}{conditions} → {actions(item['then'])}"
                lines.append(f"- **{item['id']}** {text}")
            if text in rendered: fail(f"{r}: robot rules {rendered[text]} and {item['id']} render as the same line")
            rendered.setdefault(text, item["id"])
    lines += ["", "## Messages that reach you", ", ".join(f"`{n}`" for n in R) or "-", ""]
    (out / f"{r}.md").write_text("\n".join(lines))
    print(f"ok   {r} [{fm.get('engine')}]: {len(fm['modes'])} modes · sends {len(S)} kinds · receives {len(R)} · level {fm['level']}")

# The packaging data maps what every persona uses, and every profile fills its placeholders (contracts.md §10).
personas = {p.parent.name: p.read_text() for p in sorted((ROOT / "agents").glob("*/AGENT.md"))}
profiles = {p.stem: p.read_text() for p in sorted((ROOT / "docs/profiles").glob("*.md"))}
for problem in packaging_problems(personas, (ROOT / "agents/packaging/claude.yaml").read_text(), profiles): fail(problem)

print("RESULT:", "FAIL" if fails else "PASS", f"({len(fails)} failures, {len(warns)} warnings)")
sys.exit(1 if fails else 0)
