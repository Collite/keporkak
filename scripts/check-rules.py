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
Usage: python3 scripts/check-rules.py [--render out.md] [--self-test]
"""
import sys, re, yaml, pathlib, itertools, subprocess, json, copy

ROOT = pathlib.Path(__file__).resolve().parents[1]

# YAML 1.2 booleans: `on` is a field name and `off` is an office value.
class RulesLoader(yaml.SafeLoader):
    pass
RulesLoader.yaml_implicit_resolvers = {
    key: [(tag, pattern) for tag, pattern in resolvers if tag != "tag:yaml.org,2002:bool"]
    for key, resolvers in yaml.SafeLoader.yaml_implicit_resolvers.items()
}
RulesLoader.add_implicit_resolver("tag:yaml.org,2002:bool", re.compile(r"^(?:true|false)$", re.I), list("tTfF"))
def load_yaml(text):
    return yaml.load(text, Loader=RulesLoader)

# Exercise the same command-line validator on small, isolated rule documents.
if "--self-test" in sys.argv:
    total = failures = 0
    for fixture in sorted((ROOT / "rules/fixtures").rglob("*.yaml")):
        spec = load_yaml(fixture.read_text())
        for index, scenario in enumerate(spec["scenarios"]):
            total += 1
            result = subprocess.run(
                [sys.executable, __file__, "--fixture", str(fixture), "--scenario", str(index)],
                text=True, capture_output=True,
            )
            output = result.stdout.splitlines()
            actual_failures = [line.removeprefix("FAIL ") for line in output if line.startswith("FAIL ")]
            actual_warnings = [line.removeprefix("WARN ") for line in output if line.startswith("WARN ")]
            expected = scenario["expect"]
            matches = (
                not result.stderr
                and result.returncode == (1 if expected.get("failures") else 0)
                and actual_failures == expected.get("failures", [])
                and actual_warnings == expected.get("warnings", [])
                and all(line in output for line in expected.get("output", []))
            )
            print(("ok   " if matches else "FAIL ") + spec["name"] + " / " + scenario["name"])
            if not matches:
                failures += 1
                print("     expected:", expected)
                print("     observed failures:", actual_failures, "warnings:", actual_warnings)
                if result.stderr:
                    print(result.stderr.rstrip())
    if not total:
        failures += 1
        print("FAIL no self-test fixtures found")
    print(f"SELF-TEST: {'FAIL' if failures else 'PASS'} ({total} scenarios, {failures} failures)")
    sys.exit(1 if failures else 0)

L = load_yaml((ROOT / "rules/stage-lifecycle.yaml").read_text())
K = load_yaml((ROOT / "rules/message-kinds.yaml").read_text())["kinds"]
E = load_yaml((ROOT / "rules/engagement.yaml").read_text())

FIXTURE = "--fixture" in sys.argv
W = []
V = load_yaml((ROOT / "rules/vocabulary.yaml").read_text()) if not FIXTURE else {"facts": {}, "operations": {}}
if FIXTURE:
    spec = load_yaml(pathlib.Path(sys.argv[sys.argv.index("--fixture") + 1]).read_text())
    scenario = spec["scenarios"][int(sys.argv[sys.argv.index("--scenario") + 1])]
    given = scenario["given"]
    L = {
        "states": given.get("states", {"planned": {}, "exited": {"terminal": True}}),
        "active_states": ["planned"],
        "counters": given.get("counters", []),
        "transitions": given["transitions"],
    }
    K = given.get("kinds", {"started": {
        "from": "coder", "to": "teamlead", "guard": "-", "args": {},
        "class": {"default": "A"}, "window": "stage", "max_fires": None, "deadline": None, "expiry": [], "require": [],
    }})
    E = {"roles": {"coder": {}, "teamlead": {}}, "graduation": {"never": []},
         "mailroom_declared_effects": [], "caps": given.get("caps", {})}
    V = given.get("vocabulary", V)
    W = given.get("walks", [])
ROBOTS = ([(f"fixture-{index}.yaml", robot) for index, robot in enumerate(given.get("robots", []))]
          if FIXTURE else [(path.name, load_yaml(path.read_text())) for path in sorted((ROOT / "robots").glob("*.yaml"))])
ROBOT_THRESHOLDS = {name:value for _,robot in ROBOTS for name,value in robot.get("thresholds",{}).items()}
fails = []
def fail(m): fails.append(m); print("FAIL", m)
def ok(m): print("ok  ", m)

states = set(L["states"])
trans = L["transitions"]
roles = set(E["roles"])

# All executable names are resolved against the closed vocabulary and rule owners.
def check_references(value, owner, robot=None):
    if isinstance(value, dict):
        namespaces = {"fact": V["facts"], "op": V["operations"],
                      "do": V["operations"], "cap": E.get("caps", {}),
                      "kind": K, "emit": K, "attest": V.get("attestations", {}), "counter": L.get("counters", {}), "count": L.get("counters", {}), "robot": robot.get("thresholds",{}) if robot is not None else ROBOT_THRESHOLDS}
        labels = {"fact": "fact", "op": "operation", "do": "operation",
                  "cap": "cap", "kind": "kind", "emit": "kind", "attest": "attestation", "counter": "counter", "count": "counter", "robot": "robot threshold"}
        for key, item in value.items():
            if key in namespaces and isinstance(item, str) and item not in namespaces[key]:
                fail(f"{owner}: unknown {labels[key]} '{item}'")
            check_references(item, owner, robot)
    elif isinstance(value, list):
        for item in value:
            check_references(item, owner, robot)

def contains_number(value):
    if isinstance(value, bool):
        return False
    if isinstance(value, (int, float)):
        return value != 0
    if isinstance(value, dict):
        return any(contains_number(item) for item in value.values())
    if isinstance(value, list):
        return any(contains_number(item) for item in value)
    return False

def check_requirement_form(requirement,owner):
    forms={"fact","arg","msg","re","counter","attest","signed","op","any","not","always"}
    if not isinstance(requirement,dict) or len(set(requirement)&forms)!=1:
        fail(f"{owner}: unknown requirement form")
        return
    form=next(iter(set(requirement)&forms))
    if form=="any":
        if not isinstance(requirement[form],list):fail(f"{owner}: any must contain requirements")
        else:
            for item in requirement[form]:check_requirement_form(item,owner)
    elif form=="not":check_requirement_form(requirement[form],owner)
    elif form=="always":
        if not isinstance(requirement[form],bool):fail(f"{owner}: always must be boolean")
    elif not isinstance(requirement[form],str):fail(f"{owner}: requirement name must be a string")
    elif form=="msg" and requirement[form] not in ("by","from","to","stage"):fail(f"{owner}: unknown message field")
    elif form=="re" and requirement[form] not in ("kind","class","from","to"):fail(f"{owner}: unknown reply field")
    elif form=="signed" and requirement[form]!="bora":fail(f"{owner}: unknown signer")
    elif form=="op" and requirement.get("eq") not in ("ok","failed"):fail(f"{owner}: unknown operation result")

def check_requirements(requirements, owner, robot=None):
    if not isinstance(requirements,list):
        fail(f"{owner}: require must be a list")
        return
    for requirement in requirements:check_requirement_form(requirement,owner)
    check_references(requirements, owner, robot)
    if contains_number(requirements):
        fail(f"{owner}: literal number inside a requirement")

machines = {"stage": L}
for name in ("found_item", "flag_thread", "seat", "phase", "presence"):
    if name in L:
        machines[name] = L[name]
all_transitions = [(name, transition) for name, machine in machines.items() for transition in machine["transitions"]]
seen_ids = set()
for machine, transition in all_transitions:
    owner = transition.get("id", f"{transition['from']}→{transition['to']}")
    if "require" not in transition:
        fail(f"{owner}: guard has no machine form")
        continue
    for field in ("id", "on", "effects"):
        if field not in transition:
            fail(f"{owner}: missing {field}")
    if owner in seen_ids:
        fail(f"{owner}: duplicate transition id")
    seen_ids.add(owner)
    event = transition.get("on", "message")
    if event not in ("message", "op-result", "expiry", "fact"):
        fail(f"{owner}: unknown trigger '{event}'")
    if event != "fact" and transition.get("kind") not in K:
        fail(f"{owner}: unknown kind '{transition.get('kind')}'")
    check_requirements(transition["require"], owner)
    check_references(transition.get("effects", []), owner)
    for effect in transition.get("effects", []):
        if not isinstance(effect, dict) or not set(effect) & {"do", "emit", "count"}:
            fail(f"{owner}: unknown effect")
        if "emit" in effect and effect["emit"] not in E["mailroom_declared_effects"]:
            fail(f"{owner}: undeclared mailroom emit '{effect['emit']}'")
        if "if" in effect:
            check_requirements([effect["if"]], owner)
    for requirement in transition["require"]:
        if "op" in requirement and event != "op-result":
            fail(f"{owner}: operation result outside op-result trigger")
    known_states = set(machines[machine]["states"])
    for state in (transition["from"], transition["to"]):
        if state not in known_states and not state.startswith("*"):
            fail(f"{owner}: unknown state '{state}' in {machine}")
if not fails:
    ok(f"every guard has a machine form: {len(all_transitions)} transitions with id, on, require and effects")

# A conservative satisfiability check: unknown relationships warn instead of hiding overlap.
COMPARISONS = {"eq", "ne", "in", "gt", "gte", "lt", "lte"}
def constant(value):
    if isinstance(value, dict) and set(value) == {"cap"}:
        return E["caps"].get(value["cap"])
    if isinstance(value, (str, int, float, bool, list)) or value is None:
        return value
    return None

def alternatives(requirements):
    result = [[]]
    for requirement in requirements:
        if "any" in requirement:
            branches = [branch for item in requirement["any"] for branch in alternatives([item])]
        elif "not" in requirement:
            inverse = dict(requirement["not"])
            if "eq" in inverse:
                inverse["ne"] = inverse.pop("eq")
                branches = [[inverse]]
            elif not set(inverse) & COMPARISONS:
                branches = [[{**inverse, "eq": False}]]
            else:
                branches = [[]]
        else:
            branches = [[requirement]]
        result = [left + right for left in result for right in branches]
    return result

def can_hold(atoms):
    # An op-result record names one operation.
    if len({atom["op"] for atom in atoms if "op" in atom}) > 1:
        return False
    groups = {}
    for atom in atoms:
        key = json.dumps({k:v for k,v in atom.items() if k not in COMPARISONS}, sort_keys=True)
        group = groups.setdefault(key, [])
        comparison = next((name for name in COMPARISONS if name in atom), "eq")
        value = constant(atom[comparison]) if comparison in atom else True
        if value is not None:
            group.append((comparison, value))
    for constraints in groups.values():
        equals = [value for comparison,value in constraints if comparison == "eq"]
        if equals and any(value != equals[0] for value in equals):
            return False
        if equals:
            value = equals[0]
            for comparison, bound in constraints:
                try:
                    if comparison == "ne" and value == bound: return False
                    if comparison == "in" and not (set(value) <= set(bound) if isinstance(value,list) else value in bound): return False
                    if comparison == "gt" and not value > bound: return False
                    if comparison == "gte" and not value >= bound: return False
                    if comparison == "lt" and not value < bound: return False
                    if comparison == "lte" and not value <= bound: return False
                except TypeError:
                    pass
        lower = [(v,c=="gte") for c,v in constraints if c in ("gt","gte") and isinstance(v,(int,float))]
        upper = [(v,c=="lte") for c,v in constraints if c in ("lt","lte") and isinstance(v,(int,float))]
        if lower and upper:
            lo,hi = max(lower),min(upper)
            if lo[0] > hi[0] or lo[0] == hi[0] and not (lo[1] and hi[1]): return False
    return True

for machine, definition in machines.items():
    for left,right in itertools.combinations(definition["transitions"], 2):
        identity = lambda t: (t["from"], t.get("kind"), t.get("on", "message"))
        if identity(left) != identity(right) or left.get("on") == "fact":
            continue
        if any(can_hold(a+b) for a in alternatives(left.get("require", [])) for b in alternatives(right.get("require", []))):
            print(f"WARN overlapping transitions {left.get('id')} and {right.get('id')} ({left['from']}, {left.get('kind')}, {left.get('on', 'message')})")

def argument_references(value):
    if isinstance(value, dict):
        if "arg" in value and isinstance(value["arg"],str):
            yield value["arg"]
        for item in value.values():
            yield from argument_references(item)
    elif isinstance(value,list):
        for item in value:
            yield from argument_references(item)

for machine, transition in all_transitions:
    kind = K.get(transition.get("kind"), {})
    if "args" in kind:
        for name in set(argument_references([transition.get("require",[]), transition.get("effects",[])])):
            if name not in kind["args"]:
                fail(f"{transition.get('id')}: undeclared argument '{name}'")

for name, kind in K.items():
    for field in ("args", "class", "window", "max_fires", "deadline", "expiry"):
        if field not in kind:
            fail(f"{name}: missing {field}")
    if "require" not in kind:
        fail(f"{name}: guard has no machine form")
    if kind.get("window") not in ("stage","day","return","session"):
        fail(f"{name}: invalid window")
    classification = kind.get("class")
    if not isinstance(classification,dict) or classification.get("default") not in ("A","B","C"):
        fail(f"{name}: invalid class")
    elif any(case.get("class") not in ("A","B","C") or "if" not in case for case in classification.get("cases",[])):
        fail(f"{name}: invalid class case")
    for argument in set(argument_references([kind.get("require",[]),kind.get("class",{}),kind.get("expiry",[])])):
        if argument not in kind.get("args",{}):
            fail(f"{name}: undeclared argument '{argument}'")
    for argument, definition in kind.get("args",{}).items():
        if not isinstance(definition,dict) or not set(definition)&{"type","enum"}:
            fail(f"{name}: untyped argument '{argument}'")
    check_requirements(kind.get("require", []), name)
    for effect in kind.get("expiry",[]):
        check_references(effect,name)
        if not isinstance(effect,dict) or not set(effect)&{"emit","report","transition","do"}:
            fail(f"{name}: invalid expiry effect")
        if "transition" in effect and effect["transition"] not in seen_ids:
            fail(f"{name}: expiry names unknown transition '{effect['transition']}'")
        if "emit" in effect and effect["emit"] not in E["mailroom_declared_effects"]:
            fail(f"{name}: undeclared expiry emit '{effect['emit']}'")
        if "if" in effect:
            check_requirements([effect["if"]],name)
    if kind.get("to")=="daemon" and not any(t.get("kind")==name and any("do" in effect for effect in t.get("effects",[])) for _,t in all_transitions):
        fail(f"{name}: daemon request has no operation binding")
    for case in (kind.get("class", {}).get("cases", []) if isinstance(kind.get("class"), dict) else []):
        check_requirements([case["if"]], name)
if not fails:
    ok(f"{len(K)} kinds with typed args, class cases, window, expiry and require")
ok(f"closed vocabulary loaded: {len(V['facts'])} facts, {len(V['operations'])} operations")
if not fails:
    ok("no unknown fact, operation, kind or cap; no literal number inside a requirement")

# Engagement requirements use the same closed language, with no hidden guards.
for section in ("second_key", "stop_gate"):
    if section in E:
        check_requirements(E[section].get("require", []), section)
        check_references(E[section],section)
if "stop_gate" in E and E["stop_gate"].get("require") != []:
    fail("stop_gate: requirements belong to S2.2 (Amendment 2)")
if not fails:
    ok("engagement second key, cap references and empty v1 stop gate checked")

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
# Every result/expiry is tied to its capped request. Daily/session caps alone do not prove a stage cycle finite.
capped = set()
for transition in trans:
    kind = K.get(transition.get("kind"), {})
    maximum = kind.get("max_fires")
    if kind.get("window", "stage") == "stage" and isinstance(maximum, int) and not isinstance(maximum,bool) and maximum > 0:
        capped.add((transition["from"],transition["to"]))
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
executed = persona = 0
robot_ids = set()
for filename, robot in ROBOTS:
    role = robot["role"]
    if role not in roles:
        fail(f"{filename}: unknown role '{role}'")
        continue
    may = {name for name,kind in K.items() if role in {part.strip() for part in kind["from"].split("|")}}
    check_requirements([robot.get("active_when",{})],filename,robot)
    for intercepted in robot.get("intercepts",[]):
        if isinstance(intercepted,str):
            if intercepted not in K: fail(f"{filename}: unknown intercepted kind '{intercepted}'")
        elif not isinstance(intercepted,dict) or intercepted.get("class") not in E.get("classes", {"A":{},"B":{},"C":{}}) or intercepted.get("to") not in roles:
            fail(f"{filename}: invalid class intercept")
    if "queue" in robot:
        queue=robot["queue"]
        if queue.get("one_at_a_time") is not True or queue.get("then")!="fifo":
            fail(f"{filename}: invalid queue discipline")
        for kind in queue.get("order",[]):
            if kind not in K: fail(f"{filename}: unknown queued kind '{kind}'")
        check_requirements([queue.get("next_when",{})],filename,robot)
    for rule in robot["rules"]:
        owner=rule["id"]
        if not isinstance(owner,str) or re.fullmatch(r"[A-Z]+-[0-9]+",owner) is None:
            fail(f"{owner}: robot rule id must match [A-Z]+-[0-9]+")
            continue
        if owner in robot_ids: fail(f"{owner}: duplicate robot rule id")
        robot_ids.add(owner)
        if rule.get("for")=="persona":
            persona+=1
            if "on" in rule or "then" in rule: fail(f"{owner}: persona rule must not execute")
            continue
        executed+=1
        if not {"on","if","then"} <= set(rule):
            fail(f"{owner}: guard has no machine form")
            continue
        trigger=rule["on"]
        if not isinstance(trigger,dict) or not set(trigger)&{"fact","delivered","arrives","expired","presence","op"}:
            fail(f"{owner}: invalid robot trigger")
        check_references(trigger,owner,robot)
        for field in ("delivered","arrives","expired"):
            if field in trigger and trigger[field] not in K: fail(f"{owner}: unknown trigger kind '{trigger[field]}'")
        check_requirements(rule["if"],owner,robot)
        for action in rule["then"]:
            if not isinstance(action,dict) or len(set(action)&{"send","wake"})!=1 or set(action)-{"send","wake","to","args"}:
                fail(f"{owner}: robot then must only send or wake")
                continue
            check_references(action.get("args",{}),owner,robot)
            if "send" in action:
                kind=action["send"]
                if kind not in K: fail(f"{owner}: unknown send kind '{kind}'")
                elif kind not in may: fail(f"{owner}: role '{role}' may not send '{kind}'")
                else:
                    for name in action.get("args",{}):
                        if name not in K[kind].get("args",{}): fail(f"{owner}: unknown send argument '{name}'")
                    for name,definition in K[kind].get("args",{}).items():
                        if definition.get("required") and name not in action.get("args",{}): fail(f"{owner}: missing send argument '{name}'")
            if "wake" in action:
                source=(ROOT / "agents" / role / "AGENT.md").read_text()
                frontmatter=load_yaml(re.match(r"---\n(.*?)\n---",source,re.S).group(1))
                if action["wake"] not in frontmatter.get("modes",{}): fail(f"{owner}: unknown own persona mode '{action['wake']}'")
if not fails:
    ok(f"mailroom sends only its {len(declared)} declared effects; {len(ROBOTS)} robots checked against the matrix")
    ok(f"{executed} robot rules with on/if/then; {persona} persona rules; queue and named thresholds checked")


# Walk the public rules, using supplied observations and fake operation results only.
# There is no I/O or action execution here: do/send/wake are checked and recorded.
MISSING = object()
def path_value(root, path):
    value=root
    for part in str(path).split("."):
        if isinstance(value,dict): value=value.get(part,MISSING)
        elif isinstance(value,list) and part.isdigit() and int(part)<len(value): value=value[int(part)]
        else: return MISSING
    return value

def comparable(value):
    if isinstance(value,str):
        match=re.fullmatch(r"([0-9]+(?:\.[0-9]+)?)(s|m|h|d)",value)
        if match: return float(match[1])*{"s":1,"m":60,"h":3600,"d":86400}[match[2]]
    return value

def compare(actual, operator, expected):
    if actual is MISSING or expected is MISSING: return False
    if operator in ("eq","ne"):
        if isinstance(actual,list) and isinstance(expected,list):
            same={json.dumps(item,sort_keys=True) for item in actual}=={json.dumps(item,sort_keys=True) for item in expected}
        else: same=actual==expected
        return same if operator=="eq" else not same
    if operator=="in":
        try: return all(item in expected for item in actual) if isinstance(actual,list) else actual in expected
        except TypeError: return False
    actual,expected=comparable(actual),comparable(expected)
    try:
        return {"gt":lambda:actual>expected,"gte":lambda:actual>=expected,"lt":lambda:actual<expected,"lte":lambda:actual<=expected}[operator]()
    except (TypeError,KeyError): return False

class WalkError(Exception):
    pass

class Walk:
    def __init__(self, definition):
        self.definition=definition
        self.name=definition["name"]
        self.stage=definition.get("stage","fixture-stage")
        self.contexts={}
        self.shared={"presence":"present","phase":"open"}
        self.history=[]
        self.messages={}
        self.pending={}
        self.used_operations={}
        self.kind_fires={}
        self.thread_hops={}
        self.shared_counters={}
        self.ctx={}
        self.robot=None
        self.index=0
        self.emissions=[]
        self.set_stage(self.stage)
    def set_stage(self, stage):
        self.stage=stage
        if stage not in self.contexts:
            setting=self.definition.get("stages",{}).get(stage,{})
            initial=setting.get("initial",self.definition.get("initial","planned"))
            self.contexts[stage]={"states":{"stage":initial,"seat":"ready","found_item":"found","flag_thread":"asked",**setting.get("machines",{})},"previous":None,"found_items":{},"counters":{},"facts":{**self.definition.get("facts",{}),**setting.get("facts",{})}}
        self.current=self.contexts[stage]
    def state(self,machine):
        if machine=="found_item":return self.current["found_items"].get(self.ctx.get("args",{}).get("found","default"),"found")
        return self.shared[machine] if machine in self.shared else self.current["states"].get(machine)
    def set_state(self,machine,state):
        if machine=="found_item":
            self.current["found_items"][self.ctx.get("args",{}).get("found","default")]=state
            return
        if machine in self.shared:self.shared[machine]=state
        else:self.current["states"][machine]=state
    def counter(self,name):
        declaration=L.get("counters",{}).get(name,{}) if isinstance(L.get("counters"),dict) else {}
        return (self.shared_counters if declaration.get("scope")=="effort" else self.current["counters"]).get(name,0)
    def fact(self, ref):
        name=ref["fact"]
        params={key:self.value(value) for key,value in ref.items() if key not in COMPARISONS|{"fact"}}
        if name=="stage.state":return self.state("stage")
        if name=="phase.state":return self.state("phase")
        if name=="bora.present":return self.state("presence")=="present"
        if name=="thread.state":return self.state("flag_thread")
        if name=="thread.open":return self.state("flag_thread") in ("asked","held")
        if name=="queue.names":
            queues=[record for record in self.history if record.get("kind")=="queue-approval" and record.get("signed")=="bora" and record.get("status")=="delivered"]
            return bool(queues) and self.stage in queues[-1]["args"]["stages"] and self.fact({"fact":"plan.in_current_wave"}) is True
        if name=="log.count":
            kind=params.get("kind");scope=params.get("scope","stage");records=list(self.history)
            if scope=="stage":records=[record for record in records if record.get("stage")==self.stage]
            if scope=="seat":
                seat=self.ctx.get("args",{}).get("seat",self.ctx.get("trigger",{}).get("seat",self.ctx.get("from")))
                records=[record for record in records if record.get("args",{}).get("seat")==seat or record.get("from")==seat or record.get("to")==seat]
            since=params.get("since")
            if since is not None:
                cut=-1
                for index,record in enumerate(records):
                    if isinstance(since,dict) and "wake" in since:
                        matches=record.get("operation")=="seat.wake" and record.get("mode")==since["wake"] and (self.robot is None or record.get("role")==self.robot["role"])
                    else:matches=record.get("kind")==since and record.get("status")=="delivered"
                    if matches:cut=index
                records=records[cut+1:]
            window=params.get("window")
            if window is not None:
                duration=comparable(window)
                records=[record for record in records if isinstance(duration,(int,float)) and self.index-record.get("tick",0)<=duration]
            where=params.get("where",{})
            return sum(record.get("kind")==kind and record.get("status")=="delivered" and all(record.get("args",{}).get(key,MISSING)==value for key,value in where.items()) for record in records)
        if name=="request.open":
            kind=params.get("kind")
            replies=set(re.findall(r"[a-z][a-z-]+",K.get(kind,{}).get("reply","")))
            return any(record.get("kind")==kind and record.get("stage")==self.stage and record.get("status")=="delivered" and not any(reply.get("reply_id")==record.get("id") and reply.get("status")=="delivered" and reply.get("kind") in replies for reply in self.history) for record in self.history)
        if name=="review.open_blockers":
            verdicts=[record for record in self.history if record.get("stage")==self.stage and record.get("kind") in ("verdict","re-verdict") and record.get("status")=="delivered"]
            if verdicts:return [item["id"] for item in verdicts[-1]["args"].get("blockers",[])]
        observed=self.current["facts"].get(name,MISSING)
        if isinstance(observed,dict):
            key=next((params[field] for field in ("of","seat","id","dimension","subscription","pointer") if field in params),None)
            return observed.get(str(key),MISSING)
        return observed
    def value(self,value):
        if isinstance(value,list):return [self.value(item) for item in value]
        if not isinstance(value,dict):return value
        if "fact" in value:return self.fact(value)
        if set(value)=={"arg"}:return path_value(self.ctx.get("args",{}),value["arg"])
        if set(value)=={"msg"}:return self.ctx.get(value["msg"],MISSING)
        if set(value)=={"trigger"}:return path_value(self.ctx.get("trigger",{}),value["trigger"])
        if set(value)=={"cap"}:return E["caps"].get(value["cap"],MISSING)
        if set(value)=={"engagement"}:return path_value(E,value["engagement"])
        if set(value)=={"robot"}:return (self.robot.get("thresholds",{}) if self.robot else ROBOT_THRESHOLDS).get(value["robot"],MISSING)
        for namespace in ("roster","office","budget"):
            if set(value)=={namespace}:return path_value(self.definition.get("configuration",{}).get(namespace,{}),value[namespace])
        return {key:self.value(item) for key,item in value.items()}
    def holds(self, requirement):
        if "always" in requirement:return requirement["always"] is True
        if "any" in requirement:return any(self.holds(item) for item in requirement["any"])
        if "not" in requirement:return not self.holds(requirement["not"])
        if "attest" in requirement:return requirement["attest"] in self.ctx.get("args",{}).get("attest",[])
        if "signed" in requirement:return requirement["signed"]=="bora" and self.ctx.get("signed")=="bora" and self.ctx.get("role")=="bora"
        if "fact" in requirement:actual=self.fact(requirement)
        elif "arg" in requirement:actual=path_value(self.ctx.get("args",{}),requirement["arg"])
        elif "msg" in requirement:actual=self.ctx.get(requirement["msg"],MISSING)
        elif "re" in requirement:actual=self.ctx.get("re",{}).get(requirement["re"],MISSING)
        elif "counter" in requirement:actual=self.counter(requirement["counter"])
        elif "op" in requirement:actual=self.ctx.get("result",MISSING) if self.ctx.get("operation")==requirement["op"] else MISSING
        else:return False
        operator=next((key for key in requirement if key in COMPARISONS),None)
        if operator is None:return actual is not MISSING and bool(actual)
        return compare(actual,operator,self.value(requirement[operator]))
    def require(self, requirements):
        return next((item for item in requirements if not self.holds(item)),None)
    def typed(self,value,definition):
        if "enum" in definition and value not in definition["enum"]:return False
        typename=definition.get("type")
        types={"string":str,"array":list,"object":dict,"boolean":bool,"integer":int,"number":(int,float)}
        if typename and (typename not in types or not isinstance(value,types[typename]) or typename in ("number","integer") and isinstance(value,bool)):return False
        if isinstance(value,list) and "items" in definition and not all(self.typed(item,definition["items"]) for item in value):return False
        if isinstance(value,dict) and not all(field in value for field in definition.get("required_fields",[])):return False
        return True
    def arguments(self, kind, args):
        for name in args:
            if name not in kind.get("args",{}):return f"unknown argument '{name}'"
        for name,definition in kind.get("args",{}).items():
            if name not in args:
                if definition.get("required"):return f"missing argument '{name}'"
            elif not self.typed(args[name],definition):return f"invalid argument '{name}'"
        return None
    def classification(self,kind):
        definition=kind["class"]
        return next((case["class"] for case in definition.get("cases",[]) if self.holds(case["if"])),definition["default"])
    def effects(self, effects, request_id):
        for effect in effects:
            if "if" in effect and not self.holds(effect["if"]):continue
            if "count" in effect:
                name=effect["count"]
                declaration=L.get("counters",{}).get(name,{}) if isinstance(L.get("counters"),dict) else {}
                values=self.shared_counters if declaration.get("scope")=="effort" else self.current["counters"]
                values[name]=values.get(name,0)+1
            elif "do" in effect:
                operation=effect["do"]
                if operation in self.used_operations.setdefault(request_id,set()):raise WalkError(f"operation '{operation}' requested twice")
                self.used_operations[request_id].add(operation)
                self.pending.setdefault(request_id,set()).add(operation)
            elif "emit" in effect:
                self.emissions.append({"kind":effect["emit"],"from":"mailroom","to":self.value(effect["to"]),"args":self.value(effect.get("args",{}))})
    def matches(self,transition,machine,event,kind):
        if transition.get("on","message")!=event:return False
        src=transition["from"];state=self.state(machine)
        if not (src==state or src=="*" or src=="*active" and state in L["active_states"]):return False
        if event=="fact":
            return transition.get("kind") is None and any(item.get("fact")==kind for item in transition["require"])
        return transition.get("kind")==kind
    def transitions(self,event,kind,request_id):
        fired=[]
        for machine,definition in machines.items():
            candidates=[t for t in definition["transitions"] if self.matches(t,machine,event,kind)]
            selected=next((t for t in candidates if self.require(t["require"]) is None),None)
            if selected is None:continue
            src=self.state(machine);dst=selected["to"]
            if machine=="stage" and dst=="paused":self.current["previous"]=src
            if dst=="*previous":dst=self.current["previous"]
            self.set_state(machine,dst)
            self.effects(selected["effects"],request_id)
            fired.append(selected["id"])
        return fired
    def robot_rules(self, event, name, record=True):
        sends=[];wakes=[];rules=[]
        for _,robot in ROBOTS:
            self.robot=robot
            # The return edge is received by the Deputy while its pre-edge state is absent.
            if not self.holds(robot["active_when"]):continue
            for rule in robot["rules"]:
                if rule.get("for")=="persona":continue
                trigger=rule["on"]
                if trigger.get(event)!=name:continue
                if event=="op" and trigger.get("eq")!=self.ctx.get("result"):continue
                if self.require(rule["if"]) is not None:continue
                rules.append(rule["id"])
                for action in rule["then"]:
                    if "send" in action:
                        message={"kind":action["send"],"from":robot["role"],"to":self.value(action["to"]),"args":self.value(action.get("args",{})),"by":"robot:"+rule["id"]}
                        error=self.arguments(K[message["kind"]],message["args"])
                        if error:raise WalkError(f"{rule['id']}: {error}")
                        sends.append(message)
                    else:
                        wakes.append(action["wake"])
                        if record:self.history.append({"operation":"seat.wake","mode":action["wake"],"role":robot["role"],"stage":self.stage,"tick":self.index})
        self.robot=None
        self.emissions.extend(sends)
        return {"rules":rules,"sends":[message["kind"] for message in sends],"wakes":wakes}
    def check_expectation(self,step,actual):
        expected=step.get("expect",{})
        for key,value in expected.items():
            if key=="state":observed=self.state("stage")
            elif key=="states":observed={name:self.state(name) for name in value}
            else:observed=actual.get(key)
            if observed!=value:raise WalkError(f"expected {key} {value!r}, observed {observed!r}")
    def execute_step(self,step):
        self.set_stage(step.get("stage",self.stage))
        for name in step.get("facts",{}):
            if name not in V["facts"]:raise WalkError(f"unknown observed fact '{name}'")
        self.current["facts"].update(step.get("facts",{}))
        self.ctx={"args":{},"trigger":step.get("trigger",{}),"stage":self.stage}
        if "event" in step:
            event=step["event"];field=next(key for key in event if key!="eq")
            self.ctx.update(result=event.get("eq"),operation=event.get("op"))
            previous_presence=self.state("presence")
            fired=self.transitions("fact",event[field],str(self.index)) if field=="fact" else []
            actual=self.robot_rules(field,event[field])
            current_presence=self.state("presence")
            if current_presence!=previous_presence:
                self.shared["presence"]=previous_presence
                try:edge=self.robot_rules("presence",current_presence)
                finally:self.shared["presence"]=current_presence
                actual={key:actual[key]+edge[key] for key in ("rules","sends","wakes")}
            if not actual["rules"] and not fired:raise WalkError("observation has no robot or lifecycle arrow")
            self.check_expectation(step,{**actual,"transitions":fired})
            return
        operation=step.get("operation")
        event="op-result" if operation else step.get("on","message")
        if operation:
            request_id=step["request"]
            if operation not in self.pending.get(request_id,set()):raise WalkError(f"operation result '{operation}' has no pending request")
            self.pending[request_id].remove(operation)
            self.ctx=copy.deepcopy(self.messages[request_id]);self.ctx.update(operation=operation,result=step.get("result","ok"))
            self.ctx["trigger"]={"seat":self.ctx.get("args",{}).get("seat"),"args":self.ctx.get("args",{}),"stage":self.stage}
            kind_name=self.ctx["kind"]
        else:
            kind_name=step["kind"]
            if kind_name not in K:raise WalkError(f"unknown kind '{kind_name}'")
            kind=K[kind_name];who=step.get("who",kind["from"].split("|")[0].strip());role=self.definition.get("seats",{}).get(who,who)
            if role not in {part.strip() for part in kind["from"].split("|")}:raise WalkError(f"role '{role}' may not send '{kind_name}'")
            args=step.get("args",{})
            error=self.arguments(kind,args)
            if error:raise WalkError(error)
            reply_id=step.get("re")
            reply=self.messages.get(reply_id,{}) if isinstance(reply_id,str) else (reply_id or {})
            self.ctx={"kind":kind_name,"from":who,"role":role,"by":step.get("by",who),"args":args,"signed":step.get("signed"),"stage":self.stage,"re":reply,"reply_id":reply_id,"trigger":{"args":args,"from":who,"stage":self.stage}}
            destination=kind["to"]
            self.ctx["to"]=step.get("to",reply.get("from") if isinstance(destination,dict) else destination.split("|")[0].strip())
            self.ctx["class"]=self.classification(kind)
            request_id=step.get("id",str(self.index))
            if str(self.ctx["by"]).startswith("robot:"):
                match=next((item for item in self.emissions if item.get("by")==self.ctx["by"] and item["kind"]==kind_name and item["args"]==args),None)
                if match is None:raise WalkError("robot send has no matching rule output")
                self.emissions.remove(match)
            arrivals=self.robot_rules("arrives",kind_name)
            intercepted=any(K[message["kind"]] and message["kind"]=="hold" for message in self.emissions if message.get("by") in {"robot:"+name for name in arrivals["rules"]})
            if intercepted:
                self.ctx.update(id=request_id,status="held",tick=self.index)
                self.messages[request_id]=copy.deepcopy(self.ctx)
                self.history.append(copy.deepcopy(self.ctx))
                self.check_expectation(step,{**arrivals,"held":True})
                if not step.get("expect",{}).get("held"):raise WalkError("message was held")
                return
            failure=self.require(kind["require"])
            if failure is not None:raise WalkError(f"kind {kind_name} requirement failed: {failure}")
            window=kind.get("window","stage")
            key=(kind_name,self.stage if window=="stage" else (who,step.get("session","default")) if window=="session" else (who,window))
            maximum=kind.get("max_fires")
            if isinstance(maximum,int) and self.kind_fires.get(key,0)>=maximum:raise WalkError(f"kind {kind_name} cap exhausted")
            self.kind_fires[key]=self.kind_fires.get(key,0)+1
            if len(step.get("body","").splitlines())>E.get("caps",{}).get("body_lines",float("inf")):raise WalkError("body line cap exhausted")
            self.ctx.update(id=request_id,status="delivered",tick=self.index)
            self.messages[request_id]=copy.deepcopy(self.ctx)
        fired=self.transitions(event,kind_name,request_id)
        if not fired and not operation and any(t.get("kind")==kind_name and t.get("on","message")==event for _,t in all_transitions):
            raise WalkError(f"no arrow from {self.state('stage')} for {kind_name}")
        if not operation:self.history.append(copy.deepcopy(self.ctx))
        else:self.history.append({"operation":operation,"result":self.ctx["result"],"stage":self.stage,"tick":self.index})
        observed=self.robot_rules("op" if operation else "delivered",operation or kind_name)
        if not operation:
            observed={key:arrivals[key]+observed[key] for key in ("rules","sends","wakes")}
        self.check_expectation(step,{**observed,"transitions":fired,"held":False})
    def step(self,step):
        expected=step.get("expect",{})
        if not expected.get("refused"):
            return self.execute_step(step)
        snapshot=copy.deepcopy(self.__dict__)
        attempt=copy.deepcopy(step)
        attempt["expect"]={key:value for key,value in expected.items() if key not in ("refused","refusal_contains")}
        try:
            self.execute_step(attempt)
        except WalkError as error:
            text=str(error)
            if not (text.startswith("kind ") or text.startswith("no arrow ") or text.startswith("role ") or text.startswith(("unknown argument", "missing argument", "invalid argument"))):
                raise
            if expected.get("refusal_contains","") not in text:
                raise WalkError(f"wrong refusal: {text}")
            self.__dict__.clear();self.__dict__.update(snapshot)
            self.current["facts"].update(step.get("facts",{}))
            return
        raise WalkError("expected refusal, but the message was delivered")
    def run(self):
        for self.index,step in enumerate(self.definition["steps"],1):
            try:self.step(step)
            except WalkError as error:
                fail(f"walk {self.name} step {self.index}: {error}")
                return
        expected=self.definition["final"]
        actual={stage:context["states"]["stage"] for stage,context in self.contexts.items()} if isinstance(expected,dict) else self.state("stage")
        if actual!=expected:
            fail(f"walk {self.name}: expected final {expected!r}, observed {actual!r}")
            return
        if any(self.pending.values()):
            fail(f"walk {self.name}: operation results are missing")
            return
        for kind,expected_count in self.definition.get("expected_counts",{}).items():
            actual_count=sum(record.get("kind")==kind and record.get("status")=="delivered" for record in self.history)
            if actual_count!=expected_count:
                fail(f"walk {self.name}: expected {expected_count} {kind} deliveries, observed {actual_count}")
                return
        ok(f"walk {self.name} reached {expected}")

if not FIXTURE:
    W=[load_yaml(path.read_text()) for path in sorted((ROOT / "rules/walks").glob("*.yaml"))]
for walk in W:
    Walk(walk).run()
if W and not fails:ok(f"the walks pass ({len(W)} walks)")

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
