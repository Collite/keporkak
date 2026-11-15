package kp.engine

import kp.rules.Comparison
import kp.rules.FactCall
import kp.rules.Literal
import kp.rules.Requirement
import kp.rules.Value

/** A requirement in the rule file's own form, written flow-style: `{fact: ci.state, of: {fact: pr.head}, eq: green}`. */
internal fun render(requirement: Requirement): String = when (requirement) {
    is Requirement.Fact -> "{${call(requirement.fact)}${comparison(requirement.comparison)}}"
    is Requirement.Arg -> "{arg: ${requirement.name}${comparison(requirement.comparison)}}"
    is Requirement.Message -> "{msg: ${requirement.field.key}${comparison(requirement.comparison)}}"
    is Requirement.Reply -> "{re: ${requirement.field.key}${comparison(requirement.comparison)}}"
    is Requirement.Counter -> "{counter: ${requirement.name}${comparison(requirement.comparison)}}"
    is Requirement.Attest -> "{attest: ${requirement.name}}"
    is Requirement.Signed -> "{signed: ${requirement.signer}}"
    is Requirement.Operation -> "{op: ${requirement.operation}, eq: ${requirement.outcome.key}}"
    is Requirement.Any -> requirement.items.joinToString(", ", "{any: [", "]}") { render(it) }
    is Requirement.Not -> "{not: ${render(requirement.item)}}"
    is Requirement.Always -> "{always: ${requirement.holds}}"
    is Requirement.Holds -> "{holds: ${requirement.block}}"
}

private fun call(call: FactCall): String = "fact: ${call.name}" + call.params.entries.joinToString("") { (key, value) -> ", $key: ${render(value)}" }

private fun comparison(comparison: Comparison?): String = comparison?.let { ", ${it.operator.key}: ${render(it.value)}" }.orEmpty()

internal fun render(value: Value): String = when (value) {
    is Value.Constant -> render(value.literal)
    is Value.Items -> value.items.joinToString(", ", "[", "]") { render(it) }
    is Value.Fields -> value.fields.entries.joinToString(", ", "{", "}") { (key, item) -> "$key: ${render(item)}" }
    is Value.Cap -> "{cap: ${value.name}}"
    is Value.Arg -> "{arg: ${value.path}}"
    is Value.Fact -> "{${call(value.fact)}}"
    is Value.Message -> "{msg: ${value.field.key}}"
    is Value.Reply -> "{re: ${value.field.key}}"
    is Value.Pool -> "{${value.file.key}: ${value.path}}"
    is Value.Engagement -> "{engagement: ${value.path}}"
    is Value.Robot -> "{robot: ${value.threshold}}"
    is Value.Trigger -> "{trigger: ${value.path}}"
}

internal fun render(literal: Literal): String = when (literal) {
    is Literal.Text -> literal.value
    is Literal.Number -> literal.value.toPlainString()
    is Literal.Bool -> literal.value.toString()
    Literal.Null -> "null"
}

/**
 * A requirement in its file form, as the `refused` record's `failed` carries it (contracts.md §2.2): the map the rules
 * file holds, `{counter: resumes, lt: {cap: resumes_per_stage}}` as `{"counter": "resumes", "lt": {"cap": …}}`.
 */
internal fun form(requirement: Requirement): Datum.Fields = when (requirement) {
    is Requirement.Fact -> Datum.Fields(fileCall(requirement.fact) + fileComparison(requirement.comparison))
    is Requirement.Arg -> named("arg", requirement.name, requirement.comparison)
    is Requirement.Message -> named("msg", requirement.field.key, requirement.comparison)
    is Requirement.Reply -> named("re", requirement.field.key, requirement.comparison)
    is Requirement.Counter -> named("counter", requirement.name, requirement.comparison)
    is Requirement.Attest -> named("attest", requirement.name, null)
    is Requirement.Signed -> named("signed", requirement.signer, null)
    is Requirement.Operation -> Datum.Fields(mapOf("op" to Datum.Text(requirement.operation), "eq" to Datum.Text(requirement.outcome.key)))
    is Requirement.Any -> Datum.Fields(mapOf("any" to Datum.Items(requirement.items.map(::form))))
    is Requirement.Not -> Datum.Fields(mapOf("not" to form(requirement.item)))
    is Requirement.Always -> Datum.Fields(mapOf("always" to Datum.Bool(requirement.holds)))
    is Requirement.Holds -> named("holds", requirement.block, null)
}

private fun named(form: String, name: String, comparison: Comparison?) = Datum.Fields(mapOf(form to Datum.Text(name)) + fileComparison(comparison))

private fun fileCall(call: FactCall): Map<String, Datum> = mapOf("fact" to Datum.Text(call.name)) + call.params.mapValues { form(it.value) }

private fun fileComparison(comparison: Comparison?): Map<String, Datum> = comparison?.let { mapOf(it.operator.key to form(it.value)) }.orEmpty()

private fun form(value: Value): Datum = when (value) {
    is Value.Constant -> literal(value.literal)
    is Value.Items -> Datum.Items(value.items.map(::form))
    is Value.Fields -> Datum.Fields(value.fields.mapValues { form(it.value) })
    is Value.Cap -> reference("cap", value.name)
    is Value.Arg -> reference("arg", value.path)
    is Value.Fact -> Datum.Fields(fileCall(value.fact))
    is Value.Message -> reference("msg", value.field.key)
    is Value.Reply -> reference("re", value.field.key)
    is Value.Pool -> reference(value.file.key, value.path)
    is Value.Engagement -> reference("engagement", value.path)
    is Value.Robot -> reference("robot", value.threshold)
    is Value.Trigger -> reference("trigger", value.path)
}

private fun reference(key: String, name: String) = Datum.Fields(mapOf(key to Datum.Text(name)))
