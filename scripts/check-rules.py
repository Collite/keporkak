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
  - pool/ (contracts.md §9): the three files' shapes; every level is a line of the order; the review floor is a model of
    it; office and subscription names; every {roster: …}, {office: …} and {budget: …} value the rules use resolves
Usage: python3 scripts/check-rules.py [--pool DIR] [--render out.md] [--self-test] [--trace WALK]
  --trace WALK  also prints the named walk as a `WALK <json>` line and, after each step, a `TRACE <json>` line: whether
                it was refused, the transitions it fired and every stage's state — the engine's second opinion (S1.2).
                WALK is a walk's name in rules/walks/, or the path of a walk file: then that walk alone is run (S2.8)
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
TRACE = sys.argv[sys.argv.index("--trace") + 1] if "--trace" in sys.argv else None
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
    # The roles master's pool names besides — the roster reserves a model for the principal (contracts.md §9).
    E = {"roles": {"coder": {}, "teamlead": {}, "principal": {}}, "graduation": {"never": []},
         "mailroom_declared_effects": given.get("mailroom_declared_effects", []), "caps": given.get("caps", {}),
         "profile": given.get("profile", "supervised"), "autonomy_profiles": given.get("autonomy_profiles", {"supervised": {}})}
    if "second_key" in given:
        E["second_key"] = given["second_key"]
    if "stop_gate" in given:
        E["stop_gate"] = given["stop_gate"]
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
PARTIES = {"bora", "daemon", "mailroom", "ledger", "board"}   # contracts.md §1
RESERVED_STATES = {"*", "*active", "*previous"}             # contracts.md §4.1, and `*` (any state) on master

MISSING = object()
def path_value(root, path):
    value=root
    for part in str(path).split("."):
        if isinstance(value,dict): value=value.get(part,MISSING)
        elif isinstance(value,list) and part.isdigit() and int(part)<len(value): value=value[int(part)]
        else: return MISSING
    return value

def persona_modes(role):
    source = ROOT / "agents" / role / "AGENT.md"
    if not source.is_file():
        return {}
    return load_yaml(re.match(r"---\n(.*?)\n---", source.read_text(), re.S).group(1)).get("modes", {}) or {}
ALL_MODES = {mode for persona in sorted((ROOT / "agents").glob("*/AGENT.md")) for mode in persona_modes(persona.parent.name)}

def sender_thresholds(senders):
    """A `{robot: …}` outside a robot names a threshold of the robot file of the kind's own sender (contracts.md §4.1)."""
    names = {part.strip() for part in str(senders or "").split("|")}
    return {name for _, robot in ROBOTS if robot.get("role") in names for name in robot.get("thresholds", {})}

# The blocks of requirements a `{holds: …}` may name (contracts.md §4.1, Amendment 7) — `second_key`. `stop_gate` is
# none (Amendment 19): it is judged only when a turn is about to end, and a rule whose requirement equals its own
# repeats nothing.
BLOCKS = {name: E[name].get("require", []) for name in ("second_key",) if isinstance(E.get(name), dict)}

# An attestation is described in prose (contracts.md §4.4): the loader reads its description as text, and so does this.
for name, description in (V.get("attestations") or {}).items():
    if not isinstance(description, str): fail(f"attestations.{name} must be text")

def named_blocks(requirement):
    """The blocks a requirement names with {holds: …}, inside any and not too."""
    if not isinstance(requirement, dict): return
    if isinstance(requirement.get("holds"), str): yield requirement["holds"]
    for item in requirement.get("any", []) if isinstance(requirement.get("any"), list) else []: yield from named_blocks(item)
    if "not" in requirement: yield from named_blocks(requirement["not"])

# One block never names another — itself included — so no evaluation runs in a circle (contracts.md §4.1, Amendment 9).
for name, requirements in BLOCKS.items():
    for named in sorted({block for requirement in requirements or [] for block in named_blocks(requirement)}):
        fail(f"{name}: names the block '{named}' — a block never names a block")

def check_destination(to, owner):
    if isinstance(to, str) and to not in roles | PARTIES:
        fail(f"{owner}: unknown destination '{to}'")

# A declared emit carries the args its kind requires (contracts.md §4.2, Amendment 16): every arg it gives is declared,
# every required one is given, and a value written as a literal is of the declared type and in the declared enum.
# A value the mailroom resolves at run time ({arg: …}, {re: …}, {fact: …}) is the engine's to check.
# A boolean is no number, and a number with no fraction is an integer, `1.0` too — as the engine types an arg at run
# time and as JSON Schema reads `integer`; the Kotlin loader reads them so as well (issue #26).
ARG_TYPES = {"string": lambda v: isinstance(v, str), "boolean": lambda v: isinstance(v, bool),
             "integer": lambda v: (isinstance(v, int) and not isinstance(v, bool)) or (isinstance(v, float) and v.is_integer()),
             "number": lambda v: isinstance(v, (int, float)) and not isinstance(v, bool),
             "array": lambda v: False, "object": lambda v: False}
def same_literal(a, b):
    """Two literals of a rules file are the same: a boolean only as a boolean (Python's `True == 1` is not the rules')."""
    return isinstance(a, bool) == isinstance(b, bool) and a == b
def literal_text(value):
    return ("true" if value else "false") if isinstance(value, bool) else str(value)
def check_emit_args(effect, owner):
    if not isinstance(effect, dict) or "emit" not in effect or not isinstance(K.get(effect["emit"]), dict):
        return
    kind, declared = effect["emit"], K[effect["emit"]].get("args") or {}
    given = effect.get("args") or {}
    for name in given:
        if name not in declared: fail(f"{owner}: emit '{kind}' gives undeclared arg '{name}'")
    for name, spec in declared.items():
        if isinstance(spec, dict) and spec.get("required") and name not in given:
            fail(f"{owner}: emit '{kind}' lacks required arg '{name}'")
    for name, value in given.items():
        spec = declared.get(name)
        if not isinstance(spec, dict) or not isinstance(value, (str, bool, int, float)):
            continue
        if "enum" in spec and not any(same_literal(value, item) for item in spec["enum"]):
            fail(f"{owner}: emit '{kind}' arg '{name}' is not one of {' | '.join(literal_text(item) for item in spec['enum'])}")
        elif "enum" not in spec and spec.get("type") in ARG_TYPES and not ARG_TYPES[spec["type"]](value):
            fail(f"{owner}: emit '{kind}' arg '{name}' is not {spec['type']}")

