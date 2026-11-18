package kp.engine

import java.lang.reflect.Modifier
import kotlin.reflect.KClass

/**
 * A projection of the state: what an oracle compares of [State], and what it leaves out — each field it leaves out with
 * its reason (the retro of P1, candidate 2; S2.8 T4). An oracle that compares a hand-picked part says which part, and a
 * field the state gains later is in neither until someone decides: [unaccounted] names it.
 */
class Projection(val name: String, val compares: Set<String>, val leavesOut: Map<String, String>) {
    /** What the projection does not account for among [fields]. Empty when it names each field once. */
    fun unaccounted(fields: List<String>): List<String> = emptyList()
}

/** The fields a class's instances hold, in declaration order: for a data class, its constructor's. */
fun fieldsOf(type: KClass<*>): List<String> =
    type.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }.map { it.name }
