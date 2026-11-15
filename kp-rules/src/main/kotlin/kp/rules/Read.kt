package kp.rules

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** One file being typed: every problem found in it names the file and, where there is one, the rule's id. */
internal class Doc(val file: String, private val report: Report) {
    fun problem(id: String?, message: String) = report.add(file, id, message)

    fun mapping(node: Node?, id: String?, what: String): Node.Mapping? = when (node) {
        is Node.Mapping -> node
        null -> null
        else -> null.also { problem(id, "$what must be a map") }
    }

    fun sequence(node: Node?, id: String?, what: String): List<Node>? = when (node) {
        is Node.Sequence -> node.items
        null -> null
        else -> null.also { problem(id, "$what must be a list") }
    }

    fun text(node: Node?, id: String?, what: String): String? = when {
        node == null -> null
        node is Node.Scalar && node.literal is Literal.Text -> (node.literal as Literal.Text).value
        else -> null.also { problem(id, "$what must be text") }
    }

    fun texts(node: Node?, id: String?, what: String): List<String>? =
        sequence(node, id, what)?.mapNotNull { text(it, id, "$what's item") }

    fun textMap(node: Node?, id: String?, what: String): Map<String, String>? =
        mapping(node, id, what)?.entries?.mapNotNull { (key, value) -> text(value, id, "$what.$key")?.let { key to it } }?.toMap()

    fun bool(node: Node?, id: String?, what: String): Boolean? = when {
        node == null -> null
        node is Node.Scalar && node.literal is Literal.Bool -> (node.literal as Literal.Bool).value
        else -> null.also { problem(id, "$what must be true or false") }
    }

    fun whole(node: Node?, id: String?, what: String): Int? {
        val number = ((node as? Node.Scalar)?.literal as? Literal.Number)?.value
        return when {
            node == null -> null
            number != null && number.signum() >= 0 && number.stripTrailingZeros().scale() <= 0 -> number.toInt()
            else -> null.also { problem(id, "$what must be a whole number") }
        }
    }

    fun literal(node: Node?, id: String?, what: String): Literal? = when (node) {
        null -> null
        is Node.Scalar -> node.literal
        else -> null.also { problem(id, "$what must be a single value") }
    }

    fun duration(node: Node?, id: String?, what: String): Duration? {
        val text = (node as? Node.Scalar)?.text
        return when {
            node == null || (node as? Node.Scalar)?.literal == Literal.Null -> null
            text != null && parseDuration(text) != null -> parseDuration(text)
            else -> null.also { problem(id, "$what must be a duration such as 15m, 4h or 1d") }
        }
    }

    /** Version 1 is the only one there is. */
    fun version(fields: Fields) {
        val version = fields["version"]
        if (version == null || (version as? Node.Scalar)?.literal != Literal.Number(java.math.BigDecimal.ONE)) problem(null, "version must be 1")
    }

    fun fields(node: Node.Mapping, id: String?) = Fields(node, this, id)
}

private val durationForm = Regex("([0-9]+(?:\\.[0-9]+)?)(s|m|h|d)")

internal fun parseDuration(text: String): Duration? = durationForm.matchEntire(text)?.let { match ->
    val amount = match.groupValues[1].toDouble()
    when (match.groupValues[2]) {
        "s" -> amount.seconds
        "m" -> amount.minutes
        "h" -> amount.hours
        else -> amount.days
    }
}

/** Strict access to one map: [close] reports every key that no reader asked for. */
internal class Fields(val node: Node.Mapping, private val doc: Doc, private val id: String?) {
    private val asked = mutableSetOf<String>()
    operator fun get(key: String): Node? {
        asked += key
        return node.entries[key]
    }
    fun has(key: String) = key in node.entries
    val keys: Set<String> get() = node.entries.keys
    fun close() {
        for (key in node.entries.keys - asked) doc.problem(id, "unknown field '$key'")
    }
}

