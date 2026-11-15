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
