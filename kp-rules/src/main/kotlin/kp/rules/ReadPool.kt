package kp.rules

import java.math.BigDecimal

internal const val ROSTER = "pool/roster.yaml"
internal const val OFFICES = "pool/offices.yaml"
internal const val BUDGETS = "pool/budgets.yaml"

private val sizes = listOf("S", "M", "L", "XL")
private val roots = listOf("worktrees", "scratch", "seats", "secrets")
private val computerUse = listOf("A", "B", "C", "off")
private val listener = Regex("127\\.0\\.0\\.1:([1-9][0-9]{0,4})")
private val officeName = Regex("[a-z][a-z0-9]*")
private val effortId = Regex("[A-Z][A-Z0-9-]*")

/** A folder of the project checkout, written relative to it: not absolute, not from home, never above it. */
private fun insideCheckout(folder: String) = folder.isNotEmpty() && !folder.startsWith("/") && !folder.startsWith("~") && ".." !in folder.split("/")

private fun Doc.textOrNull(node: Node?): String? = ((node as? Node.Scalar)?.literal as? Literal.Text)?.value?.takeIf { it.isNotBlank() }

private fun Doc.positive(node: Node?): Int? {
    val number = ((node as? Node.Scalar)?.literal as? Literal.Number)?.value
    return number?.takeIf { it.signum() > 0 && it.stripTrailingZeros().scale() <= 0 }?.toInt()
}

private fun Doc.line(node: Node?): Line? {
    val mapping = node as? Node.Mapping ?: return null
    if (mapping.entries.keys != setOf("vendor", "model", "effort")) return null
    return Line(textOrNull(mapping.entries["vendor"]) ?: return null, textOrNull(mapping.entries["model"]) ?: return null,
        textOrNull(mapping.entries["effort"]) ?: return null)
}

/** pool/roster.yaml (contracts.md §9): vendors, the order of (model, effort) pairs, the levels on it, the review floor, reserved models. */
internal fun readRoster(doc: Doc, node: Node): Roster? {
    val fields = doc.fields(doc.mapping(node, null, "the roster") ?: return null, null)
    doc.version(fields)
    val vendors = (fields["vendors"] as? Node.Mapping)?.entries.orEmpty().map { (name, definition) ->
        val vendor = definition as? Node.Mapping
        val read = vendor?.let { doc.fields(it, null) }
        val adapter = doc.textOrNull(read?.get("adapter"))
        if (adapter == null) doc.problem(null, "vendor '$name' has no adapter")
        val adapterVersion = read?.get("adapter_version")
        if (adapterVersion != null && (adapterVersion as? Node.Scalar)?.literal != Literal.Null && doc.textOrNull(adapterVersion) == null) {
            doc.problem(null, "vendor '$name' adapter_version must be a version or null")
        }
        read?.close()
        Vendor(name, adapter ?: "", doc.textOrNull(adapterVersion))
    }.associateBy { it.name }
    if (vendors.isEmpty()) doc.problem(null, "vendors must name at least one vendor")

    val order = mutableListOf<Line>()
    val models = mutableListOf<String>()
    val lines = (fields["order"] as? Node.Sequence)?.items.orEmpty()
    if (lines.isEmpty()) doc.problem(null, "order must list (model, effort) pairs")
    for ((index, node) in lines.withIndex()) {
        val line = doc.line(node) ?: continue.also { doc.problem(null, "order line $index must be {vendor, model, effort}") }
        if (line.vendor !in vendors) doc.problem(null, "order line $index names unknown vendor '${line.vendor}'")
        if (line in order) doc.problem(null, "order line $index repeats (${line.model}, ${line.effort})")
        order += line
        if (line.model in models && models.last() != line.model) doc.problem(null, "order line $index: model before effort — ${line.model}'s pairs must be adjacent")
        if (models.lastOrNull() != line.model) models += line.model
    }

    val levelNodes = (fields["levels"] as? Node.Mapping)?.entries.orEmpty()
    listOf("junior", "senior").filterNot { it in levelNodes }.forEach { doc.problem(null, "levels lack '$it'") }
    val levels = levelNodes.mapValues { (skill, node) ->
        val bySize = (node as? Node.Mapping)?.entries.orEmpty()
        if (bySize.keys != sizes.toSet()) doc.problem(null, "level $skill must map exactly ${sizes.joinToString(", ")}")
        bySize.mapNotNull { (size, line) ->
            val parsed = doc.line(line)
            if (parsed == null || parsed !in order) null.also { doc.problem(null, "level $skill $size is not a line of the order") } else size to parsed
        }.toMap()
    }

    val review = fields["review"] as? Node.Mapping
    val floor = doc.textOrNull(review?.entries?.get("floor"))
    if (floor == null || floor !in models) doc.problem(null, "review floor '$floor' is not a model of the order")
    review?.entries?.keys?.minus("floor")?.forEach { doc.problem(null, "unknown field 'review.$it'") }

    val reserved = (fields["reserved"]?.let { doc.mapping(it, null, "reserved") }?.entries.orEmpty()).mapNotNull { (model, roles) ->
        if (model !in models) doc.problem(null, "reserved model '$model' is not a model of the order")
        doc.texts(roles, null, "reserved $model")?.let { model to it }
    }.toMap()
    for ((skill, bySize) in levels) for ((size, line) in bySize) {
        if (line.model in reserved) doc.problem(null, "level $skill $size points at ${line.model}, which is reserved")
    }
    val roleModels = (fields["role_models"]?.let { doc.mapping(it, null, "role_models") }?.entries.orEmpty()).mapNotNull { (role, node) ->
        val mapping = node as? Node.Mapping
        val model = doc.textOrNull(mapping?.entries?.get("model"))
        val effort = doc.textOrNull(mapping?.entries?.get("effort"))
        if (mapping == null || model == null || effort == null || mapping.entries.keys != setOf("model", "effort")) {
            return@mapNotNull null.also { doc.problem(null, "role_models $role must be {model, effort}") }
        }
        if (order.none { it.model == model && it.effort == effort }) doc.problem(null, "role_models $role is not a line of the order")
        val allowed = reserved[model]
        if (allowed != null && role !in allowed) doc.problem(null, "role_models $role runs $model, which is reserved for ${allowed.joinToString(", ")}")
        role to RoleModel(model, effort)
    }.toMap()
    fields.close()
    return Roster(vendors, order, levels, floor ?: "", reserved, roleModels)
}