internal val requirementForms = listOf("fact", "arg", "msg", "re", "counter", "attest", "signed", "op", "holds", "any", "not", "always")
internal val comparisons = Operator.entries.associateBy { it.key }
private val references = setOf("cap", "arg", "msg", "re", "roster", "office", "budget", "engagement", "robot", "trigger")

/** The forms of contracts.md §4.1 — requirements, values, effects — read for one rule (its [id]) of one file. */
internal class Forms(private val doc: Doc, private val id: String?) {
    private fun problem(message: String) = doc.problem(id, message)

    /** A list of requirements; a literal number anywhere inside it is a problem (contracts.md §4). */
    fun requirements(node: Node?): List<Requirement> {
        val items = when (node) {
            is Node.Sequence -> node.items
            else -> return emptyList<Requirement>().also { problem("require must be a list") }
        }
        if (items.any(::holdsNumber)) problem("literal number inside a requirement")
        return items.mapNotNull(::requirement)
    }

    /** One requirement standing alone (an effect's `if:`, a class case, `active_when`, `next_when`). */
    fun single(node: Node): Requirement? {
        if (holdsNumber(node)) problem("literal number inside a requirement")
        return requirement(node)
    }

    private fun requirement(node: Node): Requirement? {
        val mapping = node as? Node.Mapping
        val forms = mapping?.entries?.keys?.filter { it in requirementForms }.orEmpty()
        if (mapping == null || forms.size != 1) return null.also { problem("unknown requirement form") }
        val form = forms.single()
        val fields = Fields(mapping, doc, id)
        val target = fields[form]!!
        return when (form) {
            "any" -> (target as? Node.Sequence)?.items?.mapNotNull(::requirement)?.let { Requirement.Any(it) }
                .also { if (target !is Node.Sequence) problem("any must contain requirements") }
            "not" -> {
                if (target is Node.Mapping && "not" in target.entries) problem("a requirement negates a negation")
                requirement(target)?.let { Requirement.Not(it) }
            }
            "always" -> ((target as? Node.Scalar)?.literal as? Literal.Bool)?.let { Requirement.Always(it.value) }
                .also { if (it == null) problem("always must be boolean") }
            else -> {
                val name = (target as? Node.Scalar)?.text ?: return null.also { problem("requirement name must be a string") }
                named(form, name, fields)
            }
        }.also { fields.close() }
    }

    private fun named(form: String, name: String, fields: Fields): Requirement? {
        val comparison = if (form in setOf("attest", "signed", "op", "holds")) null else comparison(fields)
        return when (form) {
            "fact" -> Requirement.Fact(call(fields, name), comparison)
            "arg" -> Requirement.Arg(name, comparison)
            "counter" -> Requirement.Counter(name, comparison)
            "attest" -> Requirement.Attest(name)
            "holds" -> Requirement.Holds(name)
            "msg" -> MessageField.entries.firstOrNull { it.key == name }?.let { Requirement.Message(it, comparison) }
                .also { if (it == null) problem("unknown message field") }
            "re" -> ReplyField.entries.firstOrNull { it.key == name }?.let { Requirement.Reply(it, comparison) }
                .also { if (it == null) problem("unknown reply field") }
            "signed" -> if (name == "bora") Requirement.Signed(name) else null.also { problem("unknown signer") }
            else -> Outcome.entries.firstOrNull { it.key == (fields["eq"] as? Node.Scalar)?.text }?.let { Requirement.Operation(name, it) }
                .also { if (it == null) problem("unknown operation result") }
        }
    }

    private fun comparison(fields: Fields): Comparison? {
        val present = fields.keys.filter { it in comparisons }
        if (present.size > 1) problem("more than one comparison in one requirement")
        val operator = present.firstOrNull() ?: return null
        return value(fields[operator]!!)?.let { Comparison(comparisons.getValue(operator), it) }
    }

