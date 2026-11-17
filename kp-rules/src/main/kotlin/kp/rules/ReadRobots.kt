package kp.rules

private val robotRuleId = Regex("[A-Z]+-[0-9]+")
private val triggerForms = listOf("fact", "delivered", "arrives", "expired", "presence", "op")

/** A robot file (contracts.md §8): its role, when it is active, what it intercepts, its rules, its queue and thresholds. */
internal fun readRobot(doc: Doc, node: Node): Robot? {
    val mapping = doc.mapping(node, null, "a robot") ?: return null
    val fields = doc.fields(mapping, null)
    val role = doc.text(fields["role"], null, "role") ?: return null.also { doc.problem(null, "a robot names its role") }
    val file = doc.file.substringAfterLast('/')
    val forms = Forms(doc, file)
    val activeWhen = fields["active_when"]?.let(forms::single) ?: Requirement.Always(false).also { doc.problem(file, "unknown requirement form") }
    val intercepts = (fields["intercepts"]?.let { doc.sequence(it, file, "intercepts") } ?: emptyList()).mapNotNull { intercept ->
        when {
            intercept is Node.Scalar && intercept.text != null -> Intercept.Kind(intercept.text!!)
            intercept is Node.Mapping && intercept.entries.keys == setOf("class", "to") -> {
                val messageClass = MessageClass.entries.firstOrNull { it.name == (intercept.entries["class"] as? Node.Scalar)?.text }
                val to = (intercept.entries["to"] as? Node.Scalar)?.text
                if (messageClass == null || to == null) null.also { doc.problem(file, "invalid class intercept") } else Intercept.ClassTo(messageClass, to)
            }
            else -> null.also { doc.problem(file, "invalid class intercept") }
        }
    }
    val rules = (doc.sequence(fields["rules"], file, "rules") ?: emptyList<Node>().also { doc.problem(file, "a robot lists its rules") }).mapNotNull { robotRule(doc, it) }
    val queue = fields["queue"]?.let { queue(doc, file, forms, it) }
    val thresholds = doc.mapping(fields["thresholds"], file, "thresholds")?.entries?.mapNotNull { (name, value) ->
        doc.literal(value, file, "threshold $name")?.let { name to it }
    }?.toMap() ?: emptyMap()
    val limits = fields["limits"]?.let { doc.texts(it, file, "limits") } ?: emptyList()
    fields.close()
    return Robot(file, role, activeWhen, intercepts, rules, queue, thresholds, limits)
}

private fun robotRule(doc: Doc, node: Node): RobotRule? {
    val mapping = doc.mapping(node, null, "a robot rule") ?: return null
    val id = (mapping.entries["id"] as? Node.Scalar)?.text ?: return null.also { doc.problem(null, "a robot rule has an id") }
    if (!robotRuleId.matches(id)) return null.also { doc.problem(id, "robot rule id must match [A-Z]+-[0-9]+") }
    val fields = doc.fields(mapping, id)
    fields["id"]
    val whenText = fields["when"]?.let { doc.text(it, id, "when") }
    val note = fields["note"]?.let { doc.text(it, id, "note") }
    val persona = fields["for"]?.let { (it as? Node.Scalar)?.text } == "persona"
    if (fields.has("for") && !persona) doc.problem(id, "for: names persona")
    if (persona) {
        if (fields.has("on") || fields.has("then")) doc.problem(id, "persona rule must not execute")
        fields.close()
        return RobotRule(id, whenText, note, true, null, emptyList(), emptyList())
    }
    if (!(fields.has("on") && fields.has("if") && fields.has("then"))) return null.also { doc.problem(id, "guard has no machine form") }
    val forms = Forms(doc, id)
    val trigger = trigger(doc, id, fields["on"]!!)
    val condition = forms.requirements(fields["if"])
    val then = (doc.sequence(fields["then"], id, "then") ?: emptyList()).mapNotNull { action(doc, id, forms, it) }
    fields.close()
    return trigger?.let { RobotRule(id, whenText, note, false, it, condition, then) }
}