# All executable names are resolved against the closed vocabulary and rule owners (issue #4).
# `{robot: …}` resolves against one robot's thresholds: the robot's own, or — outside a robot — those of the robot
# file of the rule's sender (`thresholds`). A `since:` names a kind or `{wake: <mode>}` of a persona: the robot's own.
def check_references(value, owner, robot=None, thresholds=frozenset()):
    if isinstance(value, dict):
        namespaces = {"fact": V["facts"], "op": V["operations"],
                      "do": V["operations"], "cap": E.get("caps", {}),
                      "kind": K, "emit": K, "attest": V.get("attestations", {}), "counter": L.get("counters", {}), "count": L.get("counters", {}), "robot": robot.get("thresholds",{}) if robot is not None else thresholds,
                      "holds": BLOCKS}
        labels = {"fact": "fact", "op": "operation", "do": "operation",
                  "cap": "cap", "kind": "kind", "emit": "kind", "attest": "attestation", "counter": "counter", "count": "counter", "robot": "robot threshold",
                  "holds": "engagement block"}
        for key, item in value.items():
            if key == "holds" and item == "stop_gate":
                fail(f"{owner}: {{holds: stop_gate}} — stop_gate is no block a {{holds: …}} may name: it is judged only when a turn is about to end")
            elif key in namespaces and isinstance(item, str) and item not in namespaces[key]:
                fail(f"{owner}: unknown {labels[key]} '{item}'")
            if key == "since" and isinstance(item, str) and item not in K:
                fail(f"{owner}: unknown kind '{item}'")
            if key == "since" and isinstance(item, dict) and isinstance(item.get("wake"), str):
                modes = persona_modes(robot["role"]) if robot is not None else ALL_MODES
                if item["wake"] not in modes:
                    fail(f"{owner}: unknown {'own ' if robot is not None else ''}persona mode '{item['wake']}'")
            if key == "engagement" and isinstance(item, str) and path_value(E, item) is MISSING:
                fail(f"{owner}: unknown engagement value '{item}'")
            if key == "fact" and isinstance(item, str) and item.endswith(".state") and item.removesuffix(".state") in machines:
                known = set(machines[item.removesuffix(".state")]["states"])
                for comparison in ("eq", "ne", "in"):
                    compared = value.get(comparison)
                    for state in (compared if isinstance(compared, list) else [compared]):
                        if isinstance(state, str) and state not in known:
                            fail(f"{owner}: unknown state '{state}' in {item.removesuffix('.state')}")
            if key not in ("since",) or not isinstance(item, dict):
                check_references(item, owner, robot, thresholds)
    elif isinstance(value, list):
        for item in value:
            check_references(item, owner, robot, thresholds)

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
    forms={"fact","arg","msg","re","counter","attest","signed","op","holds","any","not","always"}
    if not isinstance(requirement,dict) or len(set(requirement)&forms)!=1:
        fail(f"{owner}: unknown requirement form")
        return
    form=next(iter(set(requirement)&forms))
    if form=="any":
        if not isinstance(requirement[form],list):fail(f"{owner}: any must contain requirements")
        else:
            for item in requirement[form]:check_requirement_form(item,owner)
    elif form=="not":
        if isinstance(requirement[form],dict) and "not" in requirement[form]:fail(f"{owner}: a requirement negates a negation")
        check_requirement_form(requirement[form],owner)
    elif form=="always":
        if not isinstance(requirement[form],bool):fail(f"{owner}: always must be boolean")
    elif not isinstance(requirement[form],str):fail(f"{owner}: requirement name must be a string")
    elif form=="msg" and requirement[form] not in ("by","from","to","stage"):fail(f"{owner}: unknown message field")
    elif form=="re" and requirement[form] not in ("kind","class","from","to"):fail(f"{owner}: unknown reply field")
    elif form=="signed" and requirement[form]!="bora":fail(f"{owner}: unknown signer")
    elif form=="op" and requirement.get("eq") not in ("ok","failed"):fail(f"{owner}: unknown operation result")

def check_requirements(requirements, owner, robot=None, thresholds=frozenset()):
    if not isinstance(requirements,list):
        fail(f"{owner}: require must be a list")
        return
    for requirement in requirements:check_requirement_form(requirement,owner)
    check_references(requirements, owner, robot, thresholds)
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
    thresholds = sender_thresholds(K.get(transition.get("kind"), {}).get("from"))
    check_requirements(transition["require"], owner, thresholds=thresholds)
    check_references(transition.get("effects", []), owner, thresholds=thresholds)
    for effect in transition.get("effects", []):
        if not isinstance(effect, dict) or not set(effect) & {"do", "emit", "count"}:
            fail(f"{owner}: unknown effect")
        if "emit" in effect and effect["emit"] not in E["mailroom_declared_effects"]:
            fail(f"{owner}: undeclared mailroom emit '{effect['emit']}'")
        if isinstance(effect, dict) and "emit" in effect:
            check_destination(effect.get("to"), owner)
            check_emit_args(effect, owner)
        if "if" in effect:
            check_requirements([effect["if"]], owner, thresholds=thresholds)
    for requirement in transition["require"]:
        if "op" in requirement and event != "op-result":
            fail(f"{owner}: operation result outside op-result trigger")
    known_states = set(machines[machine]["states"])
    for state in (transition["from"], transition["to"]):
        if state not in known_states and state not in RESERVED_STATES:
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
    # The kinds that answer this one (contracts.md §4.2): each is a kind.
    if "replies" in kind:
        if not isinstance(kind["replies"], list):
            fail(f"{name}: replies must be a list")
        else:
            for reply in kind["replies"]:
                if not isinstance(reply, str): fail(f"{name}: replies's item must be text")
                elif reply not in K: fail(f"{name}: unknown kind '{reply}'")
    thresholds = sender_thresholds(kind.get("from"))
    check_requirements(kind.get("require", []), name, thresholds=thresholds)
    for effect in kind.get("expiry",[]):
        check_references(effect,name,thresholds=thresholds)
        if isinstance(effect, dict) and "emit" in effect:
            check_destination(effect.get("to"), name)
            check_emit_args(effect, name)
        if not isinstance(effect,dict) or not set(effect)&{"emit","report","transition","do","wait"}:
            fail(f"{name}: invalid expiry effect")
        # The wait goes on only under a cap (contracts.md §4.2): `{wait: again}`'s if is a counter compared with {cap: …} —
        # `lt`, on a counter an `on: expiry` transition of the kind moves, or the wait never ends (issue #18).
        if isinstance(effect, dict) and "wait" in effect:
            if effect["wait"] != "again": fail(f"{name}: wait must be again")
            condition = effect.get("if")
            if not (isinstance(condition, dict) and isinstance(condition.get("counter"), str)
                    and any(isinstance(condition.get(operator), dict) and set(condition[operator]) == {"cap"} for operator in COMPARISONS)):
                fail(f"{name}: {{wait: again}} has no if that reads a counter against a cap")
            elif not (isinstance(condition.get("lt"), dict) and any(
                    t.get("kind") == name and t.get("on") == "expiry"
                    and any(isinstance(e, dict) and e.get("count") == condition["counter"] for e in t.get("effects") or [])
                    for _, t in all_transitions)):
                fail(f"{name}: {{wait: again}}'s if is not lt a cap on a counter an on: expiry transition of the kind moves")
        if "transition" in effect and effect["transition"] not in seen_ids:
            fail(f"{name}: expiry names unknown transition '{effect['transition']}'")
        if "emit" in effect and effect["emit"] not in E["mailroom_declared_effects"]:
            fail(f"{name}: undeclared expiry emit '{effect['emit']}'")
        if "if" in effect:
            check_requirements([effect["if"]],name,thresholds=thresholds)
    if kind.get("to")=="daemon" and not any(t.get("kind")==name and any("do" in effect for effect in t.get("effects",[])) for _,t in all_transitions):
        fail(f"{name}: daemon request has no operation binding")
    for case in (kind.get("class", {}).get("cases", []) if isinstance(kind.get("class"), dict) else []):
        check_requirements([case["if"]], name, thresholds=thresholds)
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
# In the stop gate, {trigger: seat} is the seat whose turn is ending (contracts.md §4.1); nothing else triggers it.
def trigger_paths(value):
    if isinstance(value, dict):
        found = [value["trigger"]] if set(value) == {"trigger"} else []
        return found + [path for item in value.values() for path in trigger_paths(item)]
    if isinstance(value, list):
        return [path for item in value for path in trigger_paths(item)]
    return []
if isinstance(E.get("stop_gate"), dict):
    for path in dict.fromkeys(trigger_paths(E["stop_gate"].get("require", []))):
        if path != "seat":
            fail(f"stop_gate: {{trigger: {path}}} names nothing here — in stop_gate, {{trigger: seat}} is the seat whose turn is ending")
if not fails:
    ok("engagement second key, cap references and the v1 stop gate checked")

# What a request no deny: word covers gets (contracts.md §4.3, §10; D-18 (4)): these keys and these values, no other —
# as kp-rules reads them.
PERMISSIONS = {"write_inside": ("allow", "deny"), "write_outside": ("allow", "deny"), "read": ("allow", "deny"), "lifetime": ("once",)}
if "permissions" in E:
    permissions = E["permissions"] if isinstance(E["permissions"], dict) else {}
    if not isinstance(E["permissions"], dict): fail("permissions must be a mapping")
    for key in permissions:
        if key not in PERMISSIONS: fail(f"permissions: unknown key '{key}'")
    for key, values in PERMISSIONS.items():
        if key not in permissions: fail(f"permissions: missing {key}")
        elif permissions[key] not in values: fail(f"permissions.{key} must be {' or '.join(values)}")
    if not fails:
        ok(f"engagement permissions: {', '.join(PERMISSIONS)}")