    /** `{fact: <name>, <params…>}`: every key but the name and a comparison is a parameter. */
    private fun call(fields: Fields, name: String): FactCall {
        val params = fields.keys.filter { it != "fact" && it !in comparisons }.associateWith { key ->
            value(fields[key]!!, fieldsAllowed = key == "where" || key == "since")
        }
        return FactCall(name, params.filterValues { it != null }.mapValues { it.value!! })
    }

    /** A value (contracts.md §4.1). A plain map is a value only where a form takes one ([fieldsAllowed]). */
    fun value(node: Node, fieldsAllowed: Boolean = false): Value? = when (node) {
        is Node.Scalar -> Value.Constant(node.literal)
        is Node.Sequence -> Value.Items(node.items.mapNotNull { value(it) })
        is Node.Mapping -> when {
            "fact" in node.entries -> {
                val fields = Fields(node, doc, id)
                (fields["fact"] as? Node.Scalar)?.text?.let { Value.Fact(call(fields, it)) }
                    .also { if (it == null) problem("requirement name must be a string") }
            }
            node.entries.size == 1 && node.entries.keys.single() in references -> reference(node.entries.keys.single(), node.entries.values.single())
            fieldsAllowed -> fields(node)
            else -> null.also { problem("unknown value form ${node.entries.keys.joinToString(", ", "{", "}")}") }
        }
    }

    fun fields(node: Node.Mapping): Value.Fields =
        Value.Fields(node.entries.mapNotNull { (key, item) -> value(item, fieldsAllowed = key == "since" || key == "where")?.let { key to it } }.toMap())

    private fun reference(key: String, target: Node): Value? {
        val name = (target as? Node.Scalar)?.text ?: return null.also { problem("{$key: …} must name a text") }
        return when (key) {
            "cap" -> Value.Cap(name)
            "arg" -> Value.Arg(name)
            "msg" -> MessageField.entries.firstOrNull { it.key == name }?.let { Value.Message(it) }.also { if (it == null) problem("unknown message field") }
            "re" -> ReplyField.entries.firstOrNull { it.key == name }?.let { Value.Reply(it) }.also { if (it == null) problem("unknown reply field") }
            "engagement" -> Value.Engagement(name)
            "robot" -> Value.Robot(name)
            "trigger" -> Value.Trigger(name)
            else -> Value.Pool(PoolFile.entries.single { it.key == key }, name)
        }
    }

    /** A transition's effect: `emit`, `do` or `count`, each with an optional `if:`. */
    fun effect(node: Node): Effect? = effectOf(node, setOf("emit", "do", "count"), "unknown effect")

    /** A kind's expiry effect: `emit`, `report`, `transition`, `do` or `wait`. */
    fun expiry(node: Node): Effect? = effectOf(node, setOf("emit", "report", "transition", "do", "wait"), "invalid expiry effect")

    private fun effectOf(node: Node, allowed: Set<String>, refusal: String): Effect? {
        val mapping = node as? Node.Mapping
        val kinds = mapping?.entries?.keys?.filter { it in setOf("emit", "do", "count", "report", "transition", "wait") }.orEmpty()
        if (mapping == null || kinds.size != 1 || kinds.single() !in allowed) return null.also { problem(refusal) }
        val fields = Fields(mapping, doc, id)
        val condition = fields["if"]?.let(::single)
        val name = doc.text(fields[kinds.single()], id, kinds.single()) ?: return null
        val effect = when (kinds.single()) {
            "emit" -> {
                val to = fields["to"]?.let { value(it) }
                val args = arguments(fields["args"])
                if (!fields.has("to")) problem("an emit names its destination")
                to?.let { Effect.Emit(name, it, args, condition) }
            }
            "do" -> Effect.Do(name, arguments(fields["with"]), condition)
            "count" -> Effect.Count(name, condition)
            "report" -> MessageClass.entries.firstOrNull { it.name == (fields["class"] as? Node.Scalar)?.text }
                ?.let { Effect.Report(name, it, condition) }.also { if (it == null) problem("a report names its class: A, B or C") }
            "wait" -> if (name == "again") Effect.Wait(condition) else null.also { problem("wait must be again") }
            else -> Effect.Transition(name, condition)
        }
        fields.close()
        return effect
    }