/** pool/offices.yaml (contracts.md §9): one entry per office. */
/** [vendors]: the roster's, or null when the roster could not be read — then no vendor is checked. */
internal fun readOffices(doc: Doc, node: Node, vendors: Set<String>?): Map<String, Office>? {
    val fields = doc.fields(doc.mapping(node, null, "the offices file") ?: return null, null)
    doc.version(fields)
    val entries = (fields["offices"] as? Node.Mapping)?.entries.orEmpty()
    if (entries.isEmpty()) doc.problem(null, "offices must name at least one office")
    fields.close()
    return entries.map { (name, node) ->
        if (!officeName.matches(name) || name == "hand") doc.problem(null, "'$name' is not an office name (lower-case; 'hand' is reserved)")
        val office = node as? Node.Mapping
        val read = office?.let { doc.fields(it, name) }
        val text = listOf("os", "user").associateWith { key -> doc.textOrNull(read?.get(key)).also { if (it == null) doc.problem(name, "$key missing") } }
        val rootsMap = (read?.get("roots") as? Node.Mapping)?.entries.orEmpty().mapValues { doc.textOrNull(it.value) }
        roots.filter { rootsMap[it] == null }.forEach { doc.problem(name, "roots lack '$it'") }
        (rootsMap.keys - roots.toSet()).sorted().forEach { doc.problem(name, "unknown root '$it'") }
        val project = (read?.get("project") as? Node.Mapping)?.entries.orEmpty()
        val checkout = doc.textOrNull(project["checkout"])
        val efforts = (project["efforts"] as? Node.Mapping)?.entries.orEmpty().mapValues { doc.textOrNull(it.value) }
        if (checkout == null || efforts.isEmpty()) doc.problem(name, "project must name a checkout and a folder per effort")
        (project.keys - setOf("checkout", "efforts")).sorted().forEach { doc.problem(name, "unknown field 'project.$it'") }
        efforts.forEach { (effort, folder) ->
            if (!effortId.matches(effort) || folder == null || !insideCheckout(folder)) doc.problem(name, "project effort '$effort' must be an effort id with a folder inside the checkout")
        }
        val repos = (read?.get("repos") as? Node.Mapping)?.entries.orEmpty().mapValues { doc.textOrNull(it.value) }
        if (repos.isEmpty() || repos.values.any { it == null }) doc.problem(name, "repos must map repo names to checkouts")
        val targets = (read?.get("live_targets") as? Node.Sequence)?.items?.map { doc.textOrNull(it) }
        if (targets == null || targets.any { it == null }) doc.problem(name, "live_targets must be a list of names")
        val use = doc.textOrNull(read?.get("computer_use"))
        if (use !in computerUse) doc.problem(name, "unknown computer_use class '${(read?.get("computer_use") as? Node.Scalar)?.literal?.let(::show)}'")
        val qa = (read?.get("qa") as? Node.Mapping)
        val qaVendor = doc.textOrNull(qa?.entries?.get("vendor"))
        if (vendors != null && qaVendor !in vendors) doc.problem(name, "qa vendor '$qaVendor' is not a roster vendor")
        qa?.entries?.keys?.minus("vendor")?.forEach { doc.problem(name, "unknown field 'qa.$it'") }
        val listen = (read?.get("listen") as? Node.Mapping)?.entries.orEmpty().mapValues { doc.textOrNull(it.value) }
        val ports = listOf("mcp", "board").mapNotNull { key ->
            val port = listen[key]?.let { listener.matchEntire(it) }?.groupValues?.get(1)?.takeIf { it.toInt() <= 65535 }
            port.also { if (it == null) doc.problem(name, "listen.$key must be 127.0.0.1:<port>") }
        }
        if (ports.size == 2 && ports[0] == ports[1]) doc.problem(name, "mcp and board listen on one port")
        (listen.keys - setOf("mcp", "board")).forEach { doc.problem(name, "unknown field 'listen.$it'") }
        read?.close()
        Office(name, text["os"] ?: "", text["user"] ?: "", rootsMap.mapValues { it.value ?: "" }, Project(checkout ?: "", efforts.mapValues { it.value ?: "" }),
            repos.mapValues { it.value ?: "" }, targets.orEmpty().filterNotNull(), use ?: "", qaVendor ?: "", listen.mapValues { it.value ?: "" })
    }.associateBy { it.name }
}