# 1. transitions → kinds (each transition's kind and states are checked above, with its id)
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

# 6. engagement references; the profile in force is one of autonomy_profiles (contracts.md §4.3)
if "profile" not in E: fail("missing profile")
elif E["profile"] not in (E.get("autonomy_profiles") or {}): fail(f"profile '{E['profile']}' is not in autonomy_profiles")
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
        queue_id=queue.get("id")
        if not isinstance(queue_id,str): fail(f"{filename}: a queue has an id")
        elif re.fullmatch(r"[A-Z]+-[0-9]+",queue_id) is None: fail(f"{filename}: a queue's id must match [A-Z]+-[0-9]+")
        elif queue_id in robot_ids: fail(f"{queue_id}: duplicate robot rule id")
        else: robot_ids.add(queue_id)
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
        elif "presence" in trigger and trigger["presence"] not in ("present","absent"):
            fail(f"{owner}: presence is present or absent")
        check_references(trigger,owner,robot)
        for field in ("delivered","arrives","expired"):
            if field in trigger and trigger[field] not in K: fail(f"{owner}: unknown trigger kind '{trigger[field]}'")
        check_requirements(rule["if"],owner,robot)
        for action in rule["then"]:
            if not isinstance(action,dict) or len(set(action)&{"send","wake"})!=1 or set(action)-{"send","wake","to","args"}:
                fail(f"{owner}: robot then must only send or wake")
                continue
            check_references(action.get("args",{}),owner,robot)
            check_references(action.get("to"),owner,robot)
            if "send" in action:
                check_destination(action.get("to"), owner)
                kind=action["send"]
                if kind not in K: fail(f"{owner}: unknown send kind '{kind}'")
                elif kind not in may: fail(f"{owner}: role '{role}' may not send '{kind}'")
                else:
                    for name in action.get("args",{}):
                        if name not in K[kind].get("args",{}): fail(f"{owner}: unknown send argument '{name}'")
                    for name,definition in K[kind].get("args",{}).items():
                        if definition.get("required") and name not in action.get("args",{}): fail(f"{owner}: missing send argument '{name}'")
            if "wake" in action:
                if action["wake"] not in persona_modes(role): fail(f"{owner}: unknown own persona mode '{action['wake']}'")
if not fails:
    ok(f"mailroom sends only its {len(declared)} declared effects; {len(ROBOTS)} robots checked against the matrix")
    ok(f"{executed} robot rules with on/if/then; {persona} persona rules; queue and named thresholds checked")

# A rule that needs a block's requirements refers to it with `{holds: …}`; it never repeats them (contracts.md §4.1).
def requirement_parts(requirement):
    if not isinstance(requirement, dict):
        return
    yield requirement
    if isinstance(requirement.get("any"), list):
        for item in requirement["any"]:
            yield from requirement_parts(item)
    elif "not" in requirement:
        yield from requirement_parts(requirement["not"])

def conditions(effects):
    return [effect["if"] for effect in effects or [] if isinstance(effect, dict) and "if" in effect]

def class_cases(kind):
    classification = kind.get("class")
    return [case["if"] for case in classification.get("cases", []) if isinstance(case, dict) and "if" in case] if isinstance(classification, dict) else []

RULE_REQUIREMENTS = [(transition.get("id"), list(transition.get("require") or []) + conditions(transition.get("effects"))) for _, transition in all_transitions]
RULE_REQUIREMENTS += [(name, list(kind.get("require") or []) + class_cases(kind) + conditions(kind.get("expiry"))) for name, kind in K.items()]
for filename, robot in ROBOTS:
    RULE_REQUIREMENTS.append((filename, [robot.get("active_when"), robot.get("queue", {}).get("next_when")]))
    RULE_REQUIREMENTS += [(rule.get("id"), list(rule.get("if") or [])) for rule in robot.get("rules", []) if rule.get("for") != "persona"]
for owner, requirements in RULE_REQUIREMENTS:
    parts = [part for requirement in requirements for part in requirement_parts(requirement)]
    for block, block_requirements in BLOCKS.items():
        if block_requirements and all(item in parts for item in block_requirements):
            fail(f"{owner}: repeats the requirements of '{block}' — refer to the block with {{holds: {block}}}")
if not fails:
    ok(f"every {{holds: …}} names a block of engagement.yaml ({', '.join(BLOCKS)}); no rule repeats a block's requirements")

# Walk the public rules, using supplied observations and fake operation results only.
# There is no I/O or action execution here: do/send/wake are checked and recorded.

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
        else:
            same=actual==expected
            # A duration is the same as another of its length, or as a number of seconds: `0s` is `0` (contracts.md §4.4).
            left,right=comparable(actual),comparable(expected)
            if not same and (isinstance(actual,str) or isinstance(expected,str)) and all(isinstance(side,(int,float)) and not isinstance(side,bool) for side in (left,right)):
                same=left==right
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