    /** An effect's `with:` or `args:`, or a send's `args:`: named values. */
    fun arguments(node: Node?): Map<String, Value> = when (node) {
        null -> emptyMap()
        is Node.Mapping -> node.entries.mapNotNull { (key, item) -> value(item, fieldsAllowed = true)?.let { key to it } }.toMap()
        else -> emptyMap<String, Value>().also { problem("arguments must be a map") }
    }

    private fun holdsNumber(node: Node): Boolean = when (node) {
        is Node.Scalar -> (node.literal as? Literal.Number)?.value?.signum()?.let { it != 0 } ?: false
        is Node.Sequence -> node.items.any(::holdsNumber)
        is Node.Mapping -> node.entries.values.any(::holdsNumber)
    }
}

// rules/stage-lifecycle.yaml

private val companionMachines = listOf("found_item", "flag_thread", "seat", "phase", "presence")

internal fun readLifecycle(doc: Doc, node: Node): Lifecycle? {
    val fields = doc.fields(doc.mapping(node, null, "the lifecycle") ?: return null, null)
    doc.version(fields)
    val states = states(doc, fields["states"], "stage") ?: return null.also { if (!fields.has("states")) doc.problem(null, "missing states") }
    val stage = Machine("stage", states, transitions(doc, fields["transitions"]))
    val companions = companionMachines.filter { fields.has(it) }.mapNotNull { name ->
        val machine = doc.fields(doc.mapping(fields[name], null, name) ?: return@mapNotNull null, null)
        val machineStates = states(doc, machine["states"], name) ?: emptyMap()
        Machine(name, machineStates, transitions(doc, machine["transitions"])).also { machine.close() }
    }.associateBy { it.name }
    val active = doc.texts(fields["active_states"], null, "active_states") ?: emptyList()
    val counters = doc.mapping(fields["counters"], null, "counters")?.entries?.mapNotNull { (name, declaration) ->
        val counter = doc.fields(doc.mapping(declaration, null, "counter $name") ?: return@mapNotNull null, null)
        val scope = Scope.entries.firstOrNull { it.key == (counter["scope"] as? Node.Scalar)?.text }
        counter.close()
        if (scope == null) null.also { doc.problem(null, "counter $name: scope must be stage, seat or effort") } else Counter(name, scope)
    }?.associateBy { it.name } ?: emptyMap()
    fields.close()
    return Lifecycle(stage, companions, active, counters)
}

/** The stage machine's states are a map with their flags; a companion's may be a plain list of names. */
private fun states(doc: Doc, node: Node?, machine: String): Map<String, State>? = when (node) {
    is Node.Sequence -> node.items.mapNotNull { doc.text(it, null, "a $machine state") }.associateWith { State(it, null, false, false, false) }
    is Node.Mapping -> node.entries.map { (name, flags) ->
        val state = flags as? Node.Mapping
        if (state == null) doc.problem(null, "state $name must be a map")
        val read = state?.let { doc.fields(it, null) }
        State(
            name,
            read?.get("desc")?.let { doc.text(it, null, "state $name's desc") },
            read?.get("terminal")?.let { doc.bool(it, null, "state $name's terminal") } ?: false,
            read?.get("bora_gate")?.let { doc.bool(it, null, "state $name's bora_gate") } ?: false,
            read?.get("overlay")?.let { doc.bool(it, null, "state $name's overlay") } ?: false,
        ).also { read?.close() }
    }.associateBy { it.name }
    null -> null
    else -> null.also { doc.problem(null, "$machine states must be a map or a list") }
}