private fun trigger(doc: Doc, id: String, node: Node): RobotTrigger? {
    val mapping = node as? Node.Mapping
    val forms = mapping?.entries?.keys?.filter { it in triggerForms }.orEmpty()
    if (mapping == null || forms.size != 1) return null.also { doc.problem(id, "invalid robot trigger") }
    val form = forms.single()
    val name = (mapping.entries[form] as? Node.Scalar)?.text ?: return null.also { doc.problem(id, "invalid robot trigger") }
    val extra = mapping.entries.keys - form - (if (form == "op") setOf("eq") else emptySet())
    extra.forEach { doc.problem(id, "unknown field '$it'") }
    return when (form) {
        "fact" -> RobotTrigger.Fact(name)
        "delivered" -> RobotTrigger.Delivered(name)
        "arrives" -> RobotTrigger.Arrives(name)
        "expired" -> RobotTrigger.Expired(name)
        "presence" -> if (name == "present" || name == "absent") RobotTrigger.Presence(name) else null.also { doc.problem(id, "presence is present or absent") }
        else -> Outcome.entries.firstOrNull { it.key == (mapping.entries["eq"] as? Node.Scalar)?.text }?.let { RobotTrigger.Operation(name, it) }
            .also { if (it == null) doc.problem(id, "unknown operation result") }
    }
}

private fun action(doc: Doc, id: String, forms: Forms, node: Node): Action? {
    val mapping = node as? Node.Mapping
    val verbs = mapping?.entries?.keys?.filter { it == "send" || it == "wake" }.orEmpty()
    if (mapping == null || verbs.size != 1 || (mapping.entries.keys - setOf("send", "wake", "to", "args")).isNotEmpty()) {
        return null.also { doc.problem(id, "robot then must only send or wake") }
    }
    val name = (mapping.entries[verbs.single()] as? Node.Scalar)?.text ?: return null.also { doc.problem(id, "robot then must only send or wake") }
    if (verbs.single() == "wake") {
        if (mapping.entries.size != 1) doc.problem(id, "a wake names only its mode")
        return Action.Wake(name)
    }
    val to = mapping.entries["to"]?.let { forms.value(it) } ?: return null.also { doc.problem(id, "a send names its destination") }
    return Action.Send(name, to, forms.arguments(mapping.entries["args"]))
}

private fun queue(doc: Doc, file: String, forms: Forms, node: Node): Queue? {
    val fields = doc.fields(doc.mapping(node, file, "queue") ?: return null, file)
    val id = doc.text(fields["id"], file, "a queue's id") ?: "".also { doc.problem(file, "a queue has an id") }
    if (id.isNotEmpty() && !robotRuleId.matches(id)) doc.problem(file, "a queue's id must match [A-Z]+-[0-9]+")
    val oneAtATime = (fields["one_at_a_time"] as? Node.Scalar)?.literal == Literal.Bool(true)
    val fifo = (fields["then"] as? Node.Scalar)?.text == "fifo"
    if (!oneAtATime || !fifo) doc.problem(file, "invalid queue discipline")
    val order = doc.texts(fields["order"], file, "queue order") ?: emptyList()
    val nextWhen = fields["next_when"]?.let(forms::single) ?: Requirement.Always(false).also { doc.problem(file, "unknown requirement form") }
    val prose = (fields["prose"]?.let { doc.sequence(it, file, "queue prose") } ?: emptyList()).mapNotNull { rule ->
        val read = doc.fields(doc.mapping(rule, file, "a queue prose rule") ?: return@mapNotNull null, file)
        val proseId = doc.text(read["id"], file, "a queue prose rule's id") ?: return@mapNotNull null
        ProseRule(proseId, read["when"]?.let { doc.text(it, proseId, "when") }, read["note"]?.let { doc.text(it, proseId, "note") }).also { read.close() }
    }
    fields.close()
    return Queue(id, order, nextWhen, prose)
}

/** A persona's role and the modes its frontmatter declares; the rest of the persona is not the rules'. */
internal fun readPersona(doc: Doc, node: Node): Pair<String, Set<String>>? {
    val mapping = doc.mapping(node, null, "a persona's frontmatter") ?: return null
    val role = (mapping.entries["role"] as? Node.Scalar)?.text ?: return null.also { doc.problem(null, "a persona names its role") }
    val modes = when (val modes = mapping.entries["modes"]) {
        null -> emptySet()
        is Node.Mapping -> modes.entries.keys
        else -> emptySet<String>().also { doc.problem(null, "modes must be a map") }
    }
    return role to modes
}