class Refusal(WalkError):
    """A step the rules refuse, and the rule that refuses it — the id the engine gives (contracts.md §2.2): the kind for
    what every kind is held to and for its own requirements, the stage's first candidate transition when no arrow fires."""
    def __init__(self, rule, text):
        super().__init__(text)
        self.rule=rule

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
        # A state per seat, as the engine keeps one per seat it moved (S3.6 T2, D-81): `seat.state`, the seat machine's
        # transitions and a robot's trigger are that seat's. A seat is bound to the stage of the message that last moved it
        # (contracts.md §2.2, Amendment 29); a seat transition that names no seat moves each seat bound to the stage, in the
        # order they were first moved. `subject` is the seat a seat transition is read for, while it is read.
        self.seats={}
        self.bound={}
        self.first_moved=[]
        self.subject=None
        # Every operation requested, as `<operation> (<kind of the message that asked> <its seat>)`: part of a walk's end (T3).
        self.requested=[]
        # While an unordered step's events are played (T3): what the robots and the mailroom send is judged as it is sent,
        # as the daemon judges it, each named `<the step's id>/<kind>` (then `/2`, `/3`); `in_group` says so.
        self.in_group=False
        self.group_sent={}
        self.set_stage(self.stage)
    def set_stage(self, stage):
        self.stage=stage
        if stage not in self.contexts:
            setting=self.definition.get("stages",{}).get(stage,{})
            initial=setting.get("initial",self.definition.get("initial","planned"))
            self.contexts[stage]={"states":{"stage":initial,"seat":"ready","found_item":"found","flag_thread":"asked",**setting.get("machines",{})},"previous":None,"found_items":{},"counters":{},"facts":{**self.definition.get("facts",{}),**setting.get("facts",{})}}
        self.current=self.contexts[stage]
    def seat_default(self,seat):
        """The state a seat no step has moved is in (S3.6 T2): a stage's `machines: seat:` is that stage's coder's — the seat
        its table names for `coder`, as the harness seeds it — or, when the table names none, any seat first met there;
        every other seat is `ready`, as the harness seeds a walk's seats."""
        settings=self.definition.get("stages",{})
        for stage,setting in settings.items():
            names={**self.definition.get("facts",{}),**setting.get("facts",{})}.get("seat.name")
            if "seat" in setting.get("machines",{}) and isinstance(names,dict) and names.get("coder")==seat:return setting["machines"]["seat"]
        here=settings.get(self.stage,{})
        names=self.current["facts"].get("seat.name")
        if "seat" in here.get("machines",{}) and not (isinstance(names,dict) and "coder" in names):return here["machines"]["seat"]
        return "ready"
    def seat_state(self,seat):
        if not isinstance(seat,str):return MISSING
        if seat not in self.seats:self.seats[seat]=self.seat_default(seat)
        return self.seats[seat]
    def seats_of(self,stage):
        return [seat for seat in self.first_moved if self.bound.get(seat)==stage]
    def subjects(self,machine):
        """Whom a machine's transition moves (contracts.md §2.2): the seat the message names (`args.seat`), else each seat
        bound to its stage; one subject for every other machine."""
        if machine!="seat":return [None]
        seat=self.ctx.get("args",{}).get("seat")
        return [seat] if isinstance(seat,str) else self.seats_of(self.ctx.get("stage",self.stage))
    def state(self,machine):
        if machine=="found_item":return self.current["found_items"].get(self.ctx.get("args",{}).get("found","default"),"found")
        if machine=="seat":return self.seat_state(self.subject) if self.subject is not None else None
        return self.shared[machine] if machine in self.shared else self.current["states"].get(machine)
    def set_state(self,machine,state):
        if machine=="found_item":
            self.current["found_items"][self.ctx.get("args",{}).get("found","default")]=state
            return
        if machine=="seat":
            self.seats[self.subject]=state
            self.bound[self.subject]=self.ctx.get("stage",self.stage)
            if self.subject not in self.first_moved:self.first_moved.append(self.subject)
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
        # The seat machine answers a seat's state, as the engine answers it from the log (D-81): the state of the seat asked about.
        if name=="seat.state":return self.seat_state(params.get("seat"))
        if name=="thread.open":return self.state("flag_thread") in ("asked","held")
        # The last signed delivery that carries `stages` (contracts.md §4.4, Amendment 12): the wave was checked when it
        # was delivered and is not checked again.
        if name=="queue.names":
            queues=[record for record in self.history if record.get("signed")=="bora" and record.get("status")=="delivered" and isinstance(record.get("args",{}).get("stages"),list)]
            return bool(queues) and self.stage in queues[-1]["args"]["stages"]
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
            replies=set(K.get(kind,{}).get("replies",[]))
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
    def holds(self, requirement, blocks=True):
        if "always" in requirement:return requirement["always"] is True
        # A block named inside a block is reported, and never followed (Amendment 9).
        if "holds" in requirement:return blocks and requirement["holds"] in BLOCKS and all(self.holds(item,False) for item in BLOCKS[requirement["holds"]])
        if "any" in requirement:return any(self.holds(item,blocks) for item in requirement["any"])
        if "not" in requirement:return not self.holds(requirement["not"],blocks)
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
        """An arg against its declaration as both validators and the engine type it (issue #26): an enum holds a
        boolean only as a boolean, a boolean is no number, and a number with no fraction is an integer, `1.0` too."""
        if "enum" in definition and not any(same_literal(value,item) for item in definition["enum"]):return False
        typename=definition.get("type")
        containers={"array":list,"object":dict}
        if typename and not (isinstance(value,containers[typename]) if typename in containers else typename in ARG_TYPES and ARG_TYPES[typename](value)):return False
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
                seat=self.ctx.get("args",{}).get("seat")
                self.requested.append(f"{operation} ({self.ctx.get('kind')}{' '+seat if isinstance(seat,str) else ''})")
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
        """Each machine in turn, and each subject it moves in turn (contracts.md §2.2, §4.1): the first candidate whose
        requirements hold fires. `first_candidate` is the first candidate of all, which names a refusal."""
        fired=[];self.first_candidate=None
        for machine,definition in machines.items():
            for self.subject in self.subjects(machine):
                candidates=[t for t in definition["transitions"] if self.matches(t,machine,event,kind)]
                if self.first_candidate is None and candidates:self.first_candidate=candidates[0]["id"]
                selected=next((t for t in candidates if self.require(t["require"]) is None),None)
                if selected is None:continue
                src=self.state(machine);dst=selected["to"]
                # `*previous` is the state before the present one: a move to the same state keeps it, as the engine does (D-81).
                if machine=="stage" and dst=="paused" and src!=dst:self.current["previous"]=src
                if dst=="*previous":dst=self.current["previous"]
                self.set_state(machine,dst)
                self.effects(selected["effects"],request_id)
                fired.append(selected["id"])
        self.subject=None
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
                        if record:self.history.append({"operation":"seat.wake","mode":action["wake"],"role":robot["role"],"rule":rule["id"],"stage":self.stage,"tick":self.index})
        self.robot=None
        self.emissions.extend(sends)
        return {"rules":rules,"sends":[message["kind"] for message in sends],"wakes":wakes}
    def check_expectation(self,step,actual):
        expected=step.get("expect",{})
        for key,value in expected.items():
            if key=="state":observed=self.state("stage")
            elif key=="states":observed={name:self.seat_state(self.step_seat(step)) if name=="seat" else self.state(name) for name in value}
            elif key=="seats":observed={seat:self.seat_state(seat) for seat in value}
            else:observed=actual.get(key)
            if observed!=value:raise WalkError(f"expected {key} {value!r}, observed {observed!r}")
    def step_seat(self,step):
        """The seat a step speaks of, as the harness reads it: the one its args or trigger name, else its stage's coder (its
        table's `seat.name` for `coder`), else the first seat bound to its stage."""
        named=step.get("args",{}).get("seat") or step.get("trigger",{}).get("seat")
        if isinstance(named,str):return named
        names=self.current["facts"].get("seat.name")
        if isinstance(names,dict) and isinstance(names.get("coder"),str):return names["coder"]
        return next(iter(self.seats_of(self.stage)),None)
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
            self.fired=fired
            # An event whose order the world does not fix may find nothing to do: the order before it did it (T3).
            if not actual["rules"] and not fired and not self.in_group:raise WalkError("observation has no robot or lifecycle arrow")
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
            if role not in {part.strip() for part in kind["from"].split("|")}:raise Refusal(kind_name,f"role '{role}' may not send '{kind_name}'")
            args=step.get("args",{})
            error=self.arguments(kind,args)
            if error:raise Refusal(kind_name,error)
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
                holders=[message["by"] for message in self.emissions if message["kind"]=="hold" and message.get("by") in {"robot:"+name for name in arrivals["rules"]}]
                self.ctx.update(id=request_id,status="held",tick=self.index,held_by=holders)
                self.messages[request_id]=copy.deepcopy(self.ctx)
                self.history.append(copy.deepcopy(self.ctx))
                self.check_expectation(step,{**arrivals,"held":True,"class":self.ctx["class"]})
                if not step.get("expect",{}).get("held"):raise WalkError("message was held")
                return
            failure=self.require(kind["require"])
            if failure is not None:raise Refusal(kind_name,f"kind {kind_name} requirement failed: {failure}")
            window=kind.get("window","stage")
            key=(kind_name,self.stage if window=="stage" else (who,step.get("session","default")) if window=="session" else (who,window))
            maximum=kind.get("max_fires")
            # `max_fires: null` is caps.max_fires_default: no kind is without a cap (contracts.md §4.2).
            if maximum is None:maximum=E.get("caps",{}).get("max_fires_default")
            if isinstance(maximum,int) and self.kind_fires.get(key,0)>=maximum:raise Refusal(kind_name,f"kind {kind_name} cap exhausted")
            self.kind_fires[key]=self.kind_fires.get(key,0)+1
            if len(step.get("body","").splitlines())>E.get("caps",{}).get("body_lines",float("inf")):raise Refusal(kind_name,"body line cap exhausted")
            self.ctx.update(id=request_id,status="delivered",tick=self.index)
            self.messages[request_id]=copy.deepcopy(self.ctx)
        fired=self.transitions(event,kind_name,request_id)
        self.fired=fired
        if not fired and not operation and any(t.get("kind")==kind_name and t.get("on","message")==event for _,t in all_transitions):
            # The rule that refuses: the first candidate of the machines in their order, and of their subjects, as the engine
            # names it; the kind when none leaves the state.
            raise Refusal(self.first_candidate or kind_name,f"no arrow from {self.state('stage')} for {kind_name}")
        if not operation:self.history.append(copy.deepcopy(self.ctx))
        else:self.history.append({"operation":operation,"result":self.ctx["result"],"stage":self.stage,"tick":self.index})
        observed=self.robot_rules("op" if operation else "delivered",operation or kind_name)
        if not operation:
            observed={key:arrivals[key]+observed[key] for key in ("rules","sends","wakes")}
        self.check_expectation(step,{**observed,"transitions":fired,"held":False,"class":self.ctx.get("class")})
    def seat_states(self):
        """Every seat the walk names or a step moved, with its state — what a seat no step moved starts in, for those."""
        return {seat:self.seats[seat] if seat in self.seats else self.seat_default(seat) for seat in sorted(set(self.definition.get("seats",{}))|set(self.seats))}
    def let_go(self):
        # A message a robot holds is let go, in the order held, once that robot is no longer active, and judged then as on
        # arrival — its requirement, its cap, its arrows and what they request (contracts.md §8; the engine's release).
        here=self.stage
        def robot_of(by):return next(robot for _,robot in ROBOTS if any("robot:"+rule["id"]==by for rule in robot["rules"]))
        def active(by):
            self.robot=robot_of(by)
            try:return self.holds(self.robot["active_when"])
            finally:self.robot=None
        for request_id,held in sorted(((key,message) for key,message in self.messages.items() if message.get("status")=="held"),key=lambda item:item[1]["tick"]):
            if any(active(by) for by in held.get("held_by",[])):continue
            self.set_stage(held["stage"])
            self.ctx=copy.deepcopy(held);kind_name=held["kind"];kind=K[kind_name]
            window=kind.get("window","stage")
            key=(kind_name,self.stage if window=="stage" else (held["from"],"default") if window=="session" else (held["from"],window))
            maximum=kind.get("max_fires")
            if maximum is None:maximum=E.get("caps",{}).get("max_fires_default")
            if self.require(kind["require"]) is not None or (isinstance(maximum,int) and self.kind_fires.get(key,0)>=maximum):
                self.ctx.update(status="refused",tick=self.index)
                self.messages[request_id]=copy.deepcopy(self.ctx)
                continue
            self.kind_fires[key]=self.kind_fires.get(key,0)+1
            self.ctx.update(status="delivered",tick=self.index)
            self.messages[request_id]=copy.deepcopy(self.ctx)
            self.fired=self.fired+self.transitions("message",kind_name,request_id)
            self.history.append(copy.deepcopy(self.ctx))
            self.robot_rules("delivered",kind_name)
        self.set_stage(here)
    def step(self,step):
        expected=step.get("expect",{})
        if not expected.get("refused"):
            return self.execute_step(step)
        snapshot=copy.deepcopy(self.__dict__)
        attempt=copy.deepcopy(step)
        attempt["expect"]={key:value for key,value in expected.items() if key not in ("refused","refusal_contains","refused_by")}
        try:
            self.execute_step(attempt)
        except Refusal as refusal:
            text=str(refusal)
            if expected.get("refusal_contains","") not in text:
                raise WalkError(f"wrong refusal: {text}")
            # A refused step names the rule that refuses it, and another rule refusing it is a failure (S2.8; the retro of P1).
            if expected.get("refused_by")!=refusal.rule:
                raise WalkError(f"refused by {refusal.rule}, the walk names {expected.get('refused_by') or 'no rule'}")
            self.__dict__.clear();self.__dict__.update(snapshot)
            self.current["facts"].update(step.get("facts",{}))
            return
        raise WalkError("expected refusal, but the message was delivered")
    def run(self):
        """Plays the walk; a walk whose steps mark events as unordered, in every order of them (S3.6 T3). Each order ends with
        its end — everything the walk keeps but what [LEFT_OUT] names ([ended]) — and the walk fails, naming two orders
        and their ends, unless every order ends alike. `--trace` prints `LEAVES` with what an end leaves out, `ORDER` as
        an order of a step's events begins and `END` with each order's end."""
        if TRACE:
            print("WALK "+json.dumps(self.definition,sort_keys=True))
            print("LEAVES "+json.dumps({"walk":self.LEFT_OUT,"stage":self.LEFT_OUT_OF_A_STAGE,"record":self.LEFT_OUT_OF_A_RECORD},sort_keys=True))
        ends=[]
        self.play(0,[],ends)
        first=ends[0]
        other=next((each for each in ends if each[1]!=first[1]),None)
        if other is not None:
            fail(f"walk {self.name}: its orders end apart — in the order {self.shown(first[0])} it ends {json.dumps(first[1],sort_keys=True)}; "
                 f"in the order {self.shown(other[0])} it ends {json.dumps(other[1],sort_keys=True)}")
            return
        passed,text=first[2]
        (ok if passed else fail)(text)
    def play(self,start,order,ends):
        """The steps from [start] to the walk's end, in [order]; an unordered step plays each order of its events, and the
        steps after it in each. Each end reached goes to [ends], with the order and the walk's verdict on it."""
        steps=self.definition["steps"]
        for at in range(start,len(steps)):
            self.index=at+1;step=steps[at]
            if "unordered" in step:
                self.unordered(at,step,order,ends)
                return
            self.fired=[]
            try:
                self.step(step)
                self.let_go()
            except WalkError as error:
                ends.append(self.ended(order,f"step {self.index}: {error}"))
                return
            self.trace(step)
        ends.append(self.ended(order,None))
    def unordered(self,at,step,order,ends):
        """An unordered step (T3): its events in every order, each order played from the state before the step to the walk's end."""
        events=step["unordered"]
        group=step.get("id",f"step-{at+1}")
        if not isinstance(events,list) or len(events)<2 or any(not isinstance(event,dict) or "unordered" in event or "expect" in event for event in events):
            ends.append(self.ended(order,f"step {at+1}: an unordered step holds two events or more, none of them unordered itself and none with an expect — what they do is read at the walk's end"))
            return
        labels=[str(event.get("id",f"{group}.{number}")) for number,event in enumerate(events,1)]
        snapshot=copy.deepcopy(self.__dict__)
        for permutation in itertools.permutations(range(len(events))):
            self.__dict__.clear();self.__dict__.update(copy.deepcopy(snapshot))
            names=[labels[number] for number in permutation]
            if TRACE:print("ORDER "+json.dumps({"step":at+1,"events":names}))
            self.group_sent={}
            error=None
            for number in permutation:
                self.index=at+1
                try:
                    self.happen({**events[number],"id":labels[number]},group)
                    self.let_go()
                except WalkError as failure:
                    error=f"step {at+1} ({labels[number]}): {failure}"
                    break
                self.trace(events[number],labels[number])
            if error is not None:ends.append(self.ended(order+[[at+1,names]],error))
            else:self.play(at+1,order+[[at+1,names]],ends)
    def happen(self,event,group):
        """One unordered event, then what it made the robots and the mailroom send, each judged as it is sent — and what
        that sends in turn. A message refused here is an outcome of the order, counted at its end, not a failure."""
        start=len(self.emissions);fired=[]
        self.in_group=True
        try:
            self.fired=[]
            try:self.execute_step(event)
            except Refusal:self.history.append({"kind":event.get("kind"),"status":"refused","stage":self.stage,"tick":self.index})
            fired+=self.fired
            while len(self.emissions)>start:
                sent=self.emissions[start]
                count=self.group_sent[sent["kind"]]=self.group_sent.get(sent["kind"],0)+1
                message={"id":f"{group}/{sent['kind']}"+(f"/{count}" if count>1 else ""),"who":sent["from"],"kind":sent["kind"],"args":sent["args"],"to":sent["to"]}
                if str(sent.get("by","")).startswith("robot:"):message["by"]=sent["by"]
                else:self.emissions.pop(start)
                self.fired=[]
                try:self.execute_step(message)
                except Refusal:self.history.append({"kind":sent["kind"],"status":"refused","stage":self.stage,"tick":self.index})
                if len(self.emissions)>start and self.emissions[start] is sent:self.emissions.pop(start)
                fired+=self.fired
        finally:
            self.in_group=False
        self.fired=fired
    def trace(self,step,event=None):
        if TRACE:print("TRACE "+json.dumps({"step":self.index,**({"event":event} if event is not None else {}),"refused":bool(step.get("expect",{}).get("refused")),"fired":self.fired,
            "stages":{stage:context["states"]["stage"] for stage,context in self.contexts.items()},"seats":self.seat_states()},sort_keys=True))
    # What an order's end leaves out of what the walk keeps, each with its reason (architecture.md §8; review-027 B2).
    # `ended` holds the rest, and refuses to end a walk that keeps anything it neither holds nor names here.
    LEFT_OUT={
        "definition":"the walk as written: the same in every order",
        "name":"the walk's name: the same in every order",
        "index":"the tick, a step's place in the walk: the same in every order, as an unordered step's events all take the step's own",
        "stage":"the stage of the last step or event played, which a step that names none is played in: where the walk's text has got to",
        "current":"the stage being played's own part of `contexts`, which the end holds with the rest of it",
        "ctx":"the message read while one step is played",
        "robot":"the robot read while one step is played",
        "subject":"the seat a seat transition is read for while it is read",
        "fired":"the rules the last step fired, which `TRACE` prints after each step; what they did is held",
        "first_candidate":"the first candidate of the last transitions read, which names a refusal",
        "in_group":"whether an unordered step's events are being played: never, at an end",
        "group_sent":"how many of each kind the robots and the mailroom sent in the last unordered step, which numbers those messages' ids",
        "first_moved":"the order the seats were first moved, in which a seat transition that names no seat moves them: the order played, "
                      "which the orders vary on purpose; where each seat ends, and the stage it is bound to, are held",
        "used_operations":"the operations each request asked for, by its id: `operations` holds each, named by the kind and seat of the message that asked",
        "thread_hops":"never written: the walker counts no thread's hops",
        "history's order":"the order the walk kept its messages, results and wakes in: the order played, which the orders vary on "
                          "purpose; `log` holds them as a set, though what a later step reads as the last of a kind "
                          "(`log.count … since:`, `review.open_blockers`) follows that order",
    }
    # Of a stage's machines: the two the walk keeps elsewhere.
    LEFT_OUT_OF_A_STAGE={
        "seat":"its entry is set once from the walk and never read: `seats` holds each seat's state",
        "found_item":"its entry is set once from the walk and never read: `found` holds each Found line's state",
    }
    # Of a message, refusal, operation's result or wake the walk keeps. Every record the end holds is held through one filter,
    # which leaves out these fields and no other: the end rebuilds no record field by field (review-027 B2, round 2).
    LEFT_OUT_OF_A_RECORD={
        "id":"a message's request id: what the robots and the mailroom send in an unordered step is numbered in the order sent",
        "re":"a copy of the message a reply answers, which `reply_id` names",
        "by":"which robot rule sent a message — the robot is `from`: two rules may send one message for one cause, as POOL-1 and "
             "POOL-12 each send a seat's one seat-gone when its first end report comes (D-85; the walk a-killed-seats-ends); "
             "no rule reads it, though a requirement `msg: by` could",
        "rule":"which robot rule woke a seat, as `by` — the robot is the wake's `role`, which is held",
    }
    HELD={"contexts","shared","shared_counters","seats","bound","history","messages","pending","requested","kind_fires","emissions"}
    def ended(self,order,error):
        """An order's end, printed as `END` with the order, and the walk's verdict on it: (passed, the line to print). The
        end holds everything the walk keeps but what LEFT_OUT and the two lists after it name, each with its reason
        (review-027 B1, B2): of each stage, its state (`stages`), the state it returns to from a pause (`returns`), its
        flag thread (`threads`), Found lines (`found`), other machines (`machines`), facts (`facts`) and counters
        (`counters`, with the effort's); each seat's state (`seats`) and the stage it is bound to (`bound`); Bora's
        presence and the phase; how often each kind fired in its window (`fires`); every message, refusal, operation's
        result and robot's wake (an op `seat.wake`, contracts.md §4.4) the walk keeps (`log`), and each message's present
        status (`messages`); what a robot or the mailroom sent that no step delivered (`unsent`); the operations a
        transition's `do:` requested (`operations`), and those still waiting, each with the message that asked it
        (`waiting`). A record is held whole but for the fields LEFT_OUT_OF_A_RECORD names."""
        unnamed=sorted(set(self.__dict__)-self.HELD-set(self.LEFT_OUT))
        unnamed+=sorted({f"contexts.{key}" for context in self.contexts.values() for key in context if key not in ("states","previous","found_items","counters","facts")})
        unnamed+=sorted(f"shared.{key}" for key in self.shared if key not in ("presence","phase"))
        if unnamed:raise RuntimeError(f"walk {self.name}: its end neither holds nor leaves out {', '.join(unnamed)}")
        counters={stage:{name:value for name,value in sorted(context["counters"].items()) if value} for stage,context in self.contexts.items()}
        counters={stage:values for stage,values in counters.items() if values}
        if any(self.shared_counters.values()):counters["effort"]={name:value for name,value in sorted(self.shared_counters.items()) if value}
        def described(record):
            return json.dumps({key:value for key,value in record.items() if key not in self.LEFT_OUT_OF_A_RECORD},sort_keys=True)
        def asked(request_id,operation):
            # An event's request has no message: its id is its tick, the same in every order.
            message=self.messages.get(request_id)
            return json.dumps({"operation":operation,"asked by":described(message) if message is not None else request_id},sort_keys=True)
        others={stage:{machine:state for machine,state in sorted(context["states"].items()) if machine not in ("stage","flag_thread",*self.LEFT_OUT_OF_A_STAGE)}
                for stage,context in self.contexts.items()}
        end={"stages":{stage:context["states"]["stage"] for stage,context in self.contexts.items()},
             "returns":{stage:context["previous"] for stage,context in self.contexts.items()},
             "seats":self.seat_states(),"bound":dict(sorted(self.bound.items())),
             "threads":{stage:context["states"]["flag_thread"] for stage,context in self.contexts.items()},
             "found":{stage:dict(sorted(context["found_items"].items())) for stage,context in self.contexts.items() if context["found_items"]},
             **({"machines":{stage:states for stage,states in others.items() if states}} if any(others.values()) else {}),
             "facts":{stage:context["facts"] for stage,context in self.contexts.items()},
             "presence":self.shared["presence"],"phase":self.shared["phase"],"counters":counters,
             "fires":{json.dumps(key):count for key,count in sorted(self.kind_fires.items(),key=lambda item:json.dumps(item[0]))},
             "log":sorted(described(record) for record in self.history),
             "messages":sorted(described(message) for message in self.messages.values()),
             "unsent":sorted(described(sent) for sent in self.emissions),
             "operations":sorted(self.requested),
             "waiting":sorted(asked(request_id,operation) for request_id,operations in self.pending.items() for operation in operations)}
        if error is not None:end["error"]=error
        if TRACE:print("END "+json.dumps({"orders":order,"end":end},sort_keys=True))
        return order,end,((False,f"walk {self.name} {error}") if error is not None else self.verdict())
    def verdict(self):
        expected=self.definition["final"]
        actual={stage:context["states"]["stage"] for stage,context in self.contexts.items()} if isinstance(expected,dict) else self.state("stage")
        if actual!=expected:return False,f"walk {self.name}: expected final {expected!r}, observed {actual!r}"
        if any(self.pending.values()):return False,f"walk {self.name}: operation results are missing"
        for kind,expected_count in self.definition.get("expected_counts",{}).items():
            actual_count=sum(record.get("kind")==kind and record.get("status")=="delivered" for record in self.history)
            if actual_count!=expected_count:return False,f"walk {self.name}: expected {expected_count} {kind} deliveries, observed {actual_count}"
        return True,f"walk {self.name} reached {expected}"
    @staticmethod
    def shown(order):
        return ", ".join(label for _,labels in order for label in labels) or "of its steps"

