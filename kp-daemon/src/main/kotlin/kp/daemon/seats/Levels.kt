package kp.daemon.seats

import kp.daemon.Walk
import kp.engine.Datum
import kp.rules.Line
import kp.rules.Roster

/**
 * The model and the effort a role is seated on (contracts.md §9, §4.4, Amendment 22): `role_models` when it names the
 * role, else the roster's level for the role — the `level:` of its persona (`agents/<role>/AGENT.md`), a skill and a
 * thinking size. Where the persona leaves either to the assignment, the level the request names is read (a skill, and
 * a size after it: `senior`, `senior L`, `junior/M`); where it sets either at or above another role's, that role's
 * line on the stage is the base, and the line is the lowest at or above it whose model is at least the roster's
 * review floor, on the base's effort.
 */
internal class Levels(private val roster: Roster, private val personas: Map<String, String>) {
    /** The persona's `level:` as written: its skill and its thinking. */
    class Persona(val skill: String?, val thinking: String?)

    /** The line for [role]; [asked] is the level the request names, [base] another role's line on the stage. */
    fun line(role: String, asked: String?, base: (String) -> Line?): Result {
        roster.roleModels[role]?.let { chosen ->
            return roster.order.firstOrNull { it.model == chosen.model && it.effort == chosen.effort }?.let(Result::Seated)
                ?: Result.Unseated("role_models $role names no line of the roster's order")
        }
        val persona = persona(role)
        val relative = listOfNotNull(persona.skill, persona.thinking).firstNotNullOfOrNull { RELATIVE.find(it)?.groupValues?.get(1) }
        if (relative != null) {
            val under = base(relative) ?: return Result.Unseated("the level of $role is set at or above the $relative's, and the stage has no $relative's line")
            return raised(under)?.let(Result::Seated) ?: Result.Unseated("no line of the roster's order is at or above the $relative's and the review floor")
        }
        val (askedSkill, askedSize) = asked?.let(::parse) ?: (null to null)
        val skill = persona.skill?.takeIf { it in roster.levels } ?: askedSkill
            ?: return Result.Unseated("the level of $role is per assignment, and no request about it names one")
        // A level that names a skill alone is thought at M (S2.4, Found).
        val size = persona.thinking?.takeIf { it in SIZES } ?: askedSize ?: DEFAULT_SIZE
        return roster.levels[skill]?.get(size)?.let(Result::Seated) ?: Result.Unseated("the roster has no level $skill $size")
    }

    /** The lowest line at or above [base] whose model is at least the review floor: on [base]'s effort when there is one. */
    private fun raised(base: Line): Line? {
        val rank = roster.order.indexOf(base).takeIf { it >= 0 } ?: return null
        val floor = roster.order.indexOfFirst { it.model == roster.reviewFloor }
        val above = roster.order.withIndex().filter { (index, line) -> index >= rank && roster.order.indexOfFirst { it.model == line.model } >= floor }.map { it.value }
        return above.firstOrNull { it.effort == base.effort } ?: above.firstOrNull()
    }

    private fun persona(role: String): Persona {
        val text = personas["agents/$role/AGENT.md"] ?: return Persona(null, null)
        val front = text.lineSequence().dropWhile { it.trim() != FENCE }.drop(1).takeWhile { it.trim() != FENCE }.joinToString("\n")
        val level = (runCatching { Walk.parse(front) }.getOrNull() as? Datum.Fields)?.fields?.get(LEVEL) as? Datum.Fields ?: return Persona(null, null)
        return Persona((level.fields[SKILL] as? Datum.Text)?.value, (level.fields[THINKING] as? Datum.Text)?.value)
    }

    /** `senior`, `senior L`, `senior/L`, `senior-L`: a skill, and a thinking size when one follows it. */
    private fun parse(level: String): Pair<String?, String?> {
        val match = ASKED.matchEntire(level.trim()) ?: return null to null
        return match.groupValues[1] to match.groupValues[2].ifEmpty { null }
    }

    sealed interface Result {
        data class Seated(val line: Line) : Result
        data class Unseated(val why: String) : Result
    }

    private companion object {
        const val FENCE = "---"
        const val LEVEL = "level"
        const val SKILL = "skill"
        const val THINKING = "thinking"
        const val DEFAULT_SIZE = "M"
        val SIZES = setOf("S", "M", "L", "XL")
        val RELATIVE = Regex("""≥\s*the\s+([a-z][a-z-]*)'s""")
        val ASKED = Regex("""([a-z]+)(?:[\s/,-]+(S|M|L|XL))?""")
    }
}