private fun transitions(doc: Doc, node: Node?): List<Transition> =
    (doc.sequence(node, null, "transitions") ?: emptyList()).mapNotNull { transition(doc, it) }

private fun transition(doc: Doc, node: Node): Transition? {
    val mapping = doc.mapping(node, null, "a transition") ?: return null
    val id = (mapping.entries["id"] as? Node.Scalar)?.text
    val owner = id ?: "${(mapping.entries["from"] as? Node.Scalar)?.text}→${(mapping.entries["to"] as? Node.Scalar)?.text}"
    val fields = doc.fields(mapping, owner)
    fields["id"]
    val from = (fields["from"] as? Node.Scalar)?.text
    val to = (fields["to"] as? Node.Scalar)?.text
    if (!fields.has("require")) {
        // Reported once; the arrow still counts for the machine's exits and reachability, as check-rules.py counts it.
        doc.problem(owner, "guard has no machine form")
        val kind = (fields["kind"] as? Node.Scalar)?.text
        return if (id != null && from != null && to != null) Transition(id, from, to, kind, Event.MESSAGE, null, emptyList(), emptyList()) else null
    }
    listOf("id", "on", "effects").filterNot { fields.has(it) }.forEach { doc.problem(owner, "missing $it") }
    val forms = Forms(doc, owner)
    val eventName = (fields["on"] as? Node.Scalar)?.text ?: "message"
    val event = Event.entries.firstOrNull { it.key == eventName }
    if (event == null) doc.problem(owner, "unknown trigger '$eventName'")
    val kind = fields["kind"]?.let { doc.text(it, owner, "kind") }
    val guard = fields["guard"]?.let { doc.text(it, owner, "guard") }
    val require = forms.requirements(fields["require"])
    val effects = (doc.sequence(fields["effects"], owner, "effects") ?: emptyList()).mapNotNull(forms::effect)
    if (from == null || to == null) doc.problem(owner, "a transition names its from and to states")
    fields.close()
    if (id == null || from == null || to == null || event == null) return null
    return Transition(id, from, to, kind, event, guard, require, effects)
}

// rules/message-kinds.yaml

private val kindFields = listOf("args", "class", "window", "max_fires", "deadline", "expiry")

internal fun readKinds(doc: Doc, node: Node): Map<String, Kind>? {
    val fields = doc.fields(doc.mapping(node, null, "the kinds file") ?: return null, null)
    doc.version(fields)
    fields["refused_by_construction"]?.let { doc.texts(it, null, "refused_by_construction") }
    val kinds = doc.mapping(fields["kinds"], null, "kinds") ?: return null.also { doc.problem(null, "missing kinds") }
    fields.close()
    return kinds.entries.mapNotNull { (name, definition) -> kind(doc, name, definition) }.associateBy { it.name }
}

private fun kind(doc: Doc, name: String, node: Node): Kind? {
    val fields = doc.fields(doc.mapping(node, name, "a kind") ?: return null, name)
    kindFields.filterNot { fields.has(it) }.forEach { doc.problem(name, "missing $it") }
    if (!fields.has("require")) doc.problem(name, "guard has no machine form")
    val forms = Forms(doc, name)
    val from = doc.text(fields["from"], name, "from")?.split("|")?.map { it.trim() }
    val to = destination(doc, name, fields["to"])
    val cc = fields["cc"]?.let { doc.texts(it, name, "cc") } ?: emptyList()
    val prose = listOf("fires", "guard", "reply", "note", "on_expiry").associateWith { fields[it]?.let { node -> doc.text(node, name, it) } }
    val replies = fields["replies"]?.let { doc.texts(it, name, "replies") } ?: emptyList()
    val args = doc.mapping(fields["args"], name, "args")?.entries?.mapNotNull { (arg, spec) -> argSpec(doc, name, arg, spec)?.let { arg to it } }?.toMap() ?: emptyMap()
    val classRule = classRule(doc, name, forms, fields["class"])
    val maxFires = fields["max_fires"]?.let { if ((it as? Node.Scalar)?.literal == Literal.Null) null else doc.whole(it, name, "max_fires") }
    val window = Window.entries.firstOrNull { it.key == (fields["window"] as? Node.Scalar)?.text }
    if (window == null) doc.problem(name, "invalid window")
    val deadline = doc.duration(fields["deadline"], name, "deadline")
    val expiry = (doc.sequence(fields["expiry"], name, "expiry") ?: emptyList()).mapNotNull(forms::expiry)
    val require = if (fields.has("require")) forms.requirements(fields["require"]) else emptyList()
    fields.close()
    if (from == null || to == null || classRule == null || window == null) return null
    return Kind(name, from, to, cc, prose["fires"], prose["guard"], prose["reply"], replies, prose["note"], prose["on_expiry"],
        args, classRule, maxFires, window, deadline, expiry, require)
}