# The rules data reads clean (issues #5, #6): no counter that nothing reads, no anchor, the comments a round trip loses.
def counter_reads(value):
    if isinstance(value, dict):
        if isinstance(value.get("counter"), str):
            yield value["counter"]
        for item in value.values():
            yield from counter_reads(item)
    elif isinstance(value, list):
        for item in value:
            yield from counter_reads(item)
read_counters = set(counter_reads([L, K, E] + [robot for _, robot in ROBOTS]))

# Either reader, given the scalar plain: PyYAML with the rules' YAML 1.2 booleans, or the Kotlin loader's resolver.
LOADER_SCALARS = re.compile(r"-?(0|[1-9][0-9]*)(\.[0-9]+([eE][-+]?[0-9]+)?)?|(?i:true|false)|~|null|Null|NULL")
def reads_as_number_boolean_or_null(value):
    if value == "": return False  # '' is text to both readers
    tag = RulesLoader("").resolve(yaml.ScalarNode, value, (True, False))
    return tag in ("tag:yaml.org,2002:int", "tag:yaml.org,2002:float", "tag:yaml.org,2002:bool", "tag:yaml.org,2002:null") or LOADER_SCALARS.fullmatch(value) is not None
for name in L.get("counters", {}):
    if name not in read_counters: fail(f"counter '{name}' is declared but nothing reads it")