/** pool/budgets.yaml (contracts.md §9): slot limits per dimension and warning thresholds. */
/** [vendors]: the roster's, or null when the roster could not be read — then no subscription is checked. */
/** [vendors] and [offices] are null when their file is missing: then no name is checked against it — one problem, not a cascade. */
internal fun readBudgets(doc: Doc, node: Node, vendors: Set<String>?, offices: Set<String>?): Budgets? {
    val fields = doc.fields(doc.mapping(node, null, "the budgets file") ?: return null, null)
    doc.version(fields)
    val slots = (fields["slots"] as? Node.Mapping)?.entries.orEmpty()
    val single = listOf("total", "stages_in_flight_per_effort").associateWith { key ->
        doc.positive(slots[key]).also { if (it == null) doc.problem(null, "slots.$key must be a positive integer") }
    }
    val limits = listOf(Triple("per_subscription", vendors, "subscription"), Triple("per_office", offices, "office")).associate { (key, known, noun) ->
        val map = (slots[key] as? Node.Mapping)?.entries.orEmpty()
        if (map.isEmpty()) doc.problem(null, "slots.$key must name at least one $noun")
        key to map.mapNotNull { (name, limit) ->
            if (known != null && name !in known) doc.problem(null, "$key names unknown $noun '$name'")
            doc.positive(limit).also { if (it == null) doc.problem(null, "$key.$name must be a positive integer") }?.let { name to it }
        }.toMap()
    }
    (slots.keys - setOf("total", "stages_in_flight_per_effort", "per_subscription", "per_office")).forEach { doc.problem(null, "unknown field 'slots.$it'") }
    val warn = (fields["warn"] as? Node.Sequence)?.items?.map { ((it as? Node.Scalar)?.literal as? Literal.Number)?.value }
    val fractions = warn?.filterNotNull()?.takeIf { it.size == warn.size }
    if (fractions == null || fractions.isEmpty() || fractions.any { it <= BigDecimal.ZERO || it >= BigDecimal.ONE } || fractions != fractions.sorted().distinct()) {
        doc.problem(null, "warn must be ascending fractions between 0 and 1")
    }
    val onCap = doc.textOrNull(fields["on_cap"])
    if (onCap != "pause_provisioning") doc.problem(null, "on_cap must be pause_provisioning (a running seat is never stopped)")
    fields.close()
    return Budgets(Slots(single["total"] ?: 0, limits.getValue("per_subscription"), limits.getValue("per_office"), single["stages_in_flight_per_effort"] ?: 0),
        fractions.orEmpty(), onCap ?: "")
}

private fun show(literal: Literal): String = when (literal) {
    is Literal.Text -> literal.value
    is Literal.Number -> literal.value.toPlainString()
    is Literal.Bool -> if (literal.value) "True" else "False"
    Literal.Null -> "None"
}