private fun destination(doc: Doc, name: String, node: Node?): Destination? = when {
    node == null -> null.also { doc.problem(name, "missing to") }
    node is Node.Mapping -> {
        val reply = (node.entries["re"] as? Node.Scalar)?.text?.let { field -> ReplyField.entries.firstOrNull { it.key == field } }
        if (node.entries.size == 1 && reply != null) Destination.Reply(reply) else null.also { doc.problem(name, "to must be roles, {re: from} or 'a seat'") }
    }
    (node as? Node.Scalar)?.text == "a seat" -> Destination.AnySeat
    else -> doc.text(node, name, "to")?.let { text -> Destination.Parties(text.split("|").map { it.trim() }) }
}

private fun argSpec(doc: Doc, kind: String, arg: String, node: Node): ArgSpec? {
    val mapping = node as? Node.Mapping
    if (mapping == null || mapping.entries.keys.none { it == "type" || it == "enum" }) return null.also { doc.problem(kind, "untyped argument '$arg'") }
    val fields = doc.fields(mapping, kind)
    val type = fields["type"]?.let { typeNode -> ArgType.entries.firstOrNull { it.key == (typeNode as? Node.Scalar)?.text }.also { if (it == null) doc.problem(kind, "argument '$arg' has an unknown type") } }
    val enum = fields["enum"]?.let { doc.sequence(it, kind, "argument '$arg' enum")?.mapNotNull { item -> doc.literal(item, kind, "argument '$arg' enum item") } }
    val required = fields["required"]?.let { doc.bool(it, kind, "argument '$arg' required") } ?: false
    val items = fields["items"]?.let { argSpec(doc, kind, "$arg[]", it) }
    val requiredFields = fields["required_fields"]?.let { doc.texts(it, kind, "argument '$arg' required_fields") } ?: emptyList()
    fields.close()
    return ArgSpec(type, enum, required, items, requiredFields)
}

private fun classRule(doc: Doc, name: String, forms: Forms, node: Node?): ClassRule? {
    val mapping = node as? Node.Mapping
    val default = MessageClass.entries.firstOrNull { it.name == (mapping?.entries?.get("default") as? Node.Scalar)?.text }
    if (mapping == null || default == null) return null.also { if (node != null) doc.problem(name, "invalid class") }
    val fields = doc.fields(mapping, name)
    fields["default"]
    val cases = (fields["cases"]?.let { doc.sequence(it, name, "class cases") } ?: emptyList()).mapNotNull { case ->
        val entry = case as? Node.Mapping
        val messageClass = MessageClass.entries.firstOrNull { it.name == (entry?.entries?.get("class") as? Node.Scalar)?.text }
        val condition = entry?.entries?.get("if")
        if (entry == null || messageClass == null || condition == null || entry.entries.keys != setOf("if", "class")) return@mapNotNull null.also { doc.problem(name, "invalid class case") }
        forms.single(condition)?.let { ClassCase(it, messageClass) }
    }
    fields.close()
    return ClassRule(default, cases)
}