if not FIXTURE:
    RULE_FILES = [ROOT / "rules" / name for name in ("stage-lifecycle.yaml", "message-kinds.yaml", "engagement.yaml", "vocabulary.yaml")]
    RULE_FILES += sorted((ROOT / "robots").glob("*.yaml")) + sorted((ROOT / "pool").glob("*.yaml"))
    for path in RULE_FILES:
        text = path.read_text()
        label = path.relative_to(ROOT)
        if any(isinstance(event, yaml.AliasEvent) or getattr(event, "anchor", None) for event in yaml.parse(text, Loader=RulesLoader)):
            fail(f"{label}: a YAML anchor or alias — every rule is written out")
        if not text.startswith("#"):
            fail(f"{label}: the header comment is gone")
        for event in yaml.parse(text, Loader=RulesLoader):
            if isinstance(event, yaml.ScalarEvent) and event.style in ("'", '"') and reads_as_number_boolean_or_null(event.value):
                fail(f"{label}: the quoted scalar '{event.value}' (line {event.start_mark.line + 1}) would read as a number, a boolean or null — the loader resolves a scalar by its text, not its quotes")
    # What S0.1's round trip lost and S1.0 put back: a YAML dump drops comments, so edit these files as text.
    KEPT = {"rules/stage-lifecycle.yaml": ["# Guards: `observed` = the daemon checks the world"],
            "rules/message-kinds.yaml": ["# Fields: from/to (roles;", "  # ---- the start gate"],
            "rules/engagement.yaml": ["# Default deny:"] + [f"  {cap}: " for cap in ("refusals_per_seat_per_day", "fix_rounds_autonomous",
                "resumes_per_stage", "seat_gone_events_per_stage", "proposals_per_stage", "tail_retries", "qa_requests_per_stage")]}
    for name, kept in KEPT.items():
        lines = (ROOT / name).read_text().splitlines()
        for start in kept:
            if not any(line.startswith(start) and "#" in line for line in lines):
                fail(f"{name}: the comment on '{start.strip()}' is gone — edit the rules as text, never by a YAML round trip")
if not fails:
    ok("every counter is read; no anchor or alias; no quoted number, boolean or null; the legends and the caps' sources are comments")

if not FIXTURE:
    W=[load_yaml(path.read_text()) for path in sorted((ROOT / "rules/walks").glob("*.yaml"))]
    # A walk file named by its path — a spec's fixture (S2.8) — is the one walk run.
    if TRACE is not None and TRACE.endswith(".yaml") and pathlib.Path(TRACE).is_file():
        W=[load_yaml(pathlib.Path(TRACE).read_text())]
        TRACE=W[0]["name"]
for walk in W:
    if TRACE is None or walk["name"]==TRACE:Walk(walk).run()
if W and not fails:ok(f"the walks pass ({len(W)} walks)")

# 8. pool/: the office's three files (contracts.md §9) and every pool value the rules use
POOL = pathlib.Path(sys.argv[sys.argv.index("--pool") + 1]) if "--pool" in sys.argv else ROOT / "pool"
COMPUTER_USE = ("A", "B", "C", "off")
ROOTS = ("worktrees", "scratch", "seats", "secrets")
SIZES = ("S", "M", "L", "XL")
LISTENER = re.compile(r"^127\.0\.0\.1:([1-9][0-9]{0,4})$")

# A pool file that is missing or not version 1 is one line; nothing is checked in it, or against it (issue #8).
def pool_file(name):
    path = POOL / name
    if not path.is_file():
        fail(f"pool/{name}: missing")
        return None
    data = load_yaml(path.read_text())
    if not isinstance(data, dict) or data.get("version") != 1:
        fail(f"pool/{name}: version must be 1")
        return None
    return data

def positive(value):
    return isinstance(value, int) and not isinstance(value, bool) and value > 0

def text(value):
    return isinstance(value, str) and value.strip() != ""

def check_roster(roster):
    if roster is None:
        return None
    label = "pool/roster.yaml"
    vendors = roster.get("vendors") if isinstance(roster.get("vendors"), dict) else {}
    if not vendors: fail(f"{label}: vendors must name at least one vendor")
    # The SDK's pin lives in the version catalog only (contracts.md §9, Amendment 10): a vendor names its adapter and its pin.
    for name, vendor in vendors.items():
        vendor = vendor if isinstance(vendor, dict) else {}
        if not text(vendor.get("adapter")): fail(f"{label}: vendor '{name}' has no adapter")
        if vendor.get("adapter_version") is not None and not text(vendor.get("adapter_version")): fail(f"{label}: vendor '{name}' adapter_version must be a version or null")
        for key in vendor:
            if key not in ("adapter", "adapter_version"): fail(f"{label}: unknown field '{key}'")
    order = roster.get("order") if isinstance(roster.get("order"), list) else []
    if not order: fail(f"{label}: order must list (model, effort) pairs")
    pairs, models = [], []
    for index, line in enumerate(order):
        if not isinstance(line, dict) or set(line) != {"vendor", "model", "effort"} or not all(text(line[key]) for key in line):
            fail(f"{label}: order line {index} must be {{vendor, model, effort}}")
            continue
        if line["vendor"] not in vendors: fail(f"{label}: order line {index} names unknown vendor '{line['vendor']}'")
        pair = (line["vendor"], line["model"], line["effort"])
        if pair in pairs: fail(f"{label}: order line {index} repeats ({line['model']}, {line['effort']})")
        pairs.append(pair)
        if line["model"] in models and models[-1] != line["model"]: fail(f"{label}: order line {index}: model before effort — {line['model']}'s pairs must be adjacent")
        if not models or models[-1] != line["model"]: models.append(line["model"])
    levels = roster.get("levels") if isinstance(roster.get("levels"), dict) else {}
    for skill in ("junior", "senior"):
        if skill not in levels: fail(f"{label}: levels lack '{skill}'")
    for skill, sizes in levels.items():
        sizes = sizes if isinstance(sizes, dict) else {}
        if set(sizes) != set(SIZES): fail(f"{label}: level {skill} must map exactly {', '.join(SIZES)}")
        for size, line in sizes.items():
            if not isinstance(line, dict) or (line.get("vendor"), line.get("model"), line.get("effort")) not in pairs or len(line) != 3:
                fail(f"{label}: level {skill} {size} is not a line of the order")
    floor = (roster.get("review") or {}).get("floor") if isinstance(roster.get("review"), dict) else None
    if floor not in models: fail(f"{label}: review floor '{floor}' is not a model of the order")
    # A reserved model is in the order like any other; no level points at it, and a role runs it only through
    # role_models — and only a role it is reserved for (contracts.md §9, D-53).
    reserved = roster.get("reserved") if isinstance(roster.get("reserved"), dict) else {}
    if "reserved" in roster and not isinstance(roster.get("reserved"), dict): fail(f"{label}: reserved must be a map")
    roles_known = set(E.get("roles", {}))
    for model, allowed in reserved.items():
        if model not in models: fail(f"{label}: reserved model '{model}' is not a model of the order")
        if not isinstance(allowed, list) or not all(text(role) for role in allowed):
            fail(f"{label}: reserved {model} must be a list of roles")
            continue
        for role in allowed:
            if role not in roles_known: fail(f"{label}: reserved model '{model}' names unknown role '{role}'")
    for skill, sizes in levels.items():
        for size, line in (sizes if isinstance(sizes, dict) else {}).items():
            if isinstance(line, dict) and line.get("model") in reserved: fail(f"{label}: level {skill} {size} points at {line['model']}, which is reserved")
    role_models = roster.get("role_models") if isinstance(roster.get("role_models"), dict) else {}
    if "role_models" in roster and not isinstance(roster.get("role_models"), dict): fail(f"{label}: role_models must be a map")
    for role, line in role_models.items():
        if not isinstance(line, dict) or set(line) != {"model", "effort"} or not all(text(line[key]) for key in line):
            fail(f"{label}: role_models {role} must be {{model, effort}}")
            continue
        if not any(pair[1] == line["model"] and pair[2] == line["effort"] for pair in pairs): fail(f"{label}: role_models {role} is not a line of the order")
        allowed = reserved.get(line["model"])
        if isinstance(allowed, list) and role not in allowed: fail(f"{label}: role_models {role} runs {line['model']}, which is reserved for {', '.join(allowed)}")
        if role not in roles_known: fail(f"{label}: role_models names unknown role '{role}'")
    kept = "; ".join(model + " for " + ", ".join(map(str, roles)) for model, roles in reserved.items() if isinstance(roles, list)) or "none"
    ok(f"{label}: {len(pairs)} (model, effort) pairs of {len(models)} models; {len(levels)} levels × {len(SIZES)} sizes on the order; review floor {floor}; reserved {kept}")
    return vendors