// rules/engagement.yaml

internal fun readEngagement(doc: Doc, node: Node): Engagement? {
    val mapping = doc.mapping(node, null, "the engagement file") ?: return null
    val fields = doc.fields(mapping, null)
    doc.version(fields)
    listOf("roles", "graduation", "mailroom_declared_effects", "profile").filterNot { fields.has(it) }.forEach { doc.problem(null, "missing $it") }
    val principles = fields["principles"]?.let { doc.texts(it, null, "principles") } ?: emptyList()
    val roles = doc.mapping(fields["roles"], null, "roles")?.entries?.map { (role, definition) ->
        val prose = doc.mapping(definition, role, "a role")?.let { doc.fields(it, role) }
        Role(prose?.get("host")?.let { doc.text(it, role, "host") }, prose?.get("may")?.let { doc.text(it, role, "may") },
            prose?.get("never")?.let { doc.text(it, role, "never") }).also { prose?.close() }.let { role to it }
    }?.toMap() ?: emptyMap()
    val classes = doc.textMap(fields["classes"], null, "classes")?.mapNotNull { (key, prose) ->
        MessageClass.entries.firstOrNull { it.name == key }?.let { it to prose }.also { if (it == null) doc.problem(null, "unknown class '$key'") }
    }?.toMap() ?: emptyMap()
    val graduation = doc.mapping(fields["graduation"], null, "graduation")?.let { doc.fields(it, null) }?.let { read ->
        Graduation(read["rule"]?.let { doc.text(it, null, "graduation.rule") }, read["N"]?.let { doc.whole(it, null, "graduation.N") },
            read["never"]?.let { doc.texts(it, null, "graduation.never") } ?: emptyList()).also { read.close() }
    } ?: Graduation(null, null, emptyList())
    val caps = doc.mapping(fields["caps"], null, "caps")?.entries?.mapNotNull { (cap, value) -> doc.literal(value, null, "cap $cap")?.let { cap to it } }?.toMap() ?: emptyMap()
    val presence = doc.mapping(fields["presence"], null, "presence")?.let { doc.fields(it, null) }?.let { read ->
        Presence(doc.duration(read["idle_hours_to_absent"], null, "presence.idle_hours_to_absent"),
            read["signals"]?.let { doc.texts(it, null, "presence.signals") } ?: emptyList(),
            read["while_absent"]?.let { doc.text(it, null, "presence.while_absent") },
            read["on_return"]?.let { doc.text(it, null, "presence.on_return") }).also { read.close() }
    }
    val declared = doc.texts(fields["mailroom_declared_effects"], null, "mailroom_declared_effects") ?: emptyList()
    val secondKey = doc.mapping(fields["second_key"], null, "second_key")?.let { doc.fields(it, "second_key") }?.let { read ->
        val forms = Forms(doc, "second_key")
        val otherwise = doc.mapping(read["otherwise"], "second_key", "otherwise")?.entries?.get("class")
        SecondKey(read["rule"]?.let { doc.text(it, "second_key", "rule") }, forms.requirements(read["require"]),
            MessageClass.entries.firstOrNull { it.name == (otherwise as? Node.Scalar)?.text } ?: MessageClass.B.also { doc.problem("second_key", "otherwise must name a class") })
            .also { read.close() }
    }
    val profiles = doc.mapping(fields["autonomy_profiles"], null, "autonomy_profiles")?.entries?.entries?.associate { (profile, settings) ->
        profile to (doc.mapping(settings, null, "autonomy profile $profile")?.entries?.mapNotNull { (key, value) -> doc.literal(value, null, "$profile.$key")?.let { key to it } }?.toMap() ?: emptyMap())
    } ?: emptyMap()
    val profile = fields["profile"]?.let { doc.text(it, null, "profile") }
    if (profile != null && profile !in profiles) doc.problem(null, "profile '$profile' is not in autonomy_profiles")
    val qa = doc.mapping(fields["qa"], null, "qa")?.let { doc.fields(it, null) }?.let { read ->
        EngagementQa(read["instances_per_office"]?.let { doc.whole(it, null, "qa.instances_per_office") } ?: 1,
            read["queue"]?.let { doc.text(it, null, "qa.queue") }, read["computer_use"]?.let { doc.textMap(it, null, "qa.computer_use") } ?: emptyMap(),
            read["candidates"]?.let { doc.text(it, null, "qa.candidates") }, read["durable_roll"]?.let { doc.text(it, null, "qa.durable_roll") },
            read["rollback"]?.let { doc.text(it, null, "qa.rollback") }, read["exclusivity"]?.let { doc.text(it, null, "qa.exclusivity") }).also { read.close() }
    }
    val identity = fields["identity"]?.let { doc.textMap(it, null, "identity") } ?: emptyMap()
    val unguarded = fields["what_no_guard_holds"]?.let { doc.texts(it, null, "what_no_guard_holds") } ?: emptyList()
    val stopGate = doc.mapping(fields["stop_gate"], null, "stop_gate")?.let { doc.fields(it, "stop_gate") }?.let { read ->
        val forms = Forms(doc, "stop_gate")
        val blocks = read["max_consecutive_blocks"]?.let { forms.value(it) }
        StopGate(blocks ?: Value.Constant(Literal.Null).also { doc.problem("stop_gate", "max_consecutive_blocks names its cap") },
            forms.requirements(read["require"])).also { read.close() }
    }
    val permissions = fields["permissions"]?.let { doc.textMap(it, null, "permissions") } ?: emptyMap()
    fields.close()
    return Engagement(principles, roles, classes, graduation, caps, presence, declared, secondKey, profile.orEmpty(), profiles, qa, identity, unguarded,
        stopGate, permissions, flatten(mapping))
}