def relative_folder(value):
    """A folder of the project checkout, written relative to it: not absolute, not from home, never above it."""
    return text(value) and not value.startswith(("/", "~")) and ".." not in value.split("/")

def check_offices(offices, vendors):
    if offices is None:
        return None
    label = "pool/offices.yaml"
    entries = offices.get("offices") if isinstance(offices.get("offices"), dict) else {}
    if not entries: fail(f"{label}: offices must name at least one office")
    for name, office in entries.items():
        if not re.fullmatch(r"[a-z][a-z0-9]*", str(name)) or name == "hand": fail(f"{label}: '{name}' is not an office name (lower-case; 'hand' is reserved)")
        office = office if isinstance(office, dict) else {}
        for key in ("os", "user"):
            if not text(office.get(key)): fail(f"{label}: {name}: {key} missing")
        roots = office.get("roots") if isinstance(office.get("roots"), dict) else {}
        for root in ROOTS:
            if not text(roots.get(root)): fail(f"{label}: {name}: roots lack '{root}'")
        for root in sorted(set(roots) - set(ROOTS)): fail(f"{label}: {name}: unknown root '{root}'")
        project = office.get("project") if isinstance(office.get("project"), dict) else {}
        efforts = project.get("efforts") if isinstance(project.get("efforts"), dict) else {}
        if not text(project.get("checkout")) or not efforts: fail(f"{label}: {name}: project must name a checkout and a folder per effort")
        for key in sorted(set(project) - {"checkout", "efforts"}): fail(f"{label}: {name}: unknown field 'project.{key}'")
        for effort, folder in efforts.items():
            if not re.fullmatch(r"[A-Z][A-Z0-9-]*", str(effort)) or not relative_folder(folder):
                fail(f"{label}: {name}: project effort '{effort}' must be an effort id with a folder inside the checkout")
        repos = office.get("repos") if isinstance(office.get("repos"), dict) else {}
        if not repos or not all(text(path) for path in repos.values()): fail(f"{label}: {name}: repos must map repo names to checkouts")
        targets = office.get("live_targets")
        if not isinstance(targets, list) or not all(text(target) for target in targets): fail(f"{label}: {name}: live_targets must be a list of names")
        if office.get("computer_use") not in COMPUTER_USE: fail(f"{label}: {name}: unknown computer_use class '{office.get('computer_use')}'")
        qa = office.get("qa") if isinstance(office.get("qa"), dict) else {}
        if vendors is not None and qa.get("vendor") not in vendors: fail(f"{label}: {name}: qa vendor '{qa.get('vendor')}' is not a roster vendor")
        listen = office.get("listen") if isinstance(office.get("listen"), dict) else {}
        ports = []
        for listener in ("mcp", "board"):
            match = LISTENER.fullmatch(str(listen.get(listener, "")))
            if not match or int(match.group(1)) > 65535: fail(f"{label}: {name}: listen.{listener} must be 127.0.0.1:<port>")
            else: ports.append(match.group(1))
        if len(ports) == 2 and ports[0] == ports[1]: fail(f"{label}: {name}: mcp and board listen on one port")
        # How often the daemon reports its seats' facts (contracts.md §9, Amendment 22): a positive duration.
        watch = office.get("watch") if isinstance(office.get("watch"), dict) else {}
        every = comparable(watch.get("every")) if isinstance(watch.get("every"), str) else None
        if not isinstance(every, float) or every <= 0: fail(f"{label}: {name}: watch.every must be a duration such as 30s")
        for key in sorted(set(watch) - {"every"}): fail(f"{label}: {name}: unknown field 'watch.{key}'")
    ok(f"{label}: {len(entries)} office(s) ({', '.join(entries)}) with roots, computer use, listeners and the watch period checked")
    return entries

def check_budgets(budgets, vendors, entries):
    if budgets is None:
        return
    label = "pool/budgets.yaml"
    slots = budgets.get("slots") if isinstance(budgets.get("slots"), dict) else {}
    for key in ("total", "stages_in_flight_per_effort"):
        if not positive(slots.get(key)): fail(f"{label}: slots.{key} must be a positive integer")
    for key, known, noun in (("per_subscription", vendors, "subscription"), ("per_office", entries, "office")):
        limits = slots.get(key) if isinstance(slots.get(key), dict) else {}
        if not limits: fail(f"{label}: slots.{key} must name at least one {noun}")
        for name, limit in limits.items():
            if known is not None and name not in known: fail(f"{label}: {key} names unknown {noun} '{name}'")
            if not positive(limit): fail(f"{label}: {key}.{name} must be a positive integer")
    warn = budgets.get("warn")
    if not isinstance(warn, list) or not warn or not all(isinstance(value, (int, float)) and not isinstance(value, bool) and 0 < value < 1 for value in warn) or warn != sorted(set(warn)):
        fail(f"{label}: warn must be ascending fractions between 0 and 1")
    if budgets.get("on_cap") != "pause_provisioning": fail(f"{label}: on_cap must be pause_provisioning (a running seat is never stopped)")
    ok(f"{label}: slot limits name known subscriptions and offices; warnings at {warn}")

def pool_values(value, found):
    if isinstance(value, dict):
        if len(value) == 1 and next(iter(value)) in ("roster", "office", "budget"):
            found.append(value)
        for item in value.values(): pool_values(item, found)
    elif isinstance(value, list):
        for item in value: pool_values(item, found)
    return found

if not FIXTURE:
    roster_data, offices_data, budgets_data = pool_file("roster.yaml"), pool_file("offices.yaml"), pool_file("budgets.yaml")
    pool_vendors = check_roster(roster_data)
    pool_offices = check_offices(offices_data, pool_vendors)
    check_budgets(budgets_data, pool_vendors, pool_offices)
    used = pool_values([L, K, E] + [robot for _, robot in ROBOTS], [])
    for value in used:
        namespace, path = next(iter(value.items()))
        sources = {"roster": roster_data and [roster_data], "budget": budgets_data and [budgets_data], "office": pool_offices and list(pool_offices.values())}[namespace]
        if sources is None:
            continue
        if not sources or any(path_value(source, path) is MISSING for source in sources):
            fail(f"pool: {{{namespace}: {path}}} used by the rules does not resolve")
    ok(f"pool values the rules use resolve: {len(used)} ({', '.join('{' + next(iter(v)) + ': ' + str(next(iter(v.values()))) + '}' for v in used) or 'none'})")

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