// rules/vocabulary.yaml

internal fun readVocabulary(doc: Doc, node: Node): Vocabulary? {
    val fields = doc.fields(doc.mapping(node, null, "the vocabulary") ?: return null, null)
    doc.version(fields)
    listOf("facts", "operations").filterNot { fields.has(it) }.forEach { doc.problem(null, "missing $it") }
    val facts = doc.mapping(fields["facts"], null, "facts")?.entries?.mapNotNull { (name, spec) ->
        val read = doc.fields(doc.mapping(spec, name, "a fact") ?: return@mapNotNull null, name)
        val params = when (val params = read["params"]) {
            is Node.Sequence -> doc.texts(params, name, "params")?.joinToString(", ")
            else -> params?.let { doc.text(it, name, "params") }
        } ?: ""
        val sourceText = read["source"]?.let { doc.text(it, name, "source") } ?: ""
        val sources = if (sourceText == "—") emptySet() else sourceText.split("+").mapNotNull { part ->
            Source.entries.firstOrNull { it.name == part.trim() }.also { if (it == null) doc.problem(name, "unknown source '$part'") }
        }.toSet()
        FactSpec(name, params, read["returns"]?.let { doc.text(it, name, "returns") } ?: "", sources).also { read.close() }
    }?.associateBy { it.name } ?: emptyMap()
    val operations = doc.mapping(fields["operations"], null, "operations")?.entries?.mapNotNull { (name, spec) ->
        val read = doc.fields(doc.mapping(spec, name, "an operation") ?: return@mapNotNull null, name)
        OperationSpec(name, read["requested_by"]?.let { doc.text(it, name, "requested_by") }, read["identity"]?.let { doc.text(it, name, "identity") },
            read["results"]?.let { doc.text(it, name, "results") }).also { read.close() }
    }?.associateBy { it.name } ?: emptyMap()
    val attestations = fields["attestations"]?.let { doc.textMap(it, null, "attestations") } ?: emptyMap()
    fields.close()
    return Vocabulary(facts, operations, attestations)
}
